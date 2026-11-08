package com.ifgestor.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class MediaInterceptorService : Service() {

    companion object {
        const val ACTION_START = "com.ifgestor.app.ACTION_START"
        const val ACTION_STOP = "com.ifgestor.app.ACTION_STOP"
        const val ACTION_SYNC_NOW = "com.ifgestor.app.ACTION_SYNC_NOW"
        private const val CHANNEL_ID = "ifgestor_interceptor_channel"
        private const val NOTIFICATION_ID = 1001
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val processedFiles = ConcurrentHashMap.newKeySet<String>()
    private var serviceStartEpochSeconds: Long = 0L
    private var mediaObserver: ContentObserver? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        SettingsManager.refreshQueueState(this)
        serviceStartEpochSeconds = (System.currentTimeMillis() / 1000) - 2
        registerMediaObservers()
        startPeriodicQueueRetry()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopInterceptorService()
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_SYNC_NOW) {
            serviceScope.launch {
                SmbClientManager.processPendingQueue(this@MediaInterceptorService)
                updateNotification()
            }
            return START_STICKY
        }

        SettingsManager.setServiceRunning(true)
        val notification = buildNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        if (SettingsManager.isSmbSyncEnabled(this)) {
            serviceScope.launch {
                SmbClientManager.processPendingQueue(this@MediaInterceptorService)
                updateNotification()
            }
        }

        return START_STICKY
    }

    private fun startPeriodicQueueRetry() {
        serviceScope.launch {
            while (isActive) {
                delay(25_000) // Tenta descarregar a fila a cada 25s se houver itens pendentes
                if (SettingsManager.isSmbSyncEnabled(this@MediaInterceptorService)) {
                    val pending = SettingsManager.getPendingQueue(this@MediaInterceptorService)
                    if (pending.isNotEmpty()) {
                        SmbClientManager.processPendingQueue(this@MediaInterceptorService)
                        updateNotification()
                    }
                }
            }
        }
    }

    private fun registerMediaObservers() {
        mediaObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                super.onChange(selfChange, uri)
                serviceScope.launch {
                    delay(400)
                    scanRecentCameraMedia()
                }
            }
        }

        mediaObserver?.let { observer ->
            contentResolver.registerContentObserver(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                true,
                observer
            )
            contentResolver.registerContentObserver(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                true,
                observer
            )
        }
    }

    private fun scanRecentCameraMedia() {
        queryAndIntercept(MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        queryAndIntercept(MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
    }

    private fun queryAndIntercept(collectionUri: Uri) {
        val projection = mutableListOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.DATE_ADDED
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.MediaColumns.IS_PENDING)
            }
        }.toTypedArray()

        val selection = "${MediaStore.MediaColumns.DATE_ADDED} >= ?"
        val selectionArgs = arrayOf(serviceStartEpochSeconds.toString())
        val sortOrder = "${MediaStore.MediaColumns.DATE_ADDED} DESC"

        try {
            contentResolver.query(
                collectionUri,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val dataCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
                val pendingCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    cursor.getColumnIndex(MediaStore.MediaColumns.IS_PENDING)
                } else -1

                while (cursor.moveToNext()) {
                    val displayName = cursor.getString(nameCol) ?: continue
                    val filePath = cursor.getString(dataCol) ?: continue

                    if (pendingCol != -1 && cursor.getInt(pendingCol) == 1) {
                        continue
                    }

                    if (displayName.startsWith(".pending") || displayName.startsWith(".trashed")) {
                        continue
                    }

                    val normalizedPath = filePath.replace("\\", "/")
                    val isFromCamera = normalizedPath.contains("/DCIM/Camera/", ignoreCase = true) ||
                        normalizedPath.contains("/DCIM/100ANDRO/", ignoreCase = true)

                    if (!isFromCamera) continue

                    val prefix = SettingsManager.getPrefix(this)
                    if (prefix.isNotEmpty() && displayName.startsWith(prefix)) continue

                    if (processedFiles.contains(filePath)) continue

                    val sourceFile = File(filePath)
                    if (!sourceFile.exists() || sourceFile.length() == 0L) continue

                    processedFiles.add(filePath)
                    moveAndRenameFile(sourceFile, displayName, prefix)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun moveAndRenameFile(sourceFile: File, originalName: String, prefix: String) {
        val targetFolderName = SettingsManager.getLocalFolder(this)
        val baseDcim = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
        val targetDir = File(baseDcim, targetFolderName)

        if (!targetDir.exists()) {
            targetDir.mkdirs()
        }

        val newFileName = "${prefix}${originalName}"
        val destFile = File(targetDir, newFileName)

        val moved = if (sourceFile.renameTo(destFile)) {
            true
        } else {
            try {
                sourceFile.copyTo(destFile, overwrite = true)
                sourceFile.delete()
                true
            } catch (e: Exception) {
                false
            }
        }

        if (moved) {
            MediaScannerConnection.scanFile(
                this,
                arrayOf(sourceFile.absolutePath, destFile.absolutePath),
                null,
                null
            )
            SettingsManager.registerInterceptedFile(newFileName)

            if (SettingsManager.isSmbSyncEnabled(this)) {
                SettingsManager.addToPendingQueue(this, destFile.absolutePath)
                updateNotification()
                serviceScope.launch {
                    SmbClientManager.processPendingQueue(this@MediaInterceptorService)
                    updateNotification()
                }
            } else {
                SettingsManager.updateSyncStatus("Salvo na pasta local: $newFileName")
                updateNotification()
            }
        } else {
            processedFiles.remove(sourceFile.absolutePath)
        }
    }

    private fun buildNotification(): Notification {
        val prefix = SettingsManager.getPrefix(this)
        val captured = SettingsManager.interceptedCount.value
        val synced = SettingsManager.syncedCount.value
        val pending = SettingsManager.pendingQueueCount.value

        val openAppIntent = Intent(this, MainActivity::class.java)
        val openAppPending = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, MediaInterceptorService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val titleText = if (SettingsManager.isSmbSyncEnabled(this)) {
            "IFGestor Ativo (Capturados: $captured | PC: $synced | Fila: $pending)"
        } else {
            "IFGestor Ativo ($captured capturados localmente)"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle(titleText)
            .setContentText("Prefixo: $prefix | ${SettingsManager.syncStatus.value}")
            .setOngoing(true)
            .setContentIntent(openAppPending)
            .addAction(
                android.R.drawable.ic_media_pause,
                "Parar Interceptador",
                stopPending
            )
            .build()
    }

    private fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Monitoramento da Câmera",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Mantém o IFGestor interceptando fotos e vídeos em segundo plano"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun stopInterceptorService() {
        SettingsManager.setServiceRunning(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        mediaObserver?.let { contentResolver.unregisterContentObserver(it) }
        SettingsManager.setServiceRunning(false)
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
