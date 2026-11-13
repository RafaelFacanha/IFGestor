package com.ifgestor.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.media.ThumbnailUtils
import android.os.Build
import android.provider.MediaStore
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Size
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.math.BigInteger
import java.net.URLDecoder
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocketFactory
import javax.security.auth.x500.X500Principal

object WebServerManager {

    private const val KEYSTORE_ALIAS = "ifgestor_local_https_key"
    private const val SESSION_TTL_MS = 30 * 60 * 1000L
    private const val ACTION_GRANT_TTL_MS = 90 * 1000L
    private const val P2P_TOKEN_TTL_MS = 10 * 60 * 1000L
    private const val MAX_FAILED_ATTEMPTS = 5
    private const val LOCKOUT_DURATION_MS = 60 * 1000L

    data class SessionInfo(
        val token: String,
        val ip: String,
        var expiresAt: Long,
        var actionAuthorizedUntil: Long = 0L
    )

    data class Pending2faChallenge(
        val requestId: String,
        val code: String,
        val ip: String,
        val purpose: String, // LOGIN, ACTION, P2P_RECEIVE
        val description: String,
        val createdAt: Long = System.currentTimeMillis(),
        @Volatile var status: String = "PENDING", // PENDING, APPROVED, REJECTED
        var sessionToken: String? = null
    )

    data class BruteForceRecord(
        var failures: Int = 0,
        var lockedUntil: Long = 0L
    )

    private var serverInstance: IFGestorHttpServer? = null
    private val activeSessions = ConcurrentHashMap<String, SessionInfo>()
    private val pendingChallenges = ConcurrentHashMap<String, Pending2faChallenge>()
    private val p2pReceiveTokens = ConcurrentHashMap<String, Long>()
    private val bruteForceMap = ConcurrentHashMap<String, BruteForceRecord>()
    private val backgroundWorker = Executors.newCachedThreadPool()

    @Volatile
    private var runningHttps: Boolean = false

    fun isRunning(): Boolean = serverInstance?.isAlive == true

    fun isRunningHttps(): Boolean = runningHttps

    fun getServerUrl(context: Context): String {
        val scheme = if (isRunning() && runningHttps) {
            "https"
        } else if (SettingsManager.isHttpsEnabled(context)) {
            "https"
        } else {
            "http"
        }
        val ip = NetworkDiscoveryManager.getLocalIpAddress(context)
        val port = SettingsManager.getServerPort(context)
        return "$scheme://$ip:$port"
    }

    fun getMdnsUrl(context: Context): String {
        val scheme = if (isRunning() && runningHttps) {
            "https"
        } else if (SettingsManager.isHttpsEnabled(context)) {
            "https"
        } else {
            "http"
        }
        val domain = SettingsManager.getActiveMdnsDomain(context)
        val port = SettingsManager.getServerPort(context)
        return "$scheme://$domain:$port"
    }

    @Synchronized
    fun startServer(
        context: Context,
        port: Int = SettingsManager.getServerPort(context),
        enableHttps: Boolean = SettingsManager.isHttpsEnabled(context)
    ): Boolean {
        val appCtx = context.applicationContext
        if (serverInstance?.isAlive == true) {
            return true
        }
        stopServer(appCtx)

        return try {
            val srv = IFGestorHttpServer(appCtx, port)
            var httpsConfigured = false

            if (enableHttps) {
                val sslFactory = buildSelfSignedServerSocketFactory()
                if (sslFactory != null) {
                    srv.makeSecure(sslFactory, null)
                    httpsConfigured = true
                }
            }

            srv.start(15000, false)
            serverInstance = srv
            runningHttps = httpsConfigured

            SettingsManager.appendAuditLog(
                context = appCtx,
                ip = NetworkDiscoveryManager.getLocalIpAddress(appCtx),
                category = "SERVIDOR_WEB",
                action = "SERVIDOR_INICIADO",
                details = "Porta $port (${if (httpsConfigured) "HTTPS/TLS" else "HTTP"}) • Dominio ${SettingsManager.getActiveMdnsDomain(appCtx)}",
                success = true
            )
            true
        } catch (e: Exception) {
            runningHttps = false
            SettingsManager.appendAuditLog(
                context = appCtx,
                ip = "LOCAL",
                category = "SERVIDOR_WEB",
                action = "ERRO_INICIAR_SERVIDOR",
                details = e.message ?: "Erro desconhecido ao iniciar NanoHTTPD",
                success = false
            )
            false
        }
    }

    @Synchronized
    fun stopServer(context: Context? = null) {
        try {
            serverInstance?.stop()
        } catch (_: Exception) {
        }
        serverInstance = null
        runningHttps = false
        activeSessions.clear()
        pendingChallenges.clear()
        p2pReceiveTokens.clear()
        if (context != null) {
            MediaInterceptorService.cancel2faNotification(context)
        }
    }

    fun revokeAllSessions(context: Context? = null, reason: String = "Revogacao de Seguranca") {
        activeSessions.clear()
        pendingChallenges.clear()
        p2pReceiveTokens.clear()
        if (context != null) {
            MediaInterceptorService.cancel2faNotification(context)
            SettingsManager.appendAuditLog(
                context = context,
                ip = "SISTEMA",
                category = "SEGURANCA",
                action = "SESSOES_ENCERRADAS",
                details = "Todas as sessoes web foram revogadas: $reason",
                success = true
            )
        }
    }

    // =========================================================================
    // 1. CONTROLE DE APROVACAO 2FA (NOTIFICACAO OU PAINEL DO APP)
    // =========================================================================

    fun getLatestPendingChallenge(): Pending2faChallenge? {
        val now = System.currentTimeMillis()
        pendingChallenges.entries.removeIf { (_, ch) -> (now - ch.createdAt) > 120_000L }
        return pendingChallenges.values
            .filter { it.status == "PENDING" }
            .maxByOrNull { it.createdAt }
    }

    fun handle2faDecisionFromNotification(context: Context, requestId: String, approved: Boolean) {
        val challenge = if (requestId.isNotBlank()) {
            pendingChallenges[requestId]
        } else {
            getLatestPendingChallenge()
        } ?: return

        applyChallengeDecision(context, challenge, approved, "NOTIFICACAO_APARELHO")
    }

    fun approveLatestChallengeFromApp(context: Context): Boolean {
        val ch = getLatestPendingChallenge() ?: return false
        applyChallengeDecision(context, ch, true, "PAINEL_APP")
        MediaInterceptorService.cancel2faNotification(context)
        return true
    }

    fun rejectLatestChallengeFromApp(context: Context): Boolean {
        val ch = getLatestPendingChallenge() ?: return false
        applyChallengeDecision(context, ch, false, "PAINEL_APP")
        MediaInterceptorService.cancel2faNotification(context)
        return true
    }

    private fun applyChallengeDecision(
        context: Context,
        challenge: Pending2faChallenge,
        approved: Boolean,
        source: String
    ) {
        val now = System.currentTimeMillis()
        if (approved) {
            challenge.status = "APPROVED"
            when (challenge.purpose) {
                "LOGIN" -> {
                    val newToken = UUID.randomUUID().toString().replace("-", "")
                    activeSessions[newToken] = SessionInfo(
                        token = newToken,
                        ip = challenge.ip,
                        expiresAt = now + SESSION_TTL_MS,
                        actionAuthorizedUntil = now + ACTION_GRANT_TTL_MS
                    )
                    challenge.sessionToken = newToken
                }
                "ACTION" -> {
                    challenge.sessionToken?.let { tok ->
                        activeSessions[tok]?.let { sess ->
                            sess.expiresAt = now + SESSION_TTL_MS
                            sess.actionAuthorizedUntil = now + ACTION_GRANT_TTL_MS
                        }
                    }
                }
                "P2P_RECEIVE" -> {
                    val p2pToken = UUID.randomUUID().toString().replace("-", "")
                    p2pReceiveTokens[p2pToken] = now + P2P_TOKEN_TTL_MS
                    challenge.sessionToken = p2pToken
                }
            }
        } else {
            challenge.status = "REJECTED"
        }

        SettingsManager.appendAuditLog(
            context = context,
            ip = challenge.ip,
            category = "2FA",
            action = if (approved) "2FA_APROVADO" else "2FA_RECUSADO",
            details = "${challenge.purpose} (${challenge.description}) via $source",
            success = approved
        )
    }

    // =========================================================================
    // 2. GERACAO AUTOMATICA DE CERTIFICADO TLS AUTOASSINADO (ANDROID KEYSTORE)
    // =========================================================================

    private fun buildSelfSignedServerSocketFactory(): SSLServerSocketFactory? {
        return try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply {
                load(null)
            }

            if (!keyStore.containsAlias(KEYSTORE_ALIAS)) {
                val kpg = KeyPairGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_RSA,
                    "AndroidKeyStore"
                )
                val now = System.currentTimeMillis()
                val spec = KeyGenParameterSpec.Builder(
                    KEYSTORE_ALIAS,
                    KeyProperties.PURPOSE_SIGN or
                        KeyProperties.PURPOSE_VERIFY or
                        KeyProperties.PURPOSE_ENCRYPT or
                        KeyProperties.PURPOSE_DECRYPT
                )
                    .setKeySize(2048)
                    .setDigests(
                        KeyProperties.DIGEST_SHA256,
                        KeyProperties.DIGEST_SHA384,
                        KeyProperties.DIGEST_SHA512,
                        KeyProperties.DIGEST_NONE
                    )
                    .setSignaturePaddings(
                        KeyProperties.SIGNATURE_PADDING_RSA_PKCS1,
                        KeyProperties.SIGNATURE_PADDING_RSA_PSS
                    )
                    .setEncryptionPaddings(
                        KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1,
                        KeyProperties.ENCRYPTION_PADDING_NONE
                    )
                    .setCertificateSubject(X500Principal("CN=ifgestor.local, O=IFGestor Security"))
                    .setCertificateSerialNumber(BigInteger.valueOf(now))
                    .setCertificateNotBefore(Date(now - 86400_000L))
                    .setCertificateNotAfter(Date(now + 315360000_000L))
                    .build()

                kpg.initialize(spec)
                kpg.generateKeyPair()
            }

            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            kmf.init(keyStore, null)

            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(kmf.keyManagers, null, SecureRandom())
            val factory = sslContext.serverSocketFactory

