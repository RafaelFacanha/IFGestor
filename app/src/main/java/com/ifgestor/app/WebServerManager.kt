package com.ifgestor.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ThumbnailUtils
import android.os.PowerManager
import android.net.wifi.WifiManager
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
                details = "Servidor HTTPS ativo em ${mdnsManager.getPrimaryHttpsDomainUrl()} (${mdnsManager.getFallbackHttpsIpUrl()})",
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
                details = "Falha ao iniciar servidor na porta $port: ${e.message}",
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
        // 1. Página Principal SPA
        if (method == "GET" && (path == "/" || path == "/index.html")) {
            val htmlBytes = buildSpaHtml().toByteArray(Charsets.UTF_8)
            sendResponse(out, 200, "OK", "text/html; charset=utf-8", htmlBytes)
            return
        }

        // 2. Rotas Públicas / Pré-Autenticação (Login, 2FA, Troca Obrigatória do PIN 1234 e Status)
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
                    sendJsonError(out, 405, "Use exclusivamente POST com credenciais no corpo da requisição.")
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
                            put("message", "Por segurança, substitua o PIN padrão 1234 antes de continuar.")
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
                            put("message", "Código 2FA de 6 dígitos enviado para a notificação do telemóvel.")
                        }
                        sendJson(out, 200, resp, sessionCookie = session.token)
                    }
                }
                return
            }

            "/api/change-pin" -> {
                if (method != "POST") {
                    sendJsonError(out, 405, "Método inválido.")
                    return
                }
                val preSession = security.getPreAuthSession(sessionToken, clientIp)
                if (preSession == null) {
                    sendJsonError(out, 401, "Sessão expirada. Faça login novamente.")
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

                // Após trocar o PIN 1234 com sucesso, dispara o código 2FA para liberar a sessão
                security.issue2faChallenge(preSession.token, clientIp, "Acesso Web pós-troca de PIN")
                val resp = JSONObject().apply {
                    put("ok", true)
                    put("requires2fa", true)
                    put("sessionToken", preSession.token)
                    put("message", "PIN atualizado com sucesso! Digite o código 2FA enviado ao telemóvel.")
                }
                sendJson(out, 200, resp, sessionCookie = preSession.token)
                return
            }

            "/api/verify-2fa" -> {
                if (method != "POST") {
                    sendJsonError(out, 405, "Método inválido.")
                    return
                }
                val preSession = security.getPreAuthSession(sessionToken, clientIp)
                if (preSession == null) {
                    sendJsonError(out, 401, "Sessão inválida ou expirada.")
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
                    sendJsonError(out, 401, "Código 2FA inválido ou expirado.")
                }
                return
            }

            "/api/logout" -> {
                security.revokeSession(sessionToken, clientIp)
                sendJson(out, 200, JSONObject().put("ok", true), clearCookie = true)
                return
            }

            // Endpoints P2P entre telemóveis (autenticados via Duplo 2FA P2P - Item 8)
            "/api/p2p/handshake" -> {
                if (method != "POST") {
                    sendJsonError(out, 405, "Método inválido.")
                    return
                }
                val body = readJsonBody(bodyStream)
                val transferId = body.optString("transferId", "")
                val senderNickname = body.optString("senderNickname", "Telemóvel Remoto")
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
                    sendJsonError(out, 405, "Método inválido.")
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
                    sendJsonError(out, 401, res.exceptionOrNull()?.message ?: "Código 2FA recusado.")
                }
                return
            }

            "/api/p2p/upload" -> {
                if (method != "POST") {
                    sendJsonError(out, 405, "Método inválido.")
                    return
                }
                val transferId = queryParams["transferId"] ?: ""
                val fileName = queryParams["fileName"] ?: "p2p_${System.currentTimeMillis()}.jpg"
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
                    sendJsonError(out, 403, saveRes.exceptionOrNull()?.message ?: "Falha na recepção P2P.")
                }
                return
            }
        }

        // 3. Verificação Estrita de Sessão Autenticada com 2FA para todas as demais rotas da API
        val authSession = security.getValidAuthenticatedSession(sessionToken, clientIp)
        if (authSession == null) {
            sendJsonError(out, 401, "Sessão não autorizada ou expirada. Valide o PIN e o código 2FA.")
            return
        }

        // 4. Rotas Protegidas (Sandbox DCIM/Camera/IFgestor, Tarefas, P2P, ZIP, Auditoria e WhatsApp)
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
            else -> sendJsonError(out, 404, "Rota não encontrada: $path")
        }
    }

    // =========================================================================
    // Handlers de Gestão de Ficheiros no Sandbox (Itens 3, 9, 10 e 11)
    // =========================================================================

    private fun handleListFiles(queryParams: Map<String, String>, out: OutputStream) {
        val requestedSub = queryParams["subfolder"]?.ifBlank { settings.getTargetSubfolder() }
            ?: settings.getTargetSubfolder()
        val folder = settings.resolveSafeSubfolder(requestedSub)
        security.assertInsideSandbox(folder)

        val subfolders = settings.listSandboxSubfolders()
        val filesArray = JSONArray()
        val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())

        val validFiles = (folder.listFiles() ?: emptyArray())
            .filter { it.isFile && !it.name.startsWith(".") && !it.name.endsWith(".part") }
            .sortedByDescending { it.lastModified() }

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
            put("subfolders", JSONArray(subfolders))
            put("files", filesArray)
        }
        sendJson(out, 200, resp)
    }

    private fun handleCreateSubfolder(bodyStream: InputStream, clientIp: String, out: OutputStream) {
        val body = readJsonBody(bodyStream)
        val folderName = settings.sanitizeFolderName(body.optString("folderName", ""))
        val setAsActive = body.optBoolean("setAsActive", true)

        if (folderName.isBlank()) {
            sendJsonError(out, 400, "Informe um nome válido para a subpasta.")
            return
        }

        val created = settings.createSubfolder(folderName)
        if (setAsActive && created) {
            settings.setTargetSubfolder(folderName)
        }

        audit.logEvent(
            action = "SUBPASTA_CRIADA_WEB",
            actor = clientIp,
            details = "Subpasta '$folderName' criada dentro do Sandbox DCIM/Camera/IFgestor",
            success = created
        )

        val resp = JSONObject().apply {
            put("ok", created)
            put("folderName", folderName)
            put("subfolders", JSONArray(settings.listSandboxSubfolders()))
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
        val rawFileName = queryParams["fileName"] ?: "upload_${System.currentTimeMillis()}.jpg"
        val targetDir = settings.resolveSafeSubfolder(subfolder)

        val task = taskManager.createTask(
            type = FileLockAndTaskManager.TaskType.UPLOAD,
            title = "Recebendo $rawFileName",
            subtitle = "Pasta: ${targetDir.name}",
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
                details = "Ficheiro '${savedFile.name}' (${FileLockAndTaskManager.formatSize(savedFile.length())}) salvo em '${targetDir.name}'",
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
                details = "Falha no upload de '$rawFileName': ${e.message}",
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

        val (movedCount, errors) = taskManager.moveFilesSafely(files, targetSubfolder, "Web ($clientIp)")
        val resp = JSONObject().apply {
            put("ok", true)
            put("movedCount", movedCount)
            put("errors", JSONArray(errors))
        }
        sendJson(out, 200, resp)
    }

    private fun handleDeleteFiles(bodyStream: InputStream, clientIp: String, out: OutputStream) {
        val body = readJsonBody(bodyStream)
        val subfolder = body.optString("subfolder", settings.getTargetSubfolder())
        val namesArr = body.optJSONArray("fileNames") ?: JSONArray()

        if (namesArr.length() == 0) {
            sendJsonError(out, 400, "Nenhum ficheiro selecionado para exclusão.")
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

        val (deletedCount, errors) = taskManager.deleteFilesSafely(files, "Web ($clientIp)")
        val resp = JSONObject().apply {
            put("ok", true)
            put("deletedCount", deletedCount)
            put("errors", JSONArray(errors))
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

    // =========================================================================
    // Handlers de Transferência P2P Acionada pela Web (Item 8)
    // =========================================================================

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
                put("message", "Código 2FA de ORIGEM gerado neste telemóvel. Confira a notificação.")
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
        val ref = AtomicReference<Result<String>>(Result.failure( IllegalStateException("Timeout") ))

        p2pManager.confirmSource2faAndRequestReceiver(transferId, code) { result ->
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
        val ref = AtomicReference<Result<String>>(Result.failure( IllegalStateException("Timeout") ))

        p2pManager.confirmReceiver2faAndStartStreaming(transferId, code) { result ->
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
            sendJsonError(out, 400, res.exceptionOrNull()?.message ?: "Código 2FA incorreto.")
        }
    }

    // =========================================================================
    // Disparo Remoto para WhatsApp do Telemóvel (Item 16)
    // =========================================================================

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
            actor = "Web PC ($clientIp)"
        )
        if (res.isSuccess) {
            sendJson(
                out,
                200,
                JSONObject().put("ok", true).put("sharedCount", res.getOrDefault(0))
            )
        } else {
            sendJsonError(out, 400, res.exceptionOrNull()?.message ?: "Erro ao abrir WhatsApp no telemóvel.")
        }
    }

    // =========================================================================
    // Miniaturas, Visualização de Mídia e Download .ZIP via Streaming (Item 11)
    // =========================================================================

    private fun handleServeThumbnail(queryParams: Map<String, String>, out: OutputStream) {
        val subfolder = queryParams["subfolder"] ?: settings.getTargetSubfolder()
        val name = taskManager.sanitizeFileName(queryParams["name"] ?: "")
        val dir = settings.resolveSafeSubfolder(subfolder)
        val file = File(dir, name)

        if (!file.exists() || !settings.isInsideSandbox(file)) {
            sendJsonError(out, 404, "Ficheiro não encontrado.")
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
                sendJsonError(out, 404, "Miniatura indisponível.")
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
            sendJsonError(out, 404, "Ficheiro não encontrado.")
            return
        }

        if (!taskManager.tryAcquireLock(file, "Leitura/Download Web")) {
            sendJsonError(out, 423, "O ficheiro está em uso por outra transferência.")
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
            headerBuilder.append("Content-Type: $mime\r\n")
            headerBuilder.append("Content-Length: ${file.length()}\r\n")
            headerBuilder.append("Cache-Control: no-store\r\n")
            if (download) {
                headerBuilder.append("Content-Disposition: attachment; filename=\"${file.name}\"\r\n")
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
                    details = "Ficheiro '${file.name}' transferido para o PC",
                    success = true
                )
            }
        } finally {
            taskManager.releaseLock(file)
        }
    }

    /**
     * Item 11: Download .zip em lote via Streaming paginado (sem carregar tudo na RAM)
     * com progresso na Central de Tarefas e proteção de File Lock.
     */
    private fun handleStreamingZipDownload(
        queryParams: Map<String, String>,
        clientIp: String,
        out: OutputStream
    ) {
        val subfolder = queryParams["subfolder"] ?: settings.getTargetSubfolder()
        val rawNames = queryParams["files"] ?: ""
        val dir = settings.resolveSafeSubfolder(subfolder)
        security.assertInsideSandbox(dir)

        val targetFiles = if (rawNames.isBlank() || rawNames == "ALL") {
            (dir.listFiles() ?: emptyArray())
                .filter { it.isFile && !it.name.startsWith(".") && !it.name.endsWith(".part") }
        } else {
            rawNames.split(",")
                .map { File(dir, taskManager.sanitizeFileName(it)) }
                .filter { it.exists() && it.isFile && settings.isInsideSandbox(it) }
        }

        if (targetFiles.isEmpty()) {
            sendJsonError(out, 400, "Nenhum ficheiro válido selecionado para o pacote ZIP.")
            return
        }

        val totalBytes = targetFiles.sumOf { it.length() }
        val zipFileName = "IFGestor_${dir.name}_${System.currentTimeMillis()}.zip"

        val task = taskManager.createTask(
            type = FileLockAndTaskManager.TaskType.DOWNLOAD_ZIP,
            title = "Gerando ZIP (${targetFiles.size} arq.)",
            subtitle = "Iniciando streaming...",
            totalBytes = totalBytes,
            totalItems = targetFiles.size
        )

        try {
            val headers = buildString {
                append("HTTP/1.1 200 OK\r\n")
                append("Content-Type: application/zip\r\n")
                append("Content-Disposition: attachment; filename=\"$zipFileName\"\r\n")
                append("Cache-Control: no-store\r\n")
                append("Connection: close\r\n\r\n")
            }
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
                        val entry = ZipEntry(file.name).apply {
                            time = file.lastModified()
                        }
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
                                        subtitle = "Compactando ${file.name} (${index + 1}/${targetFiles.size})"
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
                finalSubtitle = "ZIP concluído (${targetFiles.size} ficheiros)"
            )
            audit.logEvent(
                action = "DOWNLOAD_ZIP_STREAM",
                actor = clientIp,
                details = "Pacote '$zipFileName' com ${targetFiles.size} ficheiro(s) enviado via streaming",
                success = true
            )
        } catch (e: Exception) {
            taskManager.failTask(task.id, "Streaming ZIP interrompido: ${e.message}")
        }
    }

    // =========================================================================
    // Utilitários HTTP / Streams / Locks de Hardware
    // =========================================================================

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
        sb.append("HTTP/1.1 $statusCode $statusText\r\n")
        sb.append("Content-Type: $contentType\r\n")
        sb.append("Content-Length: ${body.size}\r\n")
        sb.append("Cache-Control: no-store, no-cache, must-revalidate\r\n")
        sb.append("Pragma: no-cache\r\n")
        sb.append("X-Content-Type-Options: nosniff\r\n")
        if (!sessionCookie.isNullOrBlank()) {
            sb.append("Set-Cookie: IFGESTOR_SESSION=$sessionCookie; Path=/; HttpOnly; SameSite=Strict\r\n")
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

    // =========================================================================
    // Interface Web SPA Completa (HTML5 + CSS Corporativo Dark/Light + JS AJAX)
    // =========================================================================

    private fun buildSpaHtml(): String {
        return """
<!DOCTYPE html>
<html lang="pt-BR">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>IFGestor Enterprise — Servidor HTTPS & P2P</title>
<style>
:root {
  --bg: #0f172a; --surface: #1e293b; --surface2: #334155;
  --text: #f8fafc; --muted: #94a3b8; --primary: #3b82f6;
  --primary-hover: #2563eb; --success: #10b981; --danger: #ef4444;
  --warning: #f59e0b; --border: #334155;
}
body.light {
  --bg: #f1f5f9; --surface: #ffffff; --surface2: #e2e8f0;
  --text: #0f172a; --muted: #475569; --primary: #2563eb;
  --primary-hover: #1d4ed8; --border: #cbd5e1;
}
* { box-sizing: border-box; margin: 0; padding: 0; font-family: system-ui, -apple-system, sans-serif; }
body { background: var(--bg); color: var(--text); min-height: 100vh; transition: background 0.2s, color 0.2s; }
header {
  background: var(--surface); border-bottom: 1px solid var(--border);
  padding: 12px 20px; display: flex; flex-wrap: wrap; gap: 12px;
  align-items: center; justify-content: space-between; position: sticky; top: 0; z-index: 40;
}
.brand { display: flex; align-items: center; gap: 10px; font-weight: 700; font-size: 1.15rem; }
.badge-tls { background: rgba(16,185,129,0.18); color: var(--success); border: 1px solid var(--success); font-size: 0.72rem; padding: 2px 8px; border-radius: 99px; }
.top-controls { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; }
select, input, button {
  background: var(--surface2); color: var(--text); border: 1px solid var(--border);
  padding: 8px 12px; border-radius: 8px; font-size: 0.9rem; outline: none;
}
button { cursor: pointer; font-weight: 600; transition: 0.15s; }
button.btn-primary { background: var(--primary); color: #fff; border-color: var(--primary); }
button.btn-primary:hover { background: var(--primary-hover); }
button.btn-success { background: var(--success); color: #fff; border-color: var(--success); }
button.btn-danger { background: var(--danger); color: #fff; border-color: var(--danger); }
button.btn-warning { background: var(--warning); color: #fff; border-color: var(--warning); }
main { max-width: 1280px; margin: 0 auto; padding: 20px; padding-bottom: 110px; }
.toolbar {
  background: var(--surface); border: 1px solid var(--border); border-radius: 12px;
  padding: 14px; margin-bottom: 16px; display: flex; flex-wrap: wrap; gap: 10px;
  align-items: center; justify-content: space-between;
}
.folder-pills { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 16px; }
.pill {
  padding: 7px 14px; border-radius: 99px; background: var(--surface);
  border: 1px solid var(--border); cursor: pointer; font-size: 0.85rem; font-weight: 600;
}
.pill.active { background: var(--primary); color: #fff; border-color: var(--primary); }
.grid {
  display: grid; grid-template-columns: repeat(auto-fill, minmax(195px, 1fr)); gap: 14px;
}
.card {
  background: var(--surface); border: 2px solid var(--border); border-radius: 12px;
  overflow: hidden; position: relative; display: flex; flex-direction: column;
}
.card.selected { border-color: var(--primary); box-shadow: 0 0 0 2px rgba(59,130,246,0.35); }
.thumb-wrap {
  width: 100%; height: 155px; background: #000; position: relative; cursor: pointer;
  display: flex; align-items: center; justify-content: center; overflow: hidden;
}
.thumb-wrap img { width: 100%; height: 100%; object-fit: cover; }
.card-check {
  position: absolute; top: 8px; left: 8px; width: 22px; height: 22px; z-index: 5; cursor: pointer;
}
.lock-badge {
  position: absolute; top: 8px; right: 8px; background: var(--warning); color: #000;
  font-size: 0.7rem; font-weight: 700; padding: 2px 6px; border-radius: 6px;
}
.card-body { padding: 10px; font-size: 0.8rem; display: flex; flex-direction: column; gap: 4px; }
.card-title { font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.card-meta { color: var(--muted); font-size: 0.74rem; display: flex; justify-content: space-between; }
.card-actions { display: flex; gap: 4px; margin-top: 6px; }
.card-actions button { flex: 1; padding: 5px; font-size: 0.75rem; }

/* Modais */
.modal-backdrop {
  position: fixed; inset: 0; background: rgba(0,0,0,0.78); z-index: 100;
  display: none; align-items: center; justify-content: center; padding: 16px;
}
.modal-backdrop.show { display: flex; }
.modal {
  background: var(--surface); border: 1px solid var(--border); border-radius: 14px;
  max-width: 480px; width: 100%; padding: 22px; display: flex; flex-direction: column; gap: 14px;
}
.modal h3 { font-size: 1.15rem; }
.modal p { font-size: 0.88rem; color: var(--muted); }

/* Lightbox Tela Cheia */
.lightbox-content {
  max-width: 92vw; max-height: 82vh; border-radius: 8px; background: #000;
}

/* Central de Tarefas Flutuante e Recolhível (Item 10) */
.task-center {
  position: fixed; bottom: 16px; right: 16px; width: 360px; max-width: calc(100vw - 32px);
  background: var(--surface); border: 1px solid var(--border); border-radius: 12px;
  box-shadow: 0 10px 30px rgba(0,0,0,0.45); z-index: 80; overflow: hidden;
}
.task-header {
  background: var(--surface2); padding: 10px 14px; display: flex;
  justify-content: space-between; align-items: center; cursor: pointer; font-weight: 700; font-size: 0.88rem;
}
.task-body { max-height: 260px; overflow-y: auto; padding: 10px; display: flex; flex-direction: column; gap: 8px; }
.task-body.collapsed { display: none; }
.task-item { background: var(--bg); border: 1px solid var(--border); border-radius: 8px; padding: 8px 10px; font-size: 0.78rem; }
.progress-bar { width: 100%; height: 7px; background: var(--surface2); border-radius: 99px; overflow: hidden; margin-top: 6px; }
.progress-fill { height: 100%; background: var(--primary); transition: width 0.15s; }
.toast {
  position: fixed; bottom: 20px; left: 20px; background: var(--surface2); color: var(--text);
  border-left: 4px solid var(--primary); padding: 12px 16px; border-radius: 8px;
  z-index: 120; display: none; font-size: 0.88rem; box-shadow: 0 6px 18px rgba(0,0,0,0.35);
}
</style>
</head>
<body>

<header>
  <div class="brand">
    <span>IFGestor Web</span>
    <span class="badge-tls">HTTPS / TLS 2FA</span>
    <span id="SandboxBadge" style="font-size:0.75rem;color:var(--muted);">DCIM/Camera/IFgestor</span>
  </div>
  <div class="top-controls">
    <label style="font-size:0.8rem;color:var(--muted);">Aparelho na Rede:</label>
    <select id="peerSelect" onchange="onSwitchPeerDevice(this.value)">
      <option value="">Carregando dispositivos...</option>
    </select>
    <button onclick="openAuditModal()">Auditoria</button>
    <button onclick="toggleTheme()">Tema</button>
    <button class="btn-danger" onclick="doLogout()">Sair</button>
  </div>
</header>

<main>
  <div id="incomingP2pBanner" style="display:none;background:rgba(245,158,11,0.18);border:1px solid var(--warning);padding:12px;border-radius:10px;margin-bottom:14px;"></div>

  <div class="folder-pills" id="folderPills"></div>

  <div class="toolbar">
    <div style="display:flex;flex-wrap:wrap;gap:8px;align-items:center;">
      <button onclick="toggleSelectAll()">Selecionar Todos</button>
      <button class="btn-primary" onclick="document.getElementById('fileUploadInput').click()">+ Enviar Ficheiros</button>
      <input type="file" id="fileUploadInput" multiple style="display:none" onchange="uploadSelectedFiles(this.files)">
      <button onclick="promptNewSubfolder()">+ Nova Subpasta</button>
    </div>
    <div style="display:flex;flex-wrap:wrap;gap:8px;align-items:center;">
      <span id="selectionCount" style="font-size:0.84rem;color:var(--muted);">0 selecionado(s)</span>
      <button class="btn-primary" onclick="downloadSelectedZip()">Baixar .ZIP</button>
      <button onclick="openMoveModal()">Mover</button>
      <button class="btn-warning" onclick="openP2pModal()">Enviar P2P (2FA)</button>
      <button class="btn-success" onclick="openWhatsAppModal()">WhatsApp</button>
      <button class="btn-danger" onclick="deleteSelectedFiles()">Excluir</button>
    </div>
  </div>

  <div class="grid" id="filesGrid"></div>
</main>

<!-- Central de Tarefas Flutuante e Recolhível (Item 10) -->
<div class="task-center">
  <div class="task-header" onclick="toggleTaskCenter()">
    <span id="taskCenterTitle">Central de Tarefas (0)</span>
    <div style="display:flex;gap:8px;align-items:center;">
      <button style="padding:2px 6px;font-size:0.7rem;" onclick="event.stopPropagation();clearCompletedTasks()">Limpar</button>
      <span id="taskCollapseIcon">▼</span>
    </div>
  </div>
  <div class="task-body" id="taskListContainer">
    <div style="color:var(--muted);font-size:0.78rem;">Nenhuma tarefa ativa no momento.</div>
  </div>
</div>

<!-- Modal Autenticação: Login PIN / Troca PIN 1234 / Verificação 2FA (Itens 1 e 2) -->
<div class="modal-backdrop" id="authModal">
  <div class="modal">
    <h3 id="authTitle">Autenticação Segura IFGestor</h3>
    <p id="authSubtitle">Digite o seu PIN de segurança (enviado de forma oculta via POST HTTPS).</p>

    <div id="stepPin">
      <input type="password" id="loginPinInput" placeholder="PIN (ex: 1234 no 1º acesso)" style="width:100%;margin-bottom:10px;" onkeydown="if(event.key==='Enter')submitPin()">
      <button class="btn-primary" style="width:100%;" onclick="submitPin()">Continuar</button>
    </div>

    <div id="stepChangePin" style="display:none;flex-direction:column;gap:8px;">
      <input type="password" id="currentPinInput" placeholder="PIN Atual (1234)" style="width:100%;">
      <input type="password" id="newPinInput" placeholder="Novo PIN numérico (4 a 8 dígitos, diferente de 1234)" style="width:100%;">
      <button class="btn-warning" style="width:100%;" onclick="submitChangeDefaultPin()">Salvar Novo PIN e Gerar 2FA</button>
    </div>

    <div id="step2fa" style="display:none;flex-direction:column;gap:8px;">
      <input type="text" id="twoFaCodeInput" maxlength="6" placeholder="Código 2FA de 6 dígitos (veja no telemóvel)" style="width:100%;letter-spacing:3px;font-weight:700;text-align:center;" onkeydown="if(event.key==='Enter')submit2fa()">
      <button class="btn-success" style="width:100%;" onclick="submit2fa()">Validar 2FA e Acessar</button>
    </div>

    <div id="authError" style="color:var(--danger);font-size:0.82rem;"></div>
  </div>
</div>

<!-- Modal Transferência P2P com Duplo 2FA (Item 8) -->
<div class="modal-backdrop" id="p2pModal">
  <div class="modal">
    <h3>Transferência P2P entre Telemóveis (Duplo 2FA)</h3>
    <p id="p2pStatusText">Selecione o telemóvel de destino na mesma rede Wi-Fi.</p>
    <div id="p2pStep1" style="display:flex;flex-direction:column;gap:8px;">
      <label style="font-size:0.8rem;">Telemóvel de Destino:</label>
      <select id="p2pTargetPeerSelect"></select>
      <label style="font-size:0.8rem;">Subpasta no Destino:</label>
      <input type="text" id="p2pTargetFolderInput" value="Geral">
      <button class="btn-primary" onclick="startP2pHandshake()">1. Gerar 2FA no Telemóvel de Origem</button>
    </div>
    <div id="p2pStep2" style="display:none;flex-direction:column;gap:8px;">
      <input type="text" id="p2pSourceCodeInput" placeholder="Código 2FA do Telemóvel de ORIGEM">
      <button class="btn-warning" onclick="confirmP2pSource()">2. Confirmar Origem e Solicitar Destino</button>
    </div>
    <div id="p2pStep3" style="display:none;flex-direction:column;gap:8px;">
      <input type="text" id="p2pReceiverCodeInput" placeholder="Código 2FA do Telemóvel de DESTINO">
      <button class="btn-success" onclick="confirmP2pReceiver()">3. Confirmar Destino e Iniciar Envio</button>
    </div>
    <button onclick="closeModal('p2pModal')">Fechar</button>
  </div>
</div>

<!-- Modal Mover Ficheiros -->
<div class="modal-backdrop" id="moveModal">
  <div class="modal">
    <h3>Mover Ficheiros Selecionados</h3>
    <p>Escolha a subpasta de destino dentro do Sandbox DCIM/Camera/IFgestor:</p>
    <select id="moveTargetSubfolderSelect"></select>
    <div style="display:flex;gap:8px;">
      <button class="btn-primary" style="flex:1;" onclick="confirmMoveFiles()">Mover Agora</button>
      <button onclick="closeModal('moveModal')">Cancelar</button>
    </div>
  </div>
</div>

<!-- Modal WhatsApp Web & Disparo Remoto (Item 16) -->
<div class="modal-backdrop" id="whatsappModal">
  <div class="modal">
    <h3>Compartilhamento Integrado WhatsApp (2FA Ativo)</h3>
    <p>Escolha como deseja enviar os ficheiros selecionados:</p>
    <button class="btn-success" onclick="copySelectedImageToClipboardAndOpenWa()">1. Copiar Imagem para Área de Transferência (Ctrl+V no WhatsApp Web)</button>
    <button class="btn-primary" onclick="shareViaBrowserWebShare()">2. Compartilhar via Web Share API do Navegador</button>
    <button class="btn-warning" onclick="triggerNativePhoneWhatsApp()">3. Abrir Compartilhamento Direto no Telemóvel (FileProvider)</button>
    <button onclick="closeModal('whatsappModal')">Fechar</button>
  </div>
</div>

<!-- Modal Trilha de Auditoria (Item 4) -->
<div class="modal-backdrop" id="auditModal">
  <div class="modal" style="max-width:740px;">
    <h3>Trilha de Auditoria e Segurança (Audit Log)</h3>
    <div id="auditListContainer" style="max-height:360px;overflow-y:auto;display:flex;flex-direction:column;gap:6px;font-size:0.78rem;"></div>
    <button onclick="closeModal('auditModal')">Fechar</button>
  </div>
</div>

<!-- Modal Lightbox Visualizador em Tela Cheia -->
<div class="modal-backdrop" id="lightboxModal">
  <div style="display:flex;flex-direction:column;align-items:center;gap:12px;">
    <div id="lightboxMediaWrap"></div>
    <div style="display:flex;gap:10px;flex-wrap:wrap;justify-content:center;">
      <button onclick="lightboxPrev()">◀ Anterior</button>
      <button class="btn-success" onclick="copyCurrentLightboxToWhatsApp()">Copiar p/ WhatsApp Web (Ctrl+V)</button>
      <button class="btn-primary" onclick="downloadCurrentLightbox()">Baixar</button>
      <button onclick="lightboxNext()">Próximo ▶</button>
      <button class="btn-danger" onclick="closeModal('lightboxModal')">Fechar</button>
    </div>
  </div>
</div>

<div class="toast" id="toastBox"></div>

<script>
let sessionToken = localStorage.getItem('ifgestor_token') || '';
let activeSubfolder = 'Geral';
let currentFiles = [];
let selectedSet = new Set();
let peersList = [];
let activeP2pTransferId = '';
let lightboxIndex = 0;
let localUploadTasks = {};

function showToast(msg) {
  const box = document.getElementById('toastBox');
  box.textContent = msg;
  box.style.display = 'block';
  clearTimeout(window._toastTimer);
  window._toastTimer = setTimeout(() => { box.style.display = 'none'; }, 3800);
}

function toggleTheme() {
  document.body.classList.toggle('light');
}

function closeModal(id) {
  document.getElementById(id).classList.remove('show');
}

async function apiFetch(path, options = {}) {
  const headers = options.headers || {};
  if (sessionToken) headers['X-Session-Token'] = sessionToken;
  if (options.body && typeof options.body === 'object' && !(options.body instanceof Blob)) {
    headers['Content-Type'] = 'application/json; charset=utf-8';
    options.body = JSON.stringify(options.body);
  }
  options.headers = headers;
  const resp = await fetch(path, options);
  const data = await resp.json().catch(() => ({ ok: false, error: 'Erro de comunicação' }));
  if (resp.status === 401 && !path.startsWith('/api/login') && !path.startsWith('/api/verify-2fa')) {
    openAuthModal();
  }
  return { status: resp.status, data };
}

async function checkInitialAuth() {
  const { data } = await apiFetch('/api/auth/status');
  if (!data.authenticated) {
    openAuthModal();
  } else {
    closeModal('authModal');
    await refreshFilesAjax();
    await pollTasksAndPeers();
  }
}

function openAuthModal() {
  document.getElementById('authError').textContent = '';
  document.getElementById('stepPin').style.display = 'block';
  document.getElementById('stepChangePin').style.display = 'none';
  document.getElementById('step2fa').style.display = 'none';
  document.getElementById('authModal').classList.add('show');
}

async function submitPin() {
  const pin = document.getElementById('loginPinInput').value.trim();
  const { status, data } = await apiFetch('/api/login', { method: 'POST', body: { pin } });
  if (!data.ok) {
    document.getElementById('authError').textContent = data.error || 'Falha no login.';
    return;
  }
  sessionToken = data.sessionToken;
  localStorage.setItem('ifgestor_token', sessionToken);
  document.getElementById('authError').textContent = '';

  if (data.mustChangePin) {
    document.getElementById('authTitle').textContent = 'Troca Obrigatória do PIN Padrão (1234)';
    document.getElementById('authSubtitle').textContent = data.message;
    document.getElementById('currentPinInput').value = pin;
    document.getElementById('stepPin').style.display = 'none';
    document.getElementById('stepChangePin').style.display = 'flex';
  } else {
    document.getElementById('authTitle').textContent = 'Verificação em Duas Etapas (2FA)';
    document.getElementById('authSubtitle').textContent = data.message;
    document.getElementById('stepPin').style.display = 'none';
    document.getElementById('step2fa').style.display = 'flex';
  }
}

async function submitChangeDefaultPin() {
  const currentPin = document.getElementById('currentPinInput').value.trim();
  const newPin = document.getElementById('newPinInput').value.trim();
  const { data } = await apiFetch('/api/change-pin', { method: 'POST', body: { currentPin, newPin } });
  if (!data.ok) {
    document.getElementById('authError').textContent = data.error || 'Não foi possível alterar o PIN.';
    return;
  }
  document.getElementById('authError').textContent = '';
  document.getElementById('authTitle').textContent = 'Confirme o Código 2FA';
  document.getElementById('authSubtitle').textContent = data.message;
  document.getElementById('stepChangePin').style.display = 'none';
  document.getElementById('step2fa').style.display = 'flex';
}

async function submit2fa() {
  const code = document.getElementById('twoFaCodeInput').value.trim();
  const { data } = await apiFetch('/api/verify-2fa', { method: 'POST', body: { code } });
  if (!data.ok) {
    document.getElementById('authError').textContent = data.error || 'Código 2FA incorreto.';
    return;
  }
  closeModal('authModal');
  showToast('Sessão autenticada com 2FA!');
  await refreshFilesAjax();
  await pollTasksAndPeers();
}

async function doLogout() {
  await apiFetch('/api/logout', { method: 'POST' });
  sessionToken = '';
  localStorage.removeItem('ifgestor_token');
  openAuthModal();
}

// Listagem Dinâmica SPA/AJAX (Sem recarregar a página - Item 9)
async function refreshFilesAjax(subfolder = activeSubfolder) {
  const { data } = await apiFetch('/api/files?subfolder=' + encodeURIComponent(subfolder));
  if (!data.ok) return;
  activeSubfolder = data.activeSubfolder;
  currentFiles = data.files || [];

  // Atualiza pílulas de subpastas
  const pillsWrap = document.getElementById('folderPills');
  pillsWrap.innerHTML = '';
  (data.subfolders || []).forEach(sub => {
    const pill = document.createElement('div');
    pill.className = 'pill' + (sub === activeSubfolder ? ' active' : '');
    pill.textContent = '📁 ' + sub;
    pill.onclick = () => { selectedSet.clear(); refreshFilesAjax(sub); };
    pillsWrap.appendChild(pill);
  });

  // Atualiza selects de subpasta em modais
  const moveSelect = document.getElementById('moveTargetSubfolderSelect');
  moveSelect.innerHTML = '';
  (data.subfolders || []).forEach(sub => {
    const opt = document.createElement('option');
    opt.value = sub; opt.textContent = sub;
    moveSelect.appendChild(opt);
  });

  renderFilesGrid();
}

function renderFilesGrid() {
  const grid = document.getElementById('filesGrid');
  grid.innerHTML = '';
  document.getElementById('selectionCount').textContent = selectedSet.size + ' selecionado(s)';

  if (currentFiles.length === 0) {
    grid.innerHTML = '<div style="grid-column:1/-1;padding:40px;text-align:center;color:var(--muted);">Nenhum ficheiro nesta subpasta do Sandbox.</div>';
    return;
  }

  currentFiles.forEach((f, idx) => {
    const isSel = selectedSet.has(f.name);
    const card = document.createElement('div');
    card.className = 'card' + (isSel ? ' selected' : '');

    const thumbUrl = '/api/thumb?subfolder=' + encodeURIComponent(activeSubfolder) + '&name=' + encodeURIComponent(f.name) + '&sessionToken=' + encodeURIComponent(sessionToken);

    card.innerHTML = `
      <div class="thumb-wrap" onclick="openLightbox(${idx})">
        <input type="checkbox" class="card-check" ${isSel ? 'checked' : ''} onclick="event.stopPropagation();toggleFileSelect('${f.name.replace(/'/g, "\\'")}')">
        ${f.locked ? '<span class="lock-badge">EM TRÂNSITO</span>' : ''}
        <img src="${thumbUrl}" loading="lazy" alt="${f.name}">
      </div>
      <div class="card-body">
        <div class="card-title" title="${f.name}">${f.name}</div>
        <div class="card-meta"><span>${f.formattedSize}</span><span>${f.formattedDate}</span></div>
        <div class="card-actions">
          <button onclick="copySingleImageToWhatsApp('${f.name.replace(/'/g, "\\'")}')">WhatsApp</button>
          <button onclick="window.location='/api/file?subfolder=${encodeURIComponent(activeSubfolder)}&name=${encodeURIComponent(f.name)}&download=1&sessionToken=${encodeURIComponent(sessionToken)}'">Baixar</button>
        </div>
      </div>
    `;
    grid.appendChild(card);
  });
}

function toggleFileSelect(name) {
  if (selectedSet.has(name)) selectedSet.delete(name);
  else selectedSet.add(name);
  renderFilesGrid();
}

function toggleSelectAll() {
  if (selectedSet.size === currentFiles.length) selectedSet.clear();
  else currentFiles.forEach(f => selectedSet.add(f.name));
  renderFilesGrid();
}

async function promptNewSubfolder() {
  const folderName = prompt('Nome da nova subpasta dentro de DCIM/Camera/IFgestor:');
  if (!folderName) return;
  const { data } = await apiFetch('/api/subfolder/create', { method: 'POST', body: { folderName, setAsActive: true } });
  if (data.ok) {
    showToast('Subpasta criada: ' + data.folderName);
    await refreshFilesAjax(data.folderName);
  }
}

// Upload Paralelo Assíncrono com Progresso Individual sem recarregar a página (Itens 9, 10 e 11)
function uploadSelectedFiles(fileList) {
  if (!fileList || fileList.length === 0) return;
  Array.from(fileList).forEach(file => {
    const localId = 'up_' + Math.random().toString(36).substring(2, 8);
    localUploadTasks[localId] = {
      id: localId, title: 'Enviando ' + file.name, subtitle: '0%', progressPercent: 0, status: 'RUNNING'
    };

    const xhr = new XMLHttpRequest();
    const url = '/api/upload?subfolder=' + encodeURIComponent(activeSubfolder) + '&fileName=' + encodeURIComponent(file.name);
    xhr.open('POST', url, true);
    xhr.setRequestHeader('X-Session-Token', sessionToken);

    xhr.upload.onprogress = (e) => {
      if (e.lengthComputable) {
        const pct = Math.round((e.loaded * 100) / e.total);
        localUploadTasks[localId].progressPercent = pct;
        localUploadTasks[localId].subtitle = pct + '% enviado';
      }
    };
    xhr.onload = () => {
      delete localUploadTasks[localId];
      refreshFilesAjax(activeSubfolder);
    };
    xhr.onerror = () => {
      localUploadTasks[localId].status = 'FAILED';
      localUploadTasks[localId].subtitle = 'Falha no envio';
    };
    xhr.send(file);
  });
  document.getElementById('fileUploadInput').value = '';
}

function downloadSelectedZip() {
  const names = selectedSet.size > 0 ? Array.from(selectedSet).join(',') : 'ALL';
  const url = '/api/download-zip?subfolder=' + encodeURIComponent(activeSubfolder) +
              '&files=' + encodeURIComponent(names) +
              '&sessionToken=' + encodeURIComponent(sessionToken);
  showToast('Iniciando download .ZIP via streaming...');
  window.location.href = url;
}

function openMoveModal() {
  if (selectedSet.size === 0) { showToast('Selecione ao menos 1 ficheiro.'); return; }
  document.getElementById('moveModal').classList.add('show');
}

async function confirmMoveFiles() {
  const targetSubfolder = document.getElementById('moveTargetSubfolderSelect').value;
  const { data } = await apiFetch('/api/move', {
    method: 'POST',
    body: { sourceSubfolder: activeSubfolder, targetSubfolder, fileNames: Array.from(selectedSet) }
  });
  closeModal('moveModal');
  if (data.ok) {
    selectedSet.clear();
    showToast(data.movedCount + ' ficheiro(s) movido(s)!');
    await refreshFilesAjax(activeSubfolder);
  }
}

async function deleteSelectedFiles() {
  if (selectedSet.size === 0) { showToast('Selecione ao menos 1 ficheiro.'); return; }
  if (!confirm('Deseja excluir permanentemente ' + selectedSet.size + ' ficheiro(s)?')) return;
  const { data } = await apiFetch('/api/delete', {
    method: 'POST',
    body: { subfolder: activeSubfolder, fileNames: Array.from(selectedSet) }
  });
  if (data.ok) {
    selectedSet.clear();
    showToast(data.deletedCount + ' ficheiro(s) excluído(s).');
    await refreshFilesAjax(activeSubfolder);
  }
}

// Transferência P2P com Duplo 2FA (Item 8)
function openP2pModal() {
  if (selectedSet.size === 0) { showToast('Selecione os ficheiros que deseja transferir via P2P.'); return; }
  const sel = document.getElementById('p2pTargetPeerSelect');
  sel.innerHTML = '';
  const remotePeers = peersList.filter(p => !p.isSelf);
  if (remotePeers.length === 0) {
    const opt = document.createElement('option');
    opt.value = ''; opt.textContent = 'Nenhum outro telemóvel IFGestor encontrado na rede';
    sel.appendChild(opt);
  } else {
    remotePeers.forEach(p => {
      const opt = document.createElement('option');
      opt.value = JSON.stringify(p);
      opt.textContent = p.nickname + ' (' + p.hostname + ' / ' + p.ipAddress + ')';
      sel.appendChild(opt);
    });
  }
  document.getElementById('p2pTargetFolderInput').value = activeSubfolder;
  document.getElementById('p2pStep1').style.display = 'flex';
  document.getElementById('p2pStep2').style.display = 'none';
  document.getElementById('p2pStep3').style.display = 'none';
  document.getElementById('p2pModal').classList.add('show');
}

async function startP2pHandshake() {
  const rawPeer = document.getElementById('p2pTargetPeerSelect').value;
  if (!rawPeer) { showToast('Nenhum telemóvel de destino disponível.'); return; }
  const peer = JSON.parse(rawPeer);
  const targetSubfolder = document.getElementById('p2pTargetFolderInput').value.trim() || 'Geral';

  const { data } = await apiFetch('/api/p2p/initiate', {
    method: 'POST',
    body: {
      sourceSubfolder: activeSubfolder,
      targetSubfolder,
      peerIp: peer.ipAddress,
      peerPort: peer.port,
      peerNickname: peer.nickname,
      fileNames: Array.from(selectedSet)
    }
  });
  if (!data.ok) { showToast(data.error || 'Erro ao iniciar P2P'); return; }
  activeP2pTransferId = data.transferId;
  document.getElementById('p2pStatusText').textContent = data.message;
  document.getElementById('p2pStep1').style.display = 'none';
  document.getElementById('p2pStep2').style.display = 'flex';
}

async function confirmP2pSource() {
  const code = document.getElementById('p2pSourceCodeInput').value.trim();
  const { data } = await apiFetch('/api/p2p/confirm-source', {
    method: 'POST',
    body: { transferId: activeP2pTransferId, code }
  });
  if (!data.ok) { showToast(data.error || 'Código 2FA de origem inválido'); return; }
  document.getElementById('p2pStatusText').textContent = data.message;
  document.getElementById('p2pStep2').style.display = 'none';
  document.getElementById('p2pStep3').style.display = 'flex';
}

async function confirmP2pReceiver() {
  const code = document.getElementById('p2pReceiverCodeInput').value.trim();
  const { data } = await apiFetch('/api/p2p/confirm-receiver', {
    method: 'POST',
    body: { transferId: activeP2pTransferId, code }
  });
  if (!data.ok) { showToast(data.error || 'Código 2FA de destino inválido'); return; }
  showToast(data.message);
  closeModal('p2pModal');
  selectedSet.clear();
  renderFilesGrid();
}

async function approveIncomingP2pPrompt(transferId, senderName) {
  const code = prompt('Digite o código 2FA exibido na notificação deste telemóvel para aceitar ficheiros de ' + senderName + ':');
  if (!code) return;
  const { data } = await apiFetch('/api/p2p/approve-incoming', {
    method: 'POST',
    body: { transferId, code }
  });
  if (data.ok) showToast('Recebimento P2P autorizado! Aguarde o término na Central de Tarefas.');
  else showToast(data.error || 'Código 2FA inválido.');
}

// Compartilhamento Integrado WhatsApp Web e Disparo Remoto (Item 16)
function openWhatsAppModal() {
  if (selectedSet.size === 0) { showToast('Selecione ao menos 1 ficheiro.'); return; }
  document.getElementById('whatsappModal').classList.add('show');
}

async function copyImageBlobToClipboard(fileName) {
  const url = '/api/file?subfolder=' + encodeURIComponent(activeSubfolder) + '&name=' + encodeURIComponent(fileName) + '&sessionToken=' + encodeURIComponent(sessionToken);
  const img = new Image();
  img.crossOrigin = 'anonymous';
  await new Promise((res, rej) => { img.onload = res; img.onerror = rej; img.src = url; });
  const canvas = document.createElement('canvas');
  canvas.width = img.naturalWidth; canvas.height = img.naturalHeight;
  canvas.getContext('2d').drawImage(img, 0, 0);
  const pngBlob = await new Promise(r => canvas.toBlob(r, 'image/png'));
  await navigator.clipboard.write([new ClipboardItem({ 'image/png': pngBlob })]);
}

async function copySingleImageToWhatsApp(fileName) {
  try {
    await copyImageBlobToClipboard(fileName);
    showToast('Imagem copiada! Pressione Ctrl+V na conversa do WhatsApp Web.');
    window.open('https://web.whatsapp.com/', '_blank');
  } catch (e) {
    showToast('Use o botão de disparo no telemóvel ou baixe o ficheiro.');
  }
}

async function copySelectedImageToClipboardAndOpenWa() {
  const first = Array.from(selectedSet)[0];
  closeModal('whatsappModal');
  if (first) await copySingleImageToWhatsApp(first);
}

async function shareViaBrowserWebShare() {
  try {
    const filesToShare = [];
    for (const name of Array.from(selectedSet)) {
      const url = '/api/file?subfolder=' + encodeURIComponent(activeSubfolder) + '&name=' + encodeURIComponent(name) + '&sessionToken=' + encodeURIComponent(sessionToken);
      const resp = await fetch(url);
      const blob = await resp.blob();
      filesToShare.push(new File([blob], name, { type: blob.type || 'image/jpeg' }));
    }
    if (navigator.canShare && navigator.canShare({ files: filesToShare })) {
      await navigator.share({ title: 'IFGestor Media', files: filesToShare });
      closeModal('whatsappModal');
    } else {
      showToast('Web Share API não suportada neste navegador de PC. Use a opção 1 (Ctrl+V) ou 3.');
    }
  } catch (e) {
    showToast('Compartilhamento cancelado ou indisponível.');
  }
}

async function triggerNativePhoneWhatsApp() {
  const { data } = await apiFetch('/api/whatsapp-trigger', {
    method: 'POST',
    body: { subfolder: activeSubfolder, fileNames: Array.from(selectedSet) }
  });
  closeModal('whatsappModal');
  if (data.ok) showToast('WhatsApp aberto na tela do telemóvel com ' + data.sharedCount + ' ficheiro(s)!');
  else showToast(data.error || 'Erro ao acionar WhatsApp no telemóvel.');
}

// Lightbox Tela Cheia
function openLightbox(idx) {
  lightboxIndex = idx;
  renderLightboxMedia();
  document.getElementById('lightboxModal').classList.add('show');
}

function renderLightboxMedia() {
  const f = currentFiles[lightboxIndex];
  if (!f) return;
  const wrap = document.getElementById('lightboxMediaWrap');
  const url = '/api/file?subfolder=' + encodeURIComponent(activeSubfolder) + '&name=' + encodeURIComponent(f.name) + '&sessionToken=' + encodeURIComponent(sessionToken);
  if (f.isVideo) {
    wrap.innerHTML = `<video class="lightbox-content" src="${url}" controls autoplay></video>`;
  } else {
    wrap.innerHTML = `<img class="lightbox-content" src="${url}" alt="${f.name}">`;
  }
}

function lightboxPrev() {
  if (currentFiles.length === 0) return;
  lightboxIndex = (lightboxIndex - 1 + currentFiles.length) % currentFiles.length;
  renderLightboxMedia();
}

function lightboxNext() {
  if (currentFiles.length === 0) return;
  lightboxIndex = (lightboxIndex + 1) % currentFiles.length;
  renderLightboxMedia();
}

function copyCurrentLightboxToWhatsApp() {
  const f = currentFiles[lightboxIndex];
  if (f) copySingleImageToWhatsApp(f.name);
}

function downloadCurrentLightbox() {
  const f = currentFiles[lightboxIndex];
  if (!f) return;
  window.location = '/api/file?subfolder=' + encodeURIComponent(activeSubfolder) + '&name=' + encodeURIComponent(f.name) + '&download=1&sessionToken=' + encodeURIComponent(sessionToken);
}

// Auditoria (Item 4)
async function openAuditModal() {
  const { data } = await apiFetch('/api/audit');
  const container = document.getElementById('auditListContainer');
  container.innerHTML = '';
  (data.logs || []).forEach(item => {
    const row = document.createElement('div');
    row.style.padding = '8px';
    row.style.borderRadius = '6px';
    row.style.background = 'var(--bg)';
    row.style.borderLeft = '3px solid ' + (item.success ? 'var(--success)' : 'var(--danger)');
    row.innerHTML = `<strong>[${item.formattedTime}] ${item.action}</strong> — <em>${item.actor}</em><br>${item.details}`;
    container.appendChild(row);
  });
  document.getElementById('auditModal').classList.add('show');
}

// Seletor Multi-Dispositivo (Item 7) e Central de Tarefas (Item 10)
function onSwitchPeerDevice(url) {
  if (url && url !== window.location.origin) {
    window.location.href = url;
  }
}

function toggleTaskCenter() {
  const body = document.getElementById('taskListContainer');
  body.classList.toggle('collapsed');
}

async function clearCompletedTasks() {
  await apiFetch('/api/tasks/clear', { method: 'POST' });
  await pollTasksAndPeers();
}

async function pollTasksAndPeers() {
  if (!sessionToken) return;
  try {
    const { status, data } = await apiFetch('/api/tasks');
    if (status !== 200 || !data.ok) return;

    peersList = data.peers || [];
    const peerSelect = document.getElementById('peerSelect');
    peerSelect.innerHTML = '';
    peersList.forEach(p => {
      const opt = document.createElement('option');
      opt.value = p.httpsUrl;
      opt.selected = p.isSelf;
      opt.textContent = p.nickname + ' — ' + p.hostname + ' (' + p.ipAddress + ')';
      peerSelect.appendChild(opt);
    });

    // Banner de P2P Recebido aguardando 2FA
    const incoming = data.pendingIncomingP2p || [];
    const banner = document.getElementById('incomingP2pBanner');
    if (incoming.length > 0 && !incoming[0].receiver2faVerified) {
      const inc = incoming[0];
      banner.style.display = 'block';
      banner.innerHTML = `<strong>📲 Recebimento P2P Pendente:</strong> <b>${inc.senderNickname}</b> (${inc.senderIp}) deseja enviar ${inc.fileCount} ficheiro(s) para a pasta <b>${inc.targetSubfolder}</b>.
        <button class="btn-warning" style="margin-left:10px;" onclick="approveIncomingP2pPrompt('${inc.transferId}','${inc.senderNickname}')">Digitar 2FA de Destino</button>`;
    } else {
      banner.style.display = 'none';
    }

    // Atualiza Central de Tarefas
    const serverTasks = data.tasks || [];
    const allTasks = [...Object.values(localUploadTasks), ...serverTasks];
    document.getElementById('taskCenterTitle').textContent = 'Central de Tarefas (' + allTasks.length + ')';
    const listWrap = document.getElementById('taskListContainer');

    if (allTasks.length === 0) {
      listWrap.innerHTML = '<div style="color:var(--muted);font-size:0.78rem;">Nenhuma tarefa ativa no momento.</div>';
    } else {
      listWrap.innerHTML = '';
      allTasks.forEach(t => {
        const div = document.createElement('div');
        div.className = 'task-item';
        div.innerHTML = `
          <div style="display:flex;justify-content:space-between;font-weight:600;">
            <span>${t.title}</span><span>${t.progressPercent}%</span>
          </div>
          <div style="color:var(--muted);margin-top:2px;">${t.subtitle}</div>
          <div class="progress-bar"><div class="progress-fill" style="width:${t.progressPercent}%"></div></div>
        `;
        listWrap.appendChild(div);
      });
    }
  } catch (_e) {}
}

setInterval(pollTasksAndPeers, 1500);
checkInitialAuth();
</script>
</body>
</html>
        """.trimIndent()
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
