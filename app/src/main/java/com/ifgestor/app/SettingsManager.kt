package com.ifgestor.app

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

enum class NamingFormat(val label: String, val example: String) {
    PREFIX_NUMBER("Prefixo + Sequencial", "Vistoria_001.jpg"),
    PREFIX_DATETIME("Prefixo + Data/Hora", "Vistoria_20261112_143022.jpg"),
    DATETIME_PREFIX("Data/Hora + Prefixo", "20261112_143022_Vistoria.jpg")
}

data class AuditLogEntry(
    val timestamp: String,
    val ip: String,
    val action: String,
    val details: String,
    val status: String
)

data class TaskItem(
    val id: String,
    val title: String,
    val type: String, // UPLOAD, P2P_SEND, P2P_RECV, MOVE, DELETE, ZIP
    val current: Int,
    val total: Int,
    val progressPercent: Int,
    val speedOrDetail: String = "",
    val status: String = "RUNNING", // RUNNING, DONE, ERROR
    val updatedAt: Long = System.currentTimeMillis()
)

data class PeerDevice(
    val name: String,
    val host: String,
    val port: Int,
    val useHttps: Boolean = true,
    val isSelf: Boolean = false
)

class SettingsManager private constructor(private val appContext: Context) {

    private val prefs = appContext.getSharedPreferences("ifgestor_secure_prefs", Context.MODE_PRIVATE)
    private val auditFile = File(appContext.filesDir, "ifgestor_audit_log.json")

    // Deteccao automatica do diretorio fisico da camera (DCIM/Camera ou DCIM/Câmera) + Sandbox IFgestor
    val sandboxRootDir: File by lazy {
        val dcim = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
        val cameraWithAccent = File(dcim, "Câmera")
        val cameraStandard = File(dcim, "Camera")
        val baseCameraDir = when {
            cameraWithAccent.exists() && cameraWithAccent.isDirectory -> cameraWithAccent
            else -> cameraStandard
        }
        File(baseCameraDir, "IFgestor").apply {
            if (!exists()) mkdirs()
        }
    }

    // Estados Independentes dos Motores (Interceptador de Camera vs. Servidor Web)
    private val _isCameraInterceptorEnabled = MutableStateFlow(prefs.getBoolean(KEY_CAMERA_INTERCEPTOR, false))
    val isCameraInterceptorEnabled: StateFlow<Boolean> = _isCameraInterceptorEnabled.asStateFlow()

    private val _isWebServerEnabled = MutableStateFlow(prefs.getBoolean(KEY_WEB_SERVER, false))
    val isWebServerEnabled: StateFlow<Boolean> = _isWebServerEnabled.asStateFlow()

    // Mantido por compatibilidade: verdadeiro se pelo menos um motor estiver ativo
    private val _isServiceRunning = MutableStateFlow(
        _isCameraInterceptorEnabled.value || _isWebServerEnabled.value
    )
    val isServiceRunning: StateFlow<Boolean> = _isServiceRunning.asStateFlow()

    // Pasta e Prefixo Ativos (Restritos ao Sandbox DCIM/Camera/IFgestor)
    private val _targetFolderName = MutableStateFlow(loadValidatedFolder())
    val targetFolderName: StateFlow<String> = _targetFolderName.asStateFlow()

    private val _filePrefix = MutableStateFlow(prefs.getString(KEY_FILE_PREFIX, "Foto_") ?: "Foto_")
    val filePrefix: StateFlow<String> = _filePrefix.asStateFlow()

    private val _namingFormat = MutableStateFlow(loadNamingFormat())
    val namingFormat: StateFlow<NamingFormat> = _namingFormat.asStateFlow()

    private val _interceptVideos = MutableStateFlow(prefs.getBoolean(KEY_INTERCEPT_VIDEOS, true))
    val interceptVideos: StateFlow<Boolean> = _interceptVideos.asStateFlow()

    private val _showFloatingOverlay = MutableStateFlow(prefs.getBoolean(KEY_FLOATING_OVERLAY, true))
    val showFloatingOverlay: StateFlow<Boolean> = _showFloatingOverlay.asStateFlow()

    // Contadores Duplos: Sessao Atual e Total na Pasta Ativa
    private val _sessionInterceptedCount = MutableStateFlow(0)
    val sessionInterceptedCount: StateFlow<Int> = _sessionInterceptedCount.asStateFlow()

    private val _currentFolderTotalCount = MutableStateFlow(0)
    val currentFolderTotalCount: StateFlow<Int> = _currentFolderTotalCount.asStateFlow()

