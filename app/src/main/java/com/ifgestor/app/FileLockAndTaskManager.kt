package com.ifgestor.app

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class FileLockAndTaskManager private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val settings = SettingsManager(appContext)
    private val security = SecurityManager.getInstance(appContext)
    private val audit = AuditLogManager.getInstance(appContext)

    // Mapa concorrente de locks por CanonicalPath -> Descrição da Operação Ativa (Item 11)
    private val activeFileLocks = ConcurrentHashMap<String, String>()

    // Fila concorrente de tarefas paralelas para a Central de Tarefas Mobile e Web (Item 10)
    private val tasksMap = ConcurrentHashMap<String, TaskInfo>()
    private val _tasksFlow = MutableStateFlow<List<TaskInfo>>(emptyList())
    val tasksFlow: StateFlow<List<TaskInfo>> = _tasksFlow.asStateFlow()

    enum class TaskType {
        UPLOAD,
        DOWNLOAD_ZIP,
        P2P_SEND,
        P2P_RECEIVE,
        MOVE,
        DELETE
    }

    enum class TaskStatus {
        RUNNING,
        WAITING_2FA,
        COMPLETED,
        FAILED
    }

    data class TaskInfo(
        val id: String = UUID.randomUUID().toString().take(8),
        val type: TaskType,
        val title: String,
        val subtitle: String,
        val totalBytes: Long = 0L,
        val processedBytes: Long = 0L,
        val totalItems: Int = 1,
        val processedItems: Int = 0,
        val progressPercent: Int = 0,
        val status: TaskStatus = TaskStatus.RUNNING,
        val createdAt: Long = System.currentTimeMillis(),
        val updatedAt: Long = System.currentTimeMillis()
    )

    init {
        cleanupOrphanPartFiles()
    }

    // =========================================================================
    // ITEM 11: Controle de File Lock (Bloqueio contra Conflito de Concorrência)
    // =========================================================================

    private fun canonicalKey(file: File): String {
        return try {
            file.canonicalPath
        } catch (_: Exception) {
            file.absolutePath
        }
    }

    /**
     * Tenta adquirir um lock exclusivo sobre o arquivo. Retorna false se já estiver em trânsito.
     */
    fun tryAcquireLock(file: File, operationName: String): Boolean {
        val key = canonicalKey(file)
        return activeFileLocks.putIfAbsent(key, operationName) == null
    }

    /**
     * Libera o lock sobre o arquivo ao concluir ou falhar a operação.
     */
    fun releaseLock(file: File) {
        val key = canonicalKey(file)
        activeFileLocks.remove(key)
    }

    /**
     * Verifica se o arquivo está bloqueado por uma transferência ativa.
     */
    fun isFileLocked(file: File): Boolean {
        return activeFileLocks.containsKey(canonicalKey(file))
    }

    fun getLockReason(file: File): String? {
        return activeFileLocks[canonicalKey(file)]
    }

    // =========================================================================
    // ITEM 10: Gerenciamento da Central de Tarefas Paralelas (Web e Mobile)
    // =========================================================================

    fun createTask(
        type: TaskType,
        title: String,
        subtitle: String,
        totalBytes: Long = 0L,
        totalItems: Int = 1,
        initialStatus: TaskStatus = TaskStatus.RUNNING
    ): TaskInfo {
        val task = TaskInfo(
            type = type,
            title = title,
            subtitle = subtitle,
            totalBytes = totalBytes,
            totalItems = totalItems.coerceAtLeast(1),
            status = initialStatus
        )
        tasksMap[task.id] = task
        publishTasksState()
        return task
    }

    fun updateTaskProgress(
        taskId: String,
        processedBytes: Long = 0L,
        totalBytes: Long? = null,
        processedItems: Int? = null,
        subtitle: String? = null,
        status: TaskStatus? = null
    ) {
        val current = tasksMap[taskId] ?: return
        val newTotalBytes = totalBytes ?: current.totalBytes
        val newProcessedItems = processedItems ?: current.processedItems

        val percent = when {
            status == TaskStatus.COMPLETED -> 100
            newTotalBytes > 0L -> ((processedBytes * 100L) / newTotalBytes).toInt().coerceIn(0, 99)
            current.totalItems > 0 -> ((newProcessedItems * 100) / current.totalItems).coerceIn(0, 99)
            else -> current.progressPercent
        }

        val updated = current.copy(
            processedBytes = processedBytes,
            totalBytes = newTotalBytes,
            processedItems = newProcessedItems,
            progressPercent = percent,
            subtitle = subtitle ?: current.subtitle,
            status = status ?: current.status,
            updatedAt = System.currentTimeMillis()
        )
        tasksMap[taskId] = updated
        publishTasksState()
    }

    fun completeTask(taskId: String, finalSubtitle: String = "Concluído com sucesso") {
        val current = tasksMap[taskId] ?: return
        tasksMap[taskId] = current.copy(
            processedBytes = current.totalBytes.coerceAtLeast(current.processedBytes),
            processedItems = current.totalItems,
            progressPercent = 100,
            subtitle = finalSubtitle,
            status = TaskStatus.COMPLETED,
            updatedAt = System.currentTimeMillis()
        )
        publishTasksState()
    }

    fun failTask(taskId: String, errorSubtitle: String) {
        val current = tasksMap[taskId] ?: return
        tasksMap[taskId] = current.copy(
            subtitle = errorSubtitle,
            status = TaskStatus.FAILED,
            updatedAt = System.currentTimeMillis()
        )
        publishTasksState()
    }

    fun clearFinishedTasks() {
        val iterator = tasksMap.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value.status == TaskStatus.COMPLETED || entry.value.status == TaskStatus.FAILED) {
                iterator.remove()
            }
        }
        publishTasksState()
    }

    fun toJsonArray(): JSONArray {
        val arr = JSONArray()
        _tasksFlow.value.forEach { t ->
            val obj = JSONObject().apply {
                put("id", t.id)
                put("type", t.type.name)
                put("title", t.title)
                put("subtitle", t.subtitle)
                put("totalBytes", t.totalBytes)
                put("processedBytes", t.processedBytes)
                put("totalItems", t.totalItems)
                put("processedItems", t.processedItems)
                put("progressPercent", t.progressPercent)
                put("status", t.status.name)
                put("updatedAt", t.updatedAt)
            }
            arr.put(obj)
        }
        return arr
    }

    private fun publishTasksState() {
        val sorted = tasksMap.values.sortedWith(
            compareBy<TaskInfo> {
                when (it.status) {
                    TaskStatus.RUNNING, TaskStatus.WAITING_2FA -> 0
                    TaskStatus.FAILED -> 1
                    TaskStatus.COMPLETED -> 2
                }
            }.thenByDescending { it.updatedAt }
        ).take(40)
        _tasksFlow.value = sorted
    }

    // =========================================================================
    // ITEM 8 e ITEM 11: Escrita Atômica (.part) + Renomeação de Duplicados
    // =========================================================================

    /**
     * Resolve conflitos de nome adicionando sufixo incremental (_1, _2, _3) antes da extensão.
     */
    fun resolveUniqueDestinationFile(targetDir: File, originalName: String): File {
        security.assertInsideSandbox(targetDir)
        if (!targetDir.exists()) targetDir.mkdirs()

        val cleanName = sanitizeFileName(originalName)
        var candidate = File(targetDir, cleanName)
        if (!candidate.exists() && !isFileLocked(candidate)) {
            return candidate
        }

        val dotIndex = cleanName.lastIndexOf('.')
        val baseName = if (dotIndex > 0) cleanName.substring(0, dotIndex) else cleanName
        val ext = if (dotIndex > 0) cleanName.substring(dotIndex) else ""

        var counter = 1
        while (candidate.exists() || isFileLocked(candidate)) {
            candidate = File(targetDir, "${baseName}_$counter$ext")
            counter++
        }
        return candidate
    }

    /**
     * Grava um stream de rede/upload de forma atômica em um arquivo .part temporário,
     * atualizando a Central de Tarefas com throttling de 100ms e renomeando apenas ao final.
     */
    fun writeStreamAtomically(
        inputStream: InputStream,
        targetDir: File,
        desiredFileName: String,
        expectedBytes: Long,
        taskId: String? = null,
        operationLabel: String = "Transferência"
    ): File {
        security.assertInsideSandbox(targetDir)
        val finalFile = resolveUniqueDestinationFile(targetDir, desiredFileName)
        val partFile = File(targetDir, "${finalFile.name}.part")

        if (!tryAcquireLock(finalFile, operationLabel)) {
            throw IllegalStateException("O arquivo ${finalFile.name} já está em uso por outra operação.")
        }
        tryAcquireLock(partFile, operationLabel)

        var totalRead = 0L
        var lastUiUpdate = 0L

        try {
            FileOutputStream(partFile).use { fos ->
                val buffer = ByteArray( STREAM_BUFFER_SIZE )
                var read: Int
                while (inputStream.read(buffer).also { read = it } != -1) {
                    fos.write(buffer, 0, read)
                    totalRead += read

                    val now = System.currentTimeMillis()
                    if (taskId != null && (now - lastUiUpdate) >= 100L) {
                        lastUiUpdate = now
                        updateTaskProgress(
                            taskId = taskId,
                            processedBytes = totalRead,
                            totalBytes = expectedBytes.coerceAtLeast(totalRead),
                            subtitle = "${formatSize(totalRead)} recebidos"
                        )
                    }
                }
                fos.flush()
                try {
                    fos.fd.sync()
                } catch (_: Exception) {
                }
            }

            if (expectedBytes > 0L && totalRead < expectedBytes) {
                partFile.delete()
                throw IllegalStateException("Transferência interrompida antes do fim ($totalRead / $expectedBytes bytes).")
            }

            // Caso outro processo tenha criado o mesmo nome durante o stream, recalcula nome único
            val verifiedFinal = if (finalFile.exists()) {
                resolveUniqueDestinationFile(targetDir, desiredFileName)
            } else {
                finalFile
            }

            if (!partFile.renameTo(verifiedFinal)) {
                partFile.copyTo(verifiedFinal, overwrite = true)
                partFile.delete()
            }

            if (taskId != null) {
                completeTask(taskId, "Salvo como ${verifiedFinal.name} (${formatSize(totalRead)})")
            }

            return verifiedFinal
        } catch (e: Exception) {
            if (partFile.exists()) {
                partFile.delete()
            }
            if (taskId != null) {
                failTask(taskId, "Falha: ${e.message ?: "Erro de escrita"}")
            }
            throw e
        } finally {
            releaseLock(partFile)
            releaseLock(finalFile)
        }
    }

    /**
     * Move uma lista de arquivos para outra subpasta do Sandbox verificando File Locks e reportando progresso.
     */
    fun moveFilesSafely(
        sourceFiles: List<File>,
        destinationSubfolder: String,
        actor: String
    ): Pair<Int, List<String>> {
        val targetDir = settings.resolveSafeSubfolder(destinationSubfolder)
        security.assertInsideSandbox(targetDir)

        val task = createTask(
            type = TaskType.MOVE,
            title = "Movendo ${sourceFiles.size} arquivo(s)",
            subtitle = "Destino: $destinationSubfolder",
            totalItems = sourceFiles.size
        )

        var movedCount = 0
        val errors = mutableListOf<String>()

        sourceFiles.forEachIndexed { index, file ->
            try {
                security.assertInsideSandbox(file)
                if (!file.exists() || !file.isFile) {
                    errors.add("${file.name}: arquivo não encontrado")
                    return@forEachIndexed
                }
                if (!tryAcquireLock(file, "Movimentação")) {
                    val reason = getLockReason(file) ?: "em trânsito"
                    errors.add("${file.name}: bloqueado ($reason)")
                    return@forEachIndexed
                }

                try {
                    val destFile = resolveUniqueDestinationFile(targetDir, file.name)
                    val success = if (file.parentFile?.canonicalPath == targetDir.canonicalPath) {
                        true
                    } else if (file.renameTo(destFile)) {
                        true
                    } else {
                        file.copyTo(destFile, overwrite = false)
                        file.delete()
                    }

                    if (success) {
                        movedCount++
                    } else {
                        errors.add("${file.name}: falha ao mover")
                    }
                } finally {
                    releaseLock(file)
                }
            } catch (e: Exception) {
                errors.add("${file.name}: ${e.message}")
            }

            updateTaskProgress(
                taskId = task.id,
                processedItems = index + 1,
                subtitle = "Processado ${index + 1} de ${sourceFiles.size}"
            )
        }

        if (errors.isEmpty()) {
            completeTask(task.id, "$movedCount arquivo(s) movido(s) para $destinationSubfolder")
        } else {
            completeTask(task.id, "Movidos: $movedCount | Bloqueados/Erros: ${errors.size}")
        }

        audit.logEvent(
            action = "MOVIMENTACAO_LOTE",
            actor = actor,
            details = "Movidos $movedCount/${sourceFiles.size} para '$destinationSubfolder'",
            success = errors.isEmpty()
        )

        return Pair(movedCount, errors)
    }

    /**
     * Exclui uma lista de arquivos dentro do Sandbox respeitando File Locks de transferências ativas.
     */
    fun deleteFilesSafely(
        targetFiles: List<File>,
        actor: String
    ): Pair<Int, List<String>> {
        val task = createTask(
            type = TaskType.DELETE,
            title = "Excluindo ${targetFiles.size} arquivo(s)",
            subtitle = "Verificando travas de segurança...",
            totalItems = targetFiles.size
        )

        var deletedCount = 0
        val errors = mutableListOf<String>()

        targetFiles.forEachIndexed { index, file ->
            try {
                security.assertInsideSandbox(file)
                if (!file.exists()) {
                    return@forEachIndexed
                }
                if (!tryAcquireLock(file, "Exclusão")) {
                    val reason = getLockReason(file) ?: "em trânsito"
                    errors.add("${file.name}: em uso ($reason)")
                    return@forEachIndexed
                }

                try {
                    if (file.delete()) {
                        deletedCount++
                    } else {
                        errors.add("${file.name}: não foi possível excluir")
                    }
                } finally {
                    releaseLock(file)
                }
            } catch (e: Exception) {
                errors.add("${file.name}: ${e.message}")
            }

            updateTaskProgress(
                taskId = task.id,
                processedItems = index + 1,
                subtitle = "Excluído ${index + 1} de ${targetFiles.size}"
            )
        }

        if (errors.isEmpty()) {
            completeTask(task.id, "$deletedCount arquivo(s) excluído(s)")
        } else {
            completeTask(task.id, "Excluídos: $deletedCount | Bloqueados: ${errors.size}")
        }

        audit.logEvent(
            action = "EXCLUSAO_ARQUIVOS",
            actor = actor,
            details = "Excluídos $deletedCount/${targetFiles.size} arquivo(s)",
            success = errors.isEmpty()
        )

        return Pair(deletedCount, errors)
    }

    /**
     * Remove resíduos de arquivos .part órfãos deixados por quedas abruptas de bateria/rede em sessões anteriores.
     */
    private fun cleanupOrphanPartFiles() {
        try {
            val root = settings.getSandboxRootDir()
            root.walkTopDown().forEach { file ->
                if (file.isFile && file.name.endsWith(".part") && !isFileLocked(file)) {
                    file.delete()
                }
            }
        } catch (_: Exception) {
        }
    }

    fun sanitizeFileName(name: String): String {
        val cleaned = name.trim()
            .replace("/", "_")
            .replace("\\", "_")
            .replace("..", "_")
            .replace(Regex("[^a-zA-Z0-9._\\-() ]"), "_")
        return cleaned.ifBlank { "arquivo_${System.currentTimeMillis()}.bin" }
    }

    companion object {
        private const val STREAM_BUFFER_SIZE = 32 * 1024 // 32 KB

        fun formatSize(bytes: Long): String {
            if (bytes < 1024) return "$bytes B"
            val kb = bytes / 1024.0
            if (kb < 1024) return String.format("%.1f KB", kb)
            val mb = kb / 1024.0
            return String.format("%.2f MB", mb)
        }

        @Volatile
        private var INSTANCE: FileLockAndTaskManager? = null

        fun getInstance(context: Context): FileLockAndTaskManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: FileLockAndTaskManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
