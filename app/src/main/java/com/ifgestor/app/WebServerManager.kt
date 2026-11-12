package com.ifgestor.app

import android.content.Context
import android.media.MediaScannerConnection
import android.net.wifi.WifiManager
import fi.iki.elonen.NanoHTTPD
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object WebServerManager {
    private var serverInstance: PhoneHttpServer? = null

    @Synchronized
    fun startServer(context: Context, port: Int = 8080): Boolean {
        if (serverInstance?.isAlive == true) return true
        return try {
            val server = PhoneHttpServer(context.applicationContext, port)
            server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            serverInstance = server
            SettingsManager.setWebServerRunning(true)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            SettingsManager.setWebServerRunning(false)
            false
        }
    }

    @Synchronized
    fun stopServer() {
        try {
            serverInstance?.stop()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            serverInstance = null
            SettingsManager.setWebServerRunning(false)
        }
    }

    fun disconnectAllSessions() {
        serverInstance?.clearAllSessions()
    }

    fun isRunning(): Boolean = serverInstance?.isAlive == true

    fun getDeviceIpAddress(context: Context): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (!networkInterface.isUp || networkInterface.isLoopback) continue
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: continue
                        if (ip.startsWith("192.") || ip.startsWith("10.") || ip.startsWith("172.")) {
                            return ip
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        return try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val ipInt = wm.connectionInfo.ipAddress
            if (ipInt != 0) {
                String.format(
                    Locale.US,
                    "%d.%d.%d.%d",
                    ipInt and 0xff,
                    ipInt shr 8 and 0xff,
                    ipInt shr 16 and 0xff,
                    ipInt shr 24 and 0xff
                )
            } else {
                "Sem conexão Wi-Fi"
            }
        } catch (_: Exception) {
            "Sem conexão Wi-Fi"
        }
    }
}

private data class AuthSession(
    val token: String,
    val csrfToken: String,
    val clientIp: String,
    var lastActivityMs: Long,
    var elevatedUntilMs: Long = 0L
)

private data class Pending2FA(
    val tempToken: String,
    val code: String,
    val clientIp: String,
    val actionDesc: String,
    val expiresAtMs: Long
)

private data class BatchOpGrant(
    val opToken: String,
    var lastChunkActivityMs: Long
)

private data class BruteForceRecord(
    var failures: Int = 0,
    var lockedUntilMs: Long = 0L
)

