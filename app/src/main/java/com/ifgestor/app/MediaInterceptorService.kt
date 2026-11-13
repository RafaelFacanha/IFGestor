package com.ifgestor.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.media.MediaScannerConnection
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class MediaInterceptorService : Service() {

    companion object {
        const val ACTION_START_CAMERA_INTERCEPTOR = "com.ifgestor.app.ACTION_START_CAMERA_INTERCEPTOR"
        const val ACTION_STOP_CAMERA_INTERCEPTOR = "com.ifgestor.app.ACTION_STOP_CAMERA_INTERCEPTOR"
        const val ACTION_START_WEB_SERVER = "com.ifgestor.app.ACTION_START_WEB_SERVER"
        const val ACTION_STOP_WEB_SERVER = "com.ifgestor.app.ACTION_STOP_WEB_SERVER"
        const val ACTION_SYNC_ENGINES = "com.ifgestor.app.ACTION_SYNC_ENGINES"
        const val ACTION_STOP_ALL = "com.ifgestor.app.ACTION_STOP_ALL"

        // Acoes de aprovacao ou recusa do 2FA via Notificacao Segura
        const val ACTION_2FA_DECISION = "com.ifgestor.app.ACTION_2FA_DECISION"
        const val EXTRA_2FA_REQUEST_ID = "extra_2fa_request_id"
        const val EXTRA_2FA_APPROVED = "extra_2fa_approved"

        private const val CHANNEL_SERVICE_ID = "ifgestor_foreground_channel"
        private const val CHANNEL_2FA_ID = "ifgestor_2fa_secret_channel"
        private const val NOTIFICATION_SERVICE_ID = 1001
        private const val NOTIFICATION_2FA_ID = 2002

        /**
         * Dispara notificacao 2FA blindada com VISIBILITY_SECRET:
         * No ecra de bloqueio, oculta o codigo de 6 digitos e os botoes de aprovacao.
         */
        fun show2faNotification(
            context: Context,
            requestId: String,
            code: String,
            title: String,
            description: String,
            clientIp: String
        ) {
            val appCtx = context.applicationContext
            ensureChannels(appCtx)

            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }

            val approveIntent = Intent(appCtx, MediaInterceptorService::class.java).apply {
                action = ACTION_2FA_DECISION
                putExtra(EXTRA_2FA_REQUEST_ID, requestId)
                putExtra(EXTRA_2FA_APPROVED, true)
            }
            val approvePending = PendingIntent.getService(appCtx, 210, approveIntent, flags)

            val rejectIntent = Intent(appCtx, MediaInterceptorService::class.java).apply {
                action = ACTION_2FA_DECISION
                putExtra(EXTRA_2FA_REQUEST_ID, requestId)
                putExtra(EXTRA_2FA_APPROVED, false)
            }
            val rejectPending = PendingIntent.getService(appCtx, 211, rejectIntent, flags)

            val openAppIntent = Intent(appCtx, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            val openAppPending = PendingIntent.getActivity(appCtx, 212, openAppIntent, flags)

            val publicLockScreenNotification = NotificationCompat.Builder(appCtx, CHANNEL_2FA_ID)
                .setSmallIcon(R.drawable.ic_ifgestor_logo)
                .setContentTitle("IFGestor • Solicitação de Segurança")
                .setContentText("Desbloqueie o aparelho para visualizar e autorizar a operação.")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .build()

            val secretNotification = NotificationCompat.Builder(appCtx, CHANNEL_2FA_ID)
                .setSmallIcon(R.drawable.ic_ifgestor_logo)
                .setContentTitle("🔐 $title ($clientIp)")
                .setContentText("Código 2FA: $code • $description")
                .setStyle(
                    NotificationCompat.BigTextStyle().bigText(
                        "$description\n🔑 Código de 6 dígitos: $code\n📍 Origem: $clientIp\nToque em Aprovar ou digite o código no navegador."
                    )
                )
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                .setPublicVersion(publicLockScreenNotification)
                .setAutoCancel(true)
                .setContentIntent(openAppPending)
                .addAction(android.R.drawable.checkbox_on_background, "✓ Aprovar", approvePending)
                .addAction(android.R.drawable.ic_delete, "✕ Recusar", rejectPending)
                .build()

            val nm = appCtx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_2FA_ID, secretNotification)
        }

        fun cancel2faNotification(context: Context) {
            try {
                val nm = context.applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.cancel(NOTIFICATION_2FA_ID)
            } catch (_: Exception) {
            }
        }

        private fun ensureChannels(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

                val serviceChannel = NotificationChannel(
                    CHANNEL_SERVICE_ID,
                    "IFGestor Motores em Segundo Plano",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Mantém o interceptador de fotos e o servidor web ativos."
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }

                val authChannel = NotificationChannel(
                    CHANNEL_2FA_ID,
                    "IFGestor Autenticação 2FA Segura",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Alertas de aprovação 2FA ocultos no ecrã de bloqueio."
                    lockscreenVisibility = Notification.VISIBILITY_SECRET
                    enableVibration(true)
                }

                nm.createNotificationChannel(serviceChannel)
                nm.createNotificationChannel(authChannel)
            }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private var watcherScheduler: ScheduledExecutorService? = null

    private var mediaObserver: ContentObserver? = null
    private var wifiNetworkCallback: ConnectivityManager.NetworkCallback? = null

    private val processedFiles = ConcurrentHashMap.newKeySet<String>()
    @Volatile
    private var interceptorActivatedAt: Long = System.currentTimeMillis()

    override fun onCreate() {
        super.onCreate()
        ensureChannels(this)
        registerWifiDropMonitor()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action

        if (action == ACTION_2FA_DECISION) {
            val reqId = intent.getStringExtra(EXTRA_2FA_REQUEST_ID) ?: ""
            val approved = intent.getBooleanExtra(EXTRA_2FA_APPROVED, false)
            WebServerManager.handle2faDecisionFromNotification(applicationContext, reqId, approved)
            cancel2faNotification(applicationContext)
            return START_STICKY
        }

        when (action) {
            ACTION_START_CAMERA_INTERCEPTOR -> {
                interceptorActivatedAt = System.currentTimeMillis() - 1500L
                SettingsManager.setCameraInterceptorEnabled(this, true)
            }
            ACTION_STOP_CAMERA_INTERCEPTOR -> {
                SettingsManager.setCameraInterceptorEnabled(this, false)
            }
            ACTION_START_WEB_SERVER -> {
                if (!SettingsManager.isPinChangeRequired(this)) {
                    SettingsManager.setWebServerEnabled(this, true)
                }
            }
            ACTION_STOP_WEB_SERVER -> {
                SettingsManager.setWebServerEnabled(this, false)
            }
            ACTION_STOP_ALL -> {
                SettingsManager.setCameraInterceptorEnabled(this, false)
                SettingsManager.setWebServerEnabled(this, false)
            }
        }

        syncEnginesState()
        return START_STICKY
    }

    private fun syncEnginesState() {
        val camEnabled = SettingsManager.isCameraInterceptorEnabled(this)
        val webEnabled = SettingsManager.isWebServerEnabled(this)

        if (!camEnabled && !webEnabled) {
            stopCameraInterceptorEngine()
            stopWebServerEngine()
            FloatingOverlayManager.hide()
            IFGestorHomeWidget.requestWidgetUpdate(this)
            stopForeground(true)
            stopSelf()
            return
        }

        startForeground(NOTIFICATION_SERVICE_ID, buildForegroundNotification(camEnabled, webEnabled))

        if (camEnabled) {
            startCameraInterceptorEngine()
            if (SettingsManager.isFloatingWidgetEnabled(this)) {
                FloatingOverlayManager.show(this)
                FloatingOverlayManager.refresh(this)
            } else {
                FloatingOverlayManager.hide()
            }
        } else {
            stopCameraInterceptorEngine()
            FloatingOverlayManager.hide()
        }

        if (webEnabled) {
            startWebServerEngine()
        } else {
            stopWebServerEngine()
        }

        IFGestorHomeWidget.requestWidgetUpdate(this)
    }

    private fun startCameraInterceptorEngine() {
        if (mediaObserver == null) {
            interceptorActivatedAt = System.currentTimeMillis() - 1500L
            val observer = object : ContentObserver(mainHandler) {
                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    super.onChange(selfChange, uri)
                    if (SettingsManager.isCameraInterceptorEnabled(applicationContext)) {
                        ioExecutor.execute {
                            scanAndInterceptNewMedia()
                        }
                    }
                }
            }
            try {
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
                mediaObserver = observer
            } catch (_: Exception) {
            }
        }

        if (watcherScheduler == null || watcherScheduler?.isShutdown == true) {
            watcherScheduler = Executors.newSingleThreadScheduledExecutor().apply {
                scheduleWithFixedDelay({
                    if (SettingsManager.isCameraInterceptorEnabled(applicationContext)) {
                        scanAndInterceptNewMedia()
                    }
                }, 1500L, 1800L, TimeUnit.MILLISECONDS)
            }
        }
    }

    private fun stopCameraInterceptorEngine() {
        try {
            mediaObserver?.let { contentResolver.unregisterContentObserver(it) }
        } catch (_: Exception) {
        }
        mediaObserver = null

        try {
            watcherScheduler?.shutdownNow()
        } catch (_: Exception) {
        }
        watcherScheduler = null
    }

    @Synchronized
    private fun scanAndInterceptNewMedia() {
        if (!SettingsManager.isCameraInterceptorEnabled(this)) return

        val cameraRoot = SettingsManager.getPhysicalCameraRootDir()
        if (!cameraRoot.exists() || !cameraRoot.isDirectory) return

        val sandboxRootPath = SettingsManager.getSandboxRootDir().canonicalPath
        val candidates = cameraRoot.listFiles { file ->
            file.isFile &&
                !file.name.startsWith(".") &&
                !file.name.endsWith(".part") &&
                !file.name.endsWith(".tmp") &&
                file.lastModified() >= interceptorActivatedAt &&
                SettingsManager.isMediaFile(file.name)
        }?.sortedBy { it.lastModified() } ?: return

        for (sourceFile in candidates) {
            val canonicalSource = try {
                sourceFile.canonicalPath
            } catch (e: Exception) {
                continue
            }

            if (canonicalSource.startsWith(sandboxRootPath)) continue
            if (processedFiles.contains(canonicalSource)) continue
            if (SettingsManager.isFileLocked(sourceFile)) continue

            if (!waitForFileWriteCompletion(sourceFile)) continue

            processedFiles.add(canonicalSource)
            moveCapturedFileToSandbox(sourceFile)
        }
    }

    private fun waitForFileWriteCompletion(file: File): Boolean {
        return try {
            val size1 = file.length()
            if (size1 <= 0L) {
                Thread.sleep(250L)
            }
            val sizeA = file.length()
            Thread.sleep(220L)
            val sizeB = file.length()
            sizeB > 0L && sizeA == sizeB
        } catch (e: Exception) {
            false
        }
    }

    private fun moveCapturedFileToSandbox(sourceFile: File) {
        SettingsManager.tryLockFile(sourceFile)
        try {
            val targetDir = SettingsManager.getTargetDirectory(this)
            if (!targetDir.exists()) targetDir.mkdirs()

            val prefix = SettingsManager.getFilePrefix(this)
            val ext = sourceFile.extension.ifEmpty { "jpg" }
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val desiredName = "${prefix}${timestamp}.$ext"

            val destFile = SettingsManager.resolveUniqueFile(targetDir, desiredName)

            val movedOk = if (sourceFile.renameTo(destFile)) {
                true
            } else {
                try {
                    sourceFile.copyTo(destFile, overwrite = false)
                    sourceFile.delete()
                    true
                } catch (_: Exception) {
                    false
                }
            }

            if (movedOk && destFile.exists()) {
                processedFiles.add(destFile.canonicalPath)
                SettingsManager.incrementSessionInterceptCount(this)

                MediaScannerConnection.scanFile(
                    applicationContext,
                    arrayOf(sourceFile.absolutePath, destFile.absolutePath),
                    null,
                    null
                )

                vibrateShortConfirmation()

                SettingsManager.appendAuditLog(
                    context = this,
                    ip = "CAMERA/LOCAL",
                    category = "INTERCEPTADOR",
                    action = "CAPTURA_MOVIDA",
                    details = "${sourceFile.name} → ${SettingsManager.getSandboxDisplayPath(this)}/${destFile.name}",
                    success = true
                )

                mainHandler.post {
                    FloatingOverlayManager.refresh(applicationContext)
                    IFGestorHomeWidget.requestWidgetUpdate(applicationContext)
                    refreshNotificationStatus()
                }
            } else {
                processedFiles.remove(sourceFile.canonicalPath)
            }
        } catch (_: Exception) {
        } finally {
            SettingsManager.unlockFile(sourceFile)
        }
    }

    private fun vibrateShortConfirmation() {
        try {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(45L, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(45L)
            }
        } catch (_: Exception) {
        }
    }

    private fun startWebServerEngine() {
        val port = SettingsManager.getServerPort(this)
        val https = SettingsManager.isHttpsEnabled(this)
        WebServerManager.startServer(applicationContext, port, https)
        NetworkDiscoveryManager.startDiscoveryAndMdns(applicationContext, port, https)
        refreshNotificationStatus()
    }

    private fun stopWebServerEngine() {
        WebServerManager.stopServer(applicationContext)
        NetworkDiscoveryManager.stopDiscoveryAndMdns()
        cancel2faNotification(applicationContext)
    }

    private fun registerWifiDropMonitor() {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val req = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()

            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onLost(network: Network) {
                    super.onLost(network)
                    if (SettingsManager.isWebServerEnabled(applicationContext)) {
                        WebServerManager.revokeAllSessions(applicationContext, "Queda/Desconexão de Wi-Fi")
                        mainHandler.post {
                            IFGestorHomeWidget.requestWidgetUpdate(applicationContext)
                            refreshNotificationStatus()
                        }
                    }
                }
            }
            cm.registerNetworkCallback(req, callback)
            wifiNetworkCallback = callback
        } catch (_: Exception) {
        }
    }

    private fun refreshNotificationStatus() {
        val camOn = SettingsManager.isCameraInterceptorEnabled(this)
        val webOn = SettingsManager.isWebServerEnabled(this)
        if (!camOn && !webOn) return

        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_SERVICE_ID, buildForegroundNotification(camOn, webOn))
        } catch (_: Exception) {
        }
    }

    private fun buildForegroundNotification(camEnabled: Boolean, webEnabled: Boolean): Notification {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val openAppPending = PendingIntent.getActivity(this, 301, openAppIntent, flags)

        val toggleCamIntent = Intent(this, MediaInterceptorService::class.java).apply {
            action = if (camEnabled) ACTION_STOP_CAMERA_INTERCEPTOR else ACTION_START_CAMERA_INTERCEPTOR
        }
        val toggleCamPending = PendingIntent.getService(this, 302, toggleCamIntent, flags)

        val toggleWebIntent = Intent(this, MediaInterceptorService::class.java).apply {
            action = if (webEnabled) ACTION_STOP_WEB_SERVER else ACTION_START_WEB_SERVER
        }
        val toggleWebPending = PendingIntent.getService(this, 303, toggleWebIntent, flags)

        val stopAllIntent = Intent(this, MediaInterceptorService::class.java).apply {
            action = ACTION_STOP_ALL
        }
        val stopAllPending = PendingIntent.getService(this, 304, stopAllIntent, flags)

        val sessionCount = SettingsManager.getSessionInterceptCount(this)
        val folderCount = SettingsManager.getFolderMediaCount(this)
        val displayFolder = SettingsManager.getSandboxDisplayPath(this)
        val scheme = if (SettingsManager.isHttpsEnabled(this)) "https" else "http"
        val port = SettingsManager.getServerPort(this)
        val domain = SettingsManager.getActiveMdnsDomain(this)

        val title = when {
            camEnabled && webEnabled -> "🟢 Câmera Ativa • 🌐 $scheme://$domain:$port"
            camEnabled -> "🟢 Interceptador Ativo • Sessão: $sessionCount | Pasta: $folderCount"
            else -> "🌐 Site Web Ativo: $scheme://$domain:$port"
        }

        val subtitle = "Pasta: $displayFolder • Sessão: $sessionCount | Total: $folderCount"

        return NotificationCompat.Builder(this, CHANNEL_SERVICE_ID)
            .setSmallIcon(R.drawable.ic_ifgestor_logo)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setStyle(NotificationCompat.BigTextStyle().bigText(subtitle))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openAppPending)
            .addAction(
                android.R.drawable.ic_menu_camera,
                if (camEnabled) "Parar Câmera" else "Ligar Câmera",
                toggleCamPending
            )
            .addAction(
                android.R.drawable.ic_menu_share,
                if (webEnabled) "Parar Web" else "Ligar Web",
                toggleWebPending
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Desligar Tudo",
                stopAllPending
            )
            .build()
    }

    override fun onDestroy() {
        stopCameraInterceptorEngine()
        stopWebServerEngine()
        FloatingOverlayManager.hide()
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            wifiNetworkCallback?.let { cm.unregisterNetworkCallback(it) }
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
