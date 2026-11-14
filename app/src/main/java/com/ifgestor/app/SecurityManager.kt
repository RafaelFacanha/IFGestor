package com.ifgestor.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.util.Base64
import androidx.core.app.NotificationCompat
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class SecurityManager(
    private val context: Context,
    private val settingsManager: SettingsManager
) {

    data class WebSession(
        val sessionId: String,
        val clientIp: String,
        val userAgent: String,
        val createdAt: Long,
        var lastActivityAt: Long,
        var is2FaVerified: Boolean = false,
        var mustChangePin: Boolean = false,
        var action2FaUntil: Long = 0L
    )

    data class Pending2FaChallenge(
        val code: String,
        val clientIp: String,
        val purpose: String,
        val expiresAt: Long
    )

    companion object {
        private const val PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256"
        private const val PBKDF2_ITERATIONS = 12000
        private const val PBKDF2_KEY_LENGTH = 256
        private const val SALT_BYTES = 16
        private const val DEFAULT_PIN = "1234"

        private const val SESSION_TIMEOUT_MS = 30 * 60 * 1000L // 30 minutos
        private const val CHALLENGE_2FA_TIMEOUT_MS = 3 * 60 * 1000L // 3 minutos
        private const val ACTION_WINDOW_2FA_MS = 5 * 60 * 1000L // 5 minutos para lote após 2FA

        private const val CHANNEL_2FA_ID = "ifgestor_2fa_secret_channel"
        private const val NOTIFICATION_2FA_BASE_ID = 9400
    }

    private val secureRandom = SecureRandom()
    private val activeSessions = ConcurrentHashMap<String, WebSession>()
    private val pendingChallenges = ConcurrentHashMap<String, Pending2FaChallenge>()
    private val bruteForceAttempts = ConcurrentHashMap<String, Pair<Int, Long>>()

    private var wifiCallbackRegistered = false
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val wifiNetworkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onLost(network: Network) {
            super.onLost(network)
            revokeAllSessions("Queda ou desconexão da rede Wi-Fi")
        }

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities
        ) {
            super.onCapabilitiesChanged(network, networkCapabilities)
            if (!networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                revokeAllSessions("Transporte Wi-Fi desativado")
            }
        }
    }

    init {
        ensureDefaultPinInitialized()
        createSecret2FaNotificationChannel()
        registerWifiLossMonitor()
    }

    // =========================================================================
    // Item 1: Criptografia PBKDF2WithHmacSHA256 + Salt e Troca do PIN 1234
    // =========================================================================

    private fun ensureDefaultPinInitialized() {
        val prefs = settingsManager.getRawPreferences()
        val existingHash = prefs.getString(SettingsManager.KEY_PIN_HASH, null)
        val existingSalt = prefs.getString(SettingsManager.KEY_PIN_SALT, null)

        if (existingHash.isNullOrBlank() || existingSalt.isNullOrBlank()) {
            val salt = generateRandomSalt()
            val hash = hashPinPbkdf2(DEFAULT_PIN, salt)
            prefs.edit()
                .putString(SettingsManager.KEY_PIN_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
                .putString(SettingsManager.KEY_PIN_HASH, Base64.encodeToString(hash, Base64.NO_WRAP))
                .putBoolean(SettingsManager.KEY_MUST_CHANGE_DEFAULT_PIN, true)
                .apply()
        }
    }

    private fun generateRandomSalt(): ByteArray {
        val salt = ByteArray(SALT_BYTES)
        secureRandom.nextBytes(salt)
        return salt
    }

    private fun hashPinPbkdf2(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, PBKDF2_ITERATIONS, PBKDF2_KEY_LENGTH)
        return try {
            val factory = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM)
            factory.generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    fun isDefaultPinChangeRequired(): Boolean {
        return settingsManager.getRawPreferences()
            .getBoolean(SettingsManager.KEY_MUST_CHANGE_DEFAULT_PIN, true)
    }

    fun verifyPin(inputPin: String, clientIp: String = "LOCAL"): Boolean {
        if (isIpBlocked(clientIp)) return false

        val prefs = settingsManager.getRawPreferences()
        val saltB64 = prefs.getString(SettingsManager.KEY_PIN_SALT, null) ?: return false
        val hashB64 = prefs.getString(SettingsManager.KEY_PIN_HASH, null) ?: return false

        val salt = Base64.decode(saltB64, Base64.NO_WRAP)
        val expectedHash = Base64.decode(hashB64, Base64.NO_WRAP)
        val candidateHash = hashPinPbkdf2(inputPin.trim(), salt)

        // Comparação em tempo constante contra Timing Attack
        val matched = MessageDigest.isEqual(expectedHash, candidateHash)
        if (matched) {
            bruteForceAttempts.remove(clientIp)
        } else {
            registerFailedAttempt(clientIp)
        }
        return matched
    }

    /**
     * Altera o PIN exigindo o PIN atual e proibindo manter "1234" ou PIN com menos de 4 dígitos.
     */
    fun changePin(currentPin: String, newPin: String, clientIp: String = "LOCAL"): Result<Unit> {
        val cleanNew = newPin.trim()
        if (cleanNew.length < 4 || !cleanNew.all { it.isDigit() }) {
            return Result.failure(IllegalArgumentException("O novo PIN deve conter pelo menos 4 dígitos numéricos."))
        }
        if (cleanNew == DEFAULT_PIN) {
            return Result.failure(IllegalArgumentException("Por segurança, o novo PIN não pode ser 1234."))
        }
        if (!verifyPin(currentPin, clientIp)) {
            return Result.failure(SecurityException("O PIN atual informado está incorreto."))
        }

        val newSalt = generateRandomSalt()
        val newHash = hashPinPbkdf2(cleanNew, newSalt)

        settingsManager.getRawPreferences().edit()
            .putString(SettingsManager.KEY_PIN_SALT, Base64.encodeToString(newSalt, Base64.NO_WRAP))
            .putString(SettingsManager.KEY_PIN_HASH, Base64.encodeToString(newHash, Base64.NO_WRAP))
            .putBoolean(SettingsManager.KEY_MUST_CHANGE_DEFAULT_PIN, false)
            .apply()

        // Atualiza sessões ativas daquele cliente
        activeSessions.values.forEach { session ->
            if (session.clientIp == clientIp || clientIp == "LOCAL") {
                session.mustChangePin = false
            }
        }
        return Result.success(Unit)
    }

    private fun isIpBlocked(clientIp: String): Boolean {
        val record = bruteForceAttempts[clientIp] ?: return false
        val (count, lockUntil) = record
        val now = System.currentTimeMillis()
        if (count >= 5 && now < lockUntil) {
            return true
        }
        if (now >= lockUntil && count >= 5) {
            bruteForceAttempts.remove(clientIp)
        }
        return false
    }

    private fun registerFailedAttempt(clientIp: String) {
        val now = System.currentTimeMillis()
        val current = bruteForceAttempts[clientIp]
        val nextCount = (current?.first ?: 0) + 1
        val lockTime = if (nextCount >= 5) now + 60_000L else now
        bruteForceAttempts[clientIp] = Pair(nextCount, lockTime)
    }

    // =========================================================================
    // Item 2: Sessões Web, 2FA com VISIBILITY_SECRET e Revogação ao Perder Wi-Fi
    // =========================================================================

    fun createPreAuthSession(clientIp: String, userAgent: String): WebSession {
        cleanExpiredSessions()
        val token = UUID.randomUUID().toString().replace("-", "")
        val now = System.currentTimeMillis()
        val session = WebSession(
            sessionId = token,
            clientIp = clientIp,
            userAgent = userAgent,
            createdAt = now,
            lastActivityAt = now,
            is2FaVerified = false,
            mustChangePin = isDefaultPinChangeRequired()
        )
        activeSessions[token] = session
        return session
    }

    /**
     * Gera um código 2FA de 6 dígitos e dispara notificação com VISIBILITY_SECRET (Item 2).
     */
    fun issue2FaChallenge(key: String, clientIp: String, purpose: String): String {
        val code = String.format("%06d", secureRandom.nextInt(1_000_000))
        val challenge = Pending2FaChallenge(
            code = code,
            clientIp = clientIp,
            purpose = purpose,
            expiresAt = System.currentTimeMillis() + CHALLENGE_2FA_TIMEOUT_MS
        )
        pendingChallenges[key] = challenge
        sendSecret2FaNotification(code, clientIp, purpose)
        return code
    }

    fun verify2FaChallenge(key: String, inputCode: String, clientIp: String): Boolean {
        val challenge = pendingChallenges[key] ?: return false
        val now = System.currentTimeMillis()
        if (now > challenge.expiresAt) {
            pendingChallenges.remove(key)
            return false
        }
        if (challenge.clientIp != clientIp && challenge.clientIp != "P2P") {
            return false
        }
        val cleanInput = inputCode.trim()
        val matched = MessageDigest.isEqual(
            challenge.code.toByteArray(Charsets.UTF_8),
            cleanInput.toByteArray(Charsets.UTF_8)
        )
        if (matched) {
            pendingChallenges.remove(key)
            activeSessions[key]?.let { session ->
                session.is2FaVerified = true
                session.lastActivityAt = now
                session.action2FaUntil = now + ACTION_WINDOW_2FA_MS
            }
        }
        return matched
    }

    fun getValidSession(sessionId: String?, clientIp: String): WebSession? {
        if (sessionId.isNullOrBlank()) return null
        cleanExpiredSessions()
        val session = activeSessions[sessionId] ?: return null
        if (session.clientIp != clientIp) {
            activeSessions.remove(sessionId)
            return null
        }
        session.lastActivityAt = System.currentTimeMillis()
        session.mustChangePin = isDefaultPinChangeRequired()
        return session
    }

    fun isAction2FaAuthorized(session: WebSession): Boolean {
        return session.is2FaVerified && System.currentTimeMillis() <= session.action2FaUntil
    }

    fun refreshAction2FaWindow(sessionId: String) {
        activeSessions[sessionId]?.let {
            it.action2FaUntil = System.currentTimeMillis() + ACTION_WINDOW_2FA_MS
        }
    }

    fun revokeSession(sessionId: String?) {
        if (!sessionId.isNullOrBlank()) {
            activeSessions.remove(sessionId)
            pendingChallenges.remove(sessionId)
        }
    }

    fun revokeAllSessions(reason: String = "Manual") {
        activeSessions.clear()
        pendingChallenges.clear()
    }

    fun getActiveSessionsCount(): Int {
        cleanExpiredSessions()
        return activeSessions.count { it.value.is2FaVerified }
    }

    private fun cleanExpiredSessions() {
        val now = System.currentTimeMillis()
        activeSessions.entries.removeIf { (_, session) ->
            (now - session.lastActivityAt) > SESSION_TIMEOUT_MS
        }
        pendingChallenges.entries.removeIf { (_, challenge) ->
            now > challenge.expiresAt
        }
    }

    private fun createSecret2FaNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_2FA_ID,
                "Códigos de Segurança 2FA (IFGestor)",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Exibe códigos de verificação em duas etapas ocultos na tela de bloqueio"
                lockscreenVisibility = NotificationCompat.VISIBILITY_SECRET
            }
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    /**
     * Dispara a notificação 2FA com VISIBILITY_SECRET estrito (Item 2).
     */
    private fun sendSecret2FaNotification(code: String, clientIp: String, purpose: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val publicNotification = NotificationCompat.Builder(context, CHANNEL_2FA_ID)
            .setSmallIcon(R.drawable.ic_ifgestor_logo)
            .setContentTitle("IFGestor • Solicitação de Segurança")
            .setContentText("Desbloqueie o aparelho para visualizar o código 2FA.")
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()

        val notification = NotificationCompat.Builder(context, CHANNEL_2FA_ID)
            .setSmallIcon(R.drawable.ic_ifgestor_logo)
            .setContentTitle("Código 2FA: $code")
            .setContentText("$purpose • Origem: $clientIp (válido por 3 min)")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("Código de autorização: $code\nSolicitação: $purpose\nDispositivo/IP: $clientIp\nOculto na tela bloqueada por segurança.")
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_SECURITY)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setPublicVersion(publicNotification)
            .setAutoCancel(true)
            .build()

        val id = NOTIFICATION_2FA_BASE_ID + (System.currentTimeMillis() % 500).toInt()
        nm.notify(id, notification)
    }

    private fun registerWifiLossMonitor() {
        if (wifiCallbackRegistered) return
        try {
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            connectivityManager.registerNetworkCallback(request, wifiNetworkCallback)
            wifiCallbackRegistered = true
        } catch (_: Exception) {
        }
    }

    fun unregisterMonitors() {
        if (wifiCallbackRegistered) {
            try {
                connectivityManager.unregisterNetworkCallback(wifiNetworkCallback)
            } catch (_: Exception) {
            }
            wifiCallbackRegistered = false
        }
    }

    // =========================================================================
    // Item 3: Validação Estrita de Sandbox (DCIM/Camera/IFgestor)
    // =========================================================================

    fun validateSandboxFile(file: File): Boolean {
        return settingsManager.isInsideSandbox(file)
    }
}
