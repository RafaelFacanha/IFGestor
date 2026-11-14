package com.ifgestor.app

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class AuditLogManager private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val logFile = File(appContext.filesDir, LOG_FILE_NAME)
    private val lock = Any()

    private val _logsFlow = MutableStateFlow<List<AuditEntry>>(emptyList())
    val logsFlow: StateFlow<List<AuditEntry>> = _logsFlow.asStateFlow()

    data class AuditEntry(
        val id: String = UUID.randomUUID().toString().take(8),
        val timestamp: Long = System.currentTimeMillis(),
        val formattedTime: String = formatTimestamp(timestamp),
        val action: String,
        val actor: String,
        val details: String,
        val success: Boolean
    )

    init {
        loadFromDisk()
        // Conecta automaticamente os eventos do SecurityManager à trilha de auditoria
        SecurityManager.getInstance(appContext).onSecurityAuditEvent = { action, actor, details, success ->
            logEvent(action = action, actor = actor, details = details, success = success)
        }
    }

    /**
     * Registra um novo evento na Trilha de Auditoria e persiste no armazenamento interno privado.
     */
    fun logEvent(
        action: String,
        actor: String,
        details: String,
        success: Boolean = true
    ) {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            val entry = AuditEntry(
                timestamp = now,
                formattedTime = formatTimestamp(now),
                action = action.trim().uppercase(Locale.getDefault()),
                actor = actor.trim().ifBlank { "Sistema" },
                details = details.trim(),
                success = success
            )

            val updated = ArrayList<AuditEntry>(_logsFlow.value.size + 1)
            updated.add(entry)
            updated.addAll(_logsFlow.value.take(MAX_LOG_ENTRIES - 1))

            _logsFlow.value = updated
            saveToDiskLocked(updated)
        }
    }

    /**
     * Retorna a lista completa de logs de auditoria em memória (do mais recente ao mais antigo).
     */
    fun getLogs(limit: Int = MAX_LOG_ENTRIES): List<AuditEntry> {
        return _logsFlow.value.take(limit)
    }

    /**
     * Exporta os registros de auditoria como JSONArray para o endpoint /api/audit do Servidor Web.
     */
    fun toJsonArray(limit: Int = 100): JSONArray {
        val arr = JSONArray()
        getLogs(limit).forEach { item ->
            val obj = JSONObject().apply {
                put("id", item.id)
                put("timestamp", item.timestamp)
                put("formattedTime", item.formattedTime)
                put("action", item.action)
                put("actor", item.actor)
                put("details", item.details)
                put("success", item.success)
            }
            arr.put(obj)
        }
        return arr
    }

    /**
     * Limpa o histórico e registra o próprio evento de limpeza por questões de compliance/rastreabilidade.
     */
    fun clearLogs(actor: String = "Admin Mobile") {
        synchronized(lock) {
            val resetEntry = AuditEntry(
                action = "AUDITORIA_REINICIADA",
                actor = actor,
                details = "Histórico de auditoria limpo manualmente pelo administrador",
                success = true
            )
            val list = listOf(resetEntry)
            _logsFlow.value = list
            saveToDiskLocked(list)
        }
    }

    private fun loadFromDisk() {
        synchronized(lock) {
            if (!logFile.exists()) {
                _logsFlow.value = emptyList()
                return
            }
            try {
                val raw = logFile.readText(Charsets.UTF_8)
                if (raw.isBlank()) {
                    _logsFlow.value = emptyList()
                    return
                }
                val jsonArray = JSONArray(raw)
                val loaded = mutableListOf<AuditEntry>()
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.optJSONObject(i) ?: continue
                    val ts = obj.optLong("timestamp", System.currentTimeMillis())
                    loaded.add(
                        AuditEntry(
                            id = obj.optString("id", UUID.randomUUID().toString().take(8)),
                            timestamp = ts,
                            formattedTime = obj.optString("formattedTime", formatTimestamp(ts)),
                            action = obj.optString("action", "EVENTO"),
                            actor = obj.optString("actor", "Sistema"),
                            details = obj.optString("details", ""),
                            success = obj.optBoolean("success", true)
                        )
                    )
                }
                _logsFlow.value = loaded.take(MAX_LOG_ENTRIES)
            } catch (_: Exception) {
                _logsFlow.value = emptyList()
            }
        }
    }

    private fun saveToDiskLocked(entries: List<AuditEntry>) {
        try {
            val arr = JSONArray()
            entries.forEach { item ->
                val obj = JSONObject().apply {
                    put("id", item.id)
                    put("timestamp", item.timestamp)
                    put("formattedTime", item.formattedTime)
                    put("action", item.action)
                    put("actor", item.actor)
                    put("details", item.details)
                    put("success", item.success)
                }
                arr.put(obj)
            }
            val tempFile = File(appContext.filesDir, "$LOG_FILE_NAME.part")
            tempFile.writeText(arr.toString(), Charsets.UTF_8)
            if (logFile.exists()) {
                logFile.delete()
            }
            tempFile.renameTo(logFile)
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val LOG_FILE_NAME = "audit_log.json"
        private const val MAX_LOG_ENTRIES = 300

        private fun formatTimestamp(timestamp: Long): String {
            val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
            return sdf.format(Date(timestamp))
        }

        @Volatile
        private var INSTANCE: AuditLogManager? = null

        fun getInstance(context: Context): AuditLogManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AuditLogManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