private class PhoneHttpServer(
    private val context: Context,
    port: Int
) : NanoHTTPD(port) {

    private val rootStoragePath: String = SettingsManager.getStorageRootPath()
    private val secureRandom = SecureRandom()

    private val activeSessions = ConcurrentHashMap<String, AuthSession>()
    private val pendingLogin2faMap = ConcurrentHashMap<String, Pending2FA>()
    private val pendingAction2faMap = ConcurrentHashMap<String, Pending2FA>()
    private val activeBatchGrants = ConcurrentHashMap<String, BatchOpGrant>()
    private val bruteForceMap = ConcurrentHashMap<String, BruteForceRecord>()

    companion object {
        private const val SESSION_TIMEOUT_MS = 30 * 60 * 1000L
        private const val TWO_FA_EXPIRY_MS = 2 * 60 * 1000L
        private const val ELEVATED_WINDOW_MS = 5 * 60 * 1000L
        private const val BATCH_GRANT_IDLE_MS = 10 * 60 * 1000L
        private const val MAX_ATTEMPTS = 5
        private const val LOCKOUT_DURATION_MS = 5 * 60 * 1000L
    }

    fun clearAllSessions() {
        activeSessions.clear()
        pendingLogin2faMap.clear()
        pendingAction2faMap.clear()
        activeBatchGrants.clear()
        SettingsManager.publishTwoFactorCode(null, null)
        SettingsManager.updateActiveSessionsCount(0)
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri ?: "/"
        val params = parseQueryParams(session.queryParameterString)
        val clientIp = session.headers["remote-addr"]
            ?: session.headers["http-client-ip"]
            ?: "PC-Rede-Local"

        if (isIpLockedOut(clientIp)) {
            val remainingSec = ((bruteForceMap[clientIp]?.lockedUntilMs ?: 0L) - System.currentTimeMillis()) / 1000
            return secureResponse(
                newFixedLengthResponse(
                    Response.Status.FORBIDDEN,
                    MIME_HTML,
                    renderLockoutPage(remainingSec.coerceAtLeast(1))
                )
            )
        }

        // Rotas de Login Inicial (PIN + 2FA de Entrada)
        if (uri == "/auth/pin" && session.method == Method.POST) {
            return secureResponse(handlePinSubmission(params, clientIp))
        }
        if (uri == "/auth/2fa" && session.method == Method.POST) {
            return secureResponse(handleLogin2faSubmission(params, clientIp))
        }
        if (uri == "/auth/check_login_2fa") {
            return secureResponse(handleCheckLogin2faPolling(params, clientIp))
        }
        if (uri == "/auth/logout") {
            return secureResponse(handleLogout(session))
        }

        val authSession = getValidSession(session, clientIp)
        if (SettingsManager.isWebAuthEnabled(context) && authSession == null) {
            val step = params["step"]
            val tempToken = params["temp"] ?: ""
            val errorMsg = params["err"]
            val pageHtml = if (step == "2fa" && pendingLogin2faMap.containsKey(tempToken)) {
                render2faLoginPage(tempToken, errorMsg)
            } else {
                renderPinLoginPage(errorMsg)
            }
            return secureResponse(newFixedLengthResponse(Response.Status.OK, MIME_HTML, pageHtml))
        }

        val csrfToken = authSession?.csrfToken ?: "open-mode"

        return try {
            val response = when {
                uri == "/dirs" -> handleListDirectoriesJson(params)
                uri == "/action/request_2fa" && session.method == Method.POST -> {
                    if (!verifyCsrf(session, params, authSession)) {
                        newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "CSRF invalido")
                    } else {
                        handleRequestAction2fa(params, clientIp, authSession)
                    }
                }
                uri == "/action/verify_2fa" && session.method == Method.POST -> {
                    if (!verifyCsrf(session, params, authSession)) {
                        newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "CSRF invalido")
                    } else {
                        handleVerifyAction2fa(params, clientIp, authSession)
                    }
                }
                uri == "/action/lock_window" && session.method == Method.POST -> {
                    authSession?.elevatedUntilMs = 0L
                    activeBatchGrants.clear()
                    newFixedLengthResponse(Response.Status.OK, "application/json", """{"locked":true}""")
                }
                uri == "/upload" && session.method == Method.POST -> {
                    if (!verifyCsrf(session, params, authSession)) {
                        newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "CSRF invalido")
                    } else if (!verifyOperationAuthorized(session, params, authSession)) {
                        newFixedLengthResponse(Response.Status.UNAUTHORIZED, MIME_PLAINTEXT, "2FA_REQUIRED")
                    } else {
                        handleFileUpload(session, params, authSession)
                    }
                }
                uri == "/action" && session.method == Method.POST -> {
                    if (!verifyCsrf(session, params, authSession)) {
                        newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "CSRF invalido")
                    } else if (!verifyOperationAuthorized(session, params, authSession)) {
                        newFixedLengthResponse(Response.Status.UNAUTHORIZED, MIME_PLAINTEXT, "2FA_REQUIRED")
                    } else {
                        val bodyText = readUtf8Body(session)
                        handleFileAction(params, bodyText, authSession)
                    }
                }
                uri == "/file" -> handleFileStream(session, params)
                uri == "/zip" -> handleFolderZipDownload(params)
                else -> renderExplorerPage(params["dir"], csrfToken, authSession)
            }
            secureResponse(response)
        } catch (e: Exception) {
            secureResponse(
                newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR,
                    MIME_PLAINTEXT,
                    "Erro interno: ${e.message}"
                )
            )
        }
    }

    // Verifica se a operacao possui um BatchOpGrant ativo ou se esta na Janela Deslizante de 5 min
    private fun verifyOperationAuthorized(
        session: IHTTPSession,
        params: Map<String, String>,
        authSession: AuthSession?
    ): Boolean {
        val now = System.currentTimeMillis()
        val opToken = session.headers["x-op-token"] ?: params["opToken"] ?: ""

        if (opToken.isNotBlank()) {
            val grant = activeBatchGrants[opToken]
            if (grant != null && (now - grant.lastChunkActivityMs) <= BATCH_GRANT_IDLE_MS) {
                // Renova o token do lote a cada arquivo transferido (nunca interrompe lotes longos!)
                grant.lastChunkActivityMs = now
                if (authSession != null && authSession.elevatedUntilMs > now) {
                    authSession.elevatedUntilMs = now + ELEVATED_WINDOW_MS
                }
                return true
            }
        }

        if (authSession != null && authSession.elevatedUntilMs > now) {
            // Janela deslizante: renova +5 min enquanto o usuario estiver operando ativamente
            authSession.elevatedUntilMs = now + ELEVATED_WINDOW_MS
            return true
        }

        return false
    }

    private fun handleRequestAction2fa(
        params: Map<String, String>,
        clientIp: String,
        authSession: AuthSession?
    ): Response {
        val now = System.currentTimeMillis()
        // Se a janela deslizante de 5 min ja estiver ativa, libera o opToken imediatamente
        if (authSession != null && authSession.elevatedUntilMs > now) {
            authSession.elevatedUntilMs = now + ELEVATED_WINDOW_MS
            val opToken = issueBatchOpGrant()
            val remSec = ((authSession.elevatedUntilMs - now) / 1000).coerceAtLeast(1)
            return newFixedLengthResponse(
                Response.Status.OK,
                "application/json",
                """{"status":"already_authorized","opToken":"$opToken","remainingSeconds":$remSec}"""
            )
        }

        val actionDesc = (params["desc"] ?: "Operação de Arquivo no PC").trim()
        val code6Digits = String.format(Locale.US, "%06d", secureRandom.nextInt(1_000_000))
        val reqId = generateRandomHex(16)

        pendingAction2faMap[reqId] = Pending2FA(
            tempToken = reqId,
            code = code6Digits,
            clientIp = clientIp,
            actionDesc = actionDesc,
            expiresAtMs = now + TWO_FA_EXPIRY_MS
        )

        SettingsManager.publishTwoFactorCode(code6Digits, clientIp, actionDesc)

        return newFixedLengthResponse(
            Response.Status.OK,
            "application/json",
            """{"status":"pending","reqId":"$reqId"}"""
        )
    }

    private fun handleVerifyAction2fa(
        params: Map<String, String>,
        clientIp: String,
        authSession: AuthSession?
    ): Response {
        val reqId = params["reqId"] ?: ""
        val inputCode = (params["code"] ?: "").trim()
        val keep5Min = params["keep5m"] == "1"
        val mode = params["mode"] ?: "manual" // "manual" ou "poll"

        val pending = pendingAction2faMap[reqId]
        val now = System.currentTimeMillis()

        if (pending == null || now > pending.expiresAtMs) {
            pendingAction2faMap.remove(reqId)
            return newFixedLengthResponse(
                Response.Status.OK,
                "application/json",
                """{"status":"expired"}"""
            )
        }

        val approvedByPhoneButton = SettingsManager.isCodeApprovedByPhone(pending.code)
        val approvedByTypedCode = inputCode.isNotEmpty() && pending.code == inputCode

        if (!approvedByPhoneButton && !approvedByTypedCode) {
            if (mode == "manual") {
                registerAuthFailure(clientIp)
                return newFixedLengthResponse(
                    Response.Status.OK,
                    "application/json",
                    """{"status":"invalid_code"}"""
                )
            }
            return newFixedLengthResponse(
                Response.Status.OK,
                "application/json",
                """{"status":"waiting"}"""
            )
        }

        // Autorizado com sucesso (pelo codigo digitado ou pelo botao da notificacao)!
        pendingAction2faMap.remove(reqId)
        SettingsManager.consumeApprovedCode(pending.code)
        resetAuthFailures(clientIp)

        if (keep5Min && authSession != null) {
            authSession.elevatedUntilMs = now + ELEVATED_WINDOW_MS
        }

        val opToken = issueBatchOpGrant()
        val remSec = if (authSession != null && authSession.elevatedUntilMs > now) {
            ((authSession.elevatedUntilMs - now) / 1000).coerceAtLeast(0)
        } else 0L

        return newFixedLengthResponse(
            Response.Status.OK,
            "application/json",
            """{"status":"approved","opToken":"$opToken","remainingSeconds":$remSec}"""
        )
    }

    private fun issueBatchOpGrant(): String {
        val token = generateRandomHex(24)
        activeBatchGrants[token] = BatchOpGrant(
            opToken = token,
            lastChunkActivityMs = System.currentTimeMillis()
        )
        return token
    }

    private fun handleListDirectoriesJson(params: Map<String, String>): Response {
        val dir = sanitizeFile(params["dir"])
        val rootFile = File(rootStoragePath).canonicalFile
        val parentPath = if (dir.canonicalPath != rootFile.path) {
            dir.parentFile?.canonicalPath ?: ""
        } else ""

        val subDirs = dir.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".") }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()

        val itemsJson = subDirs.joinToString(",") { folder ->
            """{"name":"${escapeJs(folder.name)}","path":"${escapeJs(folder.absolutePath)}"}"""
        }

        val json = """{"currentPath":"${escapeJs(dir.absolutePath)}","shortPath":"${escapeJs(SettingsManager.formatShortPath(dir.absolutePath))}","parentPath":"${escapeJs(parentPath)}","folders":[$itemsJson]}"""
        return newFixedLengthResponse(Response.Status.OK, "application/json", json)
    }

    private fun readUtf8Body(session: IHTTPSession): String {
        val len = session.headers["content-length"]?.toIntOrNull() ?: 0
        if (len <= 0 || len > 512 * 1024) return ""
        val buf = ByteArray(len)
        var readTotal = 0
        val input = session.inputStream
        while (readTotal < len) {
            val r = input.read(buf, readTotal, len - readTotal)
            if (r == -1) break
            readTotal += r
        }
        return String(buf, 0, readTotal, Charsets.UTF_8)
    }

    private fun secureResponse(response: Response): Response {
        response.addHeader("X-Frame-Options", "DENY")
        response.addHeader("X-Content-Type-Options", "nosniff")
        response.addHeader("Referrer-Policy", "no-referrer")
        response.addHeader("Cache-Control", "no-store, no-cache, must-revalidate")
        return response
    }

    private fun isIpLockedOut(ip: String): Boolean {
        val record = bruteForceMap[ip] ?: return false
        if (record.lockedUntilMs > System.currentTimeMillis()) {
            return true
        }
        if (record.lockedUntilMs != 0L) {
            record.failures = 0
            record.lockedUntilMs = 0L
        }
        return false
    }

    private fun registerAuthFailure(ip: String) {
        val record = bruteForceMap.getOrPut(ip) { BruteForceRecord() }
        record.failures += 1
        if (record.failures >= MAX_ATTEMPTS) {
            record.lockedUntilMs = System.currentTimeMillis() + LOCKOUT_DURATION_MS
        }
    }

    private fun resetAuthFailures(ip: String) {
        bruteForceMap.remove(ip)
    }

    private fun handlePinSubmission(params: Map<String, String>, clientIp: String): Response {
        val inputPin = params["pin"] ?: ""
        if (!SettingsManager.verifyWebPin(context, inputPin)) {
            registerAuthFailure(clientIp)
            val res = newFixedLengthResponse(Response.Status.REDIRECT, MIME_HTML, "")
            res.addHeader("Location", "/?err=pin")
            return res
        }

        resetAuthFailures(clientIp)

        if (SettingsManager.isTwoFactorEnabled(context)) {
            val code6Digits = String.format(Locale.US, "%06d", secureRandom.nextInt(1_000_000))
            val tempToken = generateRandomHex(24)
            pendingLogin2faMap[tempToken] = Pending2FA(
                tempToken = tempToken,
                code = code6Digits,
                clientIp = clientIp,
                actionDesc = "Login no Gerenciador Web",
                expiresAtMs = System.currentTimeMillis() + TWO_FA_EXPIRY_MS
            )
            SettingsManager.publishTwoFactorCode(code6Digits, clientIp, "Login no Gerenciador Web")

            val res = newFixedLengthResponse(Response.Status.REDIRECT, MIME_HTML, "")
            res.addHeader("Location", "/?step=2fa&temp=$tempToken")
            return res
        }

        return createAuthenticatedSessionResponse(clientIp)
    }

    private fun handleCheckLogin2faPolling(params: Map<String, String>, clientIp: String): Response {
        val tempToken = params["temp"] ?: ""
        val pending = pendingLogin2faMap[tempToken]
        if (pending == null || System.currentTimeMillis() > pending.expiresAtMs) {
            return newFixedLengthResponse(Response.Status.OK, "application/json", """{"approved":false,"expired":true}""")
        }
        if (SettingsManager.isCodeApprovedByPhone(pending.code)) {
            pendingLogin2faMap.remove(tempToken)
            SettingsManager.consumeApprovedCode(pending.code)
            resetAuthFailures(clientIp)
            val sessionToken = generateRandomHex(32)
            val csrfToken = generateRandomHex(24)
            activeSessions[sessionToken] = AuthSession(
                token = sessionToken,
                csrfToken = csrfToken,
                clientIp = clientIp,
                lastActivityMs = System.currentTimeMillis()
            )
            SettingsManager.updateActiveSessionsCount(activeSessions.size)
            val res = newFixedLengthResponse(Response.Status.OK, "application/json", """{"approved":true}""")
            res.addHeader("Set-Cookie", "IFG_SESSION=$sessionToken; Path=/; HttpOnly; SameSite=Strict")
            return res
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json", """{"approved":false}""")
    }

    private fun handleLogin2faSubmission(params: Map<String, String>, clientIp: String): Response {
        val tempToken = params["temp"] ?: ""
        val inputCode = (params["code"] ?: "").trim()
        val pending = pendingLogin2faMap[tempToken]

        if (pending == null || System.currentTimeMillis() > pending.expiresAtMs) {
            pendingLogin2faMap.remove(tempToken)
            SettingsManager.publishTwoFactorCode(null, null)
            val res = newFixedLengthResponse(Response.Status.REDIRECT, MIME_HTML, "")
            res.addHeader("Location", "/?err=expired")
            return res
        }

        if (pending.code != inputCode && !SettingsManager.isCodeApprovedByPhone(pending.code)) {
            registerAuthFailure(clientIp)
            val res = newFixedLengthResponse(Response.Status.REDIRECT, MIME_HTML, "")
            res.addHeader("Location", "/?step=2fa&temp=$tempToken&err=2fa")
            return res
        }

        pendingLogin2faMap.remove(tempToken)
        SettingsManager.consumeApprovedCode(pending.code)
        resetAuthFailures(clientIp)
        return createAuthenticatedSessionResponse(clientIp)
    }

    private fun createAuthenticatedSessionResponse(clientIp: String): Response {
        val sessionToken = generateRandomHex(32)
        val csrfToken = generateRandomHex(24)
        activeSessions[sessionToken] = AuthSession(
            token = sessionToken,
            csrfToken = csrfToken,
            clientIp = clientIp,
            lastActivityMs = System.currentTimeMillis()
        )
        SettingsManager.updateActiveSessionsCount(activeSessions.size)

        val res = newFixedLengthResponse(Response.Status.REDIRECT, MIME_HTML, "")
        res.addHeader(
            "Set-Cookie",
            "IFG_SESSION=$sessionToken; Path=/; HttpOnly; SameSite=Strict"
        )
        res.addHeader("Location", "/")
        return res
    }

    private fun handleLogout(session: IHTTPSession): Response {
        val token = extractSessionCookie(session)
        if (token != null) {
            activeSessions.remove(token)
            SettingsManager.updateActiveSessionsCount(activeSessions.size)
        }
        val res = newFixedLengthResponse(Response.Status.REDIRECT, MIME_HTML, "")
        res.addHeader("Set-Cookie", "IFG_SESSION=; Path=/; Max-Age=0; HttpOnly; SameSite=Strict")
        res.addHeader("Location", "/")
        return res
    }

    private fun getValidSession(session: IHTTPSession, clientIp: String): AuthSession? {
        val token = extractSessionCookie(session) ?: return null
        val auth = activeSessions[token] ?: return null
        val now = System.currentTimeMillis()
        if (now - auth.lastActivityMs > SESSION_TIMEOUT_MS || auth.clientIp != clientIp) {
            activeSessions.remove(token)
            SettingsManager.updateActiveSessionsCount(activeSessions.size)
            return null
        }
        auth.lastActivityMs = now
        return auth
    }

    private fun extractSessionCookie(session: IHTTPSession): String? {
        val cookieHeader = session.headers["cookie"] ?: return null
        for (part in cookieHeader.split(";")) {
            val trimmed = part.trim()
            if (trimmed.startsWith("IFG_SESSION=")) {
                return trimmed.removePrefix("IFG_SESSION=").trim()
            }
        }
        return null
    }

    private fun verifyCsrf(
        session: IHTTPSession,
        params: Map<String, String>,
        authSession: AuthSession?
    ): Boolean {
        if (!SettingsManager.isWebAuthEnabled(context)) return true
        val expected = authSession?.csrfToken ?: return false
        val provided = session.headers["x-csrf-token"] ?: params["csrf"] ?: ""
        return expected == provided
    }

    private fun generateRandomHex(byteCount: Int): String {
        val bytes = ByteArray(byteCount)
        secureRandom.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun sanitizeFile(requestedPath: String?): File {
        val rootFile = File(rootStoragePath).canonicalFile
        if (requestedPath.isNullOrBlank()) {
            val activeTarget = File(SettingsManager.getTargetFolderPath(context)).canonicalFile
            return if (activeTarget.path.startsWith(rootFile.path) && activeTarget.exists()) {
                activeTarget
            } else {
                rootFile
            }
        }
        val candidate = File(requestedPath).canonicalFile
        return if (candidate.path.startsWith(rootFile.path)) candidate else rootFile
    }

    private fun moveFileOrDirectorySafely(source: File, destDir: File): Boolean {
        if (!source.exists() || source.absolutePath == rootStoragePath) return false
        if (!destDir.exists()) destDir.mkdirs()
        val targetFile = File(destDir, source.name)
        if (source.canonicalPath == targetFile.canonicalPath) return false
        if (source.isDirectory && targetFile.canonicalPath.startsWith(source.canonicalPath + File.separator)) {
            return false
        }
        return if (source.renameTo(targetFile)) {
            true
        } else {
            try {
                if (source.isDirectory) {
                    source.copyRecursively(targetFile, overwrite = true)
                    source.deleteRecursively()
                } else {
                    source.copyTo(targetFile, overwrite = true)
                    source.delete()
                }
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    private fun handleFileAction(
        params: Map<String, String>,
        bodyText: String,
        authSession: AuthSession?
    ): Response {
        val cmd = params["cmd"] ?: ""
        val currentDir = sanitizeFile(params["dir"])
        val targetPath = params["target"]
        val destPath = params["dest"]

        when (cmd) {
            "mkdir" -> {
                val folderName = (params["name"] ?: "").trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
                if (folderName.isNotEmpty()) {
                    File(currentDir, folderName).mkdirs()
                }
            }
            "rename" -> {
                val newName = (params["name"] ?: "").trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
                if (!targetPath.isNullOrBlank() && newName.isNotEmpty()) {
                    val item = sanitizeFile(targetPath)
                    val dest = File(item.parentFile, newName)
                    if (item.absolutePath != rootStoragePath && item.renameTo(dest)) {
                        if (SettingsManager.getTargetFolderPath(context) == item.absolutePath) {
                            SettingsManager.setTargetFolderPath(context, dest.absolutePath)
                        }
                        MediaScannerConnection.scanFile(
                            context,
                            arrayOf(item.absolutePath, dest.absolutePath),
                            null,
                            null
                        )
                    }
                }
            }
            "move" -> {
                if (!targetPath.isNullOrBlank() && !destPath.isNullOrBlank()) {
                    val src = sanitizeFile(targetPath)
                    val dstDir = sanitizeFile(destPath)
                    val oldPath = src.absolutePath
                    if (moveFileOrDirectorySafely(src, dstDir)) {
                        val newPath = File(dstDir, src.name).absolutePath
                        MediaScannerConnection.scanFile(context, arrayOf(oldPath, newPath), null, null)
                    }
                }
            }
            "batch_move" -> {
                if (!destPath.isNullOrBlank()) {
                    val dstDir = sanitizeFile(destPath)
                    val rawList = if (bodyText.isNotBlank()) bodyText else (params["targets"] ?: "")
                    val names = rawList.split("|").map { it.trim() }.filter { it.isNotEmpty() }
                    val scannedPaths = mutableListOf<String>()
                    for (name in names) {
                        val src = sanitizeFile(File(currentDir, name).absolutePath)
                        val oldPath = src.absolutePath
                        if (moveFileOrDirectorySafely(src, dstDir)) {
                            scannedPaths.add(oldPath)
                            scannedPaths.add(File(dstDir, src.name).absolutePath)
                        }
                    }
                    if (scannedPaths.isNotEmpty()) {
                        MediaScannerConnection.scanFile(context, scannedPaths.toTypedArray(), null, null)
                    }
                }
            }
            "delete" -> {
                if (!targetPath.isNullOrBlank()) {
                    val item = sanitizeFile(targetPath)
                    if (item.absolutePath != rootStoragePath) {
                        val oldPath = item.absolutePath
                        item.deleteRecursively()
                        MediaScannerConnection.scanFile(context, arrayOf(oldPath), null, null)
                    }
                }
            }
            "batch_delete" -> {
                val rawList = if (bodyText.isNotBlank()) bodyText else (params["targets"] ?: "")
                val names = rawList.split("|").map { it.trim() }.filter { it.isNotEmpty() }
                val deletedPaths = mutableListOf<String>()
                for (name in names) {
                    val candidate = File(currentDir, name)
                    val safeItem = sanitizeFile(candidate.absolutePath)
                    if (safeItem.exists() && safeItem.absolutePath != rootStoragePath) {
                        deletedPaths.add(safeItem.absolutePath)
                        safeItem.deleteRecursively()
                    }
                }
                if (deletedPaths.isNotEmpty()) {
                    MediaScannerConnection.scanFile(context, deletedPaths.toTypedArray(), null, null)
                }
            }
        }

        if (authSession != null && authSession.elevatedUntilMs > System.currentTimeMillis()) {
            authSession.elevatedUntilMs = System.currentTimeMillis() + ELEVATED_WINDOW_MS
        }

        return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "OK")
    }

    private fun handleFileUpload(
        session: IHTTPSession,
        params: Map<String, String>,
        authSession: AuthSession?
    ): Response {
        val targetDir = sanitizeFile(params["dir"])
        if (!targetDir.exists()) targetDir.mkdirs()

        val rawName = (params["name"] ?: "arquivo_${System.currentTimeMillis()}").trim()
        val safeName = rawName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val destFile = File(targetDir, safeName)

        val contentLength = session.headers["content-length"]?.toLongOrNull() ?: 0L
        if (contentLength <= 0L) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Arquivo vazio")
        }

        val opToken = session.headers["x-op-token"] ?: params["opToken"] ?: ""
        val grant = if (opToken.isNotBlank()) activeBatchGrants[opToken] else null

        FileOutputStream(destFile).use { output ->
            val buffer = ByteArray(16 * 1024)
            var remaining = contentLength
            val input = session.inputStream
            while (remaining > 0) {
                val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                val read = input.read(buffer, 0, toRead)
                if (read == -1) break
                output.write(buffer, 0, read)
                remaining -= read

                // Mantem o token do lote e a janela deslizante vivos durante transferencias grandes
                val now = System.currentTimeMillis()
                grant?.lastChunkActivityMs = now
                if (authSession != null && authSession.elevatedUntilMs > 0L) {
                    authSession.elevatedUntilMs = now + ELEVATED_WINDOW_MS
                }
            }
            output.flush()
        }

        MediaScannerConnection.scanFile(context, arrayOf(destFile.absolutePath), null, null)
        return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "OK")
    }

    private fun handleFileStream(session: IHTTPSession, params: Map<String, String>): Response {
        val file = sanitizeFile(params["path"])
        if (!file.exists() || file.isDirectory) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Arquivo nao encontrado")
        }

        val mime = resolveMimeType(file.name)
        val fileLen = file.length()
        val rangeHeader = session.headers["range"]

        if (rangeHeader != null && rangeHeader.startsWith("bytes=") && params["download"] != "1") {
            try {
                val rangeValue = rangeHeader.removePrefix("bytes=")
                val parts = rangeValue.split("-")
                val start = parts[0].toLongOrNull() ?: 0L
                val end = if (parts.size > 1 && parts[1].isNotBlank()) {
                    parts[1].toLongOrNull() ?: (fileLen - 1)
                } else {
                    fileLen - 1
                }
                val clampedEnd = end.coerceAtMost(fileLen - 1)
                val contentLength = (clampedEnd - start + 1).coerceAtLeast(0L)

                val fis = FileInputStream(file)
                fis.skip(start)
                val res = newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, mime, fis, contentLength)
                res.addHeader("Accept-Ranges", "bytes")
                res.addHeader("Content-Range", "bytes $start-$clampedEnd/$fileLen")
                return res
            } catch (_: Exception) {}
        }

        val fis = FileInputStream(file)
        val response = newFixedLengthResponse(Response.Status.OK, mime, fis, fileLen)
        response.addHeader("Accept-Ranges", "bytes")

        if (params["download"] == "1") {
            response.addHeader(
                "Content-Disposition",
                "attachment; filename=\"${file.name.replace("\"", "")}\""
            )
        }
        return response
    }

    private fun handleFolderZipDownload(params: Map<String, String>): Response {
        val dir = sanitizeFile(params["dir"])
        if (!dir.exists() || !dir.isDirectory) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Pasta invalida")
        }

        val selectedFilter = params["files"]
            ?.split("|")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()

        val zipBaseName = if (!selectedFilter.isNullOrEmpty()) {
            "Selecionados_${dir.name.ifEmpty { "IFGestor" }}"
        } else {
            dir.name.ifEmpty { "Memoria" }
        }

        val tempZip = File(context.cacheDir, "$zipBaseName.zip")
        if (tempZip.exists()) tempZip.delete()

        ZipOutputStream(BufferedOutputStream(FileOutputStream(tempZip))).use { zos ->
            val allFiles = dir.listFiles()?.filter { it.isFile && !it.name.startsWith(".") } ?: emptyList()
            val filesToZip = if (!selectedFilter.isNullOrEmpty()) {
                allFiles.filter { selectedFilter.contains(it.name) }
            } else {
                allFiles
            }

            val buffer = ByteArray(16 * 1024)
            for (f in filesToZip) {
                FileInputStream(f).use { fis ->
                    BufferedInputStream(fis).use { origin ->
                        val entry = ZipEntry(f.name)
                        zos.putNextEntry(entry)
                        var count: Int
                        while (origin.read(buffer).also { count = it } != -1) {
                            zos.write(buffer, 0, count)
                        }
                    }
                }
            }
        }

        val fis = FileInputStream(tempZip)
        val res = newFixedLengthResponse(Response.Status.OK, "application/zip", fis, tempZip.length())
        res.addHeader(
            "Content-Disposition",
            "attachment; filename=\"${zipBaseName.replace("\"", "")}.zip\""
        )
        return res
    }

    private fun getBrandLogoSvg(sizePx: Int = 56, animatedClass: String = ""): String {
        return """
            <svg width="$sizePx" height="$sizePx" viewBox="0 0 108 108" fill="none" xmlns="http://www.w3.org/2000/svg">
                <rect x="4" y="4" width="100" height="100" rx="22" fill="#0B1120" stroke="#1E293B" stroke-width="2"/>
                <path d="M54 14L86 26V52C86 73 72 88 54 95C36 88 22 73 22 52V26L54 14Z" fill="#38BDF8"/>
                <path d="M54 18L82 28.5V52C82 70.5 69.5 84 54 90.5C38.5 84 26 70.5 26 52V28.5L54 18Z" fill="#0F2942"/>
                <path d="M34 35H46L51 40H74C75.65 40 77 41.35 77 43V69C77 71.2 75.2 73 73 73H35C32.8 73 31 71.2 31 69V38C31 36.35 32.35 35 34 35Z" fill="#0284C7"/>
                <path d="M34 35H46L50 39H31V38C31 36.35 32.35 35 34 35Z" fill="#38BDF8"/>
                <g class="$animatedClass" style="transform-origin: 54px 56px;">
                    <circle cx="54" cy="56" r="15" fill="#0B1120"/>
                    <circle cx="54" cy="56" r="12.5" fill="#10B981"/>
                    <circle cx="54" cy="56" r="10.5" fill="#0F2942"/>
                    <path d="M54 47L61.5 50.5L56 54.5Z" fill="#10B981"/>
                    <path d="M63 56L59.5 63.5L55.5 58Z" fill="#38BDF8"/>
                    <path d="M54 65L46.5 61.5L52 57.5Z" fill="#10B981"/>
                    <path d="M45 56L48.5 48.5L52.5 54Z" fill="#38BDF8"/>
                    <circle cx="54" cy="56" r="2" fill="#F8FAFC"/>
                </g>
            </svg>
        """.trimIndent()
    }

    private fun renderPinLoginPage(errorType: String?): String {
        val errBanner = when (errorType) {
            "pin" -> """<div class="err">❌ PIN incorreto. Verifique no aplicativo IFGestor.</div>"""
            "expired" -> """<div class="err">⏰ O código de 2ª etapa expirou. Digite o PIN novamente.</div>"""
            else -> ""
        }
        return """
            <!DOCTYPE html>
            <html lang="pt-BR">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>IFGestor — Gerenciador de Arquivos Seguro</title>
                <style>
                    * { box-sizing: border-box; font-family: 'Inter', system-ui, -apple-system, sans-serif; }
                    body { margin: 0; background: radial-gradient(circle at top, #0f2942 0%, #070b14 100%); color: #f8fafc; display: flex; height: 100vh; align-items: center; justify-content: center; padding: 16px; }
                    .box { background: rgba(21, 31, 50, 0.94); border: 1px solid #2e3f59; padding: 36px 32px; border-radius: 22px; width: 100%; max-width: 400px; text-align: center; box-shadow: 0 25px 50px -12px rgba(0,0,0,0.7); }
                    .logo-wrap { margin-bottom: 14px; display: inline-flex; animation: pulseIn 0.8s ease-out; }
                    @keyframes pulseIn { from { transform: scale(0.8); opacity: 0; } to { transform: scale(1); opacity: 1; } }
                    .badge-top { display: inline-block; background: rgba(56, 189, 248, 0.15); color: #38bdf8; border: 1px solid rgba(56, 189, 248, 0.3); padding: 5px 12px; border-radius: 20px; font-size: 11px; font-weight: 700; letter-spacing: 0.5px; margin-bottom: 12px; }
                    h2 { margin: 0 0 8px; color: #f8fafc; font-size: 22px; }
                    p { font-size: 13px; color: #94a3b8; margin-bottom: 24px; line-height: 1.5; }
                    input { width: 100%; padding: 14px; border-radius: 12px; border: 1px solid #475569; background: #0b1120; color: #38bdf8; font-size: 22px; font-weight: 700; text-align: center; letter-spacing: 8px; margin-bottom: 18px; outline: none; }
                    input:focus { border-color: #38bdf8; }
                    button { width: 100%; padding: 14px; background: linear-gradient(135deg, #0284c7, #10b981); color: #fff; border: none; border-radius: 12px; font-size: 15px; font-weight: 700; cursor: pointer; }
                    .err { background: rgba(127, 29, 29, 0.85); border: 1px solid #ef4444; color: #fecaca; padding: 11px; border-radius: 10px; font-size: 13px; margin-bottom: 18px; }
                </style>
            </head>
            <body>
                <div class="box">
                    <div class="logo-wrap">${getBrandLogoSvg(76)}</div><br>
                    <div class="badge-top">🛡️ ACESSO CORPORATIVO PROTEGIDO</div>
                    <h2>IFGestor Web</h2>
                    <p>Digite o PIN configurado na aba <strong>Servidor PC</strong> do seu celular (Padrão: 1234)</p>
                    $errBanner
                    <form id="pinForm">
                        <input type="password" id="pinInput" placeholder="••••" required autofocus>
                        <button type="submit">Continuar para 2ª Etapa</button>
                    </form>
                </div>
                <script>
                    document.getElementById('pinForm').onsubmit = async function(e) {
                        e.preventDefault();
                        const pin = document.getElementById('pinInput').value;
                        const resp = await fetch('/auth/pin?pin=' + encodeURIComponent(pin), { method: 'POST' });
                        if (resp.redirected) {
                            window.location.href = resp.url;
                        } else {
                            window.location.reload();
                        }
                    };
                </script>
            </body>
            </html>
        """.trimIndent()
    }

    private fun render2faLoginPage(tempToken: String, errorType: String?): String {
        val errBanner = if (errorType == "2fa") {
            """<div class="err">❌ Código de verificação inválido.</div>"""
        } else ""

        return """
            <!DOCTYPE html>
            <html lang="pt-BR">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>IFGestor — Verificação em 2 Etapas</title>
                <style>
                    * { box-sizing: border-box; font-family: 'Inter', system-ui, sans-serif; }
                    body { margin: 0; background: radial-gradient(circle at top, #0f2942 0%, #070b14 100%); color: #f8fafc; display: flex; height: 100vh; align-items: center; justify-content: center; padding: 16px; }
                    .box { background: rgba(21, 31, 50, 0.94); border: 1px solid #2e3f59; padding: 36px 32px; border-radius: 22px; width: 100%; max-width: 420px; text-align: center; box-shadow: 0 25px 50px -12px rgba(0,0,0,0.7); }
                    .badge-top { display: inline-block; background: rgba(16, 185, 129, 0.15); color: #10b981; border: 1px solid rgba(16, 185, 129, 0.35); padding: 5px 12px; border-radius: 20px; font-size: 11px; font-weight: 700; margin-bottom: 14px; }
                    h2 { margin: 0 0 8px; color: #f8fafc; font-size: 22px; }
                    p { font-size: 13px; color: #cbd5e1; margin-bottom: 20px; line-height: 1.5; }
                    input { width: 100%; padding: 14px; border-radius: 12px; border: 1px solid #475569; background: #0b1120; color: #10b981; font-size: 26px; font-weight: 800; text-align: center; letter-spacing: 10px; margin-bottom: 18px; outline: none; }
                    button { width: 100%; padding: 14px; background: #10b981; color: #fff; border: none; border-radius: 12px; font-size: 15px; font-weight: 700; cursor: pointer; }
                    .err { background: rgba(127, 29, 29, 0.85); border: 1px solid #ef4444; color: #fecaca; padding: 11px; border-radius: 10px; font-size: 13px; margin-bottom: 18px; }
                </style>
            </head>
            <body>
                <div class="box">
                    <div style="margin-bottom:12px;">${getBrandLogoSvg(64)}</div>
                    <div class="badge-top">📲 2ª ETAPA DE SEGURANÇA (2FA)</div>
                    <h2>Autorize no Celular</h2>
                    <p>Toque em <strong>"✓ Aprovar Agora"</strong> na notificação do seu celular ou digite abaixo o código de <strong>6 dígitos</strong>:</p>
                    $errBanner
                    <form id="twoFaForm">
                        <input type="text" id="codeInput" maxlength="6" placeholder="000000" required autofocus>
                        <button type="submit">Liberar Gerenciador de Arquivos</button>
                    </form>
                </div>
                <script>
                    const tempToken = "${escapeJs(tempToken)}";
                    document.getElementById('twoFaForm').onsubmit = async function(e) {
                        e.preventDefault();
                        const code = document.getElementById('codeInput').value;
                        const resp = await fetch('/auth/2fa?temp=' + encodeURIComponent(tempToken) + '&code=' + encodeURIComponent(code), { method: 'POST' });
                        if (resp.redirected) {
                            window.location.href = resp.url;
                        } else {
                            window.location.reload();
                        }
                    };
                    // Verifica automaticamente se o usuario clicou em "Aprovar Agora" na notificacao do celular
                    setInterval(async () => {
                        try {
                            const r = await fetch('/auth/check_login_2fa?temp=' + encodeURIComponent(tempToken));
                            const data = await r.json();
                            if (data.approved) {
                                window.location.href = '/';
                            }
                        } catch (_) {}
                    }, 1200);
                </script>
            </body>
            </html>
        """.trimIndent()
    }

    private fun renderLockoutPage(remainingSeconds: Long): String {
        return """
            <!DOCTYPE html>
            <html lang="pt-BR">
            <head><meta charset="UTF-8"><title>Acesso Temporariamente Bloqueado</title></head>
            <body style="background:#070b14;color:#f8fafc;font-family:sans-serif;display:flex;height:100vh;align-items:center;justify-content:center;text-align:center;padding:16px;">
                <div style="background:#151f32;border:1px solid #ef4444;padding:32px;border-radius:18px;max-width:420px;">
                    <h2 style="color:#ef4444;margin-top:0;">🛡️ Bloqueio Anti-Força Bruta</h2>
                    <p style="color:#cbd5e1;font-size:14px;">Foram detectadas muitas tentativas incorretas a partir deste computador.</p>
                    <p style="color:#f8fafc;font-size:15px;">Tente novamente em <strong>$remainingSeconds segundos</strong>.</p>
                </div>
            </body>
            </html>
        """.trimIndent()
    }

    private fun buildBreadcrumbsHtml(currentDir: File): String {
        val rootFile = File(rootStoragePath).canonicalFile
        val currentCanonical = currentDir.canonicalFile
        val sb = StringBuilder()

        sb.append("""<a class="crumb" href="/?dir=${urlEncode(rootFile.absolutePath)}">🏠 Memória Interna</a>""")

        if (currentCanonical.path.startsWith(rootFile.path) && currentCanonical.path != rootFile.path) {
            val rel = currentCanonical.path.removePrefix(rootFile.path).trimStart('/', '\\')
            val parts = rel.split('/', '\\').filter { it.isNotEmpty() }
            var accum = rootFile
            for (part in parts) {
                accum = File(accum, part)
                sb.append(""" <span class="crumb-sep">›</span> """)
                sb.append("""<a class="crumb" href="/?dir=${urlEncode(accum.absolutePath)}">${escapeHtml(part)}</a>""")
            }
        }
        return sb.toString()
    }

    private fun renderExplorerPage(
        requestedDir: String?,
        csrfToken: String,
        authSession: AuthSession?
    ): Response {
        val currentDir = sanitizeFile(requestedDir)
        if (!currentDir.exists()) currentDir.mkdirs()

        val storageStats = SettingsManager.getStorageStats()
        val now = System.currentTimeMillis()
        val initialElevatedRemSec = if (authSession != null && authSession.elevatedUntilMs > now) {
            ((authSession.elevatedUntilMs - now) / 1000).coerceAtLeast(0)
        } else 0L

        val children = currentDir.listFiles()
            ?.filter { !it.name.startsWith(".") }
            ?.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
            ?: emptyList()

        val totalFolders = children.count { it.isDirectory }
        val totalPhotos = children.count { !it.isDirectory && isImageFile(it.name) }
        val totalVideos = children.count { !it.isDirectory && isVideoFile(it.name) }

        val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
        val encodedCurrentDir = urlEncode(currentDir.absolutePath)
        val parentDir = if (currentDir.absolutePath != rootStoragePath) currentDir.parentFile else null

        val mediaItemsJsArray = StringBuilder("[")
        var mediaIndex = 0
        val filePathToMediaIndex = mutableMapOf<String, Int>()

        for (item in children) {
            if (!item.isDirectory && (isImageFile(item.name) || isVideoFile(item.name))) {
                if (mediaIndex > 0) mediaItemsJsArray.append(",")
                val type = if (isImageFile(item.name)) "image" else "video"
                val fileUrl = "/file?path=" + urlEncode(item.absolutePath)
                val downloadUrl = "$fileUrl&download=1"
                mediaItemsJsArray.append(
                    """{"name":"${escapeJs(item.name)}","path":"${escapeJs(item.absolutePath)}","url":"$fileUrl","download":"$downloadUrl","type":"$type","size":"${formatSize(item.length())}","date":"${dateFormat.format(Date(item.lastModified()))}"}"""
                )
                filePathToMediaIndex[item.absolutePath] = mediaIndex
                mediaIndex++
            }
        }
        mediaItemsJsArray.append("]")

        val breadcrumbsHtml = buildBreadcrumbsHtml(currentDir)

        val sb = StringBuilder()
        sb.append(
            """
            <!DOCTYPE html>
            <html lang="pt-BR" data-theme="dark">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>IFGestor — Gerenciador de Arquivos</title>
                <style>
                    :root {
                        --card-min: 220px;
                        --thumb-h: 165px;
                        --bg-main: #0b1120;
                        --bg-surface: #151f32;
                        --bg-elevated: #1e293b;
                        --border-color: #2e3f59;
                        --text-primary: #f8fafc;
                        --text-secondary: #94a3b8;
                        --brand-primary: #0284c7;
                        --brand-accent: #38bdf8;
                        --brand-success: #10b981;
                        --brand-danger: #ef4444;
                    }
                    html[data-theme="light"] {
                        --bg-main: #f1f5f9;
                        --bg-surface: #ffffff;
                        --bg-elevated: #e2e8f0;
                        --border-color: #cbd5e1;
                        --text-primary: #0f172a;
                        --text-secondary: #475569;
                        --brand-primary: #0f2942;
                        --brand-accent: #0284c7;
                        --brand-success: #059669;
                        --brand-danger: #dc2626;
                    }
                    * { box-sizing: border-box; font-family: 'Inter', system-ui, -apple-system, sans-serif; }
                    body { margin: 0; background: var(--bg-main); color: var(--text-primary); padding: 18px 18px 95px; transition: background 0.2s, color 0.2s; }
                    .container { max-width: 1360px; margin: 0 auto; }

                    /* Tela de Abertura Animada em Tela Cheia (Splash Screen Web) */
                    .web-splash { position: fixed; inset: 0; background: radial-gradient(circle at center, #0f2942 0%, #060a12 100%); z-index: 15000; display: flex; flex-direction: column; align-items: center; justify-content: center; transition: opacity 0.45s ease, visibility 0.45s ease; }
                    .web-splash.hidden { opacity: 0; visibility: hidden; pointer-events: none; }
                    .splash-ring { padding: 24px; border-radius: 50%; background: rgba(56, 189, 248, 0.06); border: 1px solid rgba(56, 189, 248, 0.2); animation: splashPulse 1.4s ease-out infinite alternate; }
                    .spin-lens { animation: spinShutter 2.2s cubic-bezier(0.4, 0, 0.2, 1) infinite; }
                    @keyframes spinShutter { 0% { transform: rotate(0deg) scale(0.92); } 50% { transform: rotate(180deg) scale(1.04); } 100% { transform: rotate(360deg) scale(0.92); } }
                    @keyframes splashPulse { from { box-shadow: 0 0 15px rgba(56,189,248,0.15); } to { box-shadow: 0 0 40px rgba(16,185,129,0.35); } }
                    .splash-title { margin: 18px 0 4px; font-size: 28px; font-weight: 800; color: #f8fafc; letter-spacing: 0.5px; }
                    .splash-sub { margin: 0 0 22px; font-size: 13px; color: #94a3b8; }
                    .splash-bar { width: 220px; height: 5px; background: #1e293b; border-radius: 8px; overflow: hidden; }
                    .splash-bar-fill { width: 0%; height: 100%; background: linear-gradient(90deg, #38bdf8, #10b981); animation: loadProgress 1.25s ease-out forwards; }
                    @keyframes loadProgress { to { width: 100%; } }

                    /* Cabecalho Corporativo */
                    .header { background: var(--bg-surface); padding: 16px 22px; border-radius: 16px; display: flex; flex-wrap: wrap; justify-content: space-between; align-items: center; gap: 16px; margin-bottom: 14px; border: 1px solid var(--border-color); }
                    .brand-row { display: flex; align-items: center; gap: 14px; }
                    .title h1 { margin: 0; font-size: 20px; color: var(--text-primary); }
                    .title p { margin: 4px 0 0; font-size: 12px; color: var(--text-secondary); }
                    .storage-widget { min-width: 240px; background: var(--bg-elevated); padding: 10px 14px; border-radius: 12px; border: 1px solid var(--border-color); }
                    .storage-top { display: flex; justify-content: space-between; font-size: 11px; font-weight: 600; margin-bottom: 6px; color: var(--text-secondary); }
                    .storage-bar-bg { width: 100%; height: 7px; background: var(--bg-main); border-radius: 10px; overflow: hidden; }
                    .storage-bar-fill { height: 100%; width: ${storageStats.usedPercent}%; background: linear-gradient(90deg, #0284c7, #10b981); border-radius: 10px; }

                    /* Barra de Navegacao e Ferramentas */
                    .toolbar { background: var(--bg-surface); padding: 14px 18px; border-radius: 16px; margin-bottom: 14px; display: flex; flex-direction: column; gap: 12px; border: 1px solid var(--border-color); }
                    .crumbs-row { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 10px; padding-bottom: 10px; border-bottom: 1px solid var(--border-color); }
                    .crumbs { display: flex; align-items: center; flex-wrap: wrap; gap: 4px; font-size: 13px; }
                    .crumb { color: var(--brand-accent); text-decoration: none; font-weight: 600; padding: 4px 8px; border-radius: 6px; }
                    .crumb:hover { background: var(--bg-elevated); }
                    .crumb-sep { color: var(--text-secondary); font-weight: bold; }

                    .actions-row { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; justify-content: space-between; }
                    .btn-cluster { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; }

                    /* Barra de Busca e Filtros */
                    .filter-bar { background: var(--bg-surface); padding: 12px 18px; border-radius: 14px; margin-bottom: 16px; display: flex; flex-wrap: wrap; gap: 12px; align-items: center; justify-content: space-between; border: 1px solid var(--border-color); }
                    .search-box { flex: 1; min-width: 220px; position: relative; }
                    .search-box input { width: 100%; padding: 9px 14px 9px 36px; border-radius: 10px; border: 1px solid var(--border-color); background: var(--bg-main); color: var(--text-primary); font-size: 13px; outline: none; }
                    .search-icon { position: absolute; left: 11px; top: 50%; transform: translateY(-50%); font-size: 14px; color: var(--text-secondary); }
                    .pill-group { display: inline-flex; background: var(--bg-main); padding: 4px; border-radius: 10px; border: 1px solid var(--border-color); gap: 4px; flex-wrap: wrap; }
                    .pill-btn { background: transparent; color: var(--text-secondary); border: none; padding: 6px 12px; border-radius: 7px; font-size: 12px; cursor: pointer; font-weight: 600; }
                    .pill-btn.active { background: var(--brand-primary); color: #fff; }

                    button, .btn { background: var(--brand-primary); color: #fff; border: none; padding: 8px 14px; border-radius: 10px; font-size: 12px; font-weight: 600; cursor: pointer; text-decoration: none; display: inline-flex; align-items: center; gap: 6px; transition: all 0.15s; }
                    button:hover, .btn:hover { filter: brightness(1.12); }
                    .btn-sec { background: var(--bg-elevated); color: var(--text-primary); border: 1px solid var(--border-color); }
                    .btn-ok { background: var(--brand-success); color: #fff; }
                    .btn-danger { background: var(--brand-danger); color: #fff; }

                    /* Grade de Fotos, Videos e Pastas */
                    .grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(var(--card-min), 1fr)); gap: 14px; }
                    .card { background: var(--bg-surface); border: 1px solid var(--border-color); border-radius: 14px; padding: 10px; display: flex; flex-direction: column; justify-content: space-between; position: relative; transition: transform 0.12s, border-color 0.12s; }
                    .card:hover { border-color: var(--brand-accent); transform: translateY(-2px); }
                    .card.selected { border: 2px solid var(--brand-accent); background: rgba(56, 189, 248, 0.08); }
                    .select-check { position: absolute; top: 16px; right: 16px; width: 22px; height: 22px; z-index: 5; accent-color: var(--brand-primary); cursor: pointer; }
                    .thumb-box { width: 100%; height: var(--thumb-h); background: var(--bg-main); border-radius: 10px; display: flex; align-items: center; justify-content: center; overflow: hidden; margin-bottom: 8px; cursor: pointer; position: relative; }
                    .thumb-box img { width: 100%; height: 100%; object-fit: cover; }
                    .play-badge { position: absolute; bottom: 8px; right: 8px; background: rgba(15, 23, 42, 0.85); color: #fff; padding: 4px 10px; border-radius: 16px; font-size: 11px; font-weight: 700; }
                    .icon-big { font-size: 46px; }
                    .item-name { font-size: 13px; font-weight: 600; color: var(--text-primary); word-break: break-word; margin-bottom: 3px; cursor: pointer; display: block; text-decoration: none; }
                    .item-meta { font-size: 11px; color: var(--text-secondary); margin-bottom: 8px; }
                    .card-actions { display: flex; gap: 4px; }
                    .card-actions .btn, .card-actions button { padding: 6px 6px; font-size: 11px; flex: 1; justify-content: center; }

                    /* Barra Flutuante de Selecao em Lote */
                    .batch-bar { position: fixed; bottom: 20px; left: 50%; transform: translateX(-50%); background: #0f2942; color: #fff; padding: 12px 22px; border-radius: 16px; display: none; align-items: center; gap: 12px; z-index: 900; box-shadow: 0 20px 35px rgba(0,0,0,0.6); border: 1px solid #38bdf8; flex-wrap: wrap; }
                    .batch-bar.visible { display: flex; }

                    /* Zona Drag & Drop */
                    .drop-overlay { position: fixed; inset: 0; background: rgba(2, 132, 199, 0.88); color: #fff; z-index: 9500; display: none; flex-direction: column; align-items: center; justify-content: center; font-size: 24px; font-weight: 800; border: 6px dashed #fff; }
                    .drop-overlay.active { display: flex; }

                    /* Modais Customizados */
                    .modal-backdrop { position: fixed; inset: 0; background: rgba(5, 10, 20, 0.82); z-index: 9200; display: none; align-items: center; justify-content: center; padding: 16px; }
                    .modal-backdrop.open { display: flex; }
                    .modal-card { background: var(--bg-surface); border: 1px solid var(--border-color); border-radius: 18px; padding: 24px; width: 100%; max-width: 460px; box-shadow: 0 25px 50px rgba(0,0,0,0.6); }
                    .modal-card h3 { margin: 0 0 8px; font-size: 18px; color: var(--text-primary); }
                    .modal-card input[type="text"] { width: 100%; padding: 11px 14px; border-radius: 10px; border: 1px solid var(--border-color); background: var(--bg-main); color: var(--text-primary); font-size: 14px; margin: 10px 0 16px; outline: none; }
                    .modal-footer { display: flex; justify-content: flex-end; gap: 8px; margin-top: 14px; }

                    /* Lista de Pastas no Seletor de Destino (Mover) */
                    .dir-picker-list { max-height: 230px; overflow-y: auto; background: var(--bg-main); border: 1px solid var(--border-color); border-radius: 10px; padding: 6px; margin: 10px 0; }
                    .dir-item { padding: 8px 10px; border-radius: 8px; cursor: pointer; display: flex; align-items: center; gap: 8px; font-size: 13px; color: var(--text-primary); }
                    .dir-item:hover { background: var(--bg-elevated); }

                    /* Modal de Tela Cheia (Lightbox) */
                    .lightbox { display: none; position: fixed; inset: 0; background: rgba(5, 10, 20, 0.96); z-index: 9999; flex-direction: column; justify-content: space-between; }
                    .lightbox.open { display: flex; }
                    .lb-top { padding: 14px 22px; background: rgba(15, 23, 42, 0.9); display: flex; justify-content: space-between; align-items: center; gap: 12px; flex-wrap: wrap; border-bottom: 1px solid #334155; }
                    .lb-title { font-size: 15px; font-weight: 600; color: #f8fafc; word-break: break-all; }
                    .lb-counter { color: #38bdf8; font-weight: 700; margin-right: 10px; }
                    .lb-actions { display: flex; gap: 8px; align-items: center; }
                    .lb-stage { flex: 1; display: flex; align-items: center; justify-content: center; position: relative; overflow: hidden; padding: 16px 70px; }
                    .lb-stage img, .lb-stage video { max-width: 100%; max-height: 82vh; object-fit: contain; border-radius: 6px; transition: transform 0.2s; }
                    .lb-stage img.zoomed { transform: scale(1.75); cursor: zoom-out; }
                    .lb-nav { position: absolute; top: 50%; transform: translateY(-50%); background: rgba(30, 41, 59, 0.85); color: #fff; border: 1px solid #475569; width: 52px; height: 52px; border-radius: 50%; font-size: 22px; cursor: pointer; display: flex; align-items: center; justify-content: center; }
                    .lb-prev { left: 16px; }
                    .lb-next { right: 16px; }
                    .lb-footer { padding: 10px; text-align: center; font-size: 12px; color: #94a3b8; background: rgba(15, 23, 42, 0.8); }
                </style>
            </head>
            <body>
            <!-- Tela de Abertura Animada (Splash Screen) -->
            <div id="webSplash" class="web-splash">
                <div class="splash-ring">
                    ${getBrandLogoSvg(112, "spin-lens")}
                </div>
                <div class="splash-title">IFGestor</div>
                <div class="splash-sub">Gerenciador Corporativo de Mídia & Arquivos</div>
                <div class="splash-bar"><div class="splash-bar-fill"></div></div>
            </div>

            <div id="dropOverlay" class="drop-overlay">
                ⬆️ Solte os arquivos aqui para enviar (Requer autorização 2FA no celular)
            </div>

            <div class="container">
                <!-- Cabecalho Corporativo Puro (Apenas Gerenciador de Arquivos) -->
                <div class="header">
                    <div class="brand-row">
                        ${getBrandLogoSvg(48)}
                        <div class="title">
                            <h1>IFGestor — Gerenciador de Arquivos</h1>
                            <p>🛡️ Proteção 2FA Ativa em Todas as Movimentações, Envios e Exclusões</p>
                        </div>
                    </div>

                    <div class="storage-widget">
                        <div class="storage-top">
                            <span>💾 Armazenamento do Celular</span>
                            <span>${storageStats.usedPercent}%</span>
                        </div>
                        <div class="storage-bar-bg"><div class="storage-bar-fill"></div></div>
                        <div style="font-size:11px; color:var(--text-secondary); margin-top:5px;">${storageStats.formattedSummary}</div>
                    </div>

                    <div class="btn-cluster">
                        <button type="button" class="btn-ok" id="elevatedBadgeBtn" style="display:none;" onclick="lockElevatedWindowNow()" title="Clique para bloquear imediatamente">
                            🔓 Janela 2FA Ativa (<span id="elevatedTimerText">5:00</span>) • Bloquear Agora
                        </button>
                        <button type="button" class="btn-sec" onclick="toggleTheme()">☀️ Claro / 🌙 Escuro</button>
                        ${if (SettingsManager.isWebAuthEnabled(context)) """<a class="btn btn-danger" href="/auth/logout">🔒 Sair</a>""" else ""}
                    </div>
                </div>

                <!-- Barra de Caminho (Breadcrumbs) e Ferramentas de Arquivo -->
                <div class="toolbar">
                    <div class="crumbs-row">
                        <div class="crumbs">$breadcrumbsHtml</div>
                        <span id="uploadStatus" style="font-size:12px; font-weight:700; color:var(--brand-accent);"></span>
                    </div>

                    <div class="actions-row">
                        <div class="btn-cluster">
                            ${if (parentDir != null) """<a class="btn btn-sec" href="/?dir=${urlEncode(parentDir.absolutePath)}">⬅️ Pasta Acima</a>""" else ""}
                            <button type="button" onclick="openMkdirModal()">➕ Nova Pasta</button>
                            <a class="btn btn-sec" href="/zip?dir=$encodedCurrentDir">📦 Baixar Pasta (.ZIP)</a>
                            <label class="btn btn-ok" style="cursor:pointer;">
                                ⬆️ Enviar do PC para cá
                                <input type="file" id="fileInput" multiple style="display:none" onchange="uploadFilesWith2FA(this.files)">
                            </label>
                        </div>

                        <div class="btn-cluster">
                            <button type="button" class="btn-sec" onclick="toggleSelectAll()">☑️ Selecionar Tudo</button>
                            <a class="btn btn-sec" href="/?dir=$encodedCurrentDir">🔄 Atualizar</a>
                        </div>
                    </div>
                </div>

                <!-- Barra de Busca, Filtro por Tipo e Tamanho de Miniaturas -->
                <div class="filter-bar">
                    <div class="search-box">
                        <span class="search-icon">🔍</span>
                        <input type="text" id="searchInput" placeholder="Buscar fotos, vídeos ou pastas pelo nome..." oninput="applyFilters()">
                    </div>

                    <div class="pill-group">
                        <button type="button" class="pill-btn active" id="fltAll" onclick="setCategoryFilter('all')">Todos (${children.size})</button>
                        <button type="button" class="pill-btn" id="fltImage" onclick="setCategoryFilter('image')">Fotos ($totalPhotos)</button>
                        <button type="button" class="pill-btn" id="fltVideo" onclick="setCategoryFilter('video')">Vídeos ($totalVideos)</button>
                        <button type="button" class="pill-btn" id="fltFolder" onclick="setCategoryFilter('folder')">Pastas ($totalFolders)</button>
                    </div>

                    <div class="pill-group" title="Tamanho das Miniaturas">
                        <button type="button" class="pill-btn" id="btnSizeS" onclick="setThumbSize('small')">Pequenas</button>
                        <button type="button" class="pill-btn active" id="btnSizeM" onclick="setThumbSize('medium')">Médias</button>
                        <button type="button" class="pill-btn" id="btnSizeL" onclick="setThumbSize('large')">Grandes</button>
                    </div>
                </div>

                <!-- Grade Principal -->
                <div class="grid" id="itemsGrid">
            """.trimIndent()
        )

        if (children.isEmpty()) {
            sb.append("""<p style="color:var(--text-secondary); grid-column: 1/-1; text-align:center; padding: 40px 0;">Esta pasta está vazia.</p>""")
        }

        for (item in children) {
            val encodedPath = urlEncode(item.absolutePath)
            val dateStr = dateFormat.format(Date(item.lastModified()))
            val safeNameLower = escapeHtml(item.name.lowercase())

            if (item.isDirectory) {
                val countInside = item.listFiles()?.size ?: 0
                sb.append(
                    """
                    <div class="card item-card" data-name="$safeNameLower" data-type="folder" data-filename="${escapeHtml(item.name)}">
                        <input type="checkbox" class="select-check" onchange="onCardSelectChange(this)">
                        <div>
                            <a href="/?dir=$encodedPath" style="text-decoration:none;">
                                <div class="thumb-box"><span class="icon-big">📁</span></div>
                                <span class="item-name">${escapeHtml(item.name)}</span>
                            </a>
                            <div class="item-meta">Pasta ($countInside itens) • $dateStr</div>
                        </div>
                        <div class="card-actions">
                            <a class="btn" href="/?dir=$encodedPath">Abrir</a>
                            <button type="button" class="btn-sec" title="Mover Pasta" onclick="openMoveModal('${escapeJs(item.absolutePath)}', '${escapeJs(item.name)}', false)">📁↗</button>
                            <button type="button" class="btn-sec" title="Renomear" onclick="openRenameModal('${escapeJs(item.absolutePath)}', '${escapeJs(item.name)}')">✏️</button>
                            <button type="button" class="btn-danger" title="Excluir" onclick="openDeleteModal('${escapeJs(item.absolutePath)}', '${escapeJs(item.name)}')">🗑️</button>
                        </div>
                    </div>
                    """.trimIndent()
                )
            } else {
                val isImg = isImageFile(item.name)
                val isVid = isVideoFile(item.name)
                val category = when {
                    isImg -> "image"
                    isVid -> "video"
                    else -> "doc"
                }
                val sizeStr = formatSize(item.length())
                val mIdx = filePathToMediaIndex[item.absolutePath]

                val clickAction = if (mIdx != null) {
                    "openLightbox($mIdx)"
                } else {
                    "window.open('/file?path=$encodedPath', '_blank')"
                }

                val previewHtml = when {
                    isImg -> """<img src="/file?path=$encodedPath" loading="lazy" alt="${escapeHtml(item.name)}">"""
                    isVid -> """<span class="icon-big">🎬</span><span class="play-badge">▶ VÍDEO</span>"""
                    else -> """<span class="icon-big">📄</span>"""
                }

                sb.append(
                    """
                    <div class="card item-card" data-name="$safeNameLower" data-type="$category" data-filename="${escapeHtml(item.name)}">
                        <input type="checkbox" class="select-check" onchange="onCardSelectChange(this)">
                        <div>
                            <div class="thumb-box" onclick="$clickAction">$previewHtml</div>
                            <div class="item-name" onclick="$clickAction">${escapeHtml(item.name)}</div>
                            <div class="item-meta">$sizeStr • $dateStr</div>
                        </div>
                        <div class="card-actions">
                            ${if (mIdx != null) """<button type="button" title="Tela Cheia" onclick="openLightbox($mIdx)">🔍</button>""" else ""}
                            <a class="btn btn-ok" title="Baixar" href="/file?path=$encodedPath&download=1">⬇️</a>
                            <button type="button" class="btn-sec" title="Mover para outra pasta" onclick="openMoveModal('${escapeJs(item.absolutePath)}', '${escapeJs(item.name)}', false)">📁↗</button>
                            <button type="button" class="btn-sec" title="Renomear" onclick="openRenameModal('${escapeJs(item.absolutePath)}', '${escapeJs(item.name)}')">✏️</button>
                            <button type="button" class="btn-danger" title="Excluir" onclick="openDeleteModal('${escapeJs(item.absolutePath)}', '${escapeJs(item.name)}')">🗑️</button>
                        </div>
                    </div>
                    """.trimIndent()
                )
            }
        }

        sb.append(
            """
                </div>
            </div>

            <!-- Barra Flutuante de Acoes em Lote -->
            <div id="batchBar" class="batch-bar">
                <strong id="batchCountText">0 selecionados</strong>
                <button type="button" class="btn-ok" onclick="downloadSelectedZip()">📦 Baixar (.ZIP)</button>
                <button type="button" class="btn" onclick="openBatchMoveModal()">📁↗ Mover Selecionados</button>
                <button type="button" class="btn-danger" onclick="deleteSelectedBatch()">🗑️ Excluir Selecionados</button>
                <button type="button" class="btn-sec" onclick="clearAllSelection()">✖ Limpar</button>
            </div>

            <!-- Modal Customizado de Entrada / Confirmacao -->
            <div id="customModal" class="modal-backdrop">
                <div class="modal-card">
                    <h3 id="modalTitle">Título</h3>
                    <p id="modalDesc" style="font-size:13px; color:var(--text-secondary); margin:0 0 8px;"></p>
                    <input type="text" id="modalInput" placeholder="">
                    <div class="modal-footer">
                        <button type="button" class="btn-sec" onclick="closeCustomModal()">Cancelar</button>
                        <button type="button" id="modalConfirmBtn">Continuar (2FA)</button>
                    </div>
                </div>
            </div>

            <!-- Modal Seletor Livre de Pastas para Mover Arquivos -->
            <div id="moveModal" class="modal-backdrop">
                <div class="modal-card">
                    <h3>📁 Mover para qual pasta?</h3>
                    <p id="moveModalTargetDesc" style="font-size:12px; color:var(--text-secondary); margin:0 0 6px;"></p>
                    <div style="font-size:12px; font-weight:700; color:var(--brand-accent); margin-bottom:6px;" id="moveCurrentPathLabel">Memória Interna</div>
                    <div class="dir-picker-list" id="moveDirsContainer"></div>
                    <div class="modal-footer">
                        <button type="button" class="btn-sec" onclick="closeMoveModal()">Cancelar</button>
                        <button type="button" class="btn-ok" id="confirmMoveHereBtn">✓ Mover para Esta Pasta</button>
                    </div>
                </div>
            </div>

            <!-- Modal de Verificacao em 2 Etapas (2FA) por Operacao -->
            <div id="action2faModal" class="modal-backdrop">
                <div class="modal-card" style="text-align:center;">
                    <div style="font-size:11px; font-weight:800; color:#10b981; margin-bottom:6px;">📲 AUTORIZAÇÃO EM DUAS ETAPAS (2FA)</div>
                    <h3 id="a2faTitle">Confirmar Operação</h3>
                    <p style="font-size:13px; color:var(--text-secondary); line-height:1.45;">
                        Toque em <strong>"✓ Aprovar Agora"</strong> na notificação do seu celular ou digite abaixo o código de <strong>6 dígitos</strong>:
                    </p>
                    <input type="text" id="a2faCodeInput" maxlength="6" placeholder="000000" style="text-align:center; font-size:24px; font-weight:800; letter-spacing:8px; color:#10b981;">
                    <label style="display:flex; align-items:center; justify-content:center; gap:8px; font-size:12px; color:var(--text-secondary); cursor:pointer; margin-bottom:10px;">
                        <input type="checkbox" id="a2faKeep5Min" style="width:16px; height:16px;">
                        Manter operações liberadas por 5 min (sem interromper lotes)
                    </label>
                    <div id="a2faErrorText" style="color:#ef4444; font-size:12px; font-weight:600; display:none; margin-bottom:8px;">❌ Código incorreto.</div>
                    <div class="modal-footer" style="justify-content:center;">
                        <button type="button" class="btn-sec" onclick="cancelAction2fa()">Cancelar Operação</button>
                        <button type="button" class="btn-ok" id="a2faSubmitBtn">Verificar e Executar</button>
                    </div>
                </div>
            </div>

            <!-- Modal Visualizador em Tela Cheia (Lightbox) -->
            <div id="lightboxModal" class="lightbox">
                <div class="lb-top">
                    <div>
                        <span id="lbCounter" class="lb-counter">1 / 1</span>
                        <span id="lbTitle" class="lb-title">Arquivo</span>
                    </div>
                    <div class="lb-actions">
                        <button type="button" class="btn-sec" onclick="toggleLightboxZoom()">🔍 Zoom</button>
                        <a id="lbDownloadBtn" class="btn btn-ok" href="#">⬇️ Baixar</a>
                        <button type="button" class="btn-sec" onclick="moveCurrentLightboxItem()">📁↗ Mover</button>
                        <button type="button" class="btn-sec" onclick="renameCurrentLightboxItem()">✏️ Renomear</button>
                        <button type="button" class="btn-danger" onclick="deleteCurrentLightboxItem()">🗑️ Excluir</button>
                        <button type="button" class="btn-sec" onclick="closeLightbox()">✖ Fechar</button>
                    </div>
                </div>
                <div class="lb-stage" onclick="if(event.target === this) closeLightbox()">
                    <button type="button" class="lb-nav lb-prev" onclick="stepLightbox(-1)">◀</button>
                    <div id="lbMediaHolder" style="display:flex; align-items:center; justify-content:center; width:100%; height:100%;"></div>
                    <button type="button" class="lb-nav lb-next" onclick="stepLightbox(1)">▶</button>
                </div>
                <div class="lb-footer">
                    Use <strong>◀ Anterior</strong> / <strong>Próximo ▶</strong> ou as setas <strong>⬅️ ➡️</strong> do teclado
                </div>
            </div>

            <script>
                const currentDir = "$encodedCurrentDir";
                const rawCurrentDirPath = "${escapeJs(currentDir.absolutePath)}";
                const csrfToken = "$csrfToken";
                const mediaList = $mediaItemsJsArray;
                let currentLightboxIndex = 0;
                let activeCategory = 'all';
                let elevatedRemainingSec = $initialElevatedRemSec;
                let poll2faTimer = null;

                // Controle da Splash Screen Animada na Abertura da Sessao
                (function initWebSplash() {
                    const splash = document.getElementById('webSplash');
                    if (sessionStorage.getItem('ifg_splash_shown') === '1') {
                        splash.classList.add('hidden');
                    } else {
                        sessionStorage.setItem('ifg_splash_shown', '1');
                        setTimeout(() => splash.classList.add('hidden'), 1350);
                    }
                })();

                // Cronometro Visual da Janela Deslizante de 5 Minutos
                function updateElevatedBadgeUI() {
                    const btn = document.getElementById('elevatedBadgeBtn');
                    const txt = document.getElementById('elevatedTimerText');
                    if (elevatedRemainingSec > 0) {
                        btn.style.display = 'inline-flex';
                        const m = Math.floor(elevatedRemainingSec / 60);
                        const s = elevatedRemainingSec % 60;
                        txt.innerText = m + ":" + (s < 10 ? "0" + s : s);
                    } else {
                        btn.style.display = 'none';
                    }
                }
                updateElevatedBadgeUI();
                setInterval(() => {
                    if (elevatedRemainingSec > 0) {
                        elevatedRemainingSec--;
                        updateElevatedBadgeUI();
                    }
                }, 1000);

                async function lockElevatedWindowNow() {
                    await fetch('/action/lock_window', {
                        method: 'POST',
                        headers: { 'X-CSRF-Token': csrfToken }
                    });
                    elevatedRemainingSec = 0;
                    updateElevatedBadgeUI();
                }

                // Tema Dinamico Claro / Escuro
                function applyTheme(theme) {
                    document.documentElement.setAttribute('data-theme', theme);
                    localStorage.setItem('ifg_web_theme', theme);
                }
                function toggleTheme() {
                    const current = document.documentElement.getAttribute('data-theme') || 'dark';
                    applyTheme(current === 'dark' ? 'light' : 'dark');
                }
                applyTheme(localStorage.getItem('ifg_web_theme') || 'dark');

                // Tamanho das Miniaturas (Pequenas, Medias, Grandes)
                function setThumbSize(size) {
                    const root = document.documentElement;
                    document.getElementById('btnSizeS').classList.remove('active');
                    document.getElementById('btnSizeM').classList.remove('active');
                    document.getElementById('btnSizeL').classList.remove('active');

                    if (size === 'small') {
                        root.style.setProperty('--card-min', '155px');
                        root.style.setProperty('--thumb-h', '110px');
                        document.getElementById('btnSizeS').classList.add('active');
                    } else if (size === 'large') {
                        root.style.setProperty('--card-min', '330px');
                        root.style.setProperty('--thumb-h', '245px');
                        document.getElementById('btnSizeL').classList.add('active');
                    } else {
                        root.style.setProperty('--card-min', '220px');
                        root.style.setProperty('--thumb-h', '165px');
                        document.getElementById('btnSizeM').classList.add('active');
                    }
                    localStorage.setItem('ifg_thumb_size', size);
                }
                setThumbSize(localStorage.getItem('ifg_thumb_size') || 'medium');

                // Busca e Filtros
                function setCategoryFilter(cat) {
                    activeCategory = cat;
                    ['All', 'Image', 'Video', 'Folder'].forEach(id => {
                        const el = document.getElementById('flt' + id);
                        if (el) el.classList.remove('active');
                    });
                    const activeBtnId = cat === 'all' ? 'fltAll' : cat === 'image' ? 'fltImage' : cat === 'video' ? 'fltVideo' : 'fltFolder';
                    document.getElementById(activeBtnId).classList.add('active');
                    applyFilters();
                }

                function applyFilters() {
                    const q = (document.getElementById('searchInput').value || '').toLowerCase().trim();
                    document.querySelectorAll('.item-card').forEach(card => {
                        const name = card.getAttribute('data-name') || '';
                        const type = card.getAttribute('data-type') || '';
                        const matchQuery = q === '' || name.includes(q);
                        const matchCat = activeCategory === 'all' || type === activeCategory;
                        card.style.display = (matchQuery && matchCat) ? 'flex' : 'none';
                    });
                }

                // Selecao Multipla em Lote
                function getSelectedFilenames() {
                    const selected = [];
                    document.querySelectorAll('.item-card').forEach(card => {
                        const cb = card.querySelector('.select-check');
                        if (cb && cb.checked && card.style.display !== 'none') {
                            selected.push(card.getAttribute('data-filename'));
                        }
                    });
                    return selected;
                }

                function onCardSelectChange(cb) {
                    const card = cb.closest('.item-card');
                    if (cb.checked) card.classList.add('selected');
                    else card.classList.remove('selected');
                    updateBatchBar();
                }

                function updateBatchBar() {
                    const sel = getSelectedFilenames();
                    const bar = document.getElementById('batchBar');
                    if (sel.length > 0) {
                        document.getElementById('batchCountText').innerText = sel.length + " selecionado(s)";
                        bar.classList.add('visible');
                    } else {
                        bar.classList.remove('visible');
                    }
                }

                function toggleSelectAll() {
                    const visibleCards = Array.from(document.querySelectorAll('.item-card')).filter(c => c.style.display !== 'none');
                    const allChecked = visibleCards.length > 0 && visibleCards.every(c => c.querySelector('.select-check').checked);
                    visibleCards.forEach(c => {
                        const cb = c.querySelector('.select-check');
                        cb.checked = !allChecked;
                        if (cb.checked) c.classList.add('selected');
                        else c.classList.remove('selected');
                    });
                    updateBatchBar();
                }

                function clearAllSelection() {
                    document.querySelectorAll('.item-card').forEach(c => {
                        c.querySelector('.select-check').checked = false;
                        c.classList.remove('selected');
                    });
                    updateBatchBar();
                }

                function downloadSelectedZip() {
                    const sel = getSelectedFilenames();
                    if (sel.length === 0) return;
                    window.location.href = "/zip?dir=" + currentDir + "&files=" + encodeURIComponent(sel.join("|"));
                }

                // Motor de Autorizacao em 2 Etapas (2FA) para TODAS as Operacoes
                function authorizeWith2FA(actionDescription, callbackWithOpToken) {
                    fetch('/action/request_2fa?desc=' + encodeURIComponent(actionDescription), {
                        method: 'POST',
                        headers: { 'X-CSRF-Token': csrfToken }
                    })
                    .then(r => r.json())
                    .then(data => {
                        if (data.status === 'already_authorized') {
                            if (data.remainingSeconds) {
                                elevatedRemainingSec = data.remainingSeconds;
                                updateElevatedBadgeUI();
                            }
                            callbackWithOpToken(data.opToken);
                            return;
                        }
                        openAction2faModal(actionDescription, data.reqId, callbackWithOpToken);
                    });
                }

                function openAction2faModal(actionDesc, reqId, onAuthorized) {
                    if (poll2faTimer) clearInterval(poll2faTimer);
                    document.getElementById('a2faTitle').innerText = actionDesc;
                    const codeInput = document.getElementById('a2faCodeInput');
                    codeInput.value = '';
                    document.getElementById('a2faErrorText').style.display = 'none';
                    document.getElementById('action2faModal').classList.add('open');
                    setTimeout(() => codeInput.focus(), 60);

                    const verifyNow = async (mode) => {
                        const keep5m = document.getElementById('a2faKeep5Min').checked ? '1' : '0';
                        const code = codeInput.value.trim();
                        const resp = await fetch('/action/verify_2fa?reqId=' + encodeURIComponent(reqId) + '&code=' + encodeURIComponent(code) + '&keep5m=' + keep5m + '&mode=' + mode, {
                            method: 'POST',
                            headers: { 'X-CSRF-Token': csrfToken }
                        });
                        const res = await resp.json();
                        if (res.status === 'approved') {
                            if (poll2faTimer) clearInterval(poll2faTimer);
                            document.getElementById('action2faModal').classList.remove('open');
                            if (res.remainingSeconds) {
                                elevatedRemainingSec = res.remainingSeconds;
                                updateElevatedBadgeUI();
                            }
                            onAuthorized(res.opToken);
                        } else if (res.status === 'invalid_code') {
                            document.getElementById('a2faErrorText').style.display = 'block';
                        } else if (res.status === 'expired') {
                            if (poll2faTimer) clearInterval(poll2faTimer);
                            alert('O código 2FA expirou. Tente novamente.');
                            document.getElementById('action2faModal').classList.remove('open');
                        }
                    };

                    document.getElementById('a2faSubmitBtn').onclick = () => verifyNow('manual');
                    poll2faTimer = setInterval(() => verifyNow('poll'), 1200);
                }

                function cancelAction2fa() {
                    if (poll2faTimer) clearInterval(poll2faTimer);
                    document.getElementById('action2faModal').classList.remove('open');
                }

                async function executeProtectedAction(actionDesc, queryParams, bodyPayload = "") {
                    authorizeWith2FA(actionDesc, async (opToken) => {
                        const resp = await fetch("/action?" + queryParams + "&opToken=" + encodeURIComponent(opToken), {
                            method: "POST",
                            headers: {
                                "X-CSRF-Token": csrfToken,
                                "X-Op-Token": opToken,
                                "Content-Type": "text/plain; charset=utf-8"
                            },
                            body: bodyPayload
                        });
                        if (resp.ok) {
                            window.location.reload();
                        } else {
                            alert("Não foi possível concluir a operação.");
                            window.location.reload();
                        }
                    });
                }

                // Modais de Criar Pasta, Renomear e Excluir (Todos passam pelo 2FA)
                function openInputModal(title, desc, initialValue, onConfirm) {
                    document.getElementById('modalTitle').innerText = title;
                    document.getElementById('modalDesc').innerText = desc;
                    const input = document.getElementById('modalInput');
                    input.style.display = 'block';
                    input.value = initialValue || '';

                    const btn = document.getElementById('modalConfirmBtn');
                    btn.className = 'btn';
                    btn.innerText = 'Continuar (2FA)';
                    btn.onclick = () => {
                        const val = input.value.trim();
                        closeCustomModal();
                        onConfirm(val);
                    };

                    document.getElementById('customModal').classList.add('open');
                    setTimeout(() => input.focus(), 60);
                }

                function openConfirmModal(title, desc, onConfirm) {
                    document.getElementById('modalTitle').innerText = title;
                    document.getElementById('modalDesc').innerText = desc;
                    document.getElementById('modalInput').style.display = 'none';

                    const btn = document.getElementById('modalConfirmBtn');
                    btn.className = 'btn-danger';
                    btn.innerText = 'Autorizar com 2FA';
                    btn.onclick = () => {
                        closeCustomModal();
                        onConfirm();
                    };

                    document.getElementById('customModal').classList.add('open');
                }

                function closeCustomModal() {
                    document.getElementById('customModal').classList.remove('open');
                }

                function openMkdirModal() {
                    openInputModal("Criar Nova Pasta", "Digite o nome da nova pasta:", "", (val) => {
                        if (val) {
                            executeProtectedAction(
                                "Criar Pasta: " + val,
                                "cmd=mkdir&dir=" + currentDir + "&name=" + encodeURIComponent(val)
                            );
                        }
                    });
                }

                function openRenameModal(targetPath, oldName) {
                    openInputModal("Renomear Item", "Novo nome para '" + oldName + "':", oldName, (val) => {
                        if (val && val !== oldName) {
                            executeProtectedAction(
                                "Renomear '" + oldName + "' para '" + val + "'",
                                "cmd=rename&dir=" + currentDir + "&target=" + encodeURIComponent(targetPath) + "&name=" + encodeURIComponent(val)
                            );
                        }
                    });
                }

                function openDeleteModal(targetPath, name) {
                    openConfirmModal("Excluir '" + name + "'?", "Esta exclusão exigirá autorização de 2ª etapa no celular.", () => {
                        executeProtectedAction(
                            "Excluir: " + name,
                            "cmd=delete&dir=" + currentDir + "&target=" + encodeURIComponent(targetPath)
                        );
                    });
                }

                function deleteSelectedBatch() {
                    const sel = getSelectedFilenames();
                    if (sel.length === 0) return;
                    openConfirmModal(
                        "Excluir " + sel.length + " item(ns)?",
                        "A exclusão em lote exigirá autorização de 2ª etapa no celular.",
                        () => {
                            executeProtectedAction(
                                "Excluir em Lote (" + sel.length + " itens)",
                                "cmd=batch_delete&dir=" + currentDir,
                                sel.join("|")
                            );
                        }
                    );
                }

                // Seletor Livre de Pastas para Movimentacao (Individual ou Lote)
                let currentMoveBrowsePath = rawCurrentDirPath;
                function loadMovePickerDirectory(dirPath) {
                    fetch('/dirs?dir=' + encodeURIComponent(dirPath))
                    .then(r => r.json())
                    .then(data => {
                        currentMoveBrowsePath = data.currentPath;
                        document.getElementById('moveCurrentPathLabel').innerText = "📂 " + data.shortPath;
                        const box = document.getElementById('moveDirsContainer');
                        box.innerHTML = '';

                        if (data.parentPath && data.parentPath !== "") {
                            const upDiv = document.createElement('div');
                            upDiv.className = 'dir-item';
                            upDiv.innerHTML = "⬅️ <strong>.. (Subir para pasta acima)</strong>";
                            upDiv.onclick = () => loadMovePickerDirectory(data.parentPath);
                            box.appendChild(upDiv);
                        }

                        if (data.folders.length === 0) {
                            const emptyMsg = document.createElement('div');
                            emptyMsg.style.padding = '10px';
                            emptyMsg.style.fontSize = '12px';
                            emptyMsg.style.color = '#94a3b8';
                            emptyMsg.innerText = 'Nenhuma subpasta aqui. Você pode mover para cá.';
                            box.appendChild(emptyMsg);
                        } else {
                            data.folders.forEach(f => {
                                const row = document.createElement('div');
                                row.className = 'dir-item';
                                row.innerHTML = "📁 " + f.name;
                                row.onclick = () => loadMovePickerDirectory(f.path);
                                box.appendChild(row);
                            });
                        }
                    });
                }

                function openMoveModal(targetPath, itemName, isBatch) {
                    document.getElementById('moveModalTargetDesc').innerText = isBatch
                        ? "Movendo " + itemName
                        : "Movendo: " + itemName;
                    loadMovePickerDirectory(rawCurrentDirPath);
                    document.getElementById('moveModal').classList.add('open');

                    document.getElementById('confirmMoveHereBtn').onclick = () => {
                        const dest = currentMoveBrowsePath;
                        closeMoveModal();
                        if (isBatch) {
                            const sel = getSelectedFilenames();
                            executeProtectedAction(
                                "Mover " + sel.length + " item(ns)",
                                "cmd=batch_move&dir=" + currentDir + "&dest=" + encodeURIComponent(dest),
                                sel.join("|")
                            );
                        } else {
                            executeProtectedAction(
                                "Mover: " + itemName,
                                "cmd=move&dir=" + currentDir + "&target=" + encodeURIComponent(targetPath) + "&dest=" + encodeURIComponent(dest)
                            );
                        }
                    };
                }

                function openBatchMoveModal() {
                    const sel = getSelectedFilenames();
                    if (sel.length === 0) return;
                    openMoveModal("", sel.length + " item(ns) selecionados", true);
                }

                function closeMoveModal() {
                    document.getElementById('moveModal').classList.remove('open');
                }

                // Upload de Arquivos Protegido por 2FA com Token de Lote Ininterrupto
                function uploadFilesWith2FA(files) {
                    if (!files || files.length === 0) return;
                    const fileArray = Array.from(files);
                    authorizeWith2FA("Enviar " + fileArray.length + " arquivo(s) do PC", async (opToken) => {
                        const status = document.getElementById("uploadStatus");
                        for (let i = 0; i < fileArray.length; i++) {
                            const file = fileArray[i];
                            status.innerText = "⬆️ Enviando (" + (i + 1) + "/" + fileArray.length + "): " + file.name + "...";
                            await fetch("/upload?dir=" + currentDir + "&name=" + encodeURIComponent(file.name) + "&opToken=" + encodeURIComponent(opToken), {
                                method: "POST",
                                headers: {
                                    "X-CSRF-Token": csrfToken,
                                    "X-Op-Token": opToken
                                },
                                body: file
                            });
                        }
                        status.innerText = "✅ Upload concluído!";
                        window.location.reload();
                    });
                }

                // Lightbox em Tela Cheia
                function openLightbox(index) {
                    if (mediaList.length === 0) return;
                    currentLightboxIndex = index;
                    document.getElementById('lightboxModal').classList.add('open');
                    renderLightboxItem();
                }

                function closeLightbox() {
                    document.getElementById('lbMediaHolder').innerHTML = '';
                    document.getElementById('lightboxModal').classList.remove('open');
                }

                function stepLightbox(delta) {
                    if (mediaList.length === 0) return;
                    currentLightboxIndex = (currentLightboxIndex + delta + mediaList.length) % mediaList.length;
                    renderLightboxItem();
                }

                function toggleLightboxZoom() {
                    const img = document.querySelector('#lbMediaHolder img');
                    if (img) img.classList.toggle('zoomed');
                }

                function renderLightboxItem() {
                    const item = mediaList[currentLightboxIndex];
                    document.getElementById('lbCounter').innerText = (currentLightboxIndex + 1) + " / " + mediaList.length;
                    document.getElementById('lbTitle').innerText = item.name + " (" + item.size + " • " + item.date + ")";
                    document.getElementById('lbDownloadBtn').href = item.download;

                    const holder = document.getElementById('lbMediaHolder');
                    if (item.type === 'video') {
                        holder.innerHTML = '<video src="' + item.url + '" controls autoplay></video>';
                    } else {
                        holder.innerHTML = '<img src="' + item.url + '" alt="' + item.name + '" onclick="this.classList.toggle(\'zoomed\')">';
                    }
                }

                function moveCurrentLightboxItem() {
                    const item = mediaList[currentLightboxIndex];
                    if (item) {
                        closeLightbox();
                        openMoveModal(item.path, item.name, false);
                    }
                }

                function renameCurrentLightboxItem() {
                    const item = mediaList[currentLightboxIndex];
                    if (item) openRenameModal(item.path, item.name);
                }

                function deleteCurrentLightboxItem() {
                    const item = mediaList[currentLightboxIndex];
                    if (item) openDeleteModal(item.path, item.name);
                }

                document.addEventListener('keydown', function(e) {
                    const isOpen = document.getElementById('lightboxModal').classList.contains('open');
                    if (!isOpen) return;
                    if (e.key === 'ArrowRight') stepLightbox(1);
                    else if (e.key === 'ArrowLeft') stepLightbox(-1);
                    else if (e.key === 'Escape') closeLightbox();
                });

                // Drag & Drop Protegido por 2FA
                window.addEventListener('dragover', function(e) {
                    e.preventDefault();
                    document.getElementById('dropOverlay').classList.add('active');
                });
                document.getElementById('dropOverlay').addEventListener('dragleave', function(e) {
                    e.preventDefault();
                    document.getElementById('dropOverlay').classList.remove('active');
                });
                window.addEventListener('drop', function(e) {
                    e.preventDefault();
                    document.getElementById('dropOverlay').classList.remove('active');
                    if (e.dataTransfer && e.dataTransfer.files && e.dataTransfer.files.length > 0) {
                        uploadFilesWith2FA(e.dataTransfer.files);
                    }
                });
            </script>
            </body>
            </html>
            """.trimIndent()
        )

        return newFixedLengthResponse(Response.Status.OK, MIME_HTML, sb.toString())
    }

    private fun parseQueryParams(query: String?): Map<String, String> {
        if (query.isNullOrBlank()) return emptyMap()
        val map = mutableMapOf<String, String>()
        for (pair in query.split("&")) {
            val idx = pair.indexOf("=")
            if (idx > 0) {
                val k = URLDecoder.decode(pair.substring(0, idx), "UTF-8")
                val v = URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
                map[k] = v
            }
        }
        return map
    }

    private fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun escapeHtml(text: String): String {
        return text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
    }

    private fun escapeJs(text: String): String {
        return text.replace("\\", "\\\\").replace("'", "\\'").replace("\"", "\\\"")
    }

    private fun isImageFile(name: String): Boolean {
        val low = name.lowercase()
        return low.endsWith(".jpg") || low.endsWith(".jpeg") || low.endsWith(".png") || low.endsWith(".webp")
    }

    private fun isVideoFile(name: String): Boolean {
        val low = name.lowercase()
        return low.endsWith(".mp4") || low.endsWith(".mov") || low.endsWith(".mkv") || low.endsWith(".3gp")
    }

    private fun resolveMimeType(name: String): String {
        val low = name.lowercase()
        return when {
            low.endsWith(".jpg") || low.endsWith(".jpeg") -> "image/jpeg"
            low.endsWith(".png") -> "image/png"
            low.endsWith(".webp") -> "image/webp"
            low.endsWith(".mp4") -> "video/mp4"
            low.endsWith(".pdf") -> "application/pdf"
            else -> "application/octet-stream"
        }
    }

    private fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        return String.format(Locale.US, "%.2f MB", mb)
    }
}
