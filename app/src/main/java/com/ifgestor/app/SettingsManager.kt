package com.ifgestor.app

import android.content.Context
import android.os.Environment
import android.os.StatFs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.security.MessageDigest
import java.util.Locale

data class StorageStats(
    val totalBytes: Long,
    val freeBytes: Long,
    val usedBytes: Long,
    val usedPercent: Int,
    val formattedSummary: String
)

object SettingsManager {
    private const val PREFS_NAME = "ifgestor_v2_prefs"
    private const val KEY_PREFIX = "prefix"
    private const val KEY_QUICK_PRESETS = "quick_prefix_presets"
    private const val KEY_TARGET_PATH = "target_folder_path"
    private const val KEY_RECENT_PATHS = "recent_folder_paths"
    private const val KEY_OVERLAY_ENABLED = "overlay_enabled"
    private const val KEY_WEB_SERVER_ENABLED = "web_server_enabled"
    private const val KEY_SERVER_PORT = "web_server_port"
    private const val KEY_THEME_MODE = "ui_theme_mode" // 0 = Auto, 1 = Claro, 2 = Escuro

    // Chaves de Seguranca
    private const val KEY_AUTH_ENABLED = "web_auth_enabled"
    private const val KEY_TWO_FACTOR_ENABLED = "web_2fa_enabled"
    private const val KEY_PIN_HASH = "web_pin_sha256"

    private val DEFAULT_PRESETS = listOf("Vistoria_", "Entrada_", "Saida_", "Avaria_", "Doc_")

    private val _isServiceRunning = MutableStateFlow(false)
    val isServiceRunning: StateFlow<Boolean> = _isServiceRunning.asStateFlow()

    private val _isWebServerRunning = MutableStateFlow(false)
    val isWebServerRunning: StateFlow<Boolean> = _isWebServerRunning.asStateFlow()

    private val _interceptedCount = MutableStateFlow(0)
    val interceptedCount: StateFlow<Int> = _interceptedCount.asStateFlow()

    private val _lastInterceptedFile = MutableStateFlow("Nenhum arquivo ainda")
    val lastInterceptedFile: StateFlow<String> = _lastInterceptedFile.asStateFlow()

    private val _activePrefix = MutableStateFlow("Vistoria_")
    val activePrefix: StateFlow<String> = _activePrefix.asStateFlow()

    private val _quickPresets = MutableStateFlow(DEFAULT_PRESETS)
    val quickPresets: StateFlow<List<String>> = _quickPresets.asStateFlow()

    private val _activeFolderPath = MutableStateFlow(getDefaultFolderPath())
    val activeFolderPath: StateFlow<String> = _activeFolderPath.asStateFlow()

    private val _themeMode = MutableStateFlow(0)
    val themeMode: StateFlow<Int> = _themeMode.asStateFlow()

    // Estados de Seguranca em Tempo Real (2FA e Sessoes)
    private val _current2faCode = MutableStateFlow<String?>(null)
    val current2faCode: StateFlow<String?> = _current2faCode.asStateFlow()

    private val _current2faClientIp = MutableStateFlow<String?>(null)
    val current2faClientIp: StateFlow<String?> = _current2faClientIp.asStateFlow()

    private val _activeSessionsCount = MutableStateFlow(0)
    val activeSessionsCount: StateFlow<Int> = _activeSessionsCount.asStateFlow()

    var onTwoFactorCodeGenerated: ((String, String) -> Unit)? = null

    fun getDefaultFolderPath(): String {
        val dcim = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
        return File(dcim, "IFGestor_Capturas").absolutePath
    }

    fun getStorageRootPath(): String {
        return Environment.getExternalStorageDirectory().absolutePath
    }

    fun syncStateFromPrefs(context: Context) {
        _activePrefix.value = getPrefix(context)
        _activeFolderPath.value = getTargetFolderPath(context)
        _quickPresets.value = getQuickPresets(context)
        _themeMode.value = getThemeMode(context)
    }

    fun getThemeMode(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val mode = prefs.getInt(KEY_THEME_MODE, 0)
        _themeMode.value = mode
        return mode
    }