    private val _lastInterceptedFile = MutableStateFlow<String?>(null)
    val lastInterceptedFile: StateFlow<String?> = _lastInterceptedFile.asStateFlow()

    // Seguranca: Troca Obrigatoria do PIN Inicial (1234)
    private val _mustChangeDefaultPin = MutableStateFlow(prefs.getBoolean(KEY_MUST_CHANGE_PIN, true))
    val mustChangeDefaultPin: StateFlow<Boolean> = _mustChangeDefaultPin.asStateFlow()

    // Configuracoes de Rede, HTTPS, mDNS (ifgestor.local) e Descoberta P2P
    private val _useHttps = MutableStateFlow(prefs.getBoolean(KEY_USE_HTTPS, true))
    val useHttps: StateFlow<Boolean> = _useHttps.asStateFlow()

    private val _enableMdns = MutableStateFlow(prefs.getBoolean(KEY_ENABLE_MDNS, true))
    val enableMdns: StateFlow<Boolean> = _enableMdns.asStateFlow()

    private val _deviceAlias = MutableStateFlow(
        prefs.getString(KEY_DEVICE_ALIAS, defaultDeviceAlias()) ?: defaultDeviceAlias()
    )
    val deviceAlias: StateFlow<String> = _deviceAlias.asStateFlow()

    private val _activeMdnsDomain = MutableStateFlow("ifgestor.local")
    val activeMdnsDomain: StateFlow<String> = _activeMdnsDomain.asStateFlow()

    private val _discoveredPeers = MutableStateFlow<List<PeerDevice>>(emptyList())
    val discoveredPeers: StateFlow<List<PeerDevice>> = _discoveredPeers.asStateFlow()

    // Central de Tarefas Multitarefa e Recolhivel (Mobile + Web)
    private val activeTasksMap = ConcurrentHashMap<String, TaskItem>()
    private val _activeTasks = MutableStateFlow<List<TaskItem>>(emptyList())
    val activeTasks: StateFlow<List<TaskItem>> = _activeTasks.asStateFlow()

    private val _isTaskCenterCollapsed = MutableStateFlow(false)
    val isTaskCenterCollapsed: StateFlow<Boolean> = _isTaskCenterCollapsed.asStateFlow()

    // File Locks em Memoria (Impede excluir ou mover um arquivo que esta sendo enviado/recebido)
    private val lockedCanonicalPaths = ConcurrentHashMap.newKeySet<String>()

    // Trilha de Auditoria (Audit Log LGPD)
    private val _auditLogs = MutableStateFlow<List<AuditLogEntry>>(emptyList())
    val auditLogs: StateFlow<List<AuditLogEntry>> = _auditLogs.asStateFlow()

    // Lista de Subpastas do Sandbox para o Dropdown Flutuante e Widgets
    private val _sandboxSubfolders = MutableStateFlow<List<String>>(emptyList())
    val sandboxSubfolders: StateFlow<List<String>> = _sandboxSubfolders.asStateFlow()

    init {
        ensurePinInitialized()
        loadAuditLogsFromDisk()
        refreshSandboxSubfolders()
        refreshCurrentFolderCount()
    }

    private fun defaultDeviceAlias(): String {
        val model = Build.MODEL.replace(Regex("[^a-zA-Z0-9]"), "").take(10).lowercase(Locale.ROOT)
        return if (model.isNotBlank()) "ifgestor-$model" else "ifgestor"
    }

    private fun loadValidatedFolder(): String {
        val saved = prefs.getString(KEY_TARGET_FOLDER, "") ?: ""
        val sanitized = sanitizeSubfolderRelativePath(saved)
        return sanitized
    }

    private fun loadNamingFormat(): NamingFormat {
        val saved = prefs.getString(KEY_NAMING_FORMAT, NamingFormat.PREFIX_NUMBER.name)
        return try {
            NamingFormat.valueOf(saved ?: NamingFormat.PREFIX_NUMBER.name)
        } catch (_: Exception) {
            NamingFormat.PREFIX_NUMBER
        }
    }

    // =========================================================================
    // 1. ISOLAMENTO DE SANDBOX (DCIM/Camera/IFgestor) E ANTI-LOOP
    // =========================================================================