            factory.createServerSocket().use { }
            factory
        } catch (_: Exception) {
            null
        }
    }

    // =========================================================================
    // 3. SERVIDOR HTTP/HTTPS EMBARCADO (NanoHTTPD)
    // =========================================================================

    private class IFGestorHttpServer(
        private val appContext: Context,
        port: Int
    ) : NanoHTTPD(port) {

        override fun serve(session: IHTTPSession): Response {
            val uri = session.uri ?: "/"
            val method = session.method
            val clientIp = (session.headers["remote-addr"]
                ?: session.headers["http-client-ip"]
                ?: "REDE_LOCAL").trim()

            return try {
                when {
                    uri == "/" && method == Method.GET -> {
                        htmlResponse(buildSinglePageWebAppHtml())
                    }
                    uri == "/api/auth/login" && method == Method.POST -> {
                        handleAuthLogin(session, clientIp)
                    }
                    uri == "/api/auth/poll-2fa" && method == Method.POST -> {
                        handlePoll2fa(session, clientIp)
                    }
                    uri == "/api/auth/request-action-2fa" && method == Method.POST -> {
                        handleRequestAction2fa(session, clientIp)
                    }
                    uri == "/api/auth/logout" && method == Method.POST -> {
                        handleLogout(session, clientIp)
                    }
                    uri == "/api/p2p/handshake" && method == Method.POST -> {
                        handleP2pHandshake(session, clientIp)
                    }
                    uri == "/api/p2p/upload" && method == Method.POST -> {
                        handleP2pIncomingUpload(session, clientIp)
                    }
                    uri == "/api/list" && method == Method.GET -> {
                        val sess = requireValidSession(session) ?: return unauthorizedJson()
                        handleApiList(session, sess)
                    }
                    uri == "/api/tasks" && method == Method.GET -> {
                        requireValidSession(session) ?: return unauthorizedJson()
                        handleApiTasks()
                    }
                    uri == "/api/peers" && method == Method.GET -> {
                        requireValidSession(session) ?: return unauthorizedJson()
                        handleApiPeers()
                    }
                    uri == "/api/mkdir" && method == Method.POST -> {
                        val sess = requireValidSession(session) ?: return unauthorizedJson()
                        handleApiMkdir(session, sess, clientIp)
                    }
                    uri == "/api/upload" && method == Method.POST -> {
                        val sess = requireValidSession(session) ?: return unauthorizedJson()
                        handleApiRawUpload(session, sess, clientIp)
                    }
                    uri == "/api/batch-action" && method == Method.POST -> {
                        val sess = requireValidSession(session) ?: return unauthorizedJson()
                        handleApiBatchAction(session, sess, clientIp)
                    }
                    uri == "/api/p2p/send-from-web" && method == Method.POST -> {
                        val sess = requireValidSession(session) ?: return unauthorizedJson()
                        handleApiP2pSendFromWeb(session, sess, clientIp)
                    }
                    uri == "/api/whatsapp/share" && method == Method.POST -> {
                        val sess = requireValidSession(session) ?: return unauthorizedJson()
                        handleApiWhatsAppShareOnPhone(session, sess, clientIp)
                    }
                    uri == "/api/thumb" && method == Method.GET -> {
                        requireValidSession(session) ?: return unauthorizedJson()
                        handleApiThumb(session)
                    }
                    uri == "/api/raw" && method == Method.GET -> {
                        requireValidSession(session) ?: return unauthorizedJson()
                        handleApiRawFile(session, clientIp, downloadAttachment = false)
                    }
                    uri == "/api/download" && method == Method.GET -> {
                        val sess = requireValidSession(session) ?: return unauthorizedJson()
                        if (!isActionAuthorized(sess)) {
                            return jsonResponse(
                                Response.Status.FORBIDDEN,
                                JSONObject().put("error", "2FA_REQUIRED").put("message", "Autorização 2FA necessária para download.")
                            )
                        }
                        handleApiRawFile(session, clientIp, downloadAttachment = true)
                    }
                    uri == "/api/download-zip" && method == Method.GET -> {
                        val sess = requireValidSession(session) ?: return unauthorizedJson()
                        if (!isActionAuthorized(sess)) {
                            return jsonResponse(
                                Response.Status.FORBIDDEN,
                                JSONObject().put("error", "2FA_REQUIRED").put("message", "Autorização 2FA necessária para exportar ZIP.")
                            )
                        }
                        handleApiStreamZip(session, clientIp)
                    }
                    else -> jsonResponse(
                        Response.Status.NOT_FOUND,
                        JSONObject().put("error", "Rota não encontrada")
                    )
                }
            } catch (e: Exception) {
                jsonResponse(
                    Response.Status.INTERNAL_ERROR,
                    JSONObject().put("error", e.message ?: "Erro interno no servidor")
                )
            }
        }

        private fun handleAuthLogin(session: IHTTPSession, clientIp: String): Response {
            val now = System.currentTimeMillis()
            val bf = bruteForceMap.getOrPut(clientIp) { BruteForceRecord() }
            if (bf.lockedUntil > now) {
                val waitSec = ((bf.lockedUntil - now) / 1000L).coerceAtLeast(1L)
                return jsonResponse(
                    Response.Status.FORBIDDEN,
                    JSONObject()
                        .put("ok", false)
                        .put("error", "Muitas tentativas incorretas. Aguarde ${waitSec}s.")
                )
            }

            if (SettingsManager.isPinChangeRequired(appContext)) {
                return jsonResponse(
                    Response.Status.FORBIDDEN,
                    JSONObject()
                        .put("ok", false)
                        .put("error", "Por segurança (OWASP), altere o PIN padrão (1234) na aba Servidor & 2FA do celular antes do primeiro acesso web.")
                )
            }

            val params = parseFormBody(session)
            val pin = params["pin"] ?: ""

            if (!SettingsManager.verifyPin(appContext, pin)) {
                bf.failures += 1
                if (bf.failures >= MAX_FAILED_ATTEMPTS) {
                    bf.lockedUntil = now + LOCKOUT_DURATION_MS
                    bf.failures = 0
                }
                SettingsManager.appendAuditLog(
                    context = appContext,
                    ip = clientIp,
                    category = "AUTENTICACAO",
                    action = "PIN_INVALIDO",
                    details = "Tentativa de login web com PIN incorreto",
                    success = false
                )
                return jsonResponse(
                    Response.Status.UNAUTHORIZED,
                    JSONObject().put("ok", false).put("error", "PIN incorreto.")
                )
            }

            bf.failures = 0
            bf.lockedUntil = 0L

            val reqId = UUID.randomUUID().toString().take(12)
            val code6 = String.format(Locale.US, "%06d", SecureRandom().nextInt(1_000_000))
            val challenge = Pending2faChallenge(
                requestId = reqId,
                code = code6,
                ip = clientIp,
                purpose = "LOGIN",
                description = "Acesso ao Gerenciador Web IFGestor"
            )
            pendingChallenges[reqId] = challenge

            MediaInterceptorService.show2faNotification(
                context = appContext,
                requestId = reqId,
                code = code6,
                title = "Login no Site Web",
                description = "Solicitação de acesso ao painel de arquivos",
                clientIp = clientIp
            )

            SettingsManager.appendAuditLog(
                context = appContext,
                ip = clientIp,
                category = "AUTENTICACAO",
                action = "DESAFIO_2FA_ENVIADO",
                details = "PIN validado; aguardando confirmacao 2FA no aparelho",
                success = true
            )

            return jsonResponse(
                Response.Status.OK,
                JSONObject()
                    .put("ok", true)
                    .put("requestId", reqId)
                    .put("message", "Confirme na notificação do celular ou digite o código de 6 dígitos.")
            )
        }

        private fun handleRequestAction2fa(session: IHTTPSession, clientIp: String): Response {
            val sess = requireValidSession(session) ?: return unauthorizedJson()
            val params = parseFormBody(session)
            val actionLabel = params["description"] ?: "Operação em arquivos"

            if (isActionAuthorized(sess)) {
                return jsonResponse(
                    Response.Status.OK,
                    JSONObject().put("ok", true).put("alreadyAuthorized", true)
                )
            }

            val reqId = UUID.randomUUID().toString().take(12)
            val code6 = String.format(Locale.US, "%06d", SecureRandom().nextInt(1_000_000))
            val challenge = Pending2faChallenge(
                requestId = reqId,
                code = code6,
                ip = clientIp,
                purpose = "ACTION",
                description = actionLabel,
                sessionToken = sess.token
            )
            pendingChallenges[reqId] = challenge

            MediaInterceptorService.show2faNotification(
                context = appContext,
                requestId = reqId,
                code = code6,
                title = "Autorizar Ação Web",
                description = actionLabel,
                clientIp = clientIp
            )

            return jsonResponse(
                Response.Status.OK,
                JSONObject()
                    .put("ok", true)
                    .put("alreadyAuthorized", false)
                    .put("requestId", reqId)
            )
        }

        private fun handlePoll2fa(session: IHTTPSession, clientIp: String): Response {
            val params = parseFormBody(session)
            val reqId = params["requestId"] ?: ""
            val typedCode = (params["code"] ?: "").trim()

            val challenge = pendingChallenges[reqId]
                ?: return jsonResponse(
                    Response.Status.NOT_FOUND,
                    JSONObject().put("status", "EXPIRED").put("error", "Desafio 2FA expirado.")
                )

            if (System.currentTimeMillis() - challenge.createdAt > 120_000L) {
                pendingChallenges.remove(reqId)
                MediaInterceptorService.cancel2faNotification(appContext)
                return jsonResponse(
                    Response.Status.OK,
                    JSONObject().put("status", "EXPIRED")
                )
            }

            if (typedCode.length == 6 && challenge.status == "PENDING") {
                if (typedCode == challenge.code) {
                    applyChallengeDecision(appContext, challenge, true, "CODIGO_6_DIGITOS_WEB")
                    MediaInterceptorService.cancel2faNotification(appContext)
                } else {
                    SettingsManager.appendAuditLog(
                        context = appContext,
                        ip = clientIp,
                        category = "2FA",
                        action = "CODIGO_2FA_INCORRETO",
                        details = "Codigo de 6 digitos invalido para ${challenge.description}",
                        success = false
                    )
                    return jsonResponse(
                        Response.Status.OK,
                        JSONObject().put("status", "INVALID_CODE").put("error", "Código 2FA incorreto.")
                    )
                }
            }

            return when (challenge.status) {
                "APPROVED" -> {
                    pendingChallenges.remove(reqId)
                    jsonResponse(
                        Response.Status.OK,
                        JSONObject()
                            .put("status", "APPROVED")
                            .put("token", challenge.sessionToken ?: "")
                    )
                }
                "REJECTED" -> {
                    pendingChallenges.remove(reqId)
                    jsonResponse(
                        Response.Status.OK,
                        JSONObject().put("status", "REJECTED")
                    )
                }
                else -> {
                    jsonResponse(
                        Response.Status.OK,
                        JSONObject().put("status", "PENDING")
                    )
                }
            }
        }

        private fun handleLogout(session: IHTTPSession, clientIp: String): Response {
            val token = extractToken(session)
            if (token != null) {
                activeSessions.remove(token)
            }
            SettingsManager.appendAuditLog(
                context = appContext,
                ip = clientIp,
                category = "AUTENTICACAO",
                action = "LOGOUT_WEB",
                details = "Sessao encerrada pelo navegador",
                success = true
            )
            return jsonResponse(Response.Status.OK, JSONObject().put("ok", true))
        }

        private fun handleP2pHandshake(session: IHTTPSession, clientIp: String): Response {
            val params = parseFormBody(session)
            val senderAlias = params["sender"] ?: clientIp
            val fileCount = params["count"]?.toIntOrNull() ?: 1
            val folder = params["folder"] ?: ""

            val reqId = "p2p_${UUID.randomUUID().toString().take(10)}"
            val code6 = String.format(Locale.US, "%06d", SecureRandom().nextInt(1_000_000))
            val desc = "Receber $fileCount arquivo(s) de $senderAlias ($clientIp) na pasta ${folder.ifEmpty { "IFgestor (Raiz)" }}"

            val challenge = Pending2faChallenge(
                requestId = reqId,
                code = code6,
                ip = clientIp,
                purpose = "P2P_RECEIVE",
                description = desc
            )
            pendingChallenges[reqId] = challenge

            MediaInterceptorService.show2faNotification(
                context = appContext,
                requestId = reqId,
                code = code6,
                title = "📲 Receber Arquivos P2P",
                description = desc,
                clientIp = clientIp
            )

            val deadline = System.currentTimeMillis() + 60_000L
            while (System.currentTimeMillis() < deadline) {
                val st = challenge.status
                if (st == "APPROVED") {
                    pendingChallenges.remove(reqId)
                    return jsonResponse(
                        Response.Status.OK,
                        JSONObject()
                            .put("approved", true)
                            .put("token", challenge.sessionToken ?: "")
                    )
                } else if (st == "REJECTED") {
                    pendingChallenges.remove(reqId)
                    return jsonResponse(
                        Response.Status.OK,
                        JSONObject().put("approved", false)
                    )
                }
                Thread.sleep(300L)
            }

            pendingChallenges.remove(reqId)
            MediaInterceptorService.cancel2faNotification(appContext)
            return jsonResponse(Response.Status.OK, JSONObject().put("approved", false))
        }

        private fun handleP2pIncomingUpload(session: IHTTPSession, clientIp: String): Response {
            val token = session.parms["token"] ?: ""
            val exp = p2pReceiveTokens[token] ?: 0L
            if (exp < System.currentTimeMillis()) {
                return jsonResponse(
                    Response.Status.FORBIDDEN,
                    JSONObject().put("ok", false).put("error", "Token P2P inválido ou expirado.")
                )
            }

            val rawName = session.parms["name"] ?: "p2p_${System.currentTimeMillis()}.jpg"
            val folderRel = SettingsManager.sanitizeSubfolderName(session.parms["folder"] ?: "")
            val index = session.parms["index"]?.toIntOrNull() ?: 1
            val total = session.parms["total"]?.toIntOrNull() ?: 1

            val targetDir = SettingsManager.resolveSafeSandboxFile(folderRel)
                ?: SettingsManager.getSandboxRootDir()
            if (!targetDir.exists()) targetDir.mkdirs()

            val finalFile = SettingsManager.resolveUniqueFile(targetDir, rawName)
            val partFile = File(targetDir, "${finalFile.name}.part")
            val contentLen = session.headers["content-length"]?.toLongOrNull() ?: 0L
            val taskId = "p2p_recv_$token"

            SettingsManager.tryLockFile(finalFile)
            return try {
                var receivedBytes = 0L
                BufferedOutputStream(FileOutputStream(partFile)).use { out ->
                    val buf = ByteArray(32 * 1024)
                    var remaining = contentLen
                    var lastUpdate = 0L
                    while (remaining > 0L) {
                        val toRead = minOf(buf.size.toLong(), remaining).toInt()
                        val read = session.inputStream.read(buf, 0, toRead)
                        if (read <= 0) break
                        out.write(buf, 0, read)
                        receivedBytes += read
                        remaining -= read

                        val now = System.currentTimeMillis()
                        if (now - lastUpdate >= 120L) {
                            lastUpdate = now
                            SettingsManager.upsertTask(
                                id = taskId,
                                title = "📥 Recebendo P2P ($index/$total)",
                                subtitle = "${finalFile.name} ← $clientIp",
                                type = "P2P_RECEIVE",
                                currentItems = index,
                                totalItems = total,
                                bytesProcessed = receivedBytes,
                                totalBytes = contentLen.coerceAtLeast(1L),
                                status = "RUNNING"
                            )
                        }
                    }
                    out.flush()
                }

                if (contentLen > 0L && partFile.length() < contentLen) {
                    partFile.delete()
                    throw IllegalStateException("Transferência P2P interrompida antes do fim.")
                }

                if (!partFile.renameTo(finalFile)) {
                    partFile.copyTo(finalFile, overwrite = true)
                    partFile.delete()
                }

                MediaScannerConnection.scanFile(
                    appContext,
                    arrayOf(finalFile.absolutePath),
                    null,
                    null
                )

                val doneAll = (index >= total)
                SettingsManager.upsertTask(
                    id = taskId,
                    title = if (doneAll) "✅ P2P Recebido ($total arq.)" else "📥 Recebendo P2P ($index/$total)",
                    subtitle = "${finalFile.name} salvo em ${folderRel.ifEmpty { "IFgestor" }}",
                    type = "P2P_RECEIVE",
                    currentItems = index,
                    totalItems = total,
                    bytesProcessed = contentLen,
                    totalBytes = contentLen.coerceAtLeast(1L),
                    status = if (doneAll) "COMPLETED" else "RUNNING"
                )

                SettingsManager.appendAuditLog(
                    context = appContext,
                    ip = clientIp,
                    category = "P2P",
                    action = "ARQUIVO_P2P_RECEBIDO",
                    details = "Recebido ${finalFile.name} (${finalFile.length()} bytes) de $clientIp",
                    success = true
                )

                IFGestorHomeWidget.requestWidgetUpdate(appContext)
                FloatingOverlayManager.refresh(appContext)

                jsonResponse(
                    Response.Status.OK,
                    JSONObject().put("ok", true).put("savedAs", finalFile.name)
                )
            } catch (e: Exception) {
                partFile.delete()
                SettingsManager.upsertTask(
                    id = taskId,
                    title = "❌ Erro ao Receber P2P",
                    subtitle = e.message ?: "Falha na gravação atômica",
                    type = "P2P_RECEIVE",
                    currentItems = index,
                    totalItems = total,
                    status = "FAILED"
                )
                jsonResponse(
                    Response.Status.INTERNAL_ERROR,
                    JSONObject().put("ok", false).put("error", e.message)
                )
            } finally {
                SettingsManager.unlockFile(finalFile)
            }
        }

        private fun handleApiList(session: IHTTPSession, sess: SessionInfo): Response {
            val requestedRel = SettingsManager.sanitizeSubfolderName(session.parms["folder"] ?: "")
            val currentDir = SettingsManager.resolveSafeSandboxFile(requestedRel)
                ?: SettingsManager.getSandboxRootDir()
            if (!currentDir.exists()) currentDir.mkdirs()

            val rootCanonical = SettingsManager.getSandboxRootDir().canonicalPath
            val currentCanonical = currentDir.canonicalPath
            val safeRelPath = if (currentCanonical == rootCanonical) {
                ""
            } else {
                currentCanonical.removePrefix(rootCanonical).trimStart(File.separatorChar).replace('\\', '/')
            }

            val allChildren = currentDir.listFiles { f ->
                !f.name.startsWith(".") && !f.name.endsWith(".part") && !f.name.endsWith(".tmp")
            } ?: emptyArray()

            val foldersJson = JSONArray()
            val filesJson = JSONArray()
            val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())

            allChildren.filter { it.isDirectory }
                .sortedBy { it.name.lowercase(Locale.getDefault()) }
                .forEach { folder ->
                    val folderRel = if (safeRelPath.isEmpty()) folder.name else "$safeRelPath/${folder.name}"
                    val count = SettingsManager.getFolderMediaCount(appContext, folderRel)
                    foldersJson.put(
                        JSONObject().apply {
                            put("name", folder.name)
                            put("relPath", folderRel)
                            put("count", count)
                        }
                    )
                }

            allChildren.filter { it.isFile && SettingsManager.isMediaFile(it.name) }
                .sortedByDescending { it.lastModified() }
                .forEach { file ->
                    val fileRel = if (safeRelPath.isEmpty()) file.name else "$safeRelPath/${file.name}"
                    val ext = file.extension.lowercase(Locale.getDefault())
                    val isVideo = ext in listOf("mp4", "mov", "mkv", "3gp")
                    val isImage = ext in listOf("jpg", "jpeg", "png", "webp")
                    filesJson.put(
                        JSONObject().apply {
                            put("name", file.name)
                            put("relPath", fileRel)
                            put("sizeBytes", file.length())
                            put("modified", dateFormat.format(Date(file.lastModified())))
                            put("timestamp", file.lastModified())
                            put("isVideo", isVideo)
                            put("isImage", isImage)
                            put("locked", SettingsManager.isFileLocked(file))
                        }
                    )
                }

            val sandboxSubfolders = JSONArray()
            SettingsManager.getSandboxSubfolders(appContext).forEach { sub ->
                sandboxSubfolders.put(sub)
            }

            val rootDir = SettingsManager.getSandboxRootDir()
            val totalSpace = rootDir.totalSpace.coerceAtLeast(1L)
            val freeSpace = rootDir.usableSpace
            val usedSpace = (totalSpace - freeSpace).coerceAtLeast(0L)

            val responseObj = JSONObject().apply {
                put("ok", true)
                put("deviceAlias", SettingsManager.getActiveMdnsDomain(appContext).removeSuffix(".local"))
                put("domain", SettingsManager.getActiveMdnsDomain(appContext))
                put("ip", NetworkDiscoveryManager.getLocalIpAddress(appContext))
                put("port", SettingsManager.getServerPort(appContext))
                put("currentFolder", safeRelPath)
                put("sandboxDisplay", if (safeRelPath.isEmpty()) "DCIM/Camera/IFgestor" else "DCIM/Camera/IFgestor/$safeRelPath")
                put("activeCaptureSubfolder", SettingsManager.getTargetSubfolder(appContext))
                put("activePrefix", SettingsManager.getFilePrefix(appContext))
                put("sessionInterceptCount", SettingsManager.getSessionInterceptCount(appContext))
                put("folderMediaCount", filesJson.length())
                put("actionAuthorized", isActionAuthorized(sess))
                put("totalBytes", totalSpace)
                put("usedBytes", usedSpace)
                put("freeBytes", freeSpace)
                put("folders", foldersJson)
                put("files", filesJson)
                put("allSubfolders", sandboxSubfolders)
                put("peers", NetworkDiscoveryManager.getDiscoveredPeersJson(appContext))
                put("tasks", SettingsManager.getTasksJsonArray())
            }

            return jsonResponse(Response.Status.OK, responseObj)
        }

        private fun handleApiTasks(): Response {
            return jsonResponse(
                Response.Status.OK,
                JSONObject()
                    .put("ok", true)
                    .put("tasks", SettingsManager.getTasksJsonArray())
            )
        }

        private fun handleApiPeers(): Response {
            return jsonResponse(
                Response.Status.OK,
                JSONObject()
                    .put("ok", true)
                    .put("peers", NetworkDiscoveryManager.getDiscoveredPeersJson(appContext))
            )
        }

        private fun handleApiMkdir(session: IHTTPSession, sess: SessionInfo, clientIp: String): Response {
            if (!isActionAuthorized(sess)) {
                return jsonResponse(
                    Response.Status.FORBIDDEN,
                    JSONObject().put("error", "2FA_REQUIRED").put("message", "Confirme o 2FA para criar pastas.")
                )
            }
            val params = parseFormBody(session)
            val parentRel = SettingsManager.sanitizeSubfolderName(params["parent"] ?: "")
            val newName = SettingsManager.sanitizeSubfolderName(params["name"] ?: "")
            if (newName.isEmpty()) {
                return jsonResponse(
                    Response.Status.BAD_REQUEST,
                    JSONObject().put("ok", false).put("error", "Nome de pasta inválido.")
                )
            }

            val combinedRel = if (parentRel.isEmpty()) newName else "$parentRel/$newName"
            val dir = SettingsManager.resolveSafeSandboxFile(combinedRel)
                ?: return jsonResponse(
                    Response.Status.FORBIDDEN,
                    JSONObject().put("ok", false).put("error", "Caminho fora da Sandbox.")
                )

            if (!dir.exists()) dir.mkdirs()

            SettingsManager.appendAuditLog(
                context = appContext,
                ip = clientIp,
                category = "ARQUIVOS_WEB",
                action = "CRIAR_PASTA",
                details = "Subpasta criada: DCIM/Camera/IFgestor/$combinedRel",
                success = true
            )

            IFGestorHomeWidget.requestWidgetUpdate(appContext)
            FloatingOverlayManager.refresh(appContext)

            return jsonResponse(
                Response.Status.OK,
                JSONObject().put("ok", true).put("folder", combinedRel)
            )
        }

        private fun handleApiRawUpload(session: IHTTPSession, sess: SessionInfo, clientIp: String): Response {
            if (!isActionAuthorized(sess)) {
                return jsonResponse(
                    Response.Status.FORBIDDEN,
                    JSONObject().put("error", "2FA_REQUIRED").put("message", "Autorize o 2FA para enviar arquivos.")
                )
            }

            val folderRel = SettingsManager.sanitizeSubfolderName(session.parms["folder"] ?: "")
            val rawName = session.parms["name"] ?: "upload_${System.currentTimeMillis()}.jpg"
            val batchTaskId = session.parms["taskId"] ?: "web_up_${System.currentTimeMillis()}"
            val itemIndex = session.parms["index"]?.toIntOrNull() ?: 1
            val itemTotal = session.parms["total"]?.toIntOrNull() ?: 1

            val targetDir = SettingsManager.resolveSafeSandboxFile(folderRel)
                ?: return jsonResponse(
                    Response.Status.FORBIDDEN,
                    JSONObject().put("ok", false).put("error", "Diretório fora da Sandbox.")
                )
            if (!targetDir.exists()) targetDir.mkdirs()

            val finalFile = SettingsManager.resolveUniqueFile(targetDir, rawName)
            val partFile = File(targetDir, "${finalFile.name}.part")
            val contentLength = session.headers["content-length"]?.toLongOrNull() ?: 0L

            SettingsManager.tryLockFile(finalFile)
            return try {
                var written = 0L
                BufferedOutputStream(FileOutputStream(partFile)).use { out ->
                    val buffer = ByteArray(32 * 1024)
                    var remaining = contentLength
                    var lastTick = 0L
                    while (remaining > 0L) {
                        val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                        val read = session.inputStream.read(buffer, 0, toRead)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                        written += read
                        remaining -= read

                        val now = System.currentTimeMillis()
                        if (now - lastTick >= 120L) {
                            lastTick = now
                            SettingsManager.upsertTask(
                                id = batchTaskId,
                                title = "⬆️ Recebendo do PC ($itemIndex/$itemTotal)",
                                subtitle = finalFile.name,
                                type = "UPLOAD",
                                currentItems = itemIndex,
                                totalItems = itemTotal,
                                bytesProcessed = written,
                                totalBytes = contentLength.coerceAtLeast(1L),
                                status = "RUNNING"
                            )
                        }
                    }
                    out.flush()
                }

                if (contentLength > 0L && partFile.length() < contentLength) {
                    partFile.delete()
                    throw IllegalStateException("Upload interrompido antes de concluir os bytes.")
                }

                if (!partFile.renameTo(finalFile)) {
                    partFile.copyTo(finalFile, overwrite = true)
                    partFile.delete()
                }

                MediaScannerConnection.scanFile(
                    appContext,
                    arrayOf(finalFile.absolutePath),
                    null,
                    null
                )

                val finishedBatch = (itemIndex >= itemTotal)
                SettingsManager.upsertTask(
                    id = batchTaskId,
                    title = if (finishedBatch) "✅ Upload Concluído ($itemTotal arq.)" else "⬆️ Recebendo do PC ($itemIndex/$itemTotal)",
                    subtitle = finalFile.name,
                    type = "UPLOAD",
                    currentItems = itemIndex,
                    totalItems = itemTotal,
                    bytesProcessed = contentLength,
                    totalBytes = contentLength.coerceAtLeast(1L),
                    status = if (finishedBatch) "COMPLETED" else "RUNNING"
                )

                SettingsManager.appendAuditLog(
                    context = appContext,
                    ip = clientIp,
                    category = "UPLOAD_WEB",
                    action = "ARQUIVO_RECEBIDO",
                    details = "${finalFile.name} (${finalFile.length()} bytes) em ${folderRel.ifEmpty { "IFgestor" }}",
                    success = true
                )

                IFGestorHomeWidget.requestWidgetUpdate(appContext)
                FloatingOverlayManager.refresh(appContext)

                jsonResponse(
                    Response.Status.OK,
                    JSONObject()
                        .put("ok", true)
                        .put("fileName", finalFile.name)
                )
            } catch (e: Exception) {
                partFile.delete()
                SettingsManager.upsertTask(
                    id = batchTaskId,
                    title = "❌ Falha no Upload",
                    subtitle = e.message ?: finalFile.name,
                    type = "UPLOAD",
                    currentItems = itemIndex,
                    totalItems = itemTotal,
                    status = "FAILED"
                )
                jsonResponse(
                    Response.Status.INTERNAL_ERROR,
                    JSONObject().put("ok", false).put("error", e.message)
                )
            } finally {
                SettingsManager.unlockFile(finalFile)
            }
        }

        private fun handleApiBatchAction(session: IHTTPSession, sess: SessionInfo, clientIp: String): Response {
            if (!isActionAuthorized(sess)) {
                return jsonResponse(
                    Response.Status.FORBIDDEN,
                    JSONObject().put("error", "2FA_REQUIRED").put("message", "Autorize o 2FA para mover ou excluir.")
                )
            }

            val params = parseFormBody(session)
            val actionType = (params["action"] ?: "").uppercase(Locale.US)
            val targetSub = SettingsManager.sanitizeSubfolderName(params["targetFolder"] ?: "")
            val pathsRaw = params["paths"] ?: "[]"
            val arr = JSONArray(pathsRaw)
            val total = arr.length()

            if (total == 0) {
                return jsonResponse(Response.Status.BAD_REQUEST, JSONObject().put("ok", false).put("error", "Nenhum item selecionado."))
            }

            val taskId = "batch_${actionType.lowercase(Locale.US)}_${System.currentTimeMillis()}"
            var successCount = 0
            var skippedLocked = 0

            val destDir = if (actionType == "MOVE") {
                SettingsManager.resolveSafeSandboxFile(targetSub)
                    ?: return jsonResponse(Response.Status.FORBIDDEN, JSONObject().put("ok", false).put("error", "Pasta de destino inválida."))
            } else {
                null
            }
            destDir?.mkdirs()

            for (i in 0 until total) {
                val rel = arr.optString(i, "")
                val file = SettingsManager.resolveSafeSandboxFile(rel) ?: continue
                if (!file.exists() || file == SettingsManager.getSandboxRootDir()) continue

                if (SettingsManager.isFileLocked(file)) {
                    skippedLocked++
                    continue
                }

                SettingsManager.tryLockFile(file)
                try {
                    SettingsManager.upsertTask(
                        id = taskId,
                        title = if (actionType == "DELETE") "🗑️ Excluindo (${i + 1}/$total)" else "📁 Movendo (${i + 1}/$total)",
                        subtitle = file.name,
                        type = if (actionType == "DELETE") "DELETE" else "MOVE",
                        currentItems = i + 1,
                        totalItems = total,
                        status = "RUNNING"
                    )

                    if (actionType == "DELETE") {
                        val deleted = if (file.isDirectory) file.deleteRecursively() else file.delete()
                        if (deleted) {
                            successCount++
                            MediaScannerConnection.scanFile(appContext, arrayOf(file.absolutePath), null, null)
                        }
                    } else if (actionType == "MOVE" && destDir != null) {
                        val uniqueDest = SettingsManager.resolveUniqueFile(destDir, file.name)
                        val moved = if (file.renameTo(uniqueDest)) {
                            true
                        } else {
                            try {
                                file.copyTo(uniqueDest, overwrite = false)
                                file.delete()
                                true
                            } catch (_: Exception) {
                                false
                            }
                        }
                        if (moved) {
                            successCount++
                            MediaScannerConnection.scanFile(
                                appContext,
                                arrayOf(file.absolutePath, uniqueDest.absolutePath),
                                null,
                                null
                            )
                        }
                    }
                } finally {
                    SettingsManager.unlockFile(file)
                }
            }

            SettingsManager.upsertTask(
                id = taskId,
                title = if (actionType == "DELETE") "✅ Exclusão Concluída ($successCount/$total)" else "✅ Movimentação Concluída ($successCount/$total)",
                subtitle = if (skippedLocked > 0) "$skippedLocked ignorado(s) por estarem em transferência" else "Operação finalizada",
                type = if (actionType == "DELETE") "DELETE" else "MOVE",
                currentItems = successCount,
                totalItems = total,
                status = "COMPLETED"
            )

            SettingsManager.appendAuditLog(
                context = appContext,
                ip = clientIp,
                category = "ARQUIVOS_WEB",
                action = if (actionType == "DELETE") "EXCLUSAO_LOTE" else "MOVIMENTACAO_LOTE",
                details = "Processados $successCount/$total itens (Em transito ignorados: $skippedLocked)",
                success = true
            )

            IFGestorHomeWidget.requestWidgetUpdate(appContext)
            FloatingOverlayManager.refresh(appContext)

            return jsonResponse(
                Response.Status.OK,
                JSONObject()
                    .put("ok", true)
                    .put("processed", successCount)
                    .put("skippedLocked", skippedLocked)
                    .put("taskId", taskId)
            )
        }

        private fun handleApiP2pSendFromWeb(session: IHTTPSession, sess: SessionInfo, clientIp: String): Response {
            if (!isActionAuthorized(sess)) {
                return jsonResponse(
                    Response.Status.FORBIDDEN,
                    JSONObject().put("error", "2FA_REQUIRED").put("message", "Autorize o 2FA neste aparelho antes de enviar via P2P.")
                )
            }

            val params = parseFormBody(session)
            val targetIp = (params["targetIp"] ?: "").trim()
            val targetPort = (params["targetPort"] ?: "8080").toIntOrNull() ?: 8080
            val targetHttps = (params["targetHttps"] ?: "true").toBoolean()
            val targetFolder = SettingsManager.sanitizeSubfolderName(params["targetFolder"] ?: "")
            val pathsJson = JSONArray(params["paths"] ?: "[]")

            val filesToSend = mutableListOf<File>()
            for (i in 0 until pathsJson.length()) {
                val f = SettingsManager.resolveSafeSandboxFile(pathsJson.optString(i, ""))
                if (f != null && f.exists() && f.isFile) {
                    filesToSend.add(f)
                }
            }

            if (targetIp.isEmpty() || filesToSend.isEmpty()) {
                return jsonResponse(
                    Response.Status.BAD_REQUEST,
                    JSONObject().put("ok", false).put("error", "Selecione o aparelho de destino e ao menos um arquivo.")
                )
            }

            val taskId = NetworkDiscoveryManager.sendFilesPeerToPeerAsync(
                context = appContext,
                targetIp = targetIp,
                targetPort = targetPort,
                targetHttps = targetHttps,
                targetSubfolder = targetFolder,
                files = filesToSend
            )

            SettingsManager.appendAuditLog(
                context = appContext,
                ip = clientIp,
                category = "P2P",
                action = "DISPARO_P2P_VIA_WEB",
                details = "Iniciado envio P2P de ${filesToSend.size} arquivo(s) para $targetIp:$targetPort",
                success = true
            )

            return jsonResponse(
                Response.Status.OK,
                JSONObject()
                    .put("ok", true)
                    .put("taskId", taskId)
                    .put("message", "Solicitação P2P enviada! Confirme o recebimento 2FA no celular de destino.")
            )
        }

        private fun handleApiWhatsAppShareOnPhone(session: IHTTPSession, sess: SessionInfo, clientIp: String): Response {
            if (!isActionAuthorized(sess)) {
                return jsonResponse(
                    Response.Status.FORBIDDEN,
                    JSONObject().put("error", "2FA_REQUIRED").put("message", "Autorize o 2FA para compartilhar no WhatsApp.")
                )
            }

            val params = parseFormBody(session)
            val pathsJson = JSONArray(params["paths"] ?: "[]")
            val files = mutableListOf<File>()
            for (i in 0 until pathsJson.length()) {
                val f = SettingsManager.resolveSafeSandboxFile(pathsJson.optString(i, ""))
                if (f != null && f.exists() && f.isFile) {
                    files.add(f)
                }
            }

            if (files.isEmpty()) {
                return jsonResponse(
                    Response.Status.BAD_REQUEST,
                    JSONObject().put("ok", false).put("error", "Nenhum arquivo válido selecionado.")
                )
            }

            val launched = SettingsManager.shareFilesToWhatsApp(appContext, files)
            return jsonResponse(
                Response.Status.OK,
                JSONObject()
                    .put("ok", launched)
                    .put("message", if (launched) "WhatsApp aberto na tela do celular com ${files.size} arquivo(s)!" else "Não foi possível abrir o WhatsApp no aparelho.")
            )
        }

        private fun handleApiThumb(session: IHTTPSession): Response {
            val rel = session.parms["path"] ?: ""
            val file = SettingsManager.resolveSafeSandboxFile(rel)
                ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "")
            if (!file.exists() || !file.isFile) {
                return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "")
            }

            return try {
                val ext = file.extension.lowercase(Locale.getDefault())
                val bitmap: Bitmap? = if (ext in listOf("mp4", "mov", "mkv", "3gp")) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        ThumbnailUtils.createVideoThumbnail(file, Size(260, 260), null)
                    } else {
                        @Suppress("DEPRECATION")
                        ThumbnailUtils.createVideoThumbnail(file.absolutePath, MediaStore.Video.Thumbnails.MINI_KIND)
                    }
                } else {
                    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(file.absolutePath, opts)
                    var sample = 1
                    while (opts.outWidth / sample > 280 || opts.outHeight / sample > 280) {
                        sample *= 2
                    }
                    val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
                    BitmapFactory.decodeFile(file.absolutePath, decodeOpts)
                }

                if (bitmap == null) {
                    return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "")
                }

                val bos = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 76, bos)
                bitmap.recycle()
                val bytes = bos.toByteArray()
                newFixedLengthResponse(
                    Response.Status.OK,
                    "image/jpeg",
                    ByteArrayInputStream(bytes),
                    bytes.size.toLong()
                )
            } catch (_: Exception) {
                newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "")
            }
        }

        private fun handleApiRawFile(
            session: IHTTPSession,
            clientIp: String,
            downloadAttachment: Boolean
        ): Response {
            val rel = session.parms["path"] ?: ""
            val file = SettingsManager.resolveSafeSandboxFile(rel)
                ?: return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "Acesso negado fora da Sandbox")
            if (!file.exists() || !file.isFile) {
                return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Arquivo não encontrado")
            }

            val mime = SettingsManager.getMimeType(file)
            val fis = BufferedInputStream(FileInputStream(file))
            val res = newFixedLengthResponse(Response.Status.OK, mime, fis, file.length())
            res.addHeader("Cache-Control", "no-store")
            if (downloadAttachment) {
                res.addHeader("Content-Disposition", "attachment; filename=\"${file.name}\"")
                SettingsManager.appendAuditLog(
                    context = appContext,
                    ip = clientIp,
                    category = "DOWNLOAD_WEB",
                    action = "DOWNLOAD_ARQUIVO",
                    details = "Baixado ${file.name} (${file.length()} bytes)",
                    success = true
                )
            }
            return res
        }

        private fun handleApiStreamZip(session: IHTTPSession, clientIp: String): Response {
            val folderRel = SettingsManager.sanitizeSubfolderName(session.parms["folder"] ?: "")
            val pathsParam = session.parms["paths"] ?: ""

            val filesToZip = mutableListOf<File>()
            if (pathsParam.isNotBlank()) {
                val arr = JSONArray(pathsParam)
                for (i in 0 until arr.length()) {
                    val f = SettingsManager.resolveSafeSandboxFile(arr.optString(i, ""))
                    if (f != null && f.exists() && f.isFile) filesToZip.add(f)
                }
            } else {
                val dir = SettingsManager.resolveSafeSandboxFile(folderRel) ?: SettingsManager.getSandboxRootDir()
                dir.listFiles { f -> f.isFile && !f.name.startsWith(".") && !f.name.endsWith(".part") }
                    ?.forEach { filesToZip.add(it) }
            }

            val pipedIn = PipedInputStream(64 * 1024)
            val pipedOut = PipedOutputStream(pipedIn)
            val taskId = "zip_dl_${System.currentTimeMillis()}"
            val totalFiles = filesToZip.size.coerceAtLeast(1)

            backgroundWorker.execute {
                try {
                    ZipOutputStream(BufferedOutputStream(pipedOut)).use { zos ->
                        val buf = ByteArray(32 * 1024)
                        for ((idx, file) in filesToZip.withIndex()) {
                            if (!file.exists() || SettingsManager.isFileLocked(file)) continue
                            SettingsManager.upsertTask(
                                id = taskId,
                                title = "📦 Compactando ZIP (${idx + 1}/$totalFiles)",
                                subtitle = file.name,
                                type = "DOWNLOAD",
                                currentItems = idx + 1,
                                totalItems = totalFiles,
                                status = "RUNNING"
                            )
                            val entry = ZipEntry(file.name)
                            zos.putNextEntry(entry)
                            BufferedInputStream(FileInputStream(file)).use { fis ->
                                var read: Int
                                while (fis.read(buf).also { read = it } != -1) {
                                    zos.write(buf, 0, read)
                                }
                            }
                            zos.closeEntry()
                        }
                        zos.finish()
                    }
                    SettingsManager.upsertTask(
                        id = taskId,
                        title = "✅ Download ZIP Concluído ($totalFiles arq.)",
                        subtitle = "Streaming finalizado sem ocupar memória interna",
                        type = "DOWNLOAD",
                        currentItems = totalFiles,
                        totalItems = totalFiles,
                        status = "COMPLETED"
                    )
                } catch (_: Exception) {
                    SettingsManager.upsertTask(
                        id = taskId,
                        title = "⚠️ Download ZIP Interrompido",
                        subtitle = "Conexão encerrada pelo navegador",
                        type = "DOWNLOAD",
                        currentItems = 0,
                        totalItems = totalFiles,
                        status = "FAILED"
                    )
                } finally {
                    try {
                        pipedOut.close()
                    } catch (_: Exception) {
                    }
                }
            }

            SettingsManager.appendAuditLog(
                context = appContext,
                ip = clientIp,
                category = "DOWNLOAD_WEB",
                action = "STREAM_ZIP",
                details = "Exportacao ZIP em streaming com ${filesToZip.size} arquivo(s)",
                success = true
            )

            val zipName = if (folderRel.isEmpty()) "IFGestor_Arquivos.zip" else "IFGestor_${folderRel.replace('/', '_')}.zip"
            val res = newChunkedResponse(Response.Status.OK, "application/zip", pipedIn)
            res.addHeader("Content-Disposition", "attachment; filename=\"$zipName\"")
            res.addHeader("Cache-Control", "no-store")
            return res
        }

        private fun parseFormBody(session: IHTTPSession): Map<String, String> {
            val result = mutableMapOf<String, String>()
            try {
                val files = HashMap<String, String>()
                session.parseBody(files)
                for ((k, v) in session.parms) {
                    if (v != null) result[k] = v
                }
                val postData = files["postData"]
                if (!postData.isNullOrBlank()) {
                    for (pair in postData.split("&")) {
                        val idx = pair.indexOf('=')
                        if (idx > 0) {
                            val key = URLDecoder.decode(pair.substring(0, idx), "UTF-8")
                            val value = URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
                            result[key] = value
                        }
                    }
                }
            } catch (_: Exception) {
            }
            return result
        }

        private fun extractToken(session: IHTTPSession): String? {
            val headerTok = session.headers["x-ifgestor-token"]
            if (!headerTok.isNullOrBlank()) return headerTok.trim()
            val queryTok = session.parms["token"]
            if (!queryTok.isNullOrBlank()) return queryTok.trim()
            return null
        }

        private fun requireValidSession(session: IHTTPSession): SessionInfo? {
            val tok = extractToken(session) ?: return null
            val info = activeSessions[tok] ?: return null
            val now = System.currentTimeMillis()
            if (info.expiresAt < now) {
                activeSessions.remove(tok)
                return null
            }
            info.expiresAt = now + SESSION_TTL_MS
            return info
        }

        private fun isActionAuthorized(sess: SessionInfo): Boolean {
            return sess.actionAuthorizedUntil >= System.currentTimeMillis()
        }

        private fun unauthorizedJson(): Response {
            return jsonResponse(
                Response.Status.UNAUTHORIZED,
                JSONObject().put("error", "UNAUTHORIZED").put("message", "Sessão expirada ou não autenticada.")
            )
        }

        private fun jsonResponse(status: Response.Status, json: JSONObject): Response {
            return newFixedLengthResponse(status, "application/json; charset=utf-8", json.toString()).apply {
                addHeader("Cache-Control", "no-store, no-cache, must-revalidate")
                addHeader("X-Content-Type-Options", "nosniff")
                addHeader("X-Frame-Options", "DENY")
            }
        }

        private fun htmlResponse(html: String): Response {
            return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html).apply {
                addHeader("Cache-Control", "no-store, no-cache, must-revalidate")
                addHeader("X-Content-Type-Options", "nosniff")
                addHeader("X-Frame-Options", "DENY")
            }
        }

        private fun buildSinglePageWebAppHtml(): String {
            return """
<!DOCTYPE html>
<html lang="pt-BR">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>IFGestor • Gerenciador Corporativo Seguro</title>
<style>
  :root {
    --bg: #0f172a;
    --card: #1e293b;
    --card-hover: #26354d;
    --border: #334155;
    --text: #f8fafc;
    --muted: #94a3b8;
    --accent: #38bdf8;
    --primary: #0284c7;
    --emerald: #10b981;
    --danger: #ef4444;
    --whatsapp: #25D366;
  }
  body.light-mode {
    --bg: #f1f5f9;
    --card: #ffffff;
    --card-hover: #f8fafc;
    --border: #cbd5e1;
    --text: #0f172a;
    --muted: #475569;
    --accent: #0284c7;
    --primary: #0369a1;
  }
  * { box-sizing: border-box; margin: 0; padding: 0; font-family: system-ui, -apple-system, sans-serif; }
  body { background: var(--bg); color: var(--text); min-height: 100vh; padding-bottom: 90px; transition: background 0.2s; }
  .topbar {
    position: sticky; top: 0; z-index: 40;
    background: var(--card); border-bottom: 1px solid var(--border);
    padding: 12px 20px; display: flex; flex-wrap: wrap; gap: 12px;
    align-items: center; justify-content: space-between;
  }
  .brand-row { display: flex; align-items: center; gap: 12px; }
  .brand-badge {
    background: linear-gradient(135deg, #0284c7, #10b981);
    color: #fff; font-weight: 800; padding: 8px 12px; border-radius: 10px; font-size: 15px;
  }
  .sandbox-pill {
    font-size: 12px; background: rgba(56,189,248,0.12); color: var(--accent);
    border: 1px solid var(--accent); padding: 4px 10px; border-radius: 999px; font-weight: 600;
  }
  .controls-row { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; }
  select, input[type="text"], input[type="password"] {
    background: var(--bg); color: var(--text); border: 1px solid var(--border);
    padding: 8px 12px; border-radius: 8px; font-size: 13px; outline: none;
  }
  .btn {
    border: 1px solid var(--border); background: var(--card-hover); color: var(--text);
    padding: 8px 13px; border-radius: 8px; font-size: 13px; font-weight: 600;
    cursor: pointer; display: inline-flex; align-items: center; gap: 6px; transition: 0.15s;
  }
  .btn:hover { filter: brightness(1.1); }
  .btn-primary { background: var(--primary); color: #fff; border-color: var(--accent); }
  .btn-emerald { background: #059669; color: #fff; border-color: var(--emerald); }
  .btn-whatsapp { background: #128C7E; color: #fff; border-color: var(--whatsapp); }
  .btn-danger { background: #b91c1c; color: #fff; border-color: var(--danger); }
  .container { max-width: 1280px; margin: 18px auto; padding: 0 16px; }
  .status-strip {
    background: var(--card); border: 1px solid var(--border); border-radius: 12px;
    padding: 12px 16px; margin-bottom: 16px; display: flex; flex-wrap: wrap;
    gap: 16px; align-items: center; justify-content: space-between;
  }
  .storage-bar-bg { width: 180px; height: 8px; background: var(--bg); border-radius: 99px; overflow: hidden; border: 1px solid var(--border); }
  .storage-bar-fill { height: 100%; background: linear-gradient(90deg, var(--accent), var(--emerald)); width: 0%; }
  .toolbar {
    background: var(--card); border: 1px solid var(--border); border-radius: 12px;
    padding: 12px; margin-bottom: 16px; display: flex; flex-wrap: wrap; gap: 8px; align-items: center;
  }
  .folders-row { display: flex; flex-wrap: wrap; gap: 10px; margin-bottom: 18px; }
  .folder-chip {
    background: var(--card); border: 1px solid var(--border); border-radius: 10px;
    padding: 10px 14px; cursor: pointer; display: flex; align-items: center; gap: 8px;
    font-weight: 600; font-size: 13px;
  }
  .folder-chip:hover { border-color: var(--accent); }
  .grid {
    display: grid; grid-template-columns: repeat(auto-fill, minmax(195px, 1fr)); gap: 14px;
  }
  .media-card {
    background: var(--card); border: 2px solid var(--border); border-radius: 12px;
    overflow: hidden; position: relative; transition: 0.15s; display: flex; flex-direction: column;
  }
  .media-card.selected { border-color: var(--accent); box-shadow: 0 0 0 2px rgba(56,189,248,0.25); }
  .thumb-wrap {
    width: 100%; height: 155px; background: #090d16; position: relative;
    display: flex; align-items: center; justify-content: center; cursor: pointer; overflow: hidden;
  }
  .thumb-wrap img { width: 100%; height: 100%; object-fit: cover; }
  .check-badge {
    position: absolute; top: 8px; left: 8px; z-index: 5;
    width: 24px; height: 24px; accent-color: var(--accent); cursor: pointer;
  }
  .type-pill {
    position: absolute; bottom: 8px; right: 8px; background: rgba(15,23,42,0.85);
    color: #fff; font-size: 11px; padding: 2px 7px; border-radius: 6px; font-weight: 700;
  }
  .card-body { padding: 10px; font-size: 12px; display: flex; flex-direction: column; gap: 4px; }
  .file-title { font-weight: 700; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .file-meta { color: var(--muted); font-size: 11px; display: flex; justify-content: space-between; }
  .card-actions { display: flex; gap: 4px; margin-top: 6px; }
  .card-actions button { flex: 1; padding: 5px; font-size: 11px; }

  .task-drawer {
    position: fixed; right: 18px; bottom: 18px; width: 360px; max-width: calc(100vw - 36px);
    background: var(--card); border: 1px solid var(--accent); border-radius: 14px;
    box-shadow: 0 12px 32px rgba(0,0,0,0.45); z-index: 90; overflow: hidden;
  }
  .task-drawer-header {
    background: linear-gradient(90deg, #0f2942, #1e293b); color: #fff;
    padding: 10px 14px; display: flex; justify-content: space-between; align-items: center;
    cursor: pointer; font-weight: 700; font-size: 13px;
  }
  .task-drawer-body { max-height: 260px; overflow-y: auto; padding: 10px; display: flex; flex-direction: column; gap: 8px; }
  .task-drawer-body.collapsed { display: none; }
  .task-item {
    background: var(--bg); border: 1px solid var(--border); border-radius: 10px; padding: 8px 10px; font-size: 12px;
  }
  .task-bar-bg { width: 100%; height: 7px; background: #1e293b; border-radius: 99px; overflow: hidden; margin-top: 6px; }
  .task-bar-fill { height: 100%; background: linear-gradient(90deg, var(--accent), var(--emerald)); transition: width 0.15s; }

  .modal-backdrop {
    position: fixed; inset: 0; background: rgba(2,6,23,0.82);
    display: none; align-items: center; justify-content: center; z-index: 100; padding: 16px;
  }
  .modal-backdrop.active { display: flex; }
  .modal-card {
    background: var(--card); border: 1px solid var(--accent); border-radius: 16px;
    width: 100%; max-width: 430px; padding: 22px; display: flex; flex-direction: column; gap: 14px;
  }
</style>
</head>
<body>

<header class="topbar">
  <div class="brand-row">
    <div class="brand-badge">🛡️ IFGestor Web</div>
    <span class="sandbox-pill" id="sandboxBadge">🔒 DCIM/Camera/IFgestor</span>
  </div>
  <div class="controls-row">
    <label style="font-size:12px;font-weight:700;">📱 Aparelho:</label>
    <select id="peerDeviceSelector" onchange="onSwitchPeerDevice(this.value)">
      <option value="">Carregando aparelhos...</option>
    </select>
    <button class="btn" onclick="toggleTheme()">🌓 Tema</button>
    <button class="btn btn-danger" onclick="doLogout()">Sair</button>
  </div>
</header>

<main class="container">
  <section class="status-strip">
    <div>
      <div style="font-weight:700;font-size:14px;" id="currentPathLabel">📂 Pasta: DCIM/Camera/IFgestor</div>
      <div style="font-size:12px;color:var(--muted);margin-top:3px;" id="countersInfo">
        Sessão Interceptada: 0 • Total na Pasta: 0
      </div>
    </div>
    <div style="display:flex;align-items:center;gap:12px;">
      <div style="text-align:right;font-size:12px;">
        <div id="storageText">Armazenamento</div>
        <div class="storage-bar-bg"><div class="storage-bar-fill" id="storageFill"></div></div>
      </div>
      <button class="btn" onclick="refreshCurrentFolder()">🔄 Atualizar</button>
    </div>
  </section>

  <section class="toolbar">
    <input type="file" id="uploadInput" multiple style="display:none" onchange="startParallelUpload(this.files)">
    <button class="btn btn-primary" onclick="document.getElementById('uploadInput').click()">⬆️ Enviar Arquivos</button>
    <button class="btn" onclick="promptNewSubfolder()">➕ Nova Subpasta</button>
    <button class="btn" onclick="toggleSelectAll()">☑️ Selecionar Todos</button>
    <button class="btn btn-emerald" onclick="openP2pModal()">📲 Enviar P2P (Outro Celular)</button>
    <button class="btn btn-whatsapp" onclick="shareSelectedToWhatsAppWeb()">💬 WhatsApp Web (PC)</button>
    <button class="btn btn-whatsapp" onclick="triggerPhoneWhatsApp()">📱 WhatsApp no Celular</button>
    <button class="btn" onclick="promptBatchMove()">📁 Mover</button>
    <button class="btn" onclick="downloadSelectedZip()">📦 Baixar ZIP</button>
    <button class="btn btn-danger" onclick="promptBatchDelete()">🗑️ Excluir</button>
    <input type="text" id="searchBox" placeholder="🔍 Filtrar por nome..." oninput="renderFilteredFiles()" style="margin-left:auto;">
  </section>

  <div class="folders-row" id="foldersContainer"></div>
  <div class="grid" id="filesGrid"></div>
</main>

<aside class="task-drawer" id="taskDrawer">
  <div class="task-drawer-header" onclick="toggleTaskDrawer()">
    <span id="taskHeaderTitle">🔄 Central de Tarefas (0 ativas)</span>
    <span id="taskToggleArrow">▼</span>
  </div>
  <div class="task-drawer-body" id="taskListBody">
    <div style="font-size:12px;color:var(--muted);text-align:center;padding:8px;">Nenhuma operação em andamento.</div>
  </div>
</aside>

<div class="modal-backdrop active" id="loginModal">
  <div class="modal-card">
    <h3 style="font-size:18px;">🔐 Acesso Seguro IFGestor</h3>
    <p style="font-size:13px;color:var(--muted);">
      Digite o PIN configurado no celular. Em seguida, aprove a notificação 2FA no aparelho ou insira o código de 6 dígitos.
    </p>
    <input type="password" id="pinInput" placeholder="Digite seu PIN (4 a 8 dígitos)" maxlength="8">
    <div id="login2faBox" style="display:none;flex-direction:column;gap:8px;">
      <input type="text" id="login2faCodeInput" placeholder="Código 2FA de 6 dígitos (ou aprove no celular)" maxlength="6">
      <div style="font-size:12px;color:var(--accent);" id="login2faStatus">Aguardando aprovação 2FA no aparelho...</div>
    </div>
    <div style="font-size:12px;color:var(--danger);" id="loginErrorText"></div>
    <button class="btn btn-primary" id="btnSubmitLogin" onclick="submitPinLogin()">Entrar com Segurança</button>
  </div>
</div>

<div class="modal-backdrop" id="action2faModal">
  <div class="modal-card">
    <h3>🛡️ Confirmação 2FA Necessária</h3>
    <p style="font-size:13px;color:var(--muted);" id="action2faDesc">
      Aprove a operação na notificação do celular ou digite o código de 6 dígitos exibido no aparelho:
    </p>
    <input type="text" id="action2faCodeInput" placeholder="Código 2FA de 6 dígitos" maxlength="6">
    <div style="font-size:12px;color:var(--danger);" id="action2faError"></div>
    <div style="display:flex;gap:8px;justify-content:flex-end;">
      <button class="btn" onclick="closeAction2faModal()">Cancelar</button>
      <button class="btn btn-primary" onclick="verifyAction2faCodeNow()">Confirmar Código</button>
    </div>
  </div>
</div>

<div class="modal-backdrop" id="p2pModal">
  <div class="modal-card">
    <h3>📲 Transferência P2P entre Celulares</h3>
    <p style="font-size:12px;color:var(--muted);">
      Transfere os arquivos selecionados diretamente via Wi-Fi para outro celular rodando o IFGestor, com confirmação 2FA nas duas pontas.
    </p>
    <label style="font-size:12px;font-weight:700;">Aparelho de Destino na Rede:</label>
    <select id="p2pTargetDeviceSelect"></select>
    <label style="font-size:12px;font-weight:700;">Ou informe IP:Porta manualmente:</label>
    <input type="text" id="p2pManualHost" placeholder="Ex: 192.168.1.45:8080">
    <label style="font-size:12px;font-weight:700;">Subpasta de Destino (dentro de DCIM/Camera/IFgestor):</label>
    <input type="text" id="p2pTargetSubfolder" placeholder="Ex: Recebidos_P2P (vazio = Raiz)">
    <div style="display:flex;gap:8px;justify-content:flex-end;margin-top:6px;">
      <button class="btn" onclick="document.getElementById('p2pModal').classList.remove('active')">Cancelar</button>
      <button class="btn btn-emerald" onclick="startP2pTransferFromWeb()">📲 Iniciar Envio P2P</button>
    </div>
  </div>
</div>

<script>
  let authToken = sessionStorage.getItem('ifgestor_token') || '';
  let currentFolder = '';
  let cachedFiles = [];
  let cachedSubfolders = [];
  let cachedPeers = [];
  let selectedPaths = new Set();
  let localClientTasks = {};
  let serverTasks = [];
  let taskDrawerCollapsed = false;
  let loginPollTimer = null;
  let actionPollTimer = null;
  let currentLoginReqId = '';
  let currentActionReqId = '';
  let pendingActionCallback = null;

  function apiHeaders(extra) {
    return Object.assign({ 'X-IFGestor-Token': authToken }, extra || {});
  }

  function toggleTheme() {
    document.body.classList.toggle('light-mode');
  }

  function toggleTaskDrawer() {
    taskDrawerCollapsed = !taskDrawerCollapsed;
    const body = document.getElementById('taskListBody');
    const arrow = document.getElementById('taskToggleArrow');
    if (taskDrawerCollapsed) {
      body.classList.add('collapsed');
      arrow.textContent = '▲';
    } else {
      body.classList.remove('collapsed');
      arrow.textContent = '▼';
    }
  }

  async function submitPinLogin() {
    const pin = document.getElementById('pinInput').value.trim();
    const errEl = document.getElementById('loginErrorText');
    errEl.textContent = '';

    if (currentLoginReqId) {
      const code = document.getElementById('login2faCodeInput').value.trim();
      if (code.length === 6) {
        await pollLogin2faStatus(code);
      }
      return;
    }

    if (!pin) { errEl.textContent = 'Informe o PIN.'; return; }

    const body = new URLSearchParams({ pin: pin });
    const res = await fetch('/api/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8' },
      body: body.toString()
    });
    const data = await res.json();
    if (!data.ok) {
      errEl.textContent = data.error || 'Falha na autenticação.';
      return;
    }

    currentLoginReqId = data.requestId;
    document.getElementById('login2faBox').style.display = 'flex';
    document.getElementById('btnSubmitLogin').textContent = 'Validar Código 2FA';

    if (loginPollTimer) clearInterval(loginPollTimer);
    loginPollTimer = setInterval(function() { pollLogin2faStatus(''); }, 1200);
  }

  async function pollLogin2faStatus(typedCode) {
    if (!currentLoginReqId) return;
    const body = new URLSearchParams({ requestId: currentLoginReqId, code: typedCode || '' });
    const res = await fetch('/api/auth/poll-2fa', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8' },
      body: body.toString()
    });
    const data = await res.json();

    if (data.status === 'APPROVED') {
      clearInterval(loginPollTimer);
      authToken = data.token;
      sessionStorage.setItem('ifgestor_token', authToken);
      currentLoginReqId = '';
      document.getElementById('loginModal').classList.remove('active');
      refreshCurrentFolder();
    } else if (data.status === 'REJECTED' || data.status === 'EXPIRED') {
      clearInterval(loginPollTimer);
      currentLoginReqId = '';
      document.getElementById('loginErrorText').textContent = 'Acesso recusado ou expirado no celular.';
      document.getElementById('login2faBox').style.display = 'none';
      document.getElementById('btnSubmitLogin').textContent = 'Entrar com Segurança';
    } else if (data.status === 'INVALID_CODE') {
      document.getElementById('loginErrorText').textContent = 'Código 2FA de 6 dígitos incorreto.';
    }
  }

  async function doLogout() {
    await fetch('/api/auth/logout', { method: 'POST', headers: apiHeaders() });
    authToken = '';
    sessionStorage.removeItem('ifgestor_token');
    location.reload();
  }

  async function ensure2faAuthorized(description, onAuthorized) {
    const body = new URLSearchParams({ description: description });
    const res = await fetch('/api/auth/request-action-2fa', {
      method: 'POST',
      headers: apiHeaders({ 'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8' }),
      body: body.toString()
    });
    if (res.status === 401) {
      document.getElementById('loginModal').classList.add('active');
      return;
    }
    const data = await res.json();
    if (data.alreadyAuthorized) {
      onAuthorized();
      return;
    }

    currentActionReqId = data.requestId;
    pendingActionCallback = onAuthorized;
    document.getElementById('action2faDesc').textContent =
      'Operação: ' + description + '. Aprove na notificação do celular ou digite o código de 6 dígitos:';
    document.getElementById('action2faCodeInput').value = '';
    document.getElementById('action2faError').textContent = '';
    document.getElementById('action2faModal').classList.add('active');

    if (actionPollTimer) clearInterval(actionPollTimer);
    actionPollTimer = setInterval(function() { pollAction2fa(''); }, 1100);
  }

  async function verifyAction2faCodeNow() {
    const code = document.getElementById('action2faCodeInput').value.trim();
    if (code.length !== 6) {
      document.getElementById('action2faError').textContent = 'Digite os 6 dígitos do código.';
      return;
    }
    await pollAction2fa(code);
  }

  async function pollAction2fa(code) {
    if (!currentActionReqId) return;
    const body = new URLSearchParams({ requestId: currentActionReqId, code: code || '' });
    const res = await fetch('/api/auth/poll-2fa', {
      method: 'POST',
      headers: apiHeaders({ 'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8' }),
      body: body.toString()
    });
    const data = await res.json();
    if (data.status === 'APPROVED') {
      clearInterval(actionPollTimer);
      document.getElementById('action2faModal').classList.remove('active');
      const cb = pendingActionCallback;
      pendingActionCallback = null;
      currentActionReqId = '';
      if (cb) cb();
    } else if (data.status === 'REJECTED' || data.status === 'EXPIRED') {
      clearInterval(actionPollTimer);
      document.getElementById('action2faError').textContent = 'Operação negada ou expirada no celular.';
    } else if (data.status === 'INVALID_CODE') {
      document.getElementById('action2faError').textContent = 'Código 2FA inválido.';
    }
  }

  function closeAction2faModal() {
    if (actionPollTimer) clearInterval(actionPollTimer);
    currentActionReqId = '';
    pendingActionCallback = null;
    document.getElementById('action2faModal').classList.remove('active');
  }

  async function refreshCurrentFolder(targetSubfolder) {
    const folderToLoad = (targetSubfolder === undefined) ? currentFolder : targetSubfolder;
    if (!authToken) {
      document.getElementById('loginModal').classList.add('active');
      return;
    }
    const res = await fetch('/api/list?folder=' + encodeURIComponent(folderToLoad), {
      headers: apiHeaders()
    });
    if (res.status === 401) {
      document.getElementById('loginModal').classList.add('active');
      return;
    }
    const data = await res.json();
    if (!data.ok) return;

    currentFolder = data.currentFolder || '';
    cachedFiles = data.files || [];
    cachedSubfolders = data.allSubfolders || [];
    cachedPeers = data.peers || [];
    serverTasks = data.tasks || [];

    document.getElementById('sandboxBadge').textContent = '🔒 ' + data.sandboxDisplay;
    document.getElementById('currentPathLabel').textContent =
      '📂 ' + data.sandboxDisplay + ' (Prefixo ativo: ' + data.activePrefix + ')';
    document.getElementById('countersInfo').textContent =
      '🟢 Sessão Interceptada: ' + data.sessionInterceptCount + '  |  📁 Total nesta Pasta: ' + data.folderMediaCount;

    const pct = Math.min(100, Math.round((data.usedBytes / Math.max(1, data.totalBytes)) * 100));
    document.getElementById('storageFill').style.width = pct + '%';
    document.getElementById('storageText').textContent =
      formatBytes(data.freeBytes) + ' livres (' + pct + '% usado)';

    renderPeersDropdown(data.peers);
    renderFoldersBar(data.folders);
    renderFilteredFiles();
    renderCombinedTasks();
  }

  function renderPeersDropdown(peers) {
    const sel = document.getElementById('peerDeviceSelector');
    sel.innerHTML = '';
    (peers || []).forEach(function(p) {
      const opt = document.createElement('option');
      opt.value = p.url;
      opt.textContent = (p.isSelf ? '🟢 ' : '📱 ') + p.domain + ' (' + p.ip + ':' + p.port + ')';
      if (p.isSelf) opt.selected = true;
      sel.appendChild(opt);
    });
  }

  function onSwitchPeerDevice(targetUrl) {
    if (!targetUrl) return;
    if (confirm('Deseja alternar o painel para o dispositivo ' + targetUrl + '? (O segundo aparelho solicitará seu próprio PIN e 2FA)')) {
      window.location.href = targetUrl;
    } else {
      refreshCurrentFolder();
    }
  }

  function renderFoldersBar(folders) {
    const box = document.getElementById('foldersContainer');
    box.innerHTML = '';

    if (currentFolder !== '') {
      const parts = currentFolder.split('/');
      parts.pop();
      const parentRel = parts.join('/');
      const backChip = document.createElement('div');
      backChip.className = 'folder-chip';
      backChip.textContent = '⬅️ Voltar (' + (parentRel || 'Raiz IFgestor') + ')';
      backChip.onclick = function() { selectedPaths.clear(); refreshCurrentFolder(parentRel); };
      box.appendChild(backChip);
    }

    (folders || []).forEach(function(f) {
      const chip = document.createElement('div');
      chip.className = 'folder-chip';
      chip.innerHTML = '📁 <span>' + escapeHtml(f.name) + '</span> <span style="color:var(--accent);font-size:11px;">(' + f.count + ')</span>';
      chip.onclick = function() { selectedPaths.clear(); refreshCurrentFolder(f.relPath); };
      box.appendChild(chip);
    });
  }

  function renderFilteredFiles() {
    const query = (document.getElementById('searchBox').value || '').toLowerCase().trim();
    const grid = document.getElementById('filesGrid');
    grid.innerHTML = '';

    const list = cachedFiles.filter(function(f) {
      return !query || f.name.toLowerCase().indexOf(query) !== -1;
    });
    if (list.length === 0) {
      grid.innerHTML = '<div style="grid-column:1/-1;text-align:center;padding:36px;color:var(--muted);">Nenhum arquivo nesta pasta da Sandbox.</div>';
      return;
    }

    list.forEach(function(file) {
      const isSel = selectedPaths.has(file.relPath);
      const card = document.createElement('div');
      card.className = 'media-card' + (isSel ? ' selected' : '');

      const thumbUrl = '/api/thumb?path=' + encodeURIComponent(file.relPath) + '&token=' + encodeURIComponent(authToken);
      const rawUrl = '/api/raw?path=' + encodeURIComponent(file.relPath) + '&token=' + encodeURIComponent(authToken);
      const safeRelQuote = escapeQuotes(file.relPath);
      const safeNameQuote = escapeQuotes(file.name);
      const safeNameHtml = escapeHtml(file.name);
      const badgeLabel = file.isVideo ? '🎬 VÍDEO' : '📷 FOTO';
      const checkedAttr = isSel ? 'checked' : '';
      const isImgStr = file.isImage ? 'true' : 'false';

      card.innerHTML =
        '<div class="thumb-wrap" onclick="window.open(\'' + rawUrl + '\', \'_blank\')">' +
          '<input type="checkbox" class="check-badge" ' + checkedAttr +
            ' onclick="event.stopPropagation(); toggleSelectFile(\'' + safeRelQuote + '\', this.checked)">' +
          '<img src="' + thumbUrl + '" loading="lazy" onerror="this.style.display=\'none\'">' +
          '<span class="type-pill">' + badgeLabel + '</span>' +
        '</div>' +
        '<div class="card-body">' +
          '<div class="file-title" title="' + safeNameHtml + '">' + safeNameHtml + '</div>' +
          '<div class="file-meta">' +
            '<span>' + formatBytes(file.sizeBytes) + '</span>' +
            '<span>' + file.modified + '</span>' +
          '</div>' +
          '<div class="card-actions">' +
            '<button class="btn" onclick="downloadSingleFile(\'' + safeRelQuote + '\')">⬇️</button>' +
            '<button class="btn btn-whatsapp" title="Copiar/Enviar para WhatsApp Web" onclick="shareSingleToWhatsAppWeb(\'' + safeRelQuote + '\', \'' + safeNameQuote + '\', ' + isImgStr + ')">💬 WA</button>' +
          '</div>' +
        '</div>';

      grid.appendChild(card);
    });
  }

  function toggleSelectFile(relPath, checked) {
    if (checked) selectedPaths.add(relPath);
    else selectedPaths.delete(relPath);
    renderFilteredFiles();
  }

  function toggleSelectAll() {
    if (selectedPaths.size === cachedFiles.length) {
      selectedPaths.clear();
    } else {
      cachedFiles.forEach(function(f) { selectedPaths.add(f.relPath); });
    }
    renderFilteredFiles();
  }

  function startParallelUpload(fileList) {
    if (!fileList || fileList.length === 0) return;
    const filesArray = Array.from(fileList);
    ensure2faAuthorized('Enviar ' + filesArray.length + ' arquivo(s) para ' + (currentFolder || 'IFgestor'), function() {
      const batchId = 'up_' + Date.now() + '_' + Math.floor(Math.random() * 1000);
      runUploadQueue(batchId, filesArray, currentFolder);
    });
  }

  async function runUploadQueue(batchId, files, targetSubfolder) {
    const totalBytes = files.reduce(function(acc, f) { return acc + f.size; }, 0);
    let completedBytes = 0;

    for (let i = 0; i < files.length; i++) {
      const file = files[i];
      await new Promise(function(resolve) {
        const xhr = new XMLHttpRequest();
        const url = '/api/upload?folder=' + encodeURIComponent(targetSubfolder) +
          '&name=' + encodeURIComponent(file.name) +
          '&taskId=' + encodeURIComponent(batchId) +
          '&index=' + (i + 1) + '&total=' + files.length;

        xhr.open('POST', url, true);
        xhr.setRequestHeader('X-IFGestor-Token', authToken);
        xhr.setRequestHeader('Content-Type', 'application/octet-stream');

        xhr.upload.onprogress = function(ev) {
          if (ev.lengthComputable) {
            const currentTotal = completedBytes + ev.loaded;
            const pct = Math.min(99, Math.round((currentTotal / Math.max(1, totalBytes)) * 100));
            localClientTasks[batchId] = {
              id: batchId,
              title: '⬆️ Enviando (' + (i + 1) + '/' + files.length + ')',
              subtitle: file.name + ' (' + formatBytes(currentTotal) + ' / ' + formatBytes(totalBytes) + ')',
              progressPercent: pct,
              status: 'RUNNING'
            };
            renderCombinedTasks();
          }
        };

        xhr.onload = function() {
          completedBytes += file.size;
          resolve();
        };
        xhr.onerror = function() { resolve(); };
        xhr.send(file);
      });
    }

    localClientTasks[batchId] = {
      id: batchId,
      title: '✅ Upload Concluído (' + files.length + ' arq.)',
      subtitle: 'Salvo em ' + (targetSubfolder || 'IFgestor (Raiz)'),
      progressPercent: 100,
      status: 'COMPLETED'
    };
    renderCombinedTasks();
    refreshCurrentFolder();
  }

  function promptNewSubfolder() {
    const name = prompt('Nome da nova subpasta dentro de ' + (currentFolder || 'IFgestor') + ':');
    if (!name || !name.trim()) return;
    ensure2faAuthorized('Criar subpasta ' + name.trim(), async function() {
      const body = new URLSearchParams({ parent: currentFolder, name: name.trim() });
      await fetch('/api/mkdir', {
        method: 'POST',
        headers: apiHeaders({ 'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8' }),
        body: body.toString()
      });
      refreshCurrentFolder();
    });
  }

  function promptBatchDelete() {
    const list = Array.from(selectedPaths);
    if (list.length === 0) { alert('Selecione ao menos um arquivo.'); return; }
    if (!confirm('Confirma a exclusão permanente de ' + list.length + ' arquivo(s)?')) return;

    ensure2faAuthorized('Excluir ' + list.length + ' arquivo(s)', async function() {
      const body = new URLSearchParams({
        action: 'DELETE',
        paths: JSON.stringify(list)
      });
      await fetch('/api/batch-action', {
        method: 'POST',
        headers: apiHeaders({ 'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8' }),
        body: body.toString()
      });
      selectedPaths.clear();
      refreshCurrentFolder();
    });
  }

  function promptBatchMove() {
    const list = Array.from(selectedPaths);
    if (list.length === 0) { alert('Selecione ao menos um arquivo para mover.'); return; }
    const dest = prompt(
      'Mover ' + list.length + ' arquivo(s) para qual subpasta de DCIM/Camera/IFgestor?\n(Deixe vazio para a Raiz IFgestor)\nSubpastas atuais: ' + cachedSubfolders.join(', '),
      currentFolder
    );
    if (dest === null) return;

    ensure2faAuthorized('Mover ' + list.length + ' arquivo(s)', async function() {
      const body = new URLSearchParams({
        action: 'MOVE',
        targetFolder: dest.trim(),
        paths: JSON.stringify(list)
      });
      await fetch('/api/batch-action', {
        method: 'POST',
        headers: apiHeaders({ 'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8' }),
        body: body.toString()
      });
      selectedPaths.clear();
      refreshCurrentFolder();
    });
  }

  function downloadSingleFile(relPath) {
    ensure2faAuthorized('Baixar arquivo ' + relPath, function() {
      window.location.href = '/api/download?path=' + encodeURIComponent(relPath) + '&token=' + encodeURIComponent(authToken);
    });
  }

  function downloadSelectedZip() {
    const list = Array.from(selectedPaths);
    const desc = list.length > 0 ? ('Exportar ZIP com ' + list.length + ' arquivo(s)') : 'Exportar ZIP da pasta atual';
    ensure2faAuthorized(desc, function() {
      const url = '/api/download-zip?folder=' + encodeURIComponent(currentFolder) +
        (list.length > 0 ? '&paths=' + encodeURIComponent(JSON.stringify(list)) : '') +
        '&token=' + encodeURIComponent(authToken);
      window.location.href = url;
    });
  }

  function openP2pModal() {
    if (selectedPaths.size === 0) {
      alert('Selecione os arquivos que deseja enviar para outro aparelho.');
      return;
    }
    const sel = document.getElementById('p2pTargetDeviceSelect');
    sel.innerHTML = '<option value="">-- Escolha um celular detectado na rede --</option>';
    (cachedPeers || []).filter(function(p) { return !p.isSelf; }).forEach(function(p) {
      const opt = document.createElement('option');
      opt.value = p.ip + ':' + p.port + ':' + p.https;
      opt.textContent = '📱 ' + p.domain + ' (' + p.ip + ':' + p.port + ')';
      sel.appendChild(opt);
    });
    document.getElementById('p2pModal').classList.add('active');
  }

  function startP2pTransferFromWeb() {
    const dropdownVal = document.getElementById('p2pTargetDeviceSelect').value;
    const manualVal = document.getElementById('p2pManualHost').value.trim();
    const targetFolder = document.getElementById('p2pTargetSubfolder').value.trim();

    let ip = '', port = '8080', https = 'true';
    if (manualVal) {
      const cleanHost = manualVal.replace('https://', '').replace('http://', '');
      const parts = cleanHost.split(':');
      ip = parts[0];
      port = parts[1] || '8080';
    } else if (dropdownVal) {
      const parts = dropdownVal.split(':');
      ip = parts[0];
      port = parts[1] || '8080';
      https = parts[2] || 'true';
    }

    if (!ip) {
      alert('Selecione um aparelho na lista ou digite o IP do segundo celular.');
      return;
    }

    const list = Array.from(selectedPaths);
    document.getElementById('p2pModal').classList.remove('active');

    ensure2faAuthorized('Enviar ' + list.length + ' arquivo(s) P2P para ' + ip, async function() {
      const body = new URLSearchParams({
        targetIp: ip,
        targetPort: port,
        targetHttps: https,
        targetFolder: targetFolder,
        paths: JSON.stringify(list)
      });
      const res = await fetch('/api/p2p/send-from-web', {
        method: 'POST',
        headers: apiHeaders({ 'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8' }),
        body: body.toString()
      });
      const data = await res.json();
      alert(data.message || 'Transferência P2P iniciada! Acompanhe na Central de Tarefas.');
      refreshTasksOnly();
    });
  }

  async function shareSingleToWhatsAppWeb(relPath, fileName, isImage) {
    ensure2faAuthorized('Compartilhar ' + fileName + ' no WhatsApp Web', async function() {
      const rawUrl = '/api/raw?path=' + encodeURIComponent(relPath) + '&token=' + encodeURIComponent(authToken);
      try {
        const response = await fetch(rawUrl);
        const blob = await response.blob();
        const fileObj = new File([blob], fileName, { type: blob.type });

        if (navigator.canShare && navigator.canShare({ files: [fileObj] })) {
          await navigator.share({ files: [fileObj], title: fileName });
          return;
        }

        if (isImage && navigator.clipboard && window.ClipboardItem) {
          const pngBlob = await convertImageBlobToPng(blob);
          await navigator.clipboard.write([new ClipboardItem({ 'image/png': pngBlob })]);
          alert('✅ Imagem copiada para a Área de Transferência!\n\nO WhatsApp Web será aberto agora: basta clicar na conversa e pressionar Ctrl + V para colar a foto.');
          window.open('https://web.whatsapp.com/', '_blank');
          return;
        }
      } catch (e) {}

      window.open('https://web.whatsapp.com/', '_blank');
      window.location.href = '/api/download?path=' + encodeURIComponent(relPath) + '&token=' + encodeURIComponent(authToken);
    });
  }

  function shareSelectedToWhatsAppWeb() {
    const list = Array.from(selectedPaths);
    if (list.length === 0) {
      alert('Selecione uma foto ou vídeo para enviar ao WhatsApp Web.');
      return;
    }
    const first = cachedFiles.find(function(f) { return f.relPath === list[0]; });
    if (first) {
      shareSingleToWhatsAppWeb(first.relPath, first.name, first.isImage);
    }
  }

  function triggerPhoneWhatsApp() {
    const list = Array.from(selectedPaths);
    if (list.length === 0) {
      alert('Selecione os arquivos que deseja abrir no WhatsApp do aparelho celular.');
      return;
    }
    ensure2faAuthorized('Abrir ' + list.length + ' arquivo(s) no WhatsApp do celular', async function() {
      const body = new URLSearchParams({ paths: JSON.stringify(list) });
      const res = await fetch('/api/whatsapp/share', {
        method: 'POST',
        headers: apiHeaders({ 'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8' }),
        body: body.toString()
      });
      const data = await res.json();
      alert(data.message || 'Comando enviado ao WhatsApp do celular!');
    });
  }

  function convertImageBlobToPng(blob) {
    return new Promise(function(resolve, reject) {
      const img = new Image();
      const url = URL.createObjectURL(blob);
      img.onload = function() {
        const canvas = document.createElement('canvas');
        canvas.width = img.width;
        canvas.height = img.height;
        const ctx = canvas.getContext('2d');
        ctx.drawImage(img, 0, 0);
        URL.revokeObjectURL(url);
        canvas.toBlob(function(b) {
          if (b) resolve(b);
          else reject();
        }, 'image/png');
      };
      img.onerror = reject;
      img.src = url;
    });
  }

  async function refreshTasksOnly() {
    if (!authToken) return;
    try {
      const res = await fetch('/api/tasks', { headers: apiHeaders() });
      if (res.ok) {
        const data = await res.json();
        serverTasks = data.tasks || [];
        renderCombinedTasks();
      }
    } catch (e) {}
  }

  function renderCombinedTasks() {
    const map = new Map();
    (serverTasks || []).forEach(function(t) { map.set(t.id, t); });
    Object.values(localClientTasks).forEach(function(t) { map.set(t.id, t); });

    const all = Array.from(map.values());
    const runningCount = all.filter(function(t) { return t.status === 'RUNNING'; }).length;
    document.getElementById('taskHeaderTitle').textContent =
      '🔄 Central de Tarefas (' + runningCount + ' ativa' + (runningCount === 1 ? '' : 's') + ')';

    const body = document.getElementById('taskListBody');
    if (all.length === 0) {
      body.innerHTML = '<div style="font-size:12px;color:var(--muted);text-align:center;padding:8px;">Nenhuma operação recente.</div>';
      return;
    }

    body.innerHTML = '';
    all.forEach(function(t) {
      const pct = t.progressPercent || 0;
      const item = document.createElement('div');
      item.className = 'task-item';
      item.innerHTML =
        '<div style="display:flex;justify-content:space-between;font-weight:700;">' +
          '<span>' + escapeHtml(t.title) + '</span>' +
          '<span style="color:var(--accent);">' + pct + '%</span>' +
        '</div>' +
        '<div style="color:var(--muted);font-size:11px;margin-top:2px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;">' +
          escapeHtml(t.subtitle || '') +
        '</div>' +
        '<div class="task-bar-bg">' +
          '<div class="task-bar-fill" style="width:' + pct + '%"></div>' +
        '</div>';
      body.appendChild(item);
    });
  }

  function formatBytes(bytes) {
    if (!bytes || bytes <= 0) return '0 B';
    const units = ['B', 'KB', 'MB', 'GB'];
    let i = 0, val = bytes;
    while (val >= 1024 && i < units.length - 1) { val /= 1024; i++; }
    return val.toFixed(i === 0 ? 0 : 1) + ' ' + units[i];
  }

  function escapeHtml(s) {
    return String(s || '').replace(/[&<>"']/g, function(c) {
      return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c];
    });
  }

  function escapeQuotes(s) {
    return String(s || '').replace(/\\/g, '\\\\').replace(/'/g, "\\'");
  }

  setInterval(refreshTasksOnly, 1500);
  if (authToken) {
    refreshCurrentFolder();
  }
</script>
</body>
</html>
            """.trimIndent()
        }
    }
}
