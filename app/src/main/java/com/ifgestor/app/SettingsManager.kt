package com.ifgestor.app

import android.content.Context
import android.os.Environment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.security.MessageDigest

object SettingsManager {
    private const val PREFS_NAME = "ifgestor_v2_prefs"
    private const val KEY_PREFIX = "prefix"
    private const val KEY_TARGET_PATH = "target_folder_path"
    private const val KEY_RECENT_PATHS = "recent_folder_paths"
    private const val KEY_OVERLAY_ENABLED = "overlay_enabled"
    private const val KEY_WEB_SERVER_ENABLED = "web_server_enabled"
    private const val KEY_SERVER_PORT = "web_server_port"

    // Chaves de Seguranca
    private const val KEY_AUTH_ENABLED = "web_auth_enabled"
    private const val KEY_TWO_FACTOR_ENABLED = "web_2fa_enabled"
    private const val KEY_PIN_HASH = "web_pin_sha256"

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

    private val _activeFolderPath = MutableStateFlow(getDefaultFolderPath())
    val activeFolderPath: StateFlow<String> = _activeFolderPath.asStateFlow()

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
        return list.take(5)
    }

    private fun addRecentFolder(context: Context, path: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_RECENT_PATHS, "") ?: ""
        val currentList = raw.split("|").filter { it.isNotBlank() && it != path }.toMutableList()
        currentList.add(0, path)
        val joined = currentList.take(5).joinToString("|")
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

    // Configuracoes de Seguranca (PIN + 2FA)
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
