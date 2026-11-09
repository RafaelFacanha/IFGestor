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
    var lastActivityMs: Long
)

private data class Pending2FA(
    val tempToken: String,
    val code: String,
    val clientIp: String,
    val expiresAtMs: Long
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
    private val pending2faMap = ConcurrentHashMap<String, Pending2FA>()
    private val bruteForceMap = ConcurrentHashMap<String, BruteForceRecord>()

    companion object {
        private const val SESSION_TIMEOUT_MS = 30 * 60 * 1000L // 30 minutos
        private const val TWO_FA_EXPIRY_MS = 2 * 60 * 1000L    // 2 minutos
        private const val MAX_ATTEMPTS = 5
        private const val LOCKOUT_DURATION_MS = 5 * 60 * 1000L // 5 minutos
    }

    fun clearAllSessions() {
        activeSessions.clear()
        pending2faMap.clear()
        SettingsManager.publishTwoFactorCode(null, null)
        SettingsManager.updateActiveSessionsCount(0)
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri ?: "/"
        val params = parseQueryParams(session.queryParameterString)
        val clientIp = session.headers["remote-addr"]
            ?: session.headers["http-client-ip"]
            ?: "PC-Rede-Local"

        // Verifica bloqueio de Forca Bruta por IP
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

        // Rotas de Login / Logout
        if (uri == "/auth/pin" && session.method == Method.POST) {
            return secureResponse(handlePinSubmission(params, clientIp))
        }
        if (uri == "/auth/2fa" && session.method == Method.POST) {
            return secureResponse(handle2faSubmission(params, clientIp))
        }
        if (uri == "/auth/logout") {
            return secureResponse(handleLogout(session))
        }

        // Verifica se a Autenticacao por PIN + 2FA esta ativa
        val authSession = getValidSession(session, clientIp)
        if (SettingsManager.isWebAuthEnabled(context) && authSession == null) {
            val step = params["step"]
            val tempToken = params["temp"] ?: ""
            val errorMsg = params["err"]
            val pageHtml = if (step == "2fa" && pending2faMap.containsKey(tempToken)) {
                render2faPage(tempToken, errorMsg)
            } else {
                renderPinLoginPage(errorMsg)
            }
            return secureResponse(newFixedLengthResponse(Response.Status.OK, MIME_HTML, pageHtml))
        }

        val csrfToken = authSession?.csrfToken ?: "open-mode"

        return try {
            val response = when {
                uri == "/upload" && session.method == Method.POST -> {
                    if (!verifyCsrf(session, params, authSession)) {
                        newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "Token CSRF invalido")
                    } else {
                        handleFileUpload(session, params)
                    }
                }
                uri == "/action" && session.method == Method.POST -> {
                    if (!verifyCsrf(session, params, authSession)) {
                        newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "Token CSRF invalido")
                    } else {
                        handleFileAction(params)
                    }
                }
                uri == "/file" -> handleFileStream(session, params)
                uri == "/zip" -> handleFolderZipDownload(params)
                else -> renderExplorerPage(params["dir"], csrfToken)
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

        // Se a Verificacao em Duas Etapas (2FA) estiver ativa, gera codigo de 6 digitos no celular
        if (SettingsManager.isTwoFactorEnabled(context)) {
            val code6Digits = String.format(Locale.US, "%06d", secureRandom.nextInt(1_000_000))
            val tempToken = generateRandomHex(24)
            pending2faMap[tempToken] = Pending2FA(
                tempToken = tempToken,
                code = code6Digits,
                clientIp = clientIp,
                expiresAtMs = System.currentTimeMillis() + TWO_FA_EXPIRY_MS
            )
            SettingsManager.publishTwoFactorCode(code6Digits, clientIp)

            val res = newFixedLengthResponse(Response.Status.REDIRECT, MIME_HTML, "")
            res.addHeader("Location", "/?step=2fa&temp=$tempToken")
            return res
        }

        // Caso 2FA esteja desativado, cria a sessao diretamente
        return createAuthenticatedSessionResponse(clientIp)
    }

    private fun handle2faSubmission(params: Map<String, String>, clientIp: String): Response {
        val tempToken = params["temp"] ?: ""
        val inputCode = (params["code"] ?: "").trim()
        val pending = pending2faMap[tempToken]

        if (pending == null || System.currentTimeMillis() > pending.expiresAtMs) {
            pending2faMap.remove(tempToken)
            SettingsManager.publishTwoFactorCode(null, null)
            val res = newFixedLengthResponse(Response.Status.REDIRECT, MIME_HTML, "")
            res.addHeader("Location", "/?err=expired")
            return res
        }

        if (pending.code != inputCode) {
            registerAuthFailure(clientIp)
            val res = newFixedLengthResponse(Response.Status.REDIRECT, MIME_HTML, "")
            res.addHeader("Location", "/?step=2fa&temp=$tempToken&err=2fa")
            return res
        }

        // Codigo 2FA validado com sucesso!
        pending2faMap.remove(tempToken)
        SettingsManager.publishTwoFactorCode(null, null)
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

    private fun handleFileAction(params: Map<String, String>): Response {
        val cmd = params["cmd"] ?: ""
        val currentDir = sanitizeFile(params["dir"])
        val targetPath = params["target"]

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
            "set_camera" -> {
                if (currentDir.exists() && currentDir.isDirectory) {
                    SettingsManager.setTargetFolderPath(context, currentDir.absolutePath)
                }
            }
            "set_prefix" -> {
                val newPrefix = params["name"] ?: ""
                SettingsManager.setPrefix(context, newPrefix)
            }
        }

        return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "OK")
    }

    private fun handleFileUpload(session: IHTTPSession, params: Map<String, String>): Response {
        val targetDir = sanitizeFile(params["dir"])
        if (!targetDir.exists()) targetDir.mkdirs()

        val rawName = (params["name"] ?: "arquivo_${System.currentTimeMillis()}").trim()
        val safeName = rawName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val destFile = File(targetDir, safeName)

        val contentLength = session.headers["content-length"]?.toLongOrNull() ?: 0L
        if (contentLength <= 0L) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Arquivo vazio")
        }

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

        val mime = getMimeTypeForFile(file.name)
        val fileLen = file.length()
        val rangeHeader = session.headers["range"]

        // Suporte a Streaming Parcial (HTTP 206) para avancar/voltar videos em Tela Cheia
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

        val tempZip = File(context.cacheDir, "${dir.name.ifEmpty { "Memoria" }}.zip")
        if (tempZip.exists()) tempZip.delete()

        ZipOutputStream(BufferedOutputStream(FileOutputStream(tempZip))).use { zos ->
            val files = dir.listFiles()?.filter { it.isFile && !it.name.startsWith(".") } ?: emptyList()
            val buffer = ByteArray(16 * 1024)
            for (f in files) {
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
            "attachment; filename=\"${dir.name.ifEmpty { "Arquivos" }}.zip\""
        )
        return res
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
                <title>IFGestor - Acesso Seguro</title>
                <style>
                    * { box-sizing: border-box; font-family: system-ui, sans-serif; }
                    body { margin: 0; background: #0f172a; color: #f8fafc; display: flex; height: 100vh; align-items: center; justify-content: center; }
                    .box { background: #1e293b; border: 1px solid #334155; padding: 32px; border-radius: 16px; width: 100%; max-width: 380px; text-align: center; box-shadow: 0 20px 25px -5px rgba(0,0,0,0.5); }
                    h2 { margin: 0 0 8px; color: #38bdf8; }
                    p { font-size: 13px; color: #94a3b8; margin-bottom: 20px; }
                    input { width: 100%; padding: 12px; border-radius: 8px; border: 1px solid #475569; background: #0f172a; color: #fff; font-size: 20px; text-align: center; letter-spacing: 6px; margin-bottom: 16px; }
                    button { width: 100%; padding: 12px; background: #3b82f6; color: #fff; border: none; border-radius: 8px; font-size: 15px; font-weight: 700; cursor: pointer; }
                    button:hover { background: #2563eb; }
                    .err { background: #7f1d1d; color: #fecaca; padding: 10px; border-radius: 8px; font-size: 13px; margin-bottom: 16px; }
                </style>
            </head>
            <body>
                <div class="box">
                    <h2>🔒 IFGestor Seguro</h2>
                    <p>Digite o PIN configurado na aba "Acesso no PC" do celular (Padrão inicial: 1234)</p>
                    $errBanner
                    <form onsubmit="submitPin(event)">
                        <input type="password" id="pinInput" placeholder="••••" required autofocus>
                        <button type="submit">Continuar</button>
                    </form>
                </div>
                <script>
                    async function submitPin(e) {
                        e.preventDefault();
                        const pin = document.getElementById('pinInput').value;
                        window.location.href = '/auth/pin_redirect?pin=' + encodeURIComponent(pin);
                    }
                    document.querySelector('form').onsubmit = async function(e) {
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

    private fun render2faPage(tempToken: String, errorType: String?): String {
        val errBanner = if (errorType == "2fa") {
            """<div class="err">❌ Código de verificação inválido.</div>"""
        } else ""

        return """
            <!DOCTYPE html>
            <html lang="pt-BR">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>IFGestor - Verificação em 2 Etapas</title>
                <style>
                    * { box-sizing: border-box; font-family: system-ui, sans-serif; }
                    body { margin: 0; background: #0f172a; color: #f8fafc; display: flex; height: 100vh; align-items: center; justify-content: center; }
                    .box { background: #1e293b; border: 1px solid #334155; padding: 32px; border-radius: 16px; width: 100%; max-width: 400px; text-align: center; }
                    h2 { margin: 0 0 8px; color: #10b981; }
                    p { font-size: 13px; color: #cbd5e1; margin-bottom: 20px; line-height: 1.5; }
                    input { width: 100%; padding: 12px; border-radius: 8px; border: 1px solid #475569; background: #0f172a; color: #38bdf8; font-size: 24px; font-weight: bold; text-align: center; letter-spacing: 8px; margin-bottom: 16px; }
                    button { width: 100%; padding: 12px; background: #10b981; color: #fff; border: none; border-radius: 8px; font-size: 15px; font-weight: 700; cursor: pointer; }
                    button:hover { background: #059669; }
                    .err { background: #7f1d1d; color: #fecaca; padding: 10px; border-radius: 8px; font-size: 13px; margin-bottom: 16px; }
                </style>
            </head>
            <body>
                <div class="box">
                    <h2>📲 Verificação em 2 Etapas</h2>
                    <p>Um código de <strong>6 dígitos</strong> acabou de aparecer na notificação e na tela do seu celular. Digite-o abaixo para liberar o acesso:</p>
                    $errBanner
                    <form id="twoFaForm">
                        <input type="text" id="codeInput" maxlength="6" placeholder="000000" required autofocus>
                        <button type="submit">Verificar e Entrar</button>
                    </form>
                </div>
                <script>
                    document.getElementById('twoFaForm').onsubmit = async function(e) {
                        e.preventDefault();
                        const code = document.getElementById('codeInput').value;
                        const resp = await fetch('/auth/2fa?temp=${escapeJs(tempToken)}&code=' + encodeURIComponent(code), { method: 'POST' });
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

    private fun renderLockoutPage(remainingSeconds: Long): String {
        return """
            <!DOCTYPE html>
            <html lang="pt-BR">
            <head><meta charset="UTF-8"><title>Acesso Bloqueado</title></head>
            <body style="background:#0f172a;color:#f8fafc;font-family:sans-serif;display:flex;height:100vh;align-items:center;justify-content:center;text-align:center;">
                <div style="background:#7f1d1d;padding:30px;border-radius:14px;max-width:420px;">
                    <h2>🛡️ Proteção Anti-Força Bruta Ativada</h2>
                    <p>Muitas tentativas incorretas de login a partir deste computador.</p>
                    <p>Aguarde <strong>$remainingSeconds segundos</strong> antes de tentar novamente.</p>
                </div>
            </body>
            </html>
        """.trimIndent()
    }

    private fun renderExplorerPage(requestedDir: String?, csrfToken: String): Response {
        val currentDir = sanitizeFile(requestedDir)
        if (!currentDir.exists()) currentDir.mkdirs()

        val activeCameraDir = SettingsManager.getTargetFolderPath(context)
        val activePrefix = SettingsManager.getPrefix(context)
        val isCurrentActiveCamera = currentDir.absolutePath == activeCameraDir

        val children = currentDir.listFiles()
            ?.filter { !it.name.startsWith(".") }
            ?.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
            ?: emptyList()

        val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
        val encodedCurrentDir = urlEncode(currentDir.absolutePath)
        val parentDir = if (currentDir.absolutePath != rootStoragePath) currentDir.parentFile else null

        // Prepara a lista de arquivos de midia (fotos e videos) para o Modo Tela Cheia (Anterior / Proximo)
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
                    """{"name":"${escapeJs(item.name)}","path":"${escapeJs(item.absolutePath)}","url":"$fileUrl","download":"$downloadUrl","type":"$type","size":"${formatSize(item.length())}"}"""
                )
                filePathToMediaIndex[item.absolutePath] = mediaIndex
                mediaIndex++
            }
        }
        mediaItemsJsArray.append("]")

        val sb = StringBuilder()
        sb.append(
            """
            <!DOCTYPE html>
            <html lang="pt-BR">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>IFGestor - Gerenciador Web</title>
                <style>
                    :root {
                        --card-min: 230px;
                        --thumb-h: 160px;
                    }
                    * { box-sizing: border-box; font-family: system-ui, -apple-system, sans-serif; }
                    body { margin: 0; background: #0f172a; color: #f8fafc; padding: 18px; }
                    .container { max-width: 1280px; margin: 0 auto; }
                    .header { background: #1e293b; padding: 16px 20px; border-radius: 14px; display: flex; flex-wrap: wrap; justify-content: space-between; align-items: center; gap: 12px; margin-bottom: 14px; border: 1px solid #334155; }
                    .title h1 { margin: 0; font-size: 20px; color: #38bdf8; }
                    .title p { margin: 4px 0 0; font-size: 13px; color: #94a3b8; }
                    .badge { background: #0284c7; color: #fff; padding: 5px 10px; border-radius: 8px; font-size: 12px; font-weight: 600; }
                    .toolbar { background: #1e293b; padding: 14px 18px; border-radius: 12px; margin-bottom: 16px; display: flex; flex-wrap: wrap; gap: 8px; align-items: center; border: 1px solid #334155; }
                    .path-box { flex: 1 1 100%; font-size: 14px; color: #cbd5e1; margin-bottom: 6px; word-break: break-all; display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 8px; }
                    .size-group { display: inline-flex; background: #0f172a; padding: 4px; border-radius: 8px; border: 1px solid #334155; gap: 4px; }
                    .size-btn { background: transparent; color: #94a3b8; border: none; padding: 5px 10px; border-radius: 6px; font-size: 12px; cursor: pointer; font-weight: 600; }
                    .size-btn.active { background: #3b82f6; color: #fff; }
                    button, .btn { background: #3b82f6; color: #fff; border: none; padding: 8px 13px; border-radius: 8px; font-size: 13px; font-weight: 600; cursor: pointer; text-decoration: none; display: inline-flex; align-items: center; gap: 6px; }
                    button:hover, .btn:hover { background: #2563eb; }
                    .btn-sec { background: #334155; }
                    .btn-sec:hover { background: #475569; }
                    .btn-ok { background: #10b981; }
                    .btn-ok:hover { background: #059669; }
                    .btn-danger { background: #ef4444; }
                    .btn-danger:hover { background: #dc2626; }
                    .grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(var(--card-min), 1fr)); gap: 14px; }
                    .card { background: #1e293b; border: 1px solid #334155; border-radius: 12px; padding: 10px; display: flex; flex-direction: column; justify-content: space-between; transition: transform 0.12s; }
                    .card:hover { border-color: #38bdf8; }
                    .thumb-box { width: 100%; height: var(--thumb-h); background: #0f172a; border-radius: 8px; display: flex; align-items: center; justify-content: center; overflow: hidden; margin-bottom: 8px; cursor: pointer; position: relative; }
                    .thumb-box img { width: 100%; height: 100%; object-fit: cover; }
                    .play-badge { position: absolute; background: rgba(0,0,0,0.65); color: #fff; padding: 8px 14px; border-radius: 24px; font-size: 13px; font-weight: bold; }
                    .icon-big { font-size: 44px; }
                    .item-name { font-size: 13px; font-weight: 600; color: #f1f5f9; word-break: break-word; margin-bottom: 4px; cursor: pointer; }
                    .item-meta { font-size: 11px; color: #94a3b8; margin-bottom: 8px; }
                    .card-actions { display: flex; gap: 5px; flex-wrap: wrap; }
                    .card-actions .btn, .card-actions button { padding: 6px 8px; font-size: 11px; flex: 1; justify-content: center; }
                    #uploadStatus { font-size: 13px; color: #38bdf8; font-weight: 600; }

                    /* Modal de Tela Cheia (Lightbox) */
                    .lightbox { display: none; position: fixed; inset: 0; background: rgba(5, 10, 20, 0.96); z-index: 9999; flex-direction: column; justify-content: space-between; }
                    .lightbox.open { display: flex; }
                    .lb-top { padding: 14px 22px; background: rgba(30, 41, 59, 0.85); display: flex; justify-content: space-between; align-items: center; gap: 12px; flex-wrap: wrap; border-bottom: 1px solid #334155; }
                    .lb-title { font-size: 15px; font-weight: 600; color: #f8fafc; word-break: break-all; }
                    .lb-counter { color: #38bdf8; font-weight: 700; margin-right: 10px; }
                    .lb-actions { display: flex; gap: 8px; align-items: center; }
                    .lb-stage { flex: 1; display: flex; align-items: center; justify-content: center; position: relative; overflow: hidden; padding: 16px 70px; }
                    .lb-stage img, .lb-stage video { max-width: 100%; max-height: 82vh; object-fit: contain; border-radius: 6px; box-shadow: 0 12px 30px rgba(0,0,0,0.6); }
                    .lb-nav { position: absolute; top: 50%; transform: translateY(-50%); background: rgba(30, 41, 59, 0.85); color: #fff; border: 1px solid #475569; width: 52px; height: 52px; border-radius: 50%; font-size: 22px; cursor: pointer; display: flex; align-items: center; justify-content: center; user-select: none; }
                    .lb-nav:hover { background: #3b82f6; }
                    .lb-prev { left: 16px; }
                    .lb-next { right: 16px; }
                    .lb-footer { padding: 10px; text-align: center; font-size: 12px; color: #94a3b8; background: rgba(30, 41, 59, 0.6); }
                </style>
            </head>
            <body>
            <div class="container">
                <div class="header">
                    <div class="title">
                        <h1>📱 IFGestor — Gerenciador Web Seguro</h1>
                        <p>Pasta Ativa da Câmera: <strong>${SettingsManager.formatShortPath(activeCameraDir)}</strong> | Prefixo Atual: <strong>$activePrefix</strong></p>
                    </div>
                    <div style="display:flex; gap:8px; flex-wrap:wrap; align-items:center;">
                        <div class="size-group" title="Ajustar tamanho das miniaturas">
                            <button type="button" class="size-btn" id="btnSizeS" onclick="setThumbSize('small')">Pequenas</button>
                            <button type="button" class="size-btn active" id="btnSizeM" onclick="setThumbSize('medium')">Médias</button>
                            <button type="button" class="size-btn" id="btnSizeL" onclick="setThumbSize('large')">Grandes</button>
                        </div>
                        <button class="btn-sec" onclick="changePrefix('${escapeJs(activePrefix)}')">✏️ Prefixo</button>
                        <a class="btn btn-sec" href="/?dir=${urlEncode(rootStoragePath)}">🏠 Raiz</a>
                        ${if (SettingsManager.isWebAuthEnabled(context)) """<a class="btn btn-danger" href="/auth/logout">🔒 Sair</a>""" else ""}
                    </div>
                </div>

                <div class="toolbar">
                    <div class="path-box">
                        <div>
                            📂 <strong>Local Atual:</strong> ${SettingsManager.formatShortPath(currentDir.absolutePath)}
                            ${if (isCurrentActiveCamera) "<span class='badge' style='margin-left:8px;'>★ Destino Atual da Câmera</span>" else ""}
                        </div>
                        <span style="font-size:12px; color:#94a3b8;">Dica: Clique em qualquer foto ou vídeo para abrir em <strong>Tela Cheia</strong></span>
                    </div>
            """.trimIndent()
        )

        if (parentDir != null) {
            sb.append("""<a class="btn btn-sec" href="/?dir=${urlEncode(parentDir.absolutePath)}">⬅️ Pasta Acima</a>""")
        }

        sb.append(
            """
                    <button onclick="createNewFolder()">➕ Nova Pasta</button>
                    <button class="btn-ok" onclick="setCameraHere()">📷 Usar Esta Pasta na Câmera</button>
                    <a class="btn btn-sec" href="/zip?dir=$encodedCurrentDir">📦 Baixar Pasta (.ZIP)</a>
                    <label class="btn btn-sec" style="cursor:pointer;">
                        ⬆️ Enviar do PC para cá
                        <input type="file" id="fileInput" multiple style="display:none" onchange="uploadFiles(this.files)">
                    </label>
                    <a class="btn btn-sec" href="/?dir=$encodedCurrentDir">🔄 Atualizar</a>
                    <span id="uploadStatus"></span>
                </div>

                <div class="grid">
            """.trimIndent()
        )

        if (children.isEmpty()) {
            sb.append("""<p style="color:#94a3b8; grid-column: 1/-1;">Esta pasta está vazia.</p>""")
        }

        for (item in children) {
            val encodedPath = urlEncode(item.absolutePath)
            val dateStr = dateFormat.format(Date(item.lastModified()))

            if (item.isDirectory) {
                val countInside = item.listFiles()?.size ?: 0
                sb.append(
                    """
                    <div class="card">
                        <div>
                            <a href="/?dir=$encodedPath" style="text-decoration:none;">
                                <div class="thumb-box"><span class="icon-big">📁</span></div>
                                <div class="item-name">${escapeHtml(item.name)}</div>
                            </a>
                            <div class="item-meta">Pasta ($countInside itens) • $dateStr</div>
                        </div>
                        <div class="card-actions">
                            <a class="btn" href="/?dir=$encodedPath">Abrir</a>
                            <button class="btn-sec" onclick="renameItem('${escapeJs(item.absolutePath)}', '${escapeJs(item.name)}')">Renomear</button>
                            <button class="btn-danger" onclick="deleteItem('${escapeJs(item.absolutePath)}', '${escapeJs(item.name)}')">Excluir</button>
                        </div>
                    </div>
                    """.trimIndent()
                )
            } else {
                val isImg = isImageFile(item.name)
                val isVid = isVideoFile(item.name)
                val sizeStr = formatSize(item.length())
                val mIdx = filePathToMediaIndex[item.absolutePath]

                val clickAction = if (mIdx != null) {
                    "openLightbox($mIdx)"
                } else {
                    "window.open('/file?path=$encodedPath', '_blank')"
                }

                val previewHtml = when {
                    isImg -> """<img src="/file?path=$encodedPath" loading="lazy" alt="${escapeHtml(item.name)}">"""
                    isVid -> """<span class="icon-big">🎬</span><span class="play-badge">▶ TELA CHEIA</span>"""
                    else -> """<span class="icon-big">📄</span>"""
                }

                sb.append(
                    """
                    <div class="card">
                        <div>
                            <div class="thumb-box" onclick="$clickAction">$previewHtml</div>
                            <div class="item-name" onclick="$clickAction">${escapeHtml(item.name)}</div>
                            <div class="item-meta">$sizeStr • $dateStr</div>
                        </div>
                        <div class="card-actions">
                            ${if (mIdx != null) """<button onclick="openLightbox($mIdx)">🔍 Tela Cheia</button>""" else ""}
                            <a class="btn btn-ok" href="/file?path=$encodedPath&download=1">⬇️ Baixar</a>
                            <button class="btn-sec" onclick="renameItem('${escapeJs(item.absolutePath)}', '${escapeJs(item.name)}')">✏️</button>
                            <button class="btn-danger" onclick="deleteItem('${escapeJs(item.absolutePath)}', '${escapeJs(item.name)}')">🗑️</button>
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

            <!-- Modal Visualizador em Tela Cheia (Lightbox) -->
            <div id="lightboxModal" class="lightbox">
                <div class="lb-top">
                    <div>
                        <span id="lbCounter" class="lb-counter">1 / 1</span>
                        <span id="lbTitle" class="lb-title">Arquivo</span>
                    </div>
                    <div class="lb-actions">
                        <a id="lbDownloadBtn" class="btn btn-ok" href="#">⬇️ Baixar</a>
                        <button class="btn-sec" onclick="renameCurrentLightboxItem()">✏️ Renomear</button>
                        <button class="btn-danger" onclick="deleteCurrentLightboxItem()">🗑️ Excluir</button>
                        <button class="btn-sec" onclick="closeLightbox()">✖ Fechar (Esc)</button>
                    </div>
                </div>
                <div class="lb-stage" onclick="if(event.target === this) closeLightbox()">
                    <button class="lb-nav lb-prev" onclick="stepLightbox(-1)" title="Anterior (Seta Esquerda)">◀</button>
                    <div id="lbMediaHolder" style="display:flex; align-items:center; justify-content:center; width:100%; height:100%;"></div>
                    <button class="lb-nav lb-next" onclick="stepLightbox(1)" title="Próximo (Seta Direita)">▶</button>
                </div>
                <div class="lb-footer">
                    Use os botões <strong>◀ Anterior</strong> / <strong>Próximo ▶</strong> ou as teclas <strong>⬅️ ➡️</strong> do teclado para navegar pelos arquivos • <strong>ESC</strong> para sair
                </div>
            </div>

            <script>
                const currentDir = "$encodedCurrentDir";
                const csrfToken = "$csrfToken";
                const mediaList = $mediaItemsJsArray;
                let currentLightboxIndex = 0;

                // Ajuste de Tamanho das Miniaturas (Pequenas, Medias, Grandes)
                function setThumbSize(size) {
                    const root = document.documentElement;
                    document.getElementById('btnSizeS').classList.remove('active');
                    document.getElementById('btnSizeM').classList.remove('active');
                    document.getElementById('btnSizeL').classList.remove('active');

                    if (size === 'small') {
                        root.style.setProperty('--card-min', '155px');
                        root.style.setProperty('--thumb-h', '105px');
                        document.getElementById('btnSizeS').classList.add('active');
                    } else if (size === 'large') {
                        root.style.setProperty('--card-min', '340px');
                        root.style.setProperty('--thumb-h', '250px');
                        document.getElementById('btnSizeL').classList.add('active');
                    } else {
                        root.style.setProperty('--card-min', '230px');
                        root.style.setProperty('--thumb-h', '160px');
                        document.getElementById('btnSizeM').classList.add('active');
                    }
                    localStorage.setItem('ifg_thumb_size', size);
                }

                // Restaura o tamanho preferido salvo no navegador
                const savedSize = localStorage.getItem('ifg_thumb_size') || 'medium';
                setThumbSize(savedSize);

                // Funcoes do Modo Tela Cheia (Lightbox)
                function openLightbox(index) {
                    if (mediaList.length === 0) return;
                    currentLightboxIndex = index;
                    document.getElementById('lightboxModal').classList.add('open');
                    renderLightboxItem();
                }

                function closeLightbox() {
                    const holder = document.getElementById('lbMediaHolder');
                    holder.innerHTML = ''; // Pausa videos ativos
                    document.getElementById('lightboxModal').classList.remove('open');
                }

                function stepLightbox(delta) {
                    if (mediaList.length === 0) return;
                    currentLightboxIndex = (currentLightboxIndex + delta + mediaList.length) % mediaList.length;
                    renderLightboxItem();
                }

                function renderLightboxItem() {
                    const item = mediaList[currentLightboxIndex];
                    document.getElementById('lbCounter').innerText = (currentLightboxIndex + 1) + " / " + mediaList.length;
                    document.getElementById('lbTitle').innerText = item.name + " (" + item.size + ")";
                    document.getElementById('lbDownloadBtn').href = item.download;

                    const holder = document.getElementById('lbMediaHolder');
                    if (item.type === 'video') {
                        holder.innerHTML = '<video src="' + item.url + '" controls autoplay></video>';
                    } else {
                        holder.innerHTML = '<img src="' + item.url + '" alt="' + item.name + '">';
                    }
                }

                function renameCurrentLightboxItem() {
                    const item = mediaList[currentLightboxIndex];
                    if (item) renameItem(item.path, item.name);
                }

                function deleteCurrentLightboxItem() {
                    const item = mediaList[currentLightboxIndex];
                    if (item) deleteItem(item.path, item.name);
                }

                document.addEventListener('keydown', function(e) {
                    const isOpen = document.getElementById('lightboxModal').classList.contains('open');
                    if (!isOpen) return;
                    if (e.key === 'ArrowRight') stepLightbox(1);
                    else if (e.key === 'ArrowLeft') stepLightbox(-1);
                    else if (e.key === 'Escape') closeLightbox();
                });

                // Acoes Protegidas via POST + CSRF Token
                async function postAction(queryParams) {
                    const resp = await fetch("/action?" + queryParams, {
                        method: "POST",
                        headers: { "X-CSRF-Token": csrfToken }
                    });
                    if (resp.ok) {
                        window.location.reload();
                    } else {
                        alert("Sessão expirada ou sem permissão. Recarregue a página.");
                        window.location.reload();
                    }
                }

                function createNewFolder() {
                    const name = prompt("Digite o nome da nova pasta:");
                    if (name && name.trim() !== "") {
                        postAction("cmd=mkdir&dir=" + currentDir + "&name=" + encodeURIComponent(name.trim()));
                    }
                }

                function setCameraHere() {
                    postAction("cmd=set_camera&dir=" + currentDir);
                }

                function changePrefix(currentPrefix) {
                    const name = prompt("Novo prefixo para fotos e vídeos da câmera:", currentPrefix);
                    if (name !== null) {
                        postAction("cmd=set_prefix&dir=" + currentDir + "&name=" + encodeURIComponent(name.trim()));
                    }
                }

                function renameItem(targetPath, oldName) {
                    const newName = prompt("Renomear '" + oldName + "' para:", oldName);
                    if (newName && newName.trim() !== "" && newName.trim() !== oldName) {
                        postAction("cmd=rename&dir=" + currentDir + "&target=" + encodeURIComponent(targetPath) + "&name=" + encodeURIComponent(newName.trim()));
                    }
                }

                function deleteItem(targetPath, name) {
                    if (confirm("Tem certeza que deseja excluir permanentemente '" + name + "' do celular?")) {
                        postAction("cmd=delete&dir=" + currentDir + "&target=" + encodeURIComponent(targetPath));
                    }
                }

                async function uploadFiles(files) {
                    if (!files || files.length === 0) return;
                    const status = document.getElementById("uploadStatus");
                    for (let i = 0; i < files.length; i++) {
                        const file = files[i];
                        status.innerText = "Enviando (" + (i + 1) + "/" + files.length + "): " + file.name + "...";
                        await fetch("/upload?dir=" + currentDir + "&name=" + encodeURIComponent(file.name), {
                            method: "POST",
                            headers: { "X-CSRF-Token": csrfToken },
                            body: file
                        });
                    }
                    status.innerText = "Concluído! Recarregando...";
                    window.location.reload();
                }
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

    private fun getMimeTypeForFile(name: String): String {
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