    fun sanitizeSubfolderRelativePath(rawRelative: String): String {
        val cleaned = rawRelative
            .replace("\\", "/")
            .trim()
            .trim('/')
            .split("/")
            .map { segment ->
                segment.replace(Regex("[^a-zA-Z0-9_\\-\\.\\sÀ-ÿ]"), "_").trim()
            }
            .filter { it.isNotEmpty() && it != "." && it != ".." }
            .joinToString("/")

        // Se o usuario digitou o caminho legado completo, extrai apenas a subpasta interna
        val withoutLegacyPrefix = cleaned
            .removePrefix("DCIM/Camera/IFgestor")
            .removePrefix("DCIM/Câmera/IFgestor")
            .removePrefix("DCIM/IFGestor_Capturas")
            .trim('/')

        return withoutLegacyPrefix
    }

    fun getTargetDirectory(): File {
        val relative = _targetFolderName.value
        val dir = if (relative.isBlank()) {
            sandboxRootDir
        } else {
            File(sandboxRootDir, relative)
        }
        val canonicalRoot = sandboxRootDir.canonicalFile
        val canonicalTarget = dir.canonicalFile
        val safeDir = if (canonicalTarget.path.startsWith(canonicalRoot.path)) {
            canonicalTarget
        } else {
            canonicalRoot
        }
        if (!safeDir.exists()) safeDir.mkdirs()
        return safeDir
    }

    fun resolveInsideSandbox(relativePath: String?): File? {
        val rootCanonical = sandboxRootDir.canonicalFile
        if (!rootCanonical.exists()) rootCanonical.mkdirs()
        val cleanRel = (relativePath ?: "").replace("\\", "/").trim().trimStart('/')
        val candidate = if (cleanRel.isEmpty()) rootCanonical else File(rootCanonical, cleanRel)
        return try {
            val canonical = candidate.canonicalFile
            if (canonical.path == rootCanonical.path ||
                canonical.path.startsWith(rootCanonical.path + File.separator)
            ) {
                canonical
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun getRelativeSandboxPath(file: File): String {
        return try {
            val rootPath = sandboxRootDir.canonicalFile.path
            val filePath = file.canonicalFile.path
            if (filePath == rootPath) {
                ""
            } else if (filePath.startsWith(rootPath + File.separator)) {
                filePath.substring(rootPath.length + 1).replace("\\", "/")
            } else {
                ""
            }
        } catch (_: Exception) {
            ""
        }
    }

    fun isInsideSandbox(file: File): Boolean {
        return try {
            val rootPath = sandboxRootDir.canonicalFile.path
            val filePath = file.canonicalFile.path
            filePath == rootPath || filePath.startsWith(rootPath + File.separator)
        } catch (_: Exception) {
            false
        }
    }

    fun getDisplaySandboxPath(): String {
        val rel = _targetFolderName.value
        val rootLabel = if (sandboxRootDir.absolutePath.contains("Câmera")) {
            "DCIM/Câmera/IFgestor"
        } else {
            "DCIM/Camera/IFgestor"
        }
        return if (rel.isBlank()) rootLabel else "$rootLabel/$rel"
    }

    fun refreshSandboxSubfolders() {
        try {
            if (!sandboxRootDir.exists()) sandboxRootDir.mkdirs()
            val folders = mutableListOf("") // "" representa a raiz DCIM/Camera/IFgestor
            fun scanRecursive(dir: File, depth: Int) {
                if (depth > 3) return
                val children = dir.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }
                    ?.sortedBy { it.name.lowercase(Locale.ROOT) } ?: return
                for (child in children) {
                    val rel = getRelativeSandboxPath(child)
                    if (rel.isNotBlank()) {
                        folders.add(rel)
                        scanRecursive(child, depth + 1)
                    }
                }
            }
            scanRecursive(sandboxRootDir, 1)
            val current = _targetFolderName.value
            if (current.isNotBlank() && !folders.contains(current)) {
                folders.add(1, current)
            }
            _sandboxSubfolders.value = folders.distinct()
        } catch (_: Exception) {
            _sandboxSubfolders.value = listOf("")
        }
    }

    fun createSandboxSubfolder(folderName: String): Boolean {
        val clean = sanitizeSubfolderRelativePath(folderName)
        if (clean.isBlank()) return false
        val dir = resolveInsideSandbox(clean) ?: return false
        val ok = dir.exists() || dir.mkdirs()
        if (ok) {
            refreshSandboxSubfolders()
            setTargetFolderName(clean)
        }
        return ok
    }

    // Resolve colisoes de nome automaticamente (ex: Foto_1.jpg) para nao travar filas paralelas
    fun resolveNonConflictingFile(targetDir: File, desiredName: String): File {
        val safeName = desiredName.replace(Regex("[^a-zA-Z0-9_\\-\\.\\sÀ-ÿ]"), "_").trim()
            .ifBlank { "Arquivo_${System.currentTimeMillis()}" }
        var candidate = File(targetDir, safeName)
        if (!candidate.exists() && !isFileLocked(candidate)) {
            return candidate
        }
        val dotIndex = safeName.lastIndexOf('.')
        val base = if (dotIndex > 0) safeName.substring(0, dotIndex) else safeName
        val ext = if (dotIndex > 0) safeName.substring(dotIndex) else ""
        var counter = 1
        while (candidate.exists() || isFileLocked(candidate)) {
            candidate = File(targetDir, "${base}_$counter$ext")
            counter++
        }
        return candidate
    }

    // =========================================================================
    // 2. CONTROLES DOS MOTORES INDEPENDENTES E CONFIGURACOES DE CAPTURA
    // =========================================================================

    fun setCameraInterceptorEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_CAMERA_INTERCEPTOR, enabled).apply()
        _isCameraInterceptorEnabled.value = enabled
        _isServiceRunning.value = enabled || _isWebServerEnabled.value
        notifyHomeWidgetUpdate()
    }

