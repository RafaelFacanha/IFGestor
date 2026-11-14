package com.ifgestor.app

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection

class P2PTransferManager private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val settings = SettingsManager(appContext)
    private val security = SecurityManager.getInstance(appContext)
    private val audit = AuditLogManager.getInstance(appContext)
    private val taskManager = FileLockAndTaskManager.getInstance(appContext)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Transferências de saída (neste aparelho enviando para outro celular)
    private val outgoingSessions = ConcurrentHashMap<String, OutgoingP2pSession>()

    // Transferências de entrada (outro celular enviando para este aparelho)
    private val incomingSessions = ConcurrentHashMap<String, IncomingP2pSession>()

    private val _pendingIncomingFlow = MutableStateFlow<List<IncomingP2pSession>>(emptyList())
    val pendingIncomingFlow: StateFlow<List<IncomingP2pSession>> = _pendingIncomingFlow.asStateFlow()

    data class OutgoingP2pSession(
        val transferId: String,
        val peerIp: String,
        val peerPort: Int,
        val peerNickname: String,
        val targetSubfolder: String,
        val files: List<File>,
        val totalBytes: Long,
        val taskId: String,
        @Volatile var source2faVerified: Boolean = false,
        @Volatile var receiver2faVerified: Boolean = false,
        @Volatile var receiverAuthToken: String? = null,
        @Volatile var state: String = "WAITING_SOURCE_2FA" // WAITING_SOURCE_2FA, WAITING_RECEIVER_2FA, TRANSFERRING, COMPLETED, FAILED
    )

    data class IncomingP2pSession(
        val transferId: String,
        val senderNickname: String,
        val senderIp: String,
        val targetSubfolder: String,
        val fileCount: Int,
        val totalBytes: Long,
        val taskId: String,
        val authToken: String = UUID.randomUUID().toString(),
        @Volatile var receiver2faVerified: Boolean = false,
        @Volatile var receivedFilesCount: Int = 0,
        val createdAt: Long = System.currentTimeMillis()
    )

    // =========================================================================
    // LADO REMETENTE (CELULAR DE ORIGEM): Início e Duplo 2FA
    // =========================================================================

    /**
     * Passo 1 do envio P2P: valida os arquivos no Sandbox, gera o 2FA de saída no celular de origem
     * e registra a tarefa na Central de Tarefas.
     */
    fun initiateOutgoingTransfer(
        files: List<File>,
        peerIp: String,
        peerPort: Int,
        peerNickname: String,
        targetSubfolder: String
    ): Result<OutgoingP2pSession> {
        val validFiles = files.filter { it.exists() && it.isFile && settings.isInsideSandbox(it) }
        if (validFiles.isEmpty()) {
            return Result.failure(IllegalArgumentException("Nenhum arquivo válido selecionado no Sandbox."))
        }

        val totalBytes = validFiles.sumOf { it.length() }
        val transferId = UUID.randomUUID().toString().take(10)

        val task = taskManager.createTask(
            type = FileLockAndTaskManager.TaskType.P2P_SEND,
            title = "Envio P2P -> $peerNickname (${validFiles.size} arq.)",
            subtitle = "Aguardando 2FA no celular de origem...",
            totalBytes = totalBytes,
            totalItems = validFiles.size,
            initialStatus = FileLockAndTaskManager.TaskStatus.WAITING_2FA
        )

        security.createP2pChallenge(
            transferId = transferId,
            peerName = peerNickname,
            peerIp = peerIp,
            direction = "OUTGOING"
        )

        val session = OutgoingP2pSession(
            transferId = transferId,
            peerIp = peerIp,
            peerPort = peerPort,
            peerNickname = peerNickname,
            targetSubfolder = settings.sanitizeFolderName(targetSubfolder).ifBlank { "Geral" },
            files = validFiles,
            totalBytes = totalBytes,
            taskId = task.id
        )

        outgoingSessions[transferId] = session
        return Result.success(session)
    }

    /**
     * Passo 2 do envio P2P: valida o código 2FA do celular de origem e dispara o handshake HTTPS
     * para que o celular de destino exiba seu próprio código 2FA.
     */
    fun confirmSource2faAndRequestReceiver(
        transferId: String,
        source2faCode: String,
        onResult: (Result<String>) -> Unit
    ) {
        val session = outgoingSessions[transferId]
        if (session == null) {
            onResult(Result.failure(IllegalArgumentException("Sessão P2P não encontrada.")))
            return
        }

        if (!security.verifyP2pChallenge(transferId, source2faCode)) {
            onResult(Result.failure(SecurityException("Código 2FA do celular de origem incorreto ou expirado.")))
            return
        }

        session.source2faVerified = true
        session.state = "WAITING_RECEIVER_2FA"
        taskManager.updateTaskProgress(
            taskId = session.taskId,
            subtitle = "Origem aprovada. Solicitando 2FA no destino (${session.peerNickname})...",
            status = FileLockAndTaskManager.TaskStatus.WAITING_2FA
        )

        scope.launch {
            try {
                val payload = JSONObject().apply {
                    put("transferId", session.transferId)
                    put("senderNickname", settings.getDeviceNickname())
                    put("targetSubfolder", session.targetSubfolder)
                    put("fileCount", session.files.size)
                    put("totalBytes", session.totalBytes)
                }

                val response = postJsonToPeer(
                    peerIp = session.peerIp,
                    peerPort = session.peerPort,
                    path = "/api/p2p/handshake",
                    jsonBody = payload
                )

                if (response.optBoolean("ok", false)) {
                    taskManager.updateTaskProgress(
                        taskId = session.taskId,
                        subtitle = "Aguardando código 2FA do celular de destino (${session.peerNickname})...",
                        status = FileLockAndTaskManager.TaskStatus.WAITING_2FA
                    )
                    onResult(Result.success("2FA enviado ao celular de destino (${session.peerNickname})."))
                } else {
                    val err = response.optString("error", "Recusado pelo dispositivo de destino.")
                    session.state = "FAILED"
                    taskManager.failTask(session.taskId, "Falha no handshake: $err")
                    onResult(Result.failure(IllegalStateException(err)))
                }
            } catch (e: Exception) {
                session.state = "FAILED"
                taskManager.failTask(session.taskId, "Erro de conexão com ${session.peerNickname}: ${e.message}")
                onResult(Result.failure(e))
            }
        }
    }

    /**
     * Passo 3 do envio P2P: confirma o 2FA do celular de destino (ou verifica se o receptor já aprovou na tela dele)
     * e inicia imediatamente o streaming HTTPS dos arquivos.
     */
    fun confirmReceiver2faAndStartStreaming(
        transferId: String,
        receiver2faCode: String,
        onResult: (Result<String>) -> Unit
    ) {
        val session = outgoingSessions[transferId]
        if (session == null) {
            onResult(Result.failure(IllegalArgumentException("Sessão P2P não encontrada.")))
            return
        }
        if (!session.source2faVerified) {
            onResult(Result.failure(SecurityException("O 2FA do celular de origem ainda não foi validado.")))
            return
        }

        scope.launch {
            try {
                val payload = JSONObject().apply {
                    put("transferId", session.transferId)
                    put("code", receiver2faCode.trim())
                }

                val response = postJsonToPeer(
                    peerIp = session.peerIp,
                    peerPort = session.peerPort,
                    path = "/api/p2p/verify-receiver",
                    jsonBody = payload
                )

                if (!response.optBoolean("ok", false)) {
                    val msg = response.optString("error", "Código 2FA de destino inválido.")
                    onResult(Result.failure(SecurityException(msg)))
                    return@launch
                }

                val authToken = response.optString("authToken", "")
                if (authToken.isBlank()) {
                    onResult(Result.failure(SecurityException("Token de autorização P2P não recebido.")))
                    return@launch
                }

                session.receiver2faVerified = true
                session.receiverAuthToken = authToken
                session.state = "TRANSFERRING"

                onResult(Result.success("Duplo 2FA confirmado! Transferência iniciada."))
                executeFileStreaming(session)
            } catch (e: Exception) {
                onResult(Result.failure(e))
            }
        }
    }

    private fun executeFileStreaming(session: OutgoingP2pSession) {
        scope.launch {
            var bytesSentTotal = 0L
            var filesSentCount = 0

            taskManager.updateTaskProgress(
                taskId = session.taskId,
                processedBytes = 0L,
                processedItems = 0,
                subtitle = "Iniciando envio criptografado...",
                status = FileLockAndTaskManager.TaskStatus.RUNNING
            )

            for ((index, file) in session.files.withIndex()) {
                if (!taskManager.tryAcquireLock(file, "Envio P2P")) {
                    session.state = "FAILED"
                    taskManager.failTask(session.taskId, "Arquivo ${file.name} bloqueado por outra operação.")
                    return@launch
                }

                try {
                    val encodedName = URLEncoder.encode(file.name, "UTF-8")
                    val encodedSub = URLEncoder.encode(session.targetSubfolder, "UTF-8")
                    val path = "/api/p2p/upload?transferId=${session.transferId}" +
                        "&fileName=$encodedName" +
                        "&subfolder=$encodedSub" +
                        "&expectedBytes=${file.length()}"

                    val url = URL("https://${session.peerIp}:${session.peerPort}$path")
                    val conn = (url.openConnection() as HttpsURLConnection).apply {
                        sslSocketFactory = SelfSignedCertificateGenerator.getClientSocketFactory(appContext)
                        hostnameVerifier = HostnameVerifier { _, _ -> true }
                        requestMethod = "POST"
                        doOutput = true
                        doInput = true
                        useCaches = false
                        connectTimeout = 15_000
                        readTimeout = 60_000
                        setRequestProperty("Content-Type", "application/octet-stream")
                        setRequestProperty("X-P2P-Auth-Token", session.receiverAuthToken ?: "")
                        setFixedLengthStreamingMode(file.length())
                    }

                    var lastUpdate = 0L
                    BufferedInputStream(FileInputStream(file)).use { fis ->
                        conn.outputStream.use { os ->
                            val buffer = ByteArray(32 * 1024)
                            var read: Int
                            while (fis.read(buffer).also { read = it } != -1) {
                                os.write(buffer, 0, read)
                                bytesSentTotal += read

                                val now = System.currentTimeMillis()
                                if (now - lastUpdate >= 100L) {
                                    lastUpdate = now
                                    taskManager.updateTaskProgress(
                                        taskId = session.taskId,
                                        processedBytes = bytesSentTotal,
                                        processedItems = index,
                                        subtitle = "Enviando ${file.name} (${index + 1}/${session.files.size})"
                                    )
                                }
                            }
                            os.flush()
                        }
                    }

                    val code = conn.responseCode
                    if (code !in 200..299) {
                        throw IllegalStateException("Falha HTTP $code ao enviar ${file.name}")
                    }
                    filesSentCount++
                } catch (e: Exception) {
                    session.state = "FAILED"
                    taskManager.failTask(session.taskId, "Erro em ${file.name}: ${e.message}")
                    audit.logEvent(
                        action = "P2P_ENVIO_FALHOU",
                        actor = session.peerNickname,
                        details = "Falha ao enviar ${file.name}: ${e.message}",
                        success = false
                    )
                    return@launch
                } finally {
                    taskManager.releaseLock(file)
                }
            }

            session.state = "COMPLETED"
            taskManager.completeTask(
                taskId = session.taskId,
                finalSubtitle = "$filesSentCount arquivo(s) enviado(s) para ${session.peerNickname}"
            )
            audit.logEvent(
                action = "P2P_ENVIO_CONCLUIDO",
                actor = "${session.peerNickname} (${session.peerIp})",
                details = "Enviados $filesSentCount arquivo(s) (${FileLockAndTaskManager.formatSize(bytesSentTotal)})",
                success = true
            )
        }
    }

    // =========================================================================
    // LADO RECEPTOR (CELULAR DE DESTINO): Handshake, 2FA e Escrita Atômica
    // =========================================================================

    /**
     * Recebe o pedido inicial de transferência de outro celular e gera o 2FA de destino.
     */
    fun handleIncomingHandshake(
        transferId: String,
        senderNickname: String,
        senderIp: String,
        targetSubfolder: String,
        fileCount: Int,
        totalBytes: Long
    ): IncomingP2pSession {
        val cleanSub = settings.sanitizeFolderName(targetSubfolder).ifBlank { settings.getTargetSubfolder() }

        val task = taskManager.createTask(
            type = FileLockAndTaskManager.TaskType.P2P_RECEIVE,
            title = "Recebendo P2P <- $senderNickname ($fileCount arq.)",
            subtitle = "Aguardando aprovação 2FA neste aparelho...",
            totalBytes = totalBytes,
            totalItems = fileCount,
            initialStatus = FileLockAndTaskManager.TaskStatus.WAITING_2FA
        )

        security.createP2pChallenge(
            transferId = transferId,
            peerName = senderNickname,
            peerIp = senderIp,
            direction = "INCOMING"
        )

        val incoming = IncomingP2pSession(
            transferId = transferId,
            senderNickname = senderNickname,
            senderIp = senderIp,
            targetSubfolder = cleanSub,
            fileCount = fileCount,
            totalBytes = totalBytes,
            taskId = task.id
        )

        incomingSessions[transferId] = incoming
        publishPendingIncoming()
        return incoming
    }

    /**
     * Valida o código 2FA no celular de destino (seja digitado pelo remetente via API ou aprovado localmente na tela do receptor).
     */
    fun verifyIncoming2fa(transferId: String, code: String): Result<String> {
        val incoming = incomingSessions[transferId]
            ?: return Result.failure(IllegalArgumentException("Transferência P2P não encontrada no destino."))

        // Se o usuário já aprovou localmente na tela do celular receptor e enviou "APPROVED_ON_DEVICE"
        val isApproved = if (incoming.receiver2faVerified && code == "APPROVED_ON_DEVICE") {
            true
        } else {
            security.verifyP2pChallenge(transferId, code)
        }

        if (!isApproved) {
            return Result.failure(SecurityException("Código 2FA do celular de destino inválido ou expirado."))
        }

        incoming.receiver2faVerified = true
        taskManager.updateTaskProgress(
            taskId = incoming.taskId,
            subtitle = "2FA aprovado! Recebendo arquivos de ${incoming.senderNickname}...",
            status = FileLockAndTaskManager.TaskStatus.RUNNING
        )
        publishPendingIncoming()
        return Result.success(incoming.authToken)
    }

    /**
     * Recebe o stream de um arquivo enviado pelo outro celular, grava com arquivo .part
     * e renomeia automaticamente duplicados (_1, _2).
     */
    fun receiveIncomingFileStream(
        transferId: String,
        authToken: String?,
        fileName: String,
        subfolder: String?,
        expectedBytes: Long,
        inputStream: InputStream
    ): Result<File> {
        val incoming = incomingSessions[transferId]
            ?: return Result.failure(IllegalArgumentException("Sessão P2P inexistente."))

        if (!incoming.receiver2faVerified || authToken.isNullOrBlank() || authToken != incoming.authToken) {
            audit.logEvent(
                action = "P2P_UPLOAD_BLOQUEADO",
                actor = incoming.senderIp,
                details = "Tentativa de envio P2P sem 2FA de destino válido",
                success = false
            )
            return Result.failure(SecurityException("Transferência P2P não autorizada pelo 2FA de destino."))
        }

        return try {
            val targetFolder = settings.resolveSafeSubfolder(
                subfolder?.ifBlank { incoming.targetSubfolder } ?: incoming.targetSubfolder
            )
            val savedFile = taskManager.writeStreamAtomically(
                inputStream = inputStream,
                targetDir = targetFolder,
                desiredFileName = fileName,
                expectedBytes = expectedBytes,
                taskId = incoming.taskId,
                operationLabel = "Recebimento P2P"
            )

            incoming.receivedFilesCount += 1
            if (incoming.receivedFilesCount >= incoming.fileCount) {
                taskManager.completeTask(
                    taskId = incoming.taskId,
                    finalSubtitle = "Recebidos ${incoming.receivedFilesCount} arq. em ${targetFolder.name}"
                )
                incomingSessions.remove(transferId)
                publishPendingIncoming()
            } else {
                taskManager.updateTaskProgress(
                    taskId = incoming.taskId,
                    processedItems = incoming.receivedFilesCount,
                    subtitle = "Recebido ${incoming.receivedFilesCount} de ${incoming.fileCount} (${savedFile.name})",
                    status = FileLockAndTaskManager.TaskStatus.RUNNING
                )
            }

            audit.logEvent(
                action = "P2P_ARQUIVO_RECEBIDO",
                actor = "${incoming.senderNickname} (${incoming.senderIp})",
                details = "Salvo como ${savedFile.name} na pasta ${targetFolder.name}",
                success = true
            )
            Result.success(savedFile)
        } catch (e: Exception) {
            taskManager.failTask(incoming.taskId, "Falha ao receber $fileName: ${e.message}")
            Result.failure(e)
        }
    }

    fun toJsonPendingIncoming(): JSONArray {
        val arr = JSONArray()
        _pendingIncomingFlow.value.forEach { item ->
            val obj = JSONObject().apply {
                put("transferId", item.transferId)
                put("senderNickname", item.senderNickname)
                put("senderIp", item.senderIp)
                put("targetSubfolder", item.targetSubfolder)
                put("fileCount", item.fileCount)
                put("totalBytes", item.totalBytes)
                put("receiver2faVerified", item.receiver2faVerified)
            }
            arr.put(obj)
        }
        return arr
    }

    private fun publishPendingIncoming() {
        _pendingIncomingFlow.value = incomingSessions.values.sortedByDescending { it.createdAt }
    }

    private fun postJsonToPeer(
        peerIp: String,
        peerPort: Int,
        path: String,
        jsonBody: JSONObject
    ): JSONObject {
        val url = URL("https://$peerIp:$peerPort$path")
        val bytes = jsonBody.toString().toByteArray(Charsets.UTF_8)
        val conn = (url.openConnection() as HttpsURLConnection).apply {
            sslSocketFactory = SelfSignedCertificateGenerator.getClientSocketFactory(appContext)
            hostnameVerifier = HostnameVerifier { _, _ -> true }
            requestMethod = "POST"
            doOutput = true
            doInput = true
            useCaches = false
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setFixedLengthStreamingMode(bytes.size)
        }

        conn.outputStream.use { it.write(bytes) }
        val responseStream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        val text = responseStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: "{}"
        return try {
            JSONObject(text)
        } catch (_: Exception) {
            JSONObject().put("ok", false).put("error", "Resposta inválida do par P2P")
        }
    }

    companion object {
        @Volatile
        private var INSTANCE: P2PTransferManager? = null

        fun getInstance(context: Context): P2PTransferManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: P2PTransferManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
