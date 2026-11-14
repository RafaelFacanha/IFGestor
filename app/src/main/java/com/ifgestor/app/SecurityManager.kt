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
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class SecurityManager(context: Context) {

    private val appContext = context.applicationContext
    private val settings = SettingsManager(appContext)
    private val prefs = settings.getRawPreferences()
    private val secureRandom = SecureRandom()

    // Sessões Web ativas e desafios 2FA em memória (isolados por token/dispositivo)
    private val activeSessions = ConcurrentHashMap<String, WebSession>()
    private val pending2faChallenges = ConcurrentHashMap<String, Pending2faChallenge>()
    private val p2pApprovals = ConcurrentHashMap<String, P2pChallenge>()
    private val failedAttemptsByIp = ConcurrentHashMap<String, BruteForceRecord>()

    private var wifiCallbackRegistered = false
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    // Callback opcional para registrar eventos automaticamente no AuditLogManager
    var onSecurityAuditEvent: ((action: String, actor: String, details: String, success: Boolean) -> Unit)? = null

    data class WebSession(
        val token: String,
        val clientIp: String,
        val userAgent: String,
        val createdAt: Long = System.currentTimeMillis(),
        @Volatile var lastAccessAt: Long = System.currentTimeMillis(),
        @Volatile var is2faVerified: Boolean = false,
        @Volatile var mustChangeDefaultPin: Boolean = false
    )

    data class Pending2faChallenge(
        val code: String,
        val sessionToken: String,
        val clientIp: String,
        val purpose: String,
        val expiresAt: Long
    )

    data class P2pChallenge(
        val transferId: String,
        val peerName: String,
        val peerIp: String,
        val direction: String, // "OUTGOING" (Origem) ou "INCOMING" (Destino)
        val code: String,
        val expiresAt: Long,
        @Volatile var approved: Boolean = false
    )

    private data class BruteForceRecord(
        var attempts: Int = 0,
        var lockedUntil: Long = 0L
    )

    enum class PinStatus {
        SUCCESS,
        MUST_CHANGE_DEFAULT_PIN,
        INVALID_PIN,
        RATE_LIMITED
    }

    init {
        ensureNotificationChannel()
        ensureDefaultPinInitialized()
        registerWifiLossMonitor()
    }

    // =========================================================================
    // ITEM 1: Criptografia PBKDF2WithHmacSHA256 + Salt e Troca do PIN 1234
    // =========================================================================

    private fun ensureDefaultPinInitialized() {
        val existingHash = prefs.getString(SettingsManager.KEY_PIN_HASH, null)
        val existingSalt = prefs.getString(SettingsManager.KEY_PIN_SALT, null)
        if (existingHash.isNullOrBlank() || existingSalt.isNullOrBlank()) {
            val salt = ByteArray(SALT_LENGTH_BYTES)
            secureRandom.nextBytes(salt)
            val hash = pbkdf2Hash(DEFAULT_INITIAL_PIN, salt)
            prefs.edit()
                .putString(SettingsManager.KEY_PIN_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
                .putString(SettingsManager.KEY_PIN_HASH, Base64.encodeToString(hash, Base64.NO_WRAP))
                .putBoolean(SettingsManager.KEY_MUST_CHANGE_DEFAULT_PIN, true)
                .apply()
        }
    }

    private fun pbkdf2Hash(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH_BITS)
        return try {
            val factory = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM)
            factory.generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    /**
     * Verifica se o PIN padrão 1234 ainda está ativo e exige substituição obrigatória (Item 1).
     */
    fun isMustChangeDefaultPin(): Boolean {
        return prefs.getBoolean(SettingsManager.KEY_MUST_CHANGE_DEFAULT_PIN, true)
    }

    /**
     * Verifica o PIN informado em tempo constante (MessageDigest.isEqual) e aplica bloqueio anti-força bruta.
     */
    fun verifyPin(inputPin: String, clientIp: String = "mobile-local"): PinStatus {
        val now = System.currentTimeMillis()
        val record = failedAttemptsByIp.getOrPut(clientIp) { BruteForceRecord() }

        if (record.lockedUntil > now) {
            onSecurityAuditEvent?.invoke(
                "LOGIN_BLOQUEADO",
                clientIp,
                "IP temporariamente bloqueado por excesso de tentativas incorretas",
                false
            )
            return PinStatus.RATE_LIMITED
        }

        val saltB64 = prefs.getString(SettingsManager.KEY_PIN_SALT, null) ?: return PinStatus.INVALID_PIN
        val hashB64 = prefs.getString(SettingsManager.KEY_PIN_HASH, null) ?: return PinStatus.INVALID_PIN

        val salt = Base64.decode(saltB64, Base64.NO_WRAP)
        val expectedHash = Base64.decode(hashB64, Base64.NO_WRAP)
        val candidateHash = pbkdf2Hash(inputPin.trim(), salt)

        val matched = MessageDigest.isEqual(expectedHash, candidateHash)
        if (!matched) {
            record.attempts += 1
            if (record.attempts >= MAX_FAILED_ATTEMPTS) {
                record.lockedUntil = now + LOCKOUT_DURATION_MS
                record.attempts = 0
            }
            onSecurityAuditEvent?.invoke("PIN_INVALIDO", clientIp, "Tentativa de PIN incorreta", false)
            return PinStatus.INVALID_PIN
        }

        // Sucesso: limpa contador de falhas do IP
        failedAttemptsByIp.remove(clientIp)

        return if (isMustChangeDefaultPin() || inputPin.trim() == DEFAULT_INITIAL_PIN) {
            onSecurityAuditEvent?.invoke(
                "PIN_PADRAO_DETECTADO",
                clientIp,
                "Login com PIN inicial 1234; troca obrigatória exigida",
                true
            )
            PinStatus.MUST_CHANGE_DEFAULT_PIN
        } else {
            onSecurityAuditEvent?.invoke("PIN_VALIDADO", clientIp, "PIN autenticado via PBKDF2", true)
            PinStatus.SUCCESS
        }
    }

    /**
     * Altera o PIN exigindo validação do PIN atual, rejeitando "1234" ou sequências inválidas,
     * e gerando novo salt criptográfico aleatório.
     */
    fun changePin(currentPin: String, newPin: String, actorIp: String = "mobile-local"): Result<Unit> {
        val cleanNew = newPin.trim()
        if (!cleanNew.matches(Regex("^\\d{4,8}$"))) {
            return Result.failure(IllegalArgumentException("O novo PIN deve conter entre 4 e 8 dígitos numéricos."))
        }
        if (cleanNew == DEFAULT_INITIAL_PIN) {
            return Result.failure(IllegalArgumentException("Por segurança, o novo PIN não pode ser 1234."))
        }

        val currentStatus = verifyPin(currentPin, actorIp)
        if (currentStatus != PinStatus.SUCCESS && currentStatus != PinStatus.MUST_CHANGE_DEFAULT_PIN) {
            return Result.failure(SecurityException("O PIN atual informado está incorreto."))
        }

        val newSalt = ByteArray(SALT_LENGTH_BYTES)
        secureRandom.nextBytes(newSalt)
        val newHash = pbkdf2Hash(cleanNew, newSalt)

        prefs.edit()
            .putString(SettingsManager.KEY_PIN_SALT, Base64.encodeToString(newSalt, Base64.NO_WRAP))
            .putString(SettingsManager.KEY_PIN_HASH, Base64.encodeToString(newHash, Base64.NO_WRAP))
            .putBoolean(SettingsManager.KEY_MUST_CHANGE_DEFAULT_PIN, false)
            .apply()

        // Atualiza sessões ativas do próprio usuário para liberar a flag de troca obrigatória
        activeSessions.values.forEach { it.mustChangeDefaultPin = false }

        onSecurityAuditEvent?.invoke("TROCA_DE_PIN", actorIp, "PIN atualizado com novo salt PBKDF2", true)
        return Result.success(Unit)
    }

    // =========================================================================
    // ITEM 2: Sessões Isoladas, 2FA com VISIBILITY_SECRET e Revogação por Wi-Fi
    // =========================================================================

    fun createWebSession(clientIp: String, userAgent: String, mustChangePin: Boolean): WebSession {
        val tokenBytes = ByteArray(24)
        secureRandom.nextBytes(tokenBytes)
        val token = Base64.encodeToString(tokenBytes, Base64.URL_SAFE or Base64.NO_WRAP)

        val session = WebSession(
            token = token,
            clientIp = clientIp,
            userAgent = userAgent.take(120),
            mustChangeDefaultPin = mustChangePin
        )
        activeSessions[token] = session
        return session
    }

    /**
     * Gera desafio 2FA de 6 dígitos e exibe notificação com VISIBILITY_SECRET (oculta na tela bloqueada).
     */
    fun issue2faChallenge(sessionToken: String, clientIp: String, purpose: String = "Acesso Web PC"): String {
        val code = String.format("%06d", secureRandom.nextInt(1_000_000))
        val challenge = Pending2faChallenge(
            code = code,
            sessionToken = sessionToken,
            clientIp = clientIp,
            purpose = purpose,
            expiresAt = System.currentTimeMillis() + TWO_FA_EXPIRATION_MS
        )
        pending2faChallenges[sessionToken] = challenge

        showSecret2faNotification(
            notificationId = NOTIFICATION_ID_WEB_2FA,
            title = "Código 2FA: $code",
            message = "Solicitação ($purpose) de $clientIp. Expira em 2 min.",
            publicRedactedTitle = "IFGestor: Autorização 2FA solicitada",
            publicRedactedText = "Desbloqueie o aparelho para visualizar o código de segurança."
        )

        onSecurityAuditEvent?.invoke("DESAFIO_2FA_GERADO", clientIp, "Desafio 2FA enviado ($purpose)", true)
        return code
    }

    fun verify2faChallenge(sessionToken: String, submittedCode: String, clientIp: String): Boolean {
        val challenge = pending2faChallenges[sessionToken] ?: return false
        val now = System.currentTimeMillis()
        if (now > challenge.expiresAt || challenge.clientIp != clientIp) {
            pending2faChallenges.remove(sessionToken)
            onSecurityAuditEvent?.invoke("2FA_EXPIRADO_OU_IP_DIVERGENTE", clientIp, "Falha de validação 2FA", false)
            return false
        }

        val isMatch = MessageDigest.isEqual(
            challenge.code.toByteArray(Charsets.UTF_8),
            submittedCode.trim().toByteArray(Charsets.UTF_8)
        )

        if (isMatch) {
            pending2faChallenges.remove(sessionToken)
            activeSessions[sessionToken]?.let {
                it.is2faVerified = true
                it.lastAccessAt = now
            }
            cancelNotification(NOTIFICATION_ID_WEB_2FA)
            onSecurityAuditEvent?.invoke("2FA_APROVADO", clientIp, "Sessão autorizada com 2FA (${challenge.purpose})", true)
            return true
        }

        onSecurityAuditEvent?.invoke("2FA_ INCORRETO", clientIp, "Código 2FA inválido informado", false)
        return false
    }

    /**
     * Valida se o token pertence a uma sessão ativa, autenticada com 2FA, dentro da janela de tempo e do mesmo IP.
     */
    fun getValidAuthenticatedSession(token: String?, clientIp: String): WebSession? {
        if (token.isNullOrBlank()) return null
        val session = activeSessions[token] ?: return null
        val now = System.currentTimeMillis()

        if (session.clientIp != clientIp || (now - session.lastAccessAt) > SESSION_TIMEOUT_MS) {
            activeSessions.remove(token)
            return null
        }

        if (!session.is2faVerified) return null

        session.lastAccessAt = now
        return session
    }

    /**
     * Permite validar sessão pré-2FA (ex: durante a etapa de troca obrigatória do PIN 1234 ou verificação do 2FA).
     */
    fun getPreAuthSession(token: String?, clientIp: String): WebSession? {
        if (token.isNullOrBlank()) return null
        val session = activeSessions[token] ?: return null
        if (session.clientIp != clientIp) return null
        session.lastAccessAt = System.currentTimeMillis()
        return session
    }

    fun revokeSession(token: String?, actorIp: String = "local") {
        if (!token.isNullOrBlank()) {
            activeSessions.remove(token)
            pending2faChallenges.remove(token)
            onSecurityAuditEvent?.invoke("LOGOUT_SESSAO", actorIp, "Sessão encerrada", true)
        }
    }

    /**
     * Revoga imediatamente todas as sessões Web e desafios 2FA pendentes (ex: ao perder Wi-Fi ou desligar servidor).
     */
    fun revokeAllSessions(reason: String) {
        val count = activeSessions.size + pending2faChallenges.size + p2pApprovals.size
        activeSessions.clear()
        pending2faChallenges.clear()
        p2pApprovals.clear()
        if (count > 0) {
            onSecurityAuditEvent?.invoke("REVOGACAO_GLOBAL_SESSOES", "Sistema", "Motivo: $reason ($count sessões limpas)", true)
        }
    }

    // =========================================================================
    // ITEM 8: Duplo 2FA para Transferência P2P (Origem e Destino)
    // =========================================================================

    fun createP2pChallenge(
        transferId: String,
        peerName: String,
        peerIp: String,
        direction: String
    ): P2pChallenge {
        val code = String.format("%06d", secureRandom.nextInt(1_000_000))
        val challenge = P2pChallenge(
            transferId = transferId,
            peerName = peerName,
            peerIp = peerIp,
            direction = direction,
            code = code,
            expiresAt = System.currentTimeMillis() + TWO_FA_EXPIRATION_MS
        )
        p2pApprovals[transferId] = challenge

        val actionLabel = if (direction == "OUTGOING") "Envio P2P para $peerName" else "Recebimento P2P de $peerName"
        showSecret2faNotification(
            notificationId = NOTIFICATION_ID_P2P_2FA,
            title = "2FA P2P ($code) - $actionLabel",
            message = "Confirme o código $code para autorizar a transferência com $peerIp.",
            publicRedactedTitle = "IFGestor: Transferência P2P aguarda 2FA",
            publicRedactedText = "Desbloqueie o celular para ver o código de autorização P2P."
        )

        onSecurityAuditEvent?.invoke("P2P_2FA_SOLICITADO", peerIp, actionLabel, true)
        return challenge
    }

    fun verifyP2pChallenge(transferId: String, submittedCode: String): Boolean {
        val challenge = p2pApprovals[transferId] ?: return false
        if (System.currentTimeMillis() > challenge.expiresAt) {
            p2pApprovals.remove(transferId)
            return false
        }
        val ok = MessageDigest.isEqual(
            challenge.code.toByteArray(Charsets.UTF_8),
            submittedCode.trim().toByteArray(Charsets.UTF_8)
        )
        if (ok) {
            challenge.approved = true
            cancelNotification(NOTIFICATION_ID_P2P_2FA)
            onSecurityAuditEvent?.invoke(
                "P2P_2FA_APROVADO",
                challenge.peerIp,
                "Transferência ${challenge.transferId} (${challenge.direction}) autorizada",
                true
            )
        }
        return ok
    }

    fun isP2pTransferApproved(transferId: String): Boolean {
        val challenge = p2pApprovals[transferId] ?: return false
        if (System.currentTimeMillis() > challenge.expiresAt) {
            p2pApprovals.remove(transferId)
            return false
        }
        return challenge.approved
    }

    // =========================================================================
    // ITEM 3: Validação Estrita de Sandbox (DCIM/Camera/IFgestor)
    // =========================================================================

    fun assertInsideSandbox(file: File): File {
        if (!settings.isInsideSandbox(file)) {
            onSecurityAuditEvent?.invoke(
                "VIOLACAO_SANDBOX_BLOQUEADA",
                "Sistema",
                "Tentativa de acesso fora de DCIM/Camera/IFgestor: ${file.path}",
                false
            )
            throw SecurityException("Acesso negado: caminho fora do Sandbox DCIM/Camera/IFgestor.")
        }
        return file
    }

    // =========================================================================
    // Monitoramento de Perda de Wi-Fi e Notificações Sigilosas (Item 2)
    // =========================================================================

    private fun registerWifiLossMonitor() {
        if (wifiCallbackRegistered) return
        try {
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()

            connectivityManager.registerNetworkCallback(
                request,
                object : ConnectivityManager.NetworkCallback() {
                    override fun onLost(network: Network) {
                        super.onLost(network)
                        revokeAllSessions("Conexão Wi-Fi perdida")
                    }
                }
            )
            wifiCallbackRegistered = true
        } catch (_: Exception) {
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID_2FA,
                "Segurança e Códigos 2FA",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Exibe códigos de verificação em duas etapas (ocultos na tela de bloqueio)"
                lockscreenVisibility = NotificationCompat.VISIBILITY_SECRET
            }
            manager.createNotificationChannel(channel)
        }
    }

    private fun showSecret2faNotification(
        notificationId: Int,
        title: String,
        message: String,
        publicRedactedTitle: String,
        publicRedactedText: String
    ) {
        val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Versão pública sem o código para caso o sistema tente exibir resumo
        val publicNotification = NotificationCompat.Builder(appContext, CHANNEL_ID_2FA)
            .setSmallIcon(R.drawable.ic_ifgestor_logo)
            .setContentTitle(publicRedactedTitle)
            .setContentText(publicRedactedText)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

        // Notificação principal com VISIBILITY_SECRET (Item 2)
        val secretNotification = NotificationCompat.Builder(appContext, CHANNEL_ID_2FA)
            .setSmallIcon(R.drawable.ic_ifgestor_logo)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setPublicVersion(publicNotification)
            .setAutoCancel(true)
            .setTimeoutAfter(TWO_FA_EXPIRATION_MS)
            .build()

        try {
            manager.notify(notificationId, secretNotification)
        } catch (_: SecurityException) {
        }
    }

    private fun cancelNotification(notificationId: Int) {
        val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(notificationId)
    }

    companion object {
        private const val PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256"
        private const val PBKDF2_ITERATIONS = 15_000
        private const val KEY_LENGTH_BITS = 256
        private const val SALT_LENGTH_BYTES = 16
        const val DEFAULT_INITIAL_PIN = "1234"

        private const val MAX_FAILED_ATTEMPTS = 5
        private const val LOCKOUT_DURATION_MS = 60_000L
        private const val TWO_FA_EXPIRATION_MS = 120_000L
        private const val SESSION_TIMEOUT_MS = 30 * 60 * 1000L

        private const val CHANNEL_ID_2FA = "ifgestor_security_2fa_channel"
        private const val NOTIFICATION_ID_WEB_2FA = 9201
        private const val NOTIFICATION_ID_P2P_2FA = 9202

        @Volatile
        private var INSTANCE: SecurityManager? = null

        fun getInstance(context: Context): SecurityManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SecurityManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
