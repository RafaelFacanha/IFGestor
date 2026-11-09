package com.ifgestor.app

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Environment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

object SettingsManager {
    private const val PREFS_NAME = "ifgestor_prefs_v2"
    private const val KEY_PREFIX = "prefix"
    private const val KEY_TARGET_PATH = "target_folder_path"
    private const val KEY_RECENT_PATHS = "recent_folder_paths"
    private const val KEY_WIDGET_ENABLED = "floating_widget_enabled"
    private const val KEY_WEB_PORT = "web_server_port"
    private const val KEY_WEB_PIN = "web_server_pin"

    private val _isServiceRunning = MutableStateFlow(false)
    val isServiceRunning: StateFlow<Boolean> = _isServiceRunning.asStateFlow()

    private val _isOverlayVisible = MutableStateFlow(false)
    val isOverlayVisible: StateFlow<Boolean> = _isOverlayVisible.asStateFlow()

    private val _isWebServerRunning = MutableStateFlow(false)
    val isWebServerRunning: StateFlow<Boolean> = _isWebServerRunning.asStateFlow()

    private val _interceptedCount = MutableStateFlow(0)
    val interceptedCount: StateFlow<Int> = _interceptedCount.asStateFlow()

    private val _lastInterceptedFile = MutableStateFlow("Nenhum arquivo ainda")
    val lastInterceptedFile: StateFlow<String> = _lastInterceptedFile.asStateFlow()

    private val _currentPrefixFlow = MutableStateFlow("Vistoria_")
    val currentPrefixFlow: StateFlow<String> = _currentPrefixFlow.asStateFlow()

    private val _currentTargetPathFlow = MutableStateFlow(getDefaultTargetDir().absolutePath)
    val currentTargetPathFlow: StateFlow<String> = _currentTargetPathFlow.asStateFlow()

    private val _lastConnectedIp = MutableStateFlow("Nenhum acesso recente")
    val lastConnectedIp: StateFlow<String> = _lastConnectedIp.asStateFlow()

    fun getRootStorageDir(): File {
        return Environment.getExternalStorageDirectory()
    }

    fun getDefaultTargetDir(): File {
        val dcim = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
        return File(dcim, "IFGestor_Capturas")
    }

    fun syncFlowsFromPrefs(context: Context) {
        _currentPrefixFlow.value = getPrefix(context)
        _currentTargetPathFlow.value = getTargetFolderPath(context)
    }

    fun setServiceRunning(running: Boolean) {
        _isServiceRunning.value = running
        if (running) {
            _interceptedCount.value = 0
        }
    }

    fun setOverlayVisible(visible: Boolean) {
        _isOverlayVisible.value = visible
    }

    fun setWebServerRunning(running: Boolean) {
        _isWebServerRunning.value = running
    }

    fun registerConnectedClientIp(ip: String) {
        _lastConnectedIp.value = ip
    }

    fun registerInterceptedFile(fileName: String) {
        _interceptedCount.value += 1
        _lastInterceptedFile.value = fileName
    }

    fun getPrefix(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val value = prefs.getString(KEY_PREFIX, "Vistoria_") ?: "Vistoria_"
        _currentPrefixFlow.value = value
        return value
    }

    fun setPrefix(context: Context, prefix: String) {
        val sanitized = prefix.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PREFIX, sanitized)
            .apply()
        _currentPrefixFlow.value = sanitized
    }

    fun getTargetFolderPath(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val defaultPath = getDefaultTargetDir().absolutePath
        val saved = prefs.getString(KEY_TARGET_PATH, defaultPath) ?: defaultPath
        val file = File(saved)
        val validPath = if (isPathSafe(file)) file.absolutePath else defaultPath
        _currentTargetPathFlow.value = validPath
        return validPath
    }

    fun setTargetFolderPath(context: Context, fullPath: String) {
        val file = File(fullPath)
        if (!isPathSafe(file)) return
        if (!file.exists()) {
            file.mkdirs()
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TARGET_PATH, file.absolutePath)
            .apply()
        _currentTargetPathFlow.value = file.absolutePath
        addRecentFolder(context, file.absolutePath)
    }

    fun getRecentFolders(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_RECENT_PATHS, "") ?: ""
        val list = raw.split("|")
            .map { it.trim() }
            .filter { it.isNotEmpty() && isPathSafe(File(it)) }
            .distinct()
        return if (list.isEmpty()) listOf(getTargetFolderPath(context