    fun setWebServerEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_WEB_SERVER, enabled).apply()
        _isWebServerEnabled.value = enabled
        _isServiceRunning.value = _isCameraInterceptorEnabled.value || enabled
        notifyHomeWidgetUpdate()
    }

    fun setServiceRunning(running: Boolean) {
        setCameraInterceptorEnabled(running)
        setWebServerEnabled(running)
    }

    fun setTargetFolderName(folderSubpath: String) {
        val clean = sanitizeSubfolderRelativePath(folderSubpath)
        prefs.edit().putString(KEY_TARGET_FOLDER, clean).apply()
        _targetFolderName.value = clean
        getTargetDirectory() // Garante criacao fisica da subpasta
        refreshSandboxSubfolders()
        refreshCurrentFolderCount()
        notifyHomeWidgetUpdate()
    }

    fun setFilePrefix(prefix: String) {
        val clean = prefix.replace(Regex("[^a-zA-Z0-9_\\-]"), "_").ifBlank { "Foto_" }
        prefs.edit().putString(KEY_FILE_PREFIX, clean).apply()
        _filePrefix.value = clean
        notifyHomeWidgetUpdate()
    }

    fun setNamingFormat(format: NamingFormat) {
        prefs.edit().putString(KEY_NAMING_FORMAT, format.name).apply()
        _namingFormat.value = format
    }

    fun setInterceptVideos(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_INTERCEPT_VIDEOS, enabled).apply()
        _interceptVideos.value = enabled
    }

    fun setShowFloatingOverlay(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_FLOATING_OVERLAY, enabled).apply()
        _showFloatingOverlay.value = enabled
    }

    fun setUseHttps(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_USE_HTTPS, enabled).apply()
        _useHttps.value = enabled
        notifyHomeWidgetUpdate()
    }

    fun setEnableMdns(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLE_MDNS, enabled).apply()
        _enableMdns.value = enabled
        notifyHomeWidgetUpdate()
    }

    fun setDeviceAlias(alias: String) {
        val clean = alias.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9\\-]"), "-")
            .trim('-')
            .ifBlank { "ifgestor" }
        prefs.edit().putString(KEY_DEVICE_ALIAS, clean).apply()
        _deviceAlias.value = clean
        notifyHomeWidgetUpdate()
    }

    fun setActiveMdnsDomain(domain: String) {
        _activeMdnsDomain.value = domain
        notifyHomeWidgetUpdate()
    }

    fun updateDiscoveredPeers(peers: List<PeerDevice>) {
        _discoveredPeers.value = peers
    }

    fun recordInterceptedMedia(fileName: String) {
        _sessionInterceptedCount.value += 1
        _lastInterceptedFile.value = fileName
        refreshCurrentFolderCount()
        notifyHomeWidgetUpdate()
    }

    fun resetSessionCounter() {
        _sessionInterceptedCount.value = 0
        notifyHomeWidgetUpdate()
    }

    fun refreshCurrentFolderCount(): Int {
        val dir = getTargetDirectory()
        val count = dir.listFiles()?.count { file ->
            file.isFile && !file.name.startsWith(".") && !file.name.endsWith(".part")
        } ?: 0
        _currentFolderTotalCount.value = count
        return count
    }

    fun generateNextFileName(extension: String): String {
        val cleanExt = extension.trimStart('.').lowercase(Locale.ROOT).ifBlank { "jpg" }
        val prefix = _filePrefix.value
        val targetDir = getTargetDirectory()
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date())

        val desiredName = when (_namingFormat.value) {
            NamingFormat.PREFIX_NUMBER -> {
                val existingCount = targetDir.listFiles()?.count {
                    it.isFile && !it.name.endsWith(".part")
                } ?: 0
                val nextNum = (existingCount + 1).toString().padStart(3, '0')
                "${prefix}${nextNum}.$cleanExt"
            }
            NamingFormat.PREFIX_DATETIME -> "${prefix}${timestamp}.$cleanExt"
            NamingFormat.DATETIME_PREFIX -> "${timestamp}_${prefix.trimEnd('_')}.$cleanExt"
        }
        return resolveNonConflictingFile(targetDir, desiredName).name
    }

    // =========================================================================
    // 3. CRIPTOGRAFIA PBKDF2 COM SALT ALEATORIO (PADRAO OWASP MASVS)
    // =========================================================================

    private fun ensurePinInitialized() {
        val hasPbkdf2 = prefs.contains(KEY_PIN_HASH_PBKDF2) && prefs.contains(KEY_PIN_SALT)
        if (!hasPbkdf2) {
            setPinInternal("1234", isInitialDefault = true)
        }
    }

    private fun setPinInternal(newPin: String, isInitialDefault: Boolean) {
        val salt = ByteArray(16)
        SecureRandom().nextBytes(salt)
        val hashHex = pbkdf2Hex(newPin, salt)
        val saltHex = bytesToHex(salt)
        prefs.edit()
            .putString(KEY_PIN_HASH_PBKDF2, hashHex)
            .putString(KEY_PIN_SALT, saltHex)
            .putBoolean(KEY_MUST_CHANGE_PIN, isInitialDefault || newPin == "1234")
            .apply()
        _mustChangeDefaultPin.value = isInitialDefault || newPin == "1234"
    }

    fun updateServerPin(newPin: String): Boolean {
        val trimmed = newPin.trim()
        if (trimmed.length < 4 || trimmed.length > 12 || !trimmed.all { it.isDigit() }) {
            return false
        }
        setPinInternal(trimmed, isInitialDefault = false)
        appendAuditLog("LOCAL_APP", "PIN_UPDATED", "PIN de seguranca atualizado pelo usuario", "SUCCESS")
        return true
    }

    fun verifyServerPin(inputPin: String): Boolean {
        val saltHex = prefs.getString(KEY_PIN_SALT, null) ?: return false
        val storedHashHex = prefs.getString(KEY_PIN_HASH_PBKDF2, null) ?: return false
        val salt = hexToBytes(saltHex)
        val candidateHashHex = pbkdf2Hex(inputPin.trim(), salt)
        return MessageDigest.isEqual(
            storedHashHex.toByteArray(Charsets.UTF_8),
            candidateHashHex.toByteArray(Charsets.UTF_8)
        )
    }

    private fun pbkdf2Hex(pin: String, salt: ByteArray): String {
        return try {
            val spec = PBEKeySpec(pin.toCharArray(), salt, 12000, 256)
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            val bytes = factory.generateSecret(spec).encoded
            bytesToHex(bytes)
        } catch (_: Exception) {
            val md = MessageDigest.getInstance("SHA-256")
            md.update(salt)
            bytesToHex(md.digest(pin.toByteArray(Charsets.UTF_8)))
        }
    }

    private fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = if (hex.length % 2 == 0) hex else "0$hex"
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    // =========================================================================
    // 4. BLOQUEIO DE ARQUIVOS EM TRANSITO (FILE LOCKING) E CENTRAL DE TAREFAS
    // =========================================================================

    fun tryLockFile(file: File): Boolean {
        return try {
            lockedCanonicalPaths.add(file.canonicalPath)
        } catch (_: Exception) {
            false
        }
    }

    fun unlockFile(file: File) {
        try {
            lockedCanonicalPaths.remove(file.canonicalPath)
        } catch (_: Exception) {}
    }

    fun isFileLocked(file: File): Boolean {
        return try {
            lockedCanonicalPaths.contains(file.canonicalPath)
        } catch (_: Exception) {
            false
        }
    }

    fun upsertTask(
        id: String,
        title: String,
        type: String,
        current: Int,
        total: Int,
        speedOrDetail: String = "",
        status: String = "RUNNING"
    ) {
        val safeTotal = if (total <= 0) 1 else total
        val pct = ((current.toDouble() / safeTotal.toDouble()) * 100.0).toInt().coerceIn(0, 100)
        val item = TaskItem(
            id = id,
            title = title,
            type = type,
            current = current,
            total = total,
            progressPercent = if (status == "DONE") 100 else pct,
            speedOrDetail = speedOrDetail,
            status = status,
            updatedAt = System.currentTimeMillis()
        )
        activeTasksMap[id] = item
        publishTasksState()
    }

    fun removeTask(id: String) {
        activeTasksMap.remove(id)
        publishTasksState()
    }

    fun clearCompletedTasks() {
        val iterator = activeTasksMap.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value.status != "RUNNING") {
                iterator.remove()
            }
        }
        publishTasksState()
    }

    fun setTaskCenterCollapsed(collapsed: Boolean) {
        _isTaskCenterCollapsed.value = collapsed
    }

    private fun publishTasksState() {
        _activeTasks.value = activeTasksMap.values.sortedByDescending { it.updatedAt }
    }

    // =========================================================================
    // 5. TRILHA DE AUDITORIA PERSISTENTE (AUDIT LOG - COMPLIANCE LGPD)
    // =========================================================================

    @Synchronized
    fun appendAuditLog(ip: String, action: String, details: String, status: String = "SUCCESS") {
        val timestamp = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.ROOT).format(Date())
        val entry = AuditLogEntry(
            timestamp = timestamp,
            ip = ip,
            action = action,
            details = details.take(220),
            status = status
        )
        val updated = (listOf(entry) + _auditLogs.value).take(250)
        _auditLogs.value = updated
        saveAuditLogsToDisk(updated)
    }

    @Synchronized
    fun clearAuditLogs() {
        _auditLogs.value = emptyList()
        if (auditFile.exists()) auditFile.delete()
        appendAuditLog("LOCAL_APP", "AUDIT_CLEARED", "Historico de auditoria limpo no dispositivo", "INFO")
    }

    private fun loadAuditLogsFromDisk() {
        if (!auditFile.exists()) return
        try {
            val raw = auditFile.readText(Charsets.UTF_8)
            val arr = JSONArray(raw)
            val list = mutableListOf<AuditLogEntry>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    AuditLogEntry(
                        timestamp = obj.optString("timestamp"),
                        ip = obj.optString("ip"),
                        action = obj.optString("action"),
                        details = obj.optString("details"),
                        status = obj.optString("status", "INFO")
                    )
                )
            }
            _auditLogs.value = list
        } catch (_: Exception) {
            _auditLogs.value = emptyList()
        }
    }

    private fun saveAuditLogsToDisk(entries: List<AuditLogEntry>) {
        try {
            val arr = JSONArray()
            for (e in entries) {
                val obj = JSONObject().apply {
                    put("timestamp", e.timestamp)
                    put("ip", e.ip)
                    put("action", e.action)
                    put("details", e.details)
                    put("status", e.status)
                }
                arr.put(obj)
            }
            auditFile.writeText(arr.toString(), Charsets.UTF_8)
        } catch (_: Exception) {}
    }

    fun notifyHomeWidgetUpdate() {
        try {
            val intent = Intent("com.ifgestor.app.ACTION_WIDGET_REFRESH").apply {
                setPackage(appContext.packageName)
            }
            appContext.sendBroadcast(intent)
        } catch (_: Exception) {}
    }

    companion object {
        private const val KEY_CAMERA_INTERCEPTOR = "key_camera_interceptor_enabled"
        private const val KEY_WEB_SERVER = "key_web_server_enabled"
        private const val KEY_TARGET_FOLDER = "key_target_folder_sandbox"
        private const val KEY_FILE_PREFIX = "key_file_prefix"
        private const val KEY_NAMING_FORMAT = "key_naming_format"
        private const val KEY_INTERCEPT_VIDEOS = "key_intercept_videos"
        private const val KEY_FLOATING_OVERLAY = "key_floating_overlay"
        private const val KEY_PIN_HASH_PBKDF2 = "key_pin_hash_pbkdf2"
        private const val KEY_PIN_SALT = "key_pin_salt_hex"
        private const val KEY_MUST_CHANGE_PIN = "key_must_change_default_pin"
        private const val KEY_USE_HTTPS = "key_use_https"
        private const val KEY_ENABLE_MDNS = "key_enable_mdns"
        private const val KEY_DEVICE_ALIAS = "key_device_alias"

        @Volatile
        private var INSTANCE: SettingsManager? = null

        fun getInstance(context: Context): SettingsManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SettingsManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