    fun setThemeMode(context: Context, mode: Int) {
        val validMode = mode.coerceIn(0, 2)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_THEME_MODE, validMode)
            .apply()
        _themeMode.value = validMode
    }

    fun setServiceRunning(running: Boolean) {
        _isServiceRunning.value = running
        if (running) {
            _interceptedCount.value = 0
        }
    }

    fun setWebServerRunning(running: Boolean) {
        _isWebServerRunning.value = running
        if (!running) {
            _current2faCode.value = null
            _activeSessionsCount.value = 0
        }
    }

    fun publishTwoFactorCode(code: String?, clientIp: String?) {
        _current2faCode.value = code
        _current2faClientIp.value = clientIp
        if (code != null && clientIp != null) {
            onTwoFactorCodeGenerated?.invoke(code, clientIp)
        }
    }

    fun updateActiveSessionsCount(count: Int) {
        _activeSessionsCount.value = count
    }

    fun registerInterceptedFile(fileName: String) {
        _interceptedCount.value += 1
        _lastInterceptedFile.value = fileName
    }

    fun getPrefix(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_PREFIX, "Vistoria_") ?: "Vistoria_"
        _activePrefix.value = saved
        return saved
    }

    fun setPrefix(context: Context, prefix: String) {
        val sanitized = prefix.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PREFIX, sanitized)
            .apply()
        _activePrefix.value = sanitized
    }

    fun getQuickPresets(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_QUICK_PRESETS, null)
        if (raw.isNullOrBlank()) {
            _quickPresets.value = DEFAULT_PRESETS
            return DEFAULT_PRESETS
        }
        val list = raw.split("|").map { it.trim() }.filter { it.isNotEmpty() }
        val finalList = if (list.isEmpty()) DEFAULT_PRESETS else list
        _quickPresets.value = finalList
        return finalList
    }

    fun addQuickPreset(context: Context, preset: String) {
        val sanitized = preset.trim().replace(Regex("[\\\\/:*?\"<>||]"), "_")
        if (sanitized.isEmpty()) return
        val current = getQuickPresets(context).toMutableList()
        current.remove(sanitized)
        current.add(0, sanitized)
        val updated = current.take(12)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_QUICK_PRESETS, updated.joinToString("|"))
            .apply()
        _quickPresets.value = updated
    }

    fun removeQuickPreset(context: Context, preset: String) {
        val current = getQuickPresets(context).toMutableList()
        current.remove(preset)
        val updated = if (current.isEmpty()) DEFAULT_PRESETS else current
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_QUICK_PRESETS, updated.joinToString("|"))
            .apply()
        _quickPresets.value = updated
    }

    fun getTargetFolderPath(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_TARGET_PATH, null)
        val finalPath = if (saved.isNullOrBlank()) getDefaultFolderPath() else saved
        _activeFolderPath.value = finalPath
        return finalPath
    }

    fun setTargetFolderPath(context: Context, fullPath: String) {
        val cleanPath = fullPath.trim()
        val folder = File(cleanPath)
        if (!folder.exists()) {
            folder.mkdirs()
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TARGET_PATH, folder.absolutePath)
            .apply()
        _activeFolderPath.value = folder.absolutePath
        addRecentFolder(context, folder.absolutePath)
    }

    fun getRecentFolders(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_RECENT_PATHS, "") ?: ""
        val list = raw.split("|").filter { it.isNotBlank() }.toMutableList()
        val current = getTargetFolderPath(context)
        if (!list.contains(current)) {
            list.add(0, current)
        }
        return list.take(6)
    }

    private fun addRecentFolder(context: Context, path: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_RECENT_PATHS, "") ?: ""
        val currentList = raw.split("|").filter { it.isNotBlank() && it != path }.toMutableList()
        currentList.add(0, path)
        val joined = currentList.take(6).joinToString("|")
        prefs.edit().putString(KEY_RECENT_PATHS, joined).apply()
    }

    fun isOverlayEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_OVERLAY_ENABLED, true)
    }

    fun setOverlayEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_OVERLAY_ENABLED, enabled)
            .apply()
    }

    fun isWebServerEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_WEB_SERVER_ENABLED, true)
    }

    fun setWebServerEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_WEB_SERVER_ENABLED, enabled)
            .apply()
    }

    fun getServerPort(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_SERVER_PORT, 8080)
    }

    fun isWebAuthEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_AUTH_ENABLED, true)
    }

    fun setWebAuthEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUTH_ENABLED, enabled)
            .apply()
    }

    fun isTwoFactorEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_TWO_FACTOR_ENABLED, true)
    }

    fun setTwoFactorEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_TWO_FACTOR_ENABLED, enabled)
            .apply()
    }

    fun setWebPin(context: Context, rawPin: String) {
        val clean = rawPin.trim()
        if (clean.length >= 4) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_PIN_HASH, sha256(clean))
                .apply()
        }
    }

    fun verifyWebPin(context: Context, inputPin: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedHash = prefs.getString(KEY_PIN_HASH, null) ?: sha256("1234")
        return savedHash == sha256(inputPin.trim())
    }

    fun hasCustomPin(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.contains(KEY_PIN_HASH)
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun getStorageStats(): StorageStats {
        return try {
            val stat = StatFs(Environment.getExternalStorageDirectory().path)
            val total = stat.totalBytes
            val free = stat.availableBytes
            val used = (total - free).coerceAtLeast(0L)
            val percent = if (total > 0) ((used * 100L) / total).toInt().coerceIn(0, 100) else 0
            val usedGb = String.format(Locale.US, "%.1f GB", used / (1024.0 * 1024.0 * 1024.0))
            val freeGb = String.format(Locale.US, "%.1f GB", free / (1024.0 * 1024.0 * 1024.0))
            val totalGb = String.format(Locale.US, "%.1f GB", total / (1024.0 * 1024.0 * 1024.0))
            StorageStats(
                totalBytes = total,
                freeBytes = free,
                usedBytes = used,
                usedPercent = percent,
                formattedSummary = "$usedGb usados de $totalGb ($freeGb livres)"
            )
        } catch (e: Exception) {
            StorageStats(0L, 0L, 0L, 0, "Armazenamento interno disponível")
        }
    }

    fun formatShortPath(fullPath: String): String {
        val root = getStorageRootPath()
        return if (fullPath.startsWith(root)) {
            val rel = fullPath.removePrefix(root).trimStart('/')
            if (rel.isEmpty()) "Memória Interna (Raiz)" else "Memória/$rel"
        } else {
            fullPath
        }
    }
}
