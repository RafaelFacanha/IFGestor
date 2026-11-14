package com.ifgestor.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ThumbnailUtils
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

class WebServerManager private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val settings = SettingsManager(appContext)
    private val security = SecurityManager.getInstance(appContext)
    private val audit = AuditLogManager.getInstance(appContext)
    private val taskManager = FileLockAndTaskManager.getInstance(appContext)
    private val mdnsManager = MdnsDiscoveryManager.getInstance(appContext)
    private val p2pManager = P2PTransferManager.getInstance(appContext)

    private var serverSocket: SSLServerSocket? = null
    private var executor: ExecutorService? = null

    @Volatile
    private var isRunning = false

    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    @Synchronized
    fun startServer(port: Int = settings.getServerPort()): Boolean {
        if (isRunning) return true
        return try {
            acquireHardwareLocks()
            val sslFactory = SelfSignedCertificateGenerator.getServerSocketFactory(appContext)
            val sslSocket = sslFactory.createServerSocket() as SSLServerSocket
            sslSocket.reuseAddress = true
            sslSocket.bind(InetSocketAddress(port))
            sslSocket.needClientAuth = false

            serverSocket = sslSocket
            isRunning = true
            settings.setWebServerActive(true)

            mdnsManager.start(port)

            val pool = Executors.newCachedThreadPool()
            executor = pool
            pool.execute { acceptLoop(sslSocket, pool) }

            audit.logEvent(
                action = "SERVIDOR_HTTPS_INICIADO",
                actor = "Sistema",
                details = "Servidor HTTPS ativo em " + mdnsManager.getPrimaryHttpsDomainUrl(),
                success = true
            )
            true
        } catch (e: Exception) {
            isRunning = false
            settings.setWebServerActive(false)
            releaseHardwareLocks()
            audit.logEvent(
                action = "SERVIDOR_HTTPS_ERRO",
                actor = "Sistema",
                details = "Falha ao iniciar servidor: " + (e.message ?: "erro"),
                success = false
            )
            false
        }
    }

    @Synchronized
    fun stopServer() {
        if (!isRunning && serverSocket == null) return
        isRunning = false
        settings.setWebServerActive(false)

        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null

        try {
            executor?.shutdownNow()
        } catch (_: Exception) {
        }
        executor = null

        mdnsManager.stop()
        security.revokeAllSessions("Servidor Web desligado")
        releaseHardwareLocks()

        audit.logEvent(
            action = "SERVIDOR_HTTPS_PARADO",
            actor = "Sistema",
            details = "Servidor Web HTTPS encerrado e sessões revogadas",
            success = true
        )
    }

    fun isServerRunning(): Boolean = isRunning

    private fun acceptLoop(sslServerSocket: SSLServerSocket, pool: ExecutorService) {
        while (isRunning && !sslServerSocket.isClosed) {
            try {
                val client = sslServerSocket.accept()
                pool.execute { handleClientSocket(client) }
            } catch (_: Exception) {
                if (!isRunning) break
            }
        }
    }

    private fun handleClientSocket(socket: Socket) {
        socket.use { client ->
            try {
                client.soTimeout = 60_000
                if (client is SSLSocket) {
                    client.startHandshake()
                }

                val clientIp = client.inetAddress?.hostAddress ?: "unknown"
                val rawIn = BufferedInputStream(client.getInputStream(), 32 * 1024)
                val rawOut = BufferedOutputStream(client.getOutputStream(), 32 * 1024)

                val requestLine = readAsciiLine(rawIn) ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return

                val method = parts[0].uppercase(Locale.ROOT)
                val fullUri = parts[1]
                val path = fullUri.substringBefore("?")
                val queryString = if (fullUri.contains("?")) fullUri.substringAfter("?") else ""
                val queryParams = parseQueryParams(queryString)

                val headers = mutableMapOf<String, String>()
                while (true) {
                    val line = readAsciiLine(rawIn) ?: break
                    if (line.isEmpty()) break
                    val colon = line.indexOf(':')
                    if (colon > 0) {
                        val key = line.substring(0, colon).trim().lowercase(Locale.ROOT)
                        val value = line.substring(colon + 1).trim()
                        headers[key] = value
                    }
                }

                val contentLength = headers["content-length"]?.toLongOrNull() ?: 0L
                val sessionToken = extractSessionToken(headers, queryParams)

                routeRequest(
                    method = method,
                    path = path,
                    queryParams = queryParams,
                    headers = headers,
                    contentLength = contentLength,
                    sessionToken = sessionToken,
                    clientIp = clientIp,
                    bodyStream = LimitedInputStream(rawIn, contentLength),
                    out = rawOut
                )
            } catch (_: Exception) {
            }
        }
    }

    private fun routeRequest(
        method: String,
        path: String,
        queryParams: Map<String, String>,
        headers: Map<String, String>,
        contentLength: Long,
        sessionToken: String?,
        clientIp: String,
        bodyStream: InputStream,
        out: OutputStream
    ) {
        if (method == "GET" && (path == "/" || path == "/index.html")) {
            val htmlBytes = loadSpaHtmlBytes()
            sendResponse(out, 200, "OK", "text/html; charset=utf-8", htmlBytes)
            return
        }

        when (path) {
            "/api/auth/status" -> {
                val validSession = security.getValidAuthenticatedSession(sessionToken, clientIp)
                val preSession = security.getPreAuthSession(sessionToken, clientIp)
                val json = JSONObject().apply {
                    put("authenticated", validSession != null)
                    put("mustChangePin", preSession?.mustChangeDefaultPin == true || security.isMustChangeDefaultPin())
                    put("nickname", settings.getDeviceNickname())
                    put("hostname", settings.getActiveMdnsHostname())
                    put("port", settings.getServerPort())
                    put("tlsFingerprint", SelfSignedCertificateGenerator.getCertificateFingerprint(appContext))
                }
                sendJson(out, 200, json)
                return
            }

            "/api/login" -> {
                if (method != "POST") {
                    sendJsonError(out, 405, "Use exclusivamente POST.")
                    return
                }
                val body = readJsonBody(bodyStream)
                val pin = body.optString("pin", "")
                val userAgent = headers["user-agent"] ?: "Browser"

                when (security.verifyPin(pin, clientIp)) {
                    SecurityManager.PinStatus.RATE_LIMITED -> {
                        sendJsonError(out, 429, "Muitas tentativas incorretas. Aguarde 1 minuto.")
                    }
                    SecurityManager.PinStatus.INVALID_PIN -> {
                        sendJsonError(out, 401, "PIN incorreto.")
                    }
                    SecurityManager.PinStatus.MUST_CHANGE_DEFAULT_PIN -> {
                        val session = security.createWebSession(clientIp, userAgent, mustChangePin = true)
                        val resp = JSONObject().apply {
                            put("ok", true)
                            put("mustChangePin", true)
                            put("requires2fa", false)
                            put("sessionToken", session.token)
                            put("message", "Substitua o PIN padrao 1234 antes de continuar.")
                        }
                        sendJson(out, 200, resp, sessionCookie = session.token)
                    }
                    SecurityManager.PinStatus.SUCCESS -> {
                        val session = security.createWebSession(clientIp, userAgent, mustChangePin = false)
                        security.issue2faChallenge(session.token, clientIp, "Acesso Web PC")
                        val resp = JSONObject().apply {
                            put("ok", true)
                            put("mustChangePin", false)
                            put("requires2fa", true)
                            put("sessionToken", session.token)
                            put("message", "Codigo 2FA de 6 digitos enviado para a notificacao do telemovel.")
                        }
                        sendJson(out, 200, resp, sessionCookie = session.token)
                    }
                }
                return
            }

            "/api/change-pin" -> {
                if (method != "POST") {
                    sendJsonError(out, 405, "Metodo invalido.")
                    return
                }
                val preSession = security.getPreAuthSession(sessionToken, clientIp)
                if (preSession == null) {
                    sendJsonError(out, 401, "Sessao expirada. Faca login novamente.")
                    return
                }
                val body = readJsonBody(bodyStream)
                val currentPin = body.optString("currentPin", "")
                val newPin = body.optString("newPin", "")

                val changeResult = security.changePin(currentPin, newPin, clientIp)
                if (changeResult.isFailure) {
                    sendJsonError(out, 400, changeResult.exceptionOrNull()?.message ?: "Erro ao alterar PIN.")
                    return
                }

                security.issue2faChallenge(preSession.token, clientIp, "Acesso Web pos-troca de PIN")
                val resp = JSONObject().apply {
                    put("ok", true)
                    put("requires2fa", true)
                    put("sessionToken", preSession.token)
                    put("message", "PIN atualizado! Digite o codigo 2FA enviado ao telemovel.")
                }
                sendJson(out, 200, resp, sessionCookie = preSession.token)
                return
            }

            "/api/verify-2fa" -> {
                if (method != "POST") {
                    sendJsonError(out, 405, "Metodo invalido.")
                    return
                }
                val preSession = security.getPreAuthSession(sessionToken, clientIp)
                if (preSession == null) {
                    sendJsonError(out, 401, "Sessao invalida ou expirada.")
                    return
                }
                val body = readJsonBody(bodyStream)
                val code = body.optString("code", "")

                if (security.verify2faChallenge(preSession.token, code, clientIp)) {
                    val resp = JSONObject().apply {
                        put("ok", true)
                        put("sessionToken", preSession.token)
                    }
                    sendJson(out, 200, resp, sessionCookie = preSession.token)
                } else {
                    sendJsonError(out, 401, "Codigo 2FA invalido ou expirado.")
                }
                return
            }

            "/api/logout" -> {
                security.revokeSession(sessionToken, clientIp)
                sendJson(out, 200, JSONObject().put("ok", true), clearCookie = true)
                return
            }

            "/api/p2p/handshake" -> {
                if (method != "POST") {
                    sendJsonError(out, 405, "Metodo invalido.")
                    return
                }
                val body = readJsonBody(bodyStream)
                val transferId = body.optString("transferId", "")
                val senderNickname = body.optString("senderNickname", "Telemovel Remoto")
                val targetSubfolder = body.optString("targetSubfolder", settings.getTargetSubfolder())
                val fileCount = body.optInt("fileCount", 1)
                val totalBytes = body.optLong("totalBytes", 0L)

                if (transferId.isBlank()) {
                    sendJsonError(out, 400, "transferId ausente.")
                    return
                }

                p2pManager.handleIncomingHandshake(
                    transferId = transferId,
                    senderNickname = senderNickname,
                    senderIp = clientIp,
                    targetSubfolder = targetSubfolder,
                    fileCount = fileCount,
                    totalBytes = totalBytes
                )
                sendJson(out, 200, JSONObject().put("ok", true))
                return
            }

            "/api/p2p/verify-receiver" -> {
                if (method != "POST") {
                    sendJsonError(out, 405, "Metodo invalido.")
                    return
                }
                val body = readJsonBody(bodyStream)
                val transferId = body.optString("transferId", "")
                val code = body.optString("code", "")

                val res = p2pManager.verifyIncoming2fa(transferId, code)
                if (res.isSuccess) {
                    val resp = JSONObject().apply {
                        put("ok", true)
                        put("authToken", res.getOrNull() ?: "")
                    }
                    sendJson(out, 200, resp)
                } else {
                    sendJsonError(out, 401, res.exceptionOrNull()?.message ?: "Codigo 2FA recusado.")
                }
                return
            }

            "/api/p2p/upload" -> {
                if (method != "POST") {
                    sendJsonError(out, 405, "Metodo invalido.")
                    return
                }
                val transferId = queryParams["transferId"] ?: ""
                val fileName = queryParams["fileName"] ?: ("p2p_" + System.currentTimeMillis() + ".jpg")
                val subfolder = queryParams["subfolder"] ?: settings.getTargetSubfolder()
                val expectedBytes = queryParams["expectedBytes"]?.toLongOrNull() ?: contentLength
                val p2pToken = headers["x-p2p-auth-token"]

                val saveRes = p2pManager.receiveIncomingFileStream(
                    transferId = transferId,
                    authToken = p2pToken,
                    fileName = fileName,
                    subfolder = subfolder,
                    expectedBytes = expectedBytes,
                    inputStream = bodyStream
                )
                if (saveRes.isSuccess) {
                    sendJson(
                        out,
                        200,
                        JSONObject().put("ok", true).put("savedName", saveRes.getOrNull()?.name ?: fileName)
                    )
                } else {
                    sendJsonError(out, 403, saveRes.exceptionOrNull()?.message ?: "Falha na recepcao P2P.")
                }
                return
            }
        }

        val authSession = security.getValidAuthenticatedSession(sessionToken, clientIp)
        if (authSession == null) {
            sendJsonError(out, 401, "Sessao nao autorizada ou expirada. Valide o PIN e o codigo 2FA.")
            return
        }

        when (path) {
            "/api/files" -> handleListFiles(queryParams, out)
            "/api/subfolder/create" -> handleCreateSubfolder(bodyStream, clientIp, out)
            "/api/upload" -> handleWebFileUpload(queryParams, contentLength, bodyStream, clientIp, out)
            "/api/move" -> handleMoveFiles(bodyStream, clientIp, out)
            "/api/delete" -> handleDeleteFiles(bodyStream, clientIp, out)
            "/api/tasks" -> handleGetTasksAndPeers(out)
            "/api/tasks/clear" -> {
                taskManager.clearFinishedTasks()
                sendJson(out, 200, JSONObject().put("ok", true))
            }
            "/api/peers" -> {
                val resp = JSONObject().apply {
                    put("ok", true)
                    put("peers", mdnsManager.toJsonArray())
                }
                sendJson(out, 200, resp)
            }
            "/api/audit" -> {
                val resp = JSONObject().apply {
                    put("ok", true)
                    put("logs", audit.toJsonArray(150))
                }
                sendJson(out, 200, resp)
            }
            "/api/p2p/initiate" -> handleWebInitiateP2p(bodyStream, out)
            "/api/p2p/confirm-source" -> handleWebConfirmSourceP2p(bodyStream, out)
            "/api/p2p/confirm-receiver" -> handleWebConfirmReceiverP2p(bodyStream, out)
            "/api/p2p/approve-incoming" -> handleWebApproveIncomingP2p(bodyStream, out)
            "/api/whatsapp-trigger" -> handleRemoteWhatsAppTrigger(bodyStream, clientIp, out)
            "/api/thumb" -> handleServeThumbnail(queryParams, out)
            "/api/file" -> handleServeFullFile(queryParams, clientIp, out)
            "/api/download-zip" -> handleStreamingZipDownload(queryParams, clientIp, out)
            else -> sendJsonError(out, 404, "Rota nao encontrada: " + path)
        }
    }

    private fun handleListFiles(queryParams: Map<String, String>, out: OutputStream) {
        val requestedSub = queryParams["subfolder"]?.ifBlank { settings.getTargetSubfolder() }
            ?: settings.getTargetSubfolder()
        val folder = settings.resolveSafeSubfolder(requestedSub)
        security.assertInsideSandbox(folder)

        val subfolders = settings.listSandboxSubfolders()
        val subsJson = JSONArray()
        for (s in subfolders) {
            subsJson.put(s)
        }

        val filesArray = JSONArray()
        val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())

        val rawList = folder.listFiles() ?: emptyArray<File>()
        val validFiles = rawList
            .filter { file: File -> file.isFile && !file.name.startsWith(".") && !file.name.endsWith(".part") }
            .sortedByDescending { file: File -> file.lastModified() }

        for (file in validFiles) {
            val ext = file.extension.lowercase(Locale.ROOT)
            val isVideo = ext in listOf("mp4", "mkv", "mov", "3gp", "webm")
            val obj = JSONObject().apply {
                put("name", file.name)
                put("subfolder", folder.name)
                put("sizeBytes", file.length())
                put("formattedSize", FileLockAndTaskManager.formatSize(file.length()))
                put("lastModified", file.lastModified())
                put("formattedDate", sdf.format(Date(file.lastModified())))
                put("isVideo", isVideo)
                put("locked", taskManager.isFileLocked(file))
            }
            filesArray.put(obj)
        }

        val resp = JSONObject().apply {
            put("ok", true)
            put("activeSubfolder", folder.name)
            put("captureSubfolder", settings.getTargetSubfolder())
            put("activePrefix", settings.getFilePrefix())
            put("sessionPhotoCount", settings.getSessionPhotoCount())
            put("subfolders", subsJson)
            put("files", filesArray)
        }
        sendJson(out, 200, resp)
    }

    private fun handleCreateSubfolder(bodyStream: InputStream, clientIp: String, out: OutputStream) {
        val body = readJsonBody(bodyStream)
        val folderName = settings.sanitizeFolderName(body.optString("folderName", ""))
        val setAsActive = body.optBoolean("setAsActive", true)

        if (folderName.isBlank()) {
            sendJsonError(out, 400, "Informe um nome valido para a subpasta.")
            return
        }

        val created = settings.createSubfolder(folderName)
        if (setAsActive && created) {
            settings.setTargetSubfolder(folderName)
        }

        audit.logEvent(
            action = "SUBPASTA_CRIADA_WEB",
            actor = clientIp,
            details = "Subpasta '" + folderName + "' criada no Sandbox",
            success = created
        )

        val subsJson = JSONArray()
        for (s in settings.listSandboxSubfolders()) {
            subsJson.put(s)
        }

        val resp = JSONObject().apply {
            put("ok", created)
            put("folderName", folderName)
            put("subfolders", subsJson)
        }
        sendJson(out, 200, resp)
    }

    private fun handleWebFileUpload(
        queryParams: Map<String, String>,
        contentLength: Long,
        bodyStream: InputStream,
        clientIp: String,
        out: OutputStream
    ) {
        val subfolder = queryParams["subfolder"]?.ifBlank { settings.getTargetSubfolder() }
            ?: settings.getTargetSubfolder()
        val rawFileName = queryParams["fileName"] ?: ("upload_" + System.currentTimeMillis() + ".jpg")
        val targetDir = settings.resolveSafeSubfolder(subfolder)

        val task = taskManager.createTask(
            type = FileLockAndTaskManager.TaskType.UPLOAD,
            title = "Recebendo " + rawFileName,
            subtitle = "Pasta: " + targetDir.name,
            totalBytes = contentLength,
            totalItems = 1
        )

        try {
            val savedFile = taskManager.writeStreamAtomically(
                inputStream = bodyStream,
                targetDir = targetDir,
                desiredFileName = rawFileName,
                expectedBytes = contentLength,
                taskId = task.id,
                operationLabel = "Upload Web"
            )

            audit.logEvent(
                action = "UPLOAD_CONCLUIDO",
                actor = clientIp,
                details = "Ficheiro '" + savedFile.name + "' salvo em '" + targetDir.name + "'",
                success = true
            )

            val resp = JSONObject().apply {
                put("ok", true)
                put("savedName", savedFile.name)
                put("taskId", task.id)
            }
            sendJson(out, 200, resp)
        } catch (e: Exception) {
            audit.logEvent(
                action = "UPLOAD_FALHOU",
                actor = clientIp,
                details = "Falha no upload de '" + rawFileName + "': " + (e.message ?: "erro"),
                success = false
            )
            sendJsonError(out, 500, e.message ?: "Erro ao gravar ficheiro.")
        }
    }

    private fun handleMoveFiles(bodyStream: InputStream, clientIp: String, out: OutputStream) {
        val body = readJsonBody(bodyStream)
        val sourceSubfolder = body.optString("sourceSubfolder", settings.getTargetSubfolder())
        val targetSubfolder = body.optString("targetSubfolder", "")
        val namesArr = body.optJSONArray("fileNames") ?: JSONArray()

        if (targetSubfolder.isBlank() || namesArr.length() == 0) {
            sendJsonError(out, 400, "Selecione os ficheiros e a subpasta de destino.")
            return
        }

        val sourceDir = settings.resolveSafeSubfolder(sourceSubfolder)
        val files = mutableListOf<File>()
        for (i in 0 until namesArr.length()) {
            val name = taskManager.sanitizeFileName(namesArr.optString(i, ""))
            val f = File(sourceDir, name)
            if (settings.isInsideSandbox(f)) {
                files.add(f)
            }
        }

        val moveResult = taskManager.moveFilesSafely(files, targetSubfolder, "Web (" + clientIp + ")")
        val errJson = JSONArray()
        for (err in moveResult.second) {
            errJson.put(err)
        }
        val resp = JSONObject().apply {
            put("ok", true)
            put("movedCount", moveResult.first)
            put("errors", errJson)
        }
        sendJson(out, 200, resp)
    }

    private fun handleDeleteFiles(bodyStream: InputStream, clientIp: String, out: OutputStream) {
        val body = readJsonBody(bodyStream)
        val subfolder = body.optString("subfolder", settings.getTargetSubfolder())
        val namesArr = body.optJSONArray("fileNames") ?: JSONArray()

        if (namesArr.length() == 0) {
            sendJsonError(out, 400, "Nenhum ficheiro selecionado para exclusao.")
            return
        }

        val dir = settings.resolveSafeSubfolder(subfolder)
        val files = mutableListOf<File>()
        for (i in 0 until namesArr.length()) {
            val name = taskManager.sanitizeFileName(namesArr.optString(i, ""))
            val f = File(dir, name)
            if (settings.isInsideSandbox(f)) {
                files.add(f)
            }
        }

        val delResult = taskManager.deleteFilesSafely(files, "Web (" + clientIp + ")")
        val errJson = JSONArray()
        for (err in delResult.second) {
            errJson.put(err)
        }
        val resp = JSONObject().apply {
            put("ok", true)
            put("deletedCount", delResult.first)
            put("errors", errJson)
        }
        sendJson(out, 200, resp)
    }

    private fun handleGetTasksAndPeers(out: OutputStream) {
        val resp = JSONObject().apply {
            put("ok", true)
            put("tasks", taskManager.toJsonArray())
            put("peers", mdnsManager.toJsonArray())
            put("pendingIncomingP2p", p2pManager.toJsonPendingIncoming())
        }
        sendJson(out, 200, resp)
    }

    private fun handleWebInitiateP2p(bodyStream: InputStream, out: OutputStream) {
        val body = readJsonBody(bodyStream)
        val sourceSubfolder = body.optString("sourceSubfolder", settings.getTargetSubfolder())
        val targetSubfolder = body.optString("targetSubfolder", sourceSubfolder)
        val peerIp = body.optString("peerIp", "")
        val peerPort = body.optInt("peerPort", 8080)
        val peerNickname = body.optString("peerNickname", peerIp)
        val namesArr = body.optJSONArray("fileNames") ?: JSONArray()

        val sourceDir = settings.resolveSafeSubfolder(sourceSubfolder)
        val files = mutableListOf<File>()
        for (i in 0 until namesArr.length()) {
            val f = File(sourceDir, taskManager.sanitizeFileName(namesArr.optString(i, "")))
            if (f.exists() && settings.isInsideSandbox(f)) {
                files.add(f)
            }
        }

        val initRes = p2pManager.initiateOutgoingTransfer(
            files = files,
            peerIp = peerIp,
            peerPort = peerPort,
            peerNickname = peerNickname,
            targetSubfolder = targetSubfolder
        )

        if (initRes.isSuccess) {
            val session = initRes.getOrThrow()
            val resp = JSONObject().apply {
                put("ok", true)
                put("transferId", session.transferId)
                put("message", "Codigo 2FA de ORIGEM gerado neste telemovel. Confira a notificacao.")
            }
            sendJson(out, 200, resp)
        } else {
            sendJsonError(out, 400, initRes.exceptionOrNull()?.message ?: "Falha ao iniciar P2P.")
        }
    }

    private fun handleWebConfirmSourceP2p(bodyStream: InputStream, out: OutputStream) {
        val body = readJsonBody(bodyStream)
        val transferId = body.optString("transferId", "")
        val code = body.optString("code", "")

        val latch = CountDownLatch(1)
        val ref = AtomicReference<Result<String>>(Result.failure(IllegalStateException("Timeout")))

        p2pManager.confirmSource2faAndRequestReceiver(transferId, code) { result: Result<String> ->
            ref.set(result)
            latch.countDown()
        }
        latch.await(15, TimeUnit.SECONDS)

        val finalRes = ref.get()
        if (finalRes.isSuccess) {
            sendJson(out, 200, JSONObject().put("ok", true).put("message", finalRes.getOrNull()))
        } else {
            sendJsonError(out, 400, finalRes.exceptionOrNull()?.message ?: "Erro ao validar 2FA de origem.")
        }
    }

    private fun handleWebConfirmReceiverP2p(bodyStream: InputStream, out: OutputStream) {
        val body = readJsonBody(bodyStream)
        val transferId = body.optString("transferId", "")
        val code = body.optString("code", "")

        val latch = CountDownLatch(1)
        val ref = AtomicReference<Result<String>>(Result.failure(IllegalStateException("Timeout")))

        p2pManager.confirmReceiver2faAndStartStreaming(transferId, code) { result: Result<String> ->
            ref.set(result)
            latch.countDown()
        }
        latch.await(15, TimeUnit.SECONDS)

        val finalRes = ref.get()
        if (finalRes.isSuccess) {
            sendJson(out, 200, JSONObject().put("ok", true).put("message", finalRes.getOrNull()))
        } else {
            sendJsonError(out, 400, finalRes.exceptionOrNull()?.message ?: "Erro ao validar 2FA de destino.")
        }
    }

    private fun handleWebApproveIncomingP2p(bodyStream: InputStream, out: OutputStream) {
        val body = readJsonBody(bodyStream)
        val transferId = body.optString("transferId", "")
        val code = body.optString("code", "")

        val res = p2pManager.verifyIncoming2fa(transferId, code)
        if (res.isSuccess) {
            sendJson(out, 200, JSONObject().put("ok", true).put("authToken", res.getOrNull()))
        } else {
            sendJsonError(out, 400, res.exceptionOrNull()?.message ?: "Codigo 2FA incorreto.")
        }
    }

    private fun handleRemoteWhatsAppTrigger(bodyStream: InputStream, clientIp: String, out: OutputStream) {
        val body = readJsonBody(bodyStream)
        val subfolder = body.optString("subfolder", settings.getTargetSubfolder())
        val namesArr = body.optJSONArray("fileNames") ?: JSONArray()
        val caption = body.optString("caption", "")

        val dir = settings.resolveSafeSubfolder(subfolder)
        val files = mutableListOf<File>()
        for (i in 0 until namesArr.length()) {
            val f = File(dir, taskManager.sanitizeFileName(namesArr.optString(i, "")))
            if (f.exists() && settings.isInsideSandbox(f)) {
                files.add(f)
            }
        }

        val res = WhatsAppShareHelper.shareFilesToWhatsApp(
            context = appContext,
            files = files,
            captionText = caption.ifBlank { null },
            actor = "Web PC (" + clientIp + ")"
        )
        if (res.isSuccess) {
            sendJson(
                out,
                200,
                JSONObject().put("ok", true).put("sharedCount", res.getOrDefault(0))
            )
        } else {
            sendJsonError(out, 400, res.exceptionOrNull()?.message ?: "Erro ao abrir WhatsApp no telemovel.")
        }
    }

    private fun handleServeThumbnail(queryParams: Map<String, String>, out: OutputStream) {
        val subfolder = queryParams["subfolder"] ?: settings.getTargetSubfolder()
        val name = taskManager.sanitizeFileName(queryParams["name"] ?: "")
        val dir = settings.resolveSafeSubfolder(subfolder)
        val file = File(dir, name)

        if (!file.exists() || !settings.isInsideSandbox(file)) {
            sendJsonError(out, 404, "Ficheiro nao encontrado.")
            return
        }

        try {
            val ext = file.extension.lowercase(Locale.ROOT)
            val isVideo = ext in listOf("mp4", "mkv", "mov", "3gp", "webm")

            @Suppress("DEPRECATION")
            val bitmap: Bitmap? = if (isVideo) {
                ThumbnailUtils.createVideoThumbnail(file.absolutePath, MediaStore.Images.Thumbnails.MINI_KIND)
            } else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                var sampleSize = 1
                while (bounds.outWidth / sampleSize > 360 || bounds.outHeight / sampleSize > 360) {
                    sampleSize *= 2
                }
                val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
                BitmapFactory.decodeFile(file.absolutePath, decodeOpts)
            }

            if (bitmap == null) {
                sendJsonError(out, 404, "Miniatura indisponivel.")
                return
            }

            val baos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 75, baos)
            bitmap.recycle()
            val bytes = baos.toByteArray()

            sendResponse(out, 200, "OK", "image/jpeg", bytes)
        } catch (_: Exception) {
            sendJsonError(out, 500, "Erro ao gerar miniatura.")
        }
    }

    private fun handleServeFullFile(
        queryParams: Map<String, String>,
        clientIp: String,
        out: OutputStream
    ) {
        val subfolder = queryParams["subfolder"] ?: settings.getTargetSubfolder()
        val name = taskManager.sanitizeFileName(queryParams["name"] ?: "")
        val download = queryParams["download"] == "1"

        val dir = settings.resolveSafeSubfolder(subfolder)
        val file = File(dir, name)
        if (!file.exists() || !settings.isInsideSandbox(file)) {
            sendJsonError(out, 404, "Ficheiro nao encontrado.")
            return
        }

        if (!taskManager.tryAcquireLock(file, "Leitura/Download Web")) {
            sendJsonError(out, 423, "O ficheiro esta em uso por outra transferencia.")
            return
        }

        try {
            val ext = file.extension.lowercase(Locale.ROOT)
            val mime = when (ext) {
                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                "webp" -> "image/webp"
                "gif" -> "image/gif"
                "mp4" -> "video/mp4"
                "webm" -> "video/webm"
                "mov" -> "video/quicktime"
                else -> "application/octet-stream"
            }

            val headerBuilder = StringBuilder()
            headerBuilder.append("HTTP/1.1 200 OK\r\n")
            headerBuilder.append("Content-Type: ").append(mime).append("\r\n")
            headerBuilder.append("Content-Length: ").append(file.length()).append("\r\n")
            headerBuilder.append("Cache-Control: no-store\r\n")
            if (download) {
                headerBuilder.append("Content-Disposition: attachment; filename=\"").append(file.name).append("\"\r\n")
            }
            headerBuilder.append("Connection: close\r\n\r\n")
            out.write(headerBuilder.toString().toByteArray(Charsets.UTF_8))

            BufferedInputStream(FileInputStream(file), 32 * 1024).use { fis ->
                val buffer = ByteArray(32 * 1024)
                var read: Int
                while (fis.read(buffer).also { read = it } != -1) {
                    out.write(buffer, 0, read)
                }
            }
            out.flush()

            if (download) {
                audit.logEvent(
                    action = "DOWNLOAD_INDIVIDUAL",
                    actor = clientIp,
                    details = "Ficheiro '" + file.name + "' transferido para o PC",
                    success = true
                )
            }
        } finally {
            taskManager.releaseLock(file)
        }
    }

    private fun handleStreamingZipDownload(
        queryParams: Map<String, String>,
        clientIp: String,
        out: OutputStream
    ) {
        val subfolder = queryParams["subfolder"] ?: settings.getTargetSubfolder()
        val rawNames = queryParams["files"] ?: ""
        val dir = settings.resolveSafeSubfolder(subfolder)
        security.assertInsideSandbox(dir)

        val rawList = dir.listFiles() ?: emptyArray<File>()
        val targetFiles: List<File> = if (rawNames.isBlank() || rawNames == "ALL") {
            rawList.filter { file: File -> file.isFile && !file.name.startsWith(".") && !file.name.endsWith(".part") }
        } else {
            rawNames.split(",")
                .map { itemName: String -> File(dir, taskManager.sanitizeFileName(itemName)) }
                .filter { file: File -> file.exists() && file.isFile && settings.isInsideSandbox(file) }
        }

        if (targetFiles.isEmpty()) {
            sendJsonError(out, 400, "Nenhum ficheiro valido selecionado para o pacote ZIP.")
            return
        }

        var totalBytes = 0L
        for (f in targetFiles) {
            totalBytes += f.length()
        }
        val zipFileName = "IFGestor_" + dir.name + "_" + System.currentTimeMillis() + ".zip"

        val task = taskManager.createTask(
            type = FileLockAndTaskManager.TaskType.DOWNLOAD_ZIP,
            title = "Gerando ZIP (" + targetFiles.size + " arq.)",
            subtitle = "Iniciando streaming...",
            totalBytes = totalBytes,
            totalItems = targetFiles.size
        )

        try {
            val headers = StringBuilder()
                .append("HTTP/1.1 200 OK\r\n")
                .append("Content-Type: application/zip\r\n")
                .append("Content-Disposition: attachment; filename=\"").append(zipFileName).append("\"\r\n")
                .append("Cache-Control: no-store\r\n")
                .append("Connection: close\r\n\r\n")
                .toString()
            out.write(headers.toByteArray(Charsets.UTF_8))

            var bytesProcessed = 0L
            var lastUiUpdate = 0L

            ZipOutputStream(BufferedOutputStream(out, 32 * 1024)).use { zos ->
                val buffer = ByteArray(32 * 1024)
                for ((index, file) in targetFiles.withIndex()) {
                    if (!taskManager.tryAcquireLock(file, "Streaming ZIP")) {
                        continue
                    }
                    try {
                        val entry = ZipEntry(file.name)
                        entry.time = file.lastModified()
                        zos.putNextEntry(entry)
                        BufferedInputStream(FileInputStream(file), 32 * 1024).use { fis ->
                            var read: Int
                            while (fis.read(buffer).also { read = it } != -1) {
                                zos.write(buffer, 0, read)
                                bytesProcessed += read

                                val now = System.currentTimeMillis()
                                if (now - lastUiUpdate >= 120L) {
                                    lastUiUpdate = now
                                    taskManager.updateTaskProgress(
                                        taskId = task.id,
                                        processedBytes = bytesProcessed,
                                        processedItems = index,
                                        subtitle = "Compactando " + file.name + " (" + (index + 1) + "/" + targetFiles.size + ")"
                                    )
                                }
                            }
                        }
                        zos.closeEntry()
                    } finally {
                        taskManager.releaseLock(file)
                    }
                }
                zos.finish()
                zos.flush()
            }

            taskManager.completeTask(
                taskId = task.id,
                finalSubtitle = "ZIP concluido (" + targetFiles.size + " ficheiros)"
            )
            audit.logEvent(
                action = "DOWNLOAD_ZIP_STREAM",
                actor = clientIp,
                details = "Pacote '" + zipFileName + "' enviado via streaming",
                success = true
            )
        } catch (e: Exception) {
            taskManager.failTask(task.id, "Streaming ZIP interrompido: " + (e.message ?: "erro"))
        }
    }

    private fun loadSpaHtmlBytes(): ByteArray {
        return try {
            appContext.assets.open("index.html").use { it.readBytes() }
        } catch (_: Exception) {
            "<html><body><h3>IFGestor Web</h3><p>Adicione app/src/main/assets/index.html</p></body></html>"
                .toByteArray(Charsets.UTF_8)
        }
    }

    private fun readAsciiLine(input: InputStream): String? {
        val sb = StringBuilder()
        var c = input.read()
        if (c == -1) return null
        while (c != -1) {
            if (c == '\n'.code) break
            if (c != '\r'.code) {
                sb.append(c.toChar())
            }
            c = input.read()
        }
        return sb.toString()
    }

    private fun parseQueryParams(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        val map = mutableMapOf<String, String>()
        query.split("&").forEach { pair ->
            val idx = pair.indexOf('=')
            if (idx > 0) {
                val k = URLDecoder.decode(pair.substring(0, idx), "UTF-8")
                val v = URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
                map[k] = v
            }
        }
        return map
    }

    private fun extractSessionToken(
        headers: Map<String, String>,
        queryParams: Map<String, String>
    ): String? {
        val headerToken = headers["x-session-token"]
        if (!headerToken.isNullOrBlank()) return headerToken.trim()

        val cookieHeader = headers["cookie"] ?: ""
        cookieHeader.split(";").forEach { part ->
            val trimmed = part.trim()
            if (trimmed.startsWith("IFGESTOR_SESSION=")) {
                return trimmed.removePrefix("IFGESTOR_SESSION=").trim()
            }
        }
        return queryParams["sessionToken"]
    }

    private fun readJsonBody(bodyStream: InputStream): JSONObject {
        return try {
            val text = bodyStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            if (text.isBlank()) JSONObject() else JSONObject(text)
        } catch (_: Exception) {
            JSONObject()
        }
    }

    private fun sendJson(
        out: OutputStream,
        statusCode: Int,
        json: JSONObject,
        sessionCookie: String? = null,
        clearCookie: Boolean = false
    ) {
        val statusText = when (statusCode) {
            200 -> "OK"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            423 -> "Locked"
            429 -> "Too Many Requests"
            else -> "Internal Server Error"
        }
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        sendResponse(out, statusCode, statusText, "application/json; charset=utf-8", bytes, sessionCookie, clearCookie)
    }

    private fun sendJsonError(out: OutputStream, statusCode: Int, message: String) {
        val obj = JSONObject().apply {
            put("ok", false)
            put("error", message)
        }
        sendJson(out, statusCode, obj)
    }

    private fun sendResponse(
        out: OutputStream,
        statusCode: Int,
        statusText: String,
        contentType: String,
        body: ByteArray,
        sessionCookie: String? = null,
        clearCookie: Boolean = false
    ) {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(statusCode).append(" ").append(statusText).append("\r\n")
        sb.append("Content-Type: ").append(contentType).append("\r\n")
        sb.append("Content-Length: ").append(body.size).append("\r\n")
        sb.append("Cache-Control: no-store, no-cache, must-revalidate\r\n")
        sb.append("Pragma: no-cache\r\n")
        sb.append("X-Content-Type-Options: nosniff\r\n")
        if (!sessionCookie.isNullOrBlank()) {
            sb.append("Set-Cookie: IFGESTOR_SESSION=").append(sessionCookie).append("; Path=/; HttpOnly; SameSite=Strict\r\n")
        } else if (clearCookie) {
            sb.append("Set-Cookie: IFGESTOR_SESSION=; Path=/; Max-Age=0; HttpOnly\r\n")
        }
        sb.append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray(Charsets.UTF_8))
        out.write(body)
        out.flush()
    }

    private class LimitedInputStream(
        private val delegate: InputStream,
        private var remaining: Long
    ) : InputStream() {
        override fun read(): Int {
            if (remaining <= 0L) return -1
            val res = delegate.read()
            if (res != -1) remaining--
            return res
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0L) return -1
            val toRead = minOf(len.toLong(), remaining).toInt()
            val count = delegate.read(b, off, toRead)
            if (count > 0) remaining -= count
            return count
        }
    }

    @Suppress("DEPRECATION")
    private fun acquireHardwareLocks() {
        try {
            val wm = appContext.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wifiLock == null) {
                wifiLock = wm?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "IFGestor:HttpsWifiLock")?.apply {
                    setReferenceCounted(false)
                }
            }
            if (wifiLock?.isHeld == false) wifiLock?.acquire()

            val pm = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (wakeLock == null) {
                wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "IFGestor:HttpsCpuWakeLock")?.apply {
                    setReferenceCounted(false)
                }
            }
            if (wakeLock?.isHeld == false) wakeLock?.acquire()
        } catch (_: Exception) {
        }
    }

    private fun releaseHardwareLocks() {
        try {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (_: Exception) {
        }
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {
        }
    }

    companion object {
        @Volatile
        private var INSTANCE: WebServerManager? = null

        fun getInstance(context: Context): WebServerManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: WebServerManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
