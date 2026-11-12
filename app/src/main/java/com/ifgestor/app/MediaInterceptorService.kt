package com.ifgestor.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.media.MediaScannerConnection
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.MediaStore
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class MediaInterceptorService : Service() {

    companion object {
        const val ACTION_START = "com.ifgestor.app.ACTION_START"
        const val ACTION_STOP = "com.ifgestor.app.ACTION_STOP"
        const val ACTION_TOGGLE_OVERLAY = "com.ifgestor.app.ACTION_TOGGLE_OVERLAY"
        const val ACTION_REFRESH_CONFIG = "com.ifgestor.app.ACTION_REFRESH_CONFIG"
        const val ACTION_APPROVE_2FA = "com.ifgestor.app.ACTION_APPROVE_2FA"
        private const val CHANNEL_ID = "ifgestor_interceptor_channel"
        private const val AUTH_CHANNEL_ID = "ifgestor_2fa_channel"
        private const val NOTIFICATION_ID = 1001
        private const val TWO_FA_NOTIFICATION_ID = 1002
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val processedFiles = ConcurrentHashMap.newKeySet<String>()
    private var serviceStartEpochSeconds: Long = 0L
    private var mediaObserver: ContentObserver? = null
    private var overlayManager: FloatingOverlayManager? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        SettingsManager.syncStateFromPrefs(this)
        serviceStartEpochSeconds = (System.currentTimeMillis() / 1000) - 2
        acquireLocksForScreenOff()

        overlayManager = FloatingOverlayManager(this) {
            updateNotification()
        }

        SettingsManager.onTwoFactorRequestWithAction = { code, clientIp, actionDesc ->
            showTwoFactorNotification(code, clientIp, actionDesc)
        }

        registerMediaObservers()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopInterceptorService()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_OVERLAY -> {
                overlayManager?.toggleVisibility()
                return START_STICKY
            }
            ACTION_REFRESH_CONFIG -> {
                syncWebServerState()
                overlayManager?.refreshBubbleLabel()
                updateNotification()
                return START_STICKY
            }
            ACTION_APPROVE_2FA -> {
                val approvedCode = SettingsManager.approvePendingTwoFactor()
                val manager = getSystemService(NotificationManager::class.java)
                manager?.cancel(TWO_FA_NOTIFICATION_ID)
                if (approvedCode != null) {
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(
                            this,
                            "✓ Operação autorizada no computador!",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                return START_STICKY
            }
        }

        SettingsManager.setServiceRunning(true)
        syncWebServerState()

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

        if (SettingsManager.isOverlayEnabled(this)) {
            overlayManager?.show()
            overlayManager?.refreshBubbleLabel()
        } else {
            overlayManager?.hide()
        }

        return START_STICKY
    }

    private fun showTwoFactorNotification(code: String, clientIp: String, actionDesc: String) {
        val openAppIntent = Intent(this, MainActivity::class.java)
        val openAppPending = PendingIntent.getActivity(
            this,
            3,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val approveIntent = Intent(this, MediaInterceptorService::class.java).apply {
            action = ACTION_APPROVE_2FA
        }
        val approvePending = PendingIntent.getService(
            this,
            4,
            approveIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, AUTH_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_ifgestor_logo)
            .setContentTitle("Código 2FA: $code • $actionDesc")
            .setContentText("PC ($clientIp) aguarda confirmação. Digite o código ou aprove abaixo.")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "Operação solicitada: $actionDesc\n" +
                        "Computador: $clientIp\n" +
                        "Código 2FA: $code (ou toque em 'Aprovar Agora')"
                )
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openAppPending)
            .addAction(
                android.R.drawable.checkbox_on_background,
                "✓ Aprovar Agora",
                approvePending
            )
            .build()

        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(TWO_FA_NOTIFICATION_ID, notification)
    }

    private fun syncWebServerState() {
        if (SettingsManager.isWebServerEnabled(this)) {
            WebServerManager.startServer(this, SettingsManager.getServerPort(this))
        } else {
            WebServerManager.stopServer()
        }
    }

    private fun acquireLocksForScreenOff() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "IFGestor::BackgroundWakeLock"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }

            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val lockType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                @Suppress("DEPRECATION")
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wm.createWifiLock(lockType, "IFGestor::BackgroundWifiLock").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun releaseLocks() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (e: Exception) {
            e.printStackTrace()
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
                    val targetDir = File(SettingsManager.getTargetFolderPath(this))
                    val sourceFile = File(filePath)

                    if (!sourceFile.exists() || sourceFile.length() == 0L) continue

                    if (sourceFile.parentFile?.absolutePath == targetDir.absolutePath &&
                        prefix.isNotEmpty() && displayName.startsWith(prefix)
                    ) {
                        continue
                    }

                    if (processedFiles.contains(filePath)) continue

                    processedFiles.add(filePath)
                    moveAndRenameFile(sourceFile, displayName, prefix, targetDir)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun moveAndRenameFile(
        sourceFile: File,
        originalName: String,
        prefix: String,
        targetDir: File
    ) {
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
            processedFiles.add(destFile.absolutePath)
            MediaScannerConnection.scanFile(
                this,
                arrayOf(sourceFile.absolutePath, destFile.absolutePath),
                null,
                null
            )
            SettingsManager.registerInterceptedFile(newFileName)
            Handler(Looper.getMainLooper()).post {
                overlayManager?.refreshBubbleLabel()
                updateNotification()
            }
        } else {
            processedFiles.remove(sourceFile.absolutePath)
        }
    }

    private fun buildNotification(): Notification {
        val prefix = SettingsManager.getPrefix(this)
        val count = SettingsManager.interceptedCount.value
        val ip = WebServerManager.getDeviceIpAddress(this)
        val port = SettingsManager.getServerPort(this)
        val webInfo = if (WebServerManager.isRunning()) "PC: http://$ip:$port" else "Web: Desativado"

        val openAppIntent = Intent(this, MainActivity::class.java)
        val openAppPending = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val toggleOverlayIntent = Intent(this, MediaInterceptorService::class.java).apply {
            action = ACTION_TOGGLE_OVERLAY
        }
        val toggleOverlayPending = PendingIntent.getService(
            this,
            2,
            toggleOverlayIntent,
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

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_ifgestor_logo)
            .setContentTitle("IFGestor Ativo ($count capturados)")
            .setContentText("Prefixo: $prefix | $webInfo")
            .setOngoing(true)
            .setContentIntent(openAppPending)
            .addAction(
                android.R.drawable.ic_menu_view,
                "Widget Flutuante",
                toggleOverlayPending
            )
            .addAction(
                android.R.drawable.ic_media_pause,
                "Parar",
                stopPending
            )
            .build()
    }

    private fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)

            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Monitoramento da Câmera e Servidor Web",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Mantém o IFGestor interceptando fotos e servindo arquivos na rede"
            }

            val authChannel = NotificationChannel(
                AUTH_CHANNEL_ID,
                "Autorizações 2FA de Segurança",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Exibe solicitações de aprovação em duas etapas do computador"
            }

            manager?.createNotificationChannel(serviceChannel)
            manager?.createNotificationChannel(authChannel)
        }
    }

    private fun stopInterceptorService() {
        SettingsManager.onTwoFactorCodeGenerated = null
        SettingsManager.onTwoFactorRequestWithAction = null
        overlayManager?.hide()
        WebServerManager.stopServer()
        releaseLocks()
        SettingsManager.setServiceRunning(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        SettingsManager.onTwoFactorCodeGenerated = null
        SettingsManager.onTwoFactorRequestWithAction = null
        overlayManager?.hide()
        WebServerManager.stopServer()
        releaseLocks()
        mediaObserver?.let { contentResolver.unregisterContentObserver(it) }
        SettingsManager.setServiceRunning(false)
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
