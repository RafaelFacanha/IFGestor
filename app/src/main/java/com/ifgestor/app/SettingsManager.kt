package com.ifgestor.app

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object SettingsManager {
    private const val PREFS_NAME = "ifgestor_prefs"
    private const val KEY_PREFIX = "prefix"
    private const val KEY_FOLDER = "local_folder"
    private const val KEY_DELETE_LOCAL = "delete_local"

    private const val KEY_SMB_ENABLED = "smb_enabled"
    private const val KEY_SMB_HOST = "smb_host"
    private const val KEY_SMB_SHARE = "smb_share"
    private const val KEY_SMB_USER = "smb_user"
    private const val KEY_SMB_PASS = "smb_pass"
    private const val KEY_SMB_REMOTE_FOLDER = "smb_remote_folder"
    private const val KEY_PENDING_QUEUE = "smb_pending_queue"

    private val _isServiceRunning = MutableStateFlow(false)
    val isServiceRunning: StateFlow<Boolean> = _isServiceRunning.asStateFlow()

    private val _interceptedCount = MutableStateFlow(0)
    val interceptedCount: StateFlow<Int> = _interceptedCount.asStateFlow()

    private val _syncedCount = MutableStateFlow(0)
    val syncedCount: StateFlow<Int> = _syncedCount.asStateFlow()

    private val _pendingQueueCount = MutableStateFlow(0)
    val pendingQueueCount: StateFlow<Int> = _pendingQueueCount.asStateFlow()

    private val _lastInterceptedFile = MutableStateFlow("Nenhum arquivo ainda")
    val lastInterceptedFile: StateFlow<String> = _lastInterceptedFile.asStateFlow()

    private val _syncStatus = MutableStateFlow("Aguardando capturas")
    val syncStatus: StateFlow<String> = _syncStatus.asStateFlow()

    fun setServiceRunning(running: Boolean) {
        _isServiceRunning.value = running
        if (running) {
            _interceptedCount.value = 0
            _syncedCount.value = 0
        }
    }

    fun registerInterceptedFile(fileName: String) {
        _interceptedCount.value += 1
        _lastInterceptedFile.value = fileName
    }

    fun incrementSyncedCount() {
        _syncedCount.value += 1
    }

    fun updateSyncStatus(status: String) {
        _syncStatus.value = status
    }

    fun refreshQueueState(context: Context) {
        _pendingQueueCount.value = getPendingQueue(context).size
    }

    @Synchronized
    fun getPendingQueue(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedSet = prefs.getStringSet(KEY_PENDING_QUEUE, emptySet()) ?: emptySet()
        return savedSet.toList().sorted()
    }

    @Synchronized
    fun addToPendingQueue(context: Context, filePath: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = HashSet(prefs.getStringSet(KEY_PENDING_QUEUE, emptySet()) ?: emptySet())
        current.add(filePath)
        prefs.edit().putStringSet(KEY_PENDING_QUEUE, current).apply()
        _pendingQueueCount.value = current.size
    }

    @Synchronized
    fun removeFromPendingQueue(context: Context, filePath: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = HashSet(prefs.getStringSet(KEY_PENDING_QUEUE, emptySet()) ?: emptySet())
        current.remove(filePath)
        prefs.edit().putStringSet(KEY_PENDING_QUEUE, current).apply()
        _pendingQueueCount.value = current.size
    }

    @Synchronized
    fun clearPendingQueue(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_PENDING_QUEUE).apply()
        _pendingQueueCount.value = 0
    }

    fun getPrefix(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_PREFIX, "Vistoria_") ?: "Vistoria_"
    }

    fun setPrefix(context: Context, prefix: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PREFIX, prefix.trim())
            .apply()
    }

    fun getLocalFolder(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_FOLDER, "IFGestor_Capturas") ?: "IFGestor_Capturas"
    }

    fun setLocalFolder(context: Context, folder: String) {
        val sanitized = folder.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_FOLDER, sanitized.ifEmpty { "IFGestor_Capturas" })
            .apply()
    }

    fun getDeleteLocalAfterSync(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_DELETE_LOCAL, false)
    }

    fun setDeleteLocalAfterSync(context: Context, delete: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DELETE_LOCAL, delete)
            .apply()
    }

    fun isSmbSyncEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_SMB_ENABLED, false)
    }

    fun setSmbSyncEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SMB_ENABLED, enabled)
            .apply()
    }

    fun getSmbConfig(context: Context): SmbConnectionConfig {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return SmbConnectionConfig(
            host = prefs.getString(KEY_SMB_HOST, "") ?: "",
            shareName = prefs.getString(KEY_SMB_SHARE, "") ?: "",
            username = prefs.getString(KEY_SMB_USER, "") ?: "",
            password = prefs.getString(KEY_SMB_PASS, "") ?: ""
        )
    }

    fun saveSmbConfig(context: Context, config: SmbConnectionConfig) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SMB_HOST, config.host.trim())
            .putString(KEY_SMB_SHARE, config.shareName.trim())
            .putString(KEY_SMB_USER, config.username.trim())
            .putString(KEY_SMB_PASS, config.password)
            .apply()
    }

    fun getSmbRemoteFolder(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_SMB_REMOTE_FOLDER, "") ?: ""
    }

    fun setSmbRemoteFolder(context: Context, remoteFolder: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SMB_REMOTE_FOLDER, remoteFolder.trim().trim('\\', '/'))
            .apply()
    }
}
