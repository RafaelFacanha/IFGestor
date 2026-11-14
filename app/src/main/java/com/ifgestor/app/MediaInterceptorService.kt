package com.ifgestor.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class MediaInterceptorService : Service() {

    private lateinit var settings: SettingsManager
    private lateinit var audit: AuditLogManager
    private lateinit var taskManager: FileLockAndTaskManager
    private lateinit var webServer: WebServerManager
    private lateinit var mdnsManager: MdnsDiscoveryManager

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var mediaObserver: ContentObserver? = null
    private var observerRegistered = false

    // ITEM 3: Trava Anti-Loop para impedir reprocessamento do mesmo arquivo pelo ContentObserver
    private val recentlyProcessedUris = ConcurrentHashMap.newKeySet<String>()
    private val recentlyProcessedPaths = ConcurrentHashMap.newKeySet<String>()
    private var serviceStartTimestamp: Long = System.currentTimeMillis()

    override fun onCreate() {
        super.onCreate()
        settings = SettingsManager(applicationContext)
        audit = AuditLogManager.getInstance(applicationContext)
        taskManager = FileLockAndTaskManager.getInstance(applicationContext)
        webServer = WebServerManager.getInstance(applicationContext)
        mdnsManager = MdnsDiscoveryManager.getInstance(applicationContext)

        ensureForegroundChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_SYNC_ENGINES

        when (action) {
            ACTION_START_INTERCEPTOR -> setInterceptorEngineState(true)
            ACTION_STOP_INTERCEPTOR -> setInterceptorEngineState(false)
            ACTION_TOGGLE_INTERCEPTOR -> setInterceptorEngineState(!_interceptorRunningFlow.value)

            ACTION_START_SERVER -> setWebServerEngineState(true)
            ACTION_STOP_SERVER -> setWebServerEngineState(false)
            ACTION_TOGGLE_SERVER -> setWebServerEngineState(!_webServerRunningFlow.value)

            ACTION_START_BOTH -> {
                setInterceptorEngineState(true)
                setWebServerEngineState(true)
            }

            ACTION_STOP_ALL -> {
                setInterceptorEngineState(false)
                setWebServerEngineState(false)
            }

            ACTION_REFRESH_UI -> {
                refreshNotificationsAndWidgets()
            }

            else -> {
                // Restaura o estado salvo nas preferências se o serviço for reiniciado pelo sistema
                val savedInterceptor = settings.isInterceptorActive()
                val savedServer = settings.isWebServerActive()
                setInterceptorEngineState(savedInterceptor)
                setWebServerEngineState(savedServer)
            }
        }

        evaluateServiceLifecycle()
        return START_STICKY
    }

    // =========================================================================
    // ITEM 12: Controle Independente dos Dois Motores (Interceptador vs Servidor Web)
    // =========================================================================

    private fun setInterceptorEngineState(enable: Boolean) {
        if (enable) {
            if (!observerRegistered) {
                serviceStartTimestamp = (System.currentTimeMillis() / 1000L) - 2L
                registerMediaContentObserver()
                observerRegistered = true
                audit.logEvent(
                    action = "INTERCEPTADOR_ATIVADO",
                    actor = "Motor Mobile",
                    details = "Monitorando câmera -> DCIM/Camera/IFgestor/${settings.getTargetSubfolder()} (Prefixo: ${settings.getFilePrefix()})",
                    success = true
                )
            }
            settings.setInterceptorActive(true)
            _interceptorRunningFlow.value = true

            if (settings.isFloatingWidgetEnabled()) {
                FloatingOverlayManager.getInstance(applicationContext).showOrUpdateOverlay()
            }
        } else {
            if (observerRegistered) {
                unregisterMediaContentObserver()
                observerRegistered = false
                audit.logEvent(
                    action = "INTERCEPTADOR_PAUSADO",
                    actor = "Motor Mobile",
                    details = "Interceptação automática de fotos e vídeos desativada",
                    success = true
                )
            }
            settings.setInterceptorActive(false)
            _interceptorRunningFlow.value = false
            FloatingOverlayManager.getInstance(applicationContext).hideOverlay()
        }
        refreshNotificationsAndWidgets()
    }

    private fun setWebServerEngineState(enable: Boolean) {
        if (enable) {
            if (!webServer.isServerRunning()) {
                val started = webServer.startServer(settings.getServerPort())
                _webServerRunningFlow.value = started
                settings.setWebServerActive(started)
            } else {
                _webServerRunningFlow.value = true
                settings.setWebServerActive(true)
            }
        } else {
            if (webServer.isServerRunning()) {
                webServer.stopServer()
            }
            _webServerRunningFlow.value = false
            settings.setWebServerActive(false)
        }
        refreshNotificationsAndWidgets()
    }

    private fun evaluateServiceLifecycle() {
        val anyEngineActive = _interceptorRunningFlow.value || _webServerRunningFlow.value
        if (anyEngineActive) {
            startForeground(NOTIFICATION_ID_FOREGROUND, buildForegroundNotification())
            IFGestorHomeWidgetProvider.updateAllWidgets(applicationContext)
        } else {
            FloatingOverlayManager.getInstance(applicationContext).hideOverlay()
            IFGestorHomeWidgetProvider.updateAllWidgets(applicationContext)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            stopSelf()
        }
    }

    fun refreshNotificationsAndWidgets() {
        if (_interceptorRunningFlow.value || _webServerRunningFlow.value) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID_FOREGROUND, buildForegroundNotification())
        }
        if (_interceptorRunningFlow.value && settings.isFloatingWidgetEnabled()) {
            FloatingOverlayManager.getInstance(applicationContext).showOrUpdateOverlay()
        } else {
            FloatingOverlayManager.getInstance(applicationContext).hideOverlay()
        }
        IFGestorHomeWidgetProvider.updateAllWidgets(applicationContext)
    }

    // =========================================================================
    // ITEM 3: Interceptador de Mídia com Trava Anti-Loop e Sandbox Estrito
    // =========================================================================

    private fun registerMediaContentObserver() {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                super.onChange(selfChange, uri)
                if (!_interceptorRunningFlow.value || uri == null) return
                val uriStr = uri.toString()

                // Ignora URIs genéricas sem ID numérico ou já processadas recentemente
                val id = try {
                    ContentUris.parseId(uri)
                } catch (_: Exception) {
                    -1L
                }
                if (id <= 0L) return

                val key = "$uriStr#$id"
                if (!recentlyProcessedUris.add(key)) return

                serviceScope.launch {
                    try {
                        // Aguarda a câmera nativa concluir a gravação e liberar o descritor
                        delay(750L)
                        inspectAndInterceptMediaUri(uri)
                    } finally {
                        delay(10_000L)
                        recentlyProcessedUris.remove(key)
                    }
                }
            }
        }

        mediaObserver = observer
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

    private fun unregisterMediaContentObserver() {
        mediaObserver?.let {
            try {
                contentResolver.unregisterContentObserver(it)
            } catch (_: Exception) {
            }
        }
        mediaObserver = null
    }

    @Suppress("DEPRECATION")
    private suspend fun inspectAndInterceptMediaUri(uri: Uri) {
        val projection = mutableListOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATE_ADDED
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            projection.add(MediaStore.MediaColumns.IS_PENDING)
        }

        // Tenta até 4 vezes caso o arquivo ainda esteja com IS_PENDING == 1 (ex: vídeos longos ou HDR)
        for (attempt in 1..4) {
            var filePath: String? = null
            var dateAdded = 0L
            var isPending = 0

            contentResolver.query(uri, projection.toTypedArray(), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val dataIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                    val dateIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED)
                    if (dataIdx >= 0) filePath = cursor.getString(dataIdx)
                    if (dateIdx >= 0) dateAdded = cursor.getLong(dateIdx)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val pendingIdx = cursor.getColumnIndex(MediaStore.MediaColumns.IS_PENDING)
                        if (pendingIdx >= 0) isPending = cursor.getInt(pendingIdx)
                    }
                }
            }

            if (isPending == 1) {
                delay(800L)
                continue
            }

            val rawPath = filePath ?: return
            val sourceFile = File(rawPath)
            if (!sourceFile.exists() || !sourceFile.isFile || sourceFile.length() <= 0L) {
                delay(500L)
                continue
            }

            // Ignora fotos antigas anteriores ao início do motor
            if (dateAdded > 0L && dateAdded < serviceStartTimestamp) {
                return
            }

            // TRAVA ANTI-LOOP 1: Se o arquivo já está dentro do Sandbox DCIM/Camera/IFgestor, ignora imediatamente
            if (settings.isInsideSandbox(sourceFile)) {
                return
            }

            // TRAVA ANTI-LOOP 2: Ignora arquivos fora da pasta da câmera ou já processados pelo caminho canônico
            val canonicalSource = try {
                sourceFile.canonicalPath
            } catch (_: Exception) {
                sourceFile.absolutePath
            }
            if (!canonicalSource.contains("/DCIM/", ignoreCase = true)) {
                return
            }
            if (!recentlyProcessedPaths.add(canonicalSource)) {
                return
            }

            val prefix = settings.getFilePrefix()
            if (sourceFile.name.startsWith(prefix, ignoreCase = true) || sourceFile.name.endsWith(".part")) {
                return
            }

            moveAndPrefixCapturedFile(sourceFile, uri, prefix)
            return
        }
    }

    private fun moveAndPrefixCapturedFile(sourceFile: File, originalUri: Uri, prefix: String) {
        val targetDir = settings.getActiveCaptureDirectory()
        val ext = sourceFile.extension.ifBlank { "jpg" }
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val desiredName = "${prefix}${timestamp}.$ext"

        val finalDestFile = taskManager.resolveUniqueDestinationFile(targetDir, desiredName)

        // Registra o destino na trava anti-loop ANTES de criar o arquivo no disco
        val destCanonical = try {
            finalDestFile.canonicalPath
        } catch (_: Exception) {
            finalDestFile.absolutePath
        }
        recentlyProcessedPaths.add(destCanonical)

        if (!taskManager.tryAcquireLock(finalDestFile, "Interceptação Câmera")) {
            return
        }

        try {
            val partFile = File(targetDir, "${finalDestFile.name}.part")
            var movedDirectly = sourceFile.renameTo(finalDestFile)

            if (!movedDirectly) {
                sourceFile.copyTo(partFile, overwrite = true)
                if (partFile.renameTo(finalDestFile)) {
                    movedDirectly = true
                } else {
                    partFile.copyTo(finalDestFile, overwrite = true)
                    partFile.delete()
                    movedDirectly = finalDestFile.exists()
                }
                if (movedDirectly) {
                    if (!sourceFile.delete()) {
                        try {
                            contentResolver.delete(originalUri, null, null)
                        } catch (_: Exception) {
                        }
                    }
                }
            }

            if (movedDirectly && finalDestFile.exists()) {
                val sessionCount = settings.incrementSessionPhotoCount()
                _lastInterceptedFileFlow.value = finalDestFile.name
                _sessionCountFlow.value = sessionCount

                // Atualiza o MediaStore para apontar para o novo arquivo dentro de DCIM/Camera/IFgestor
                MediaScannerConnection.scanFile(
                    applicationContext,
                    arrayOf(finalDestFile.absolutePath, sourceFile.absolutePath),
                    null,
                    null
                )

                audit.logEvent(
                    action = "MIDIA_INTERCEPTADA",
                    actor = "Câmera Nativa",
                    details = "${sourceFile.name} -> ${targetDir.name}/${finalDestFile.name} (Sessão: #$sessionCount)",
                    success = true
                )

                refreshNotificationsAndWidgets()
            }
        } catch (e: Exception) {
            audit.logEvent(
                action = "ERRO_INTERCEPTACAO",
                actor = "Câmera Nativa",
                details = "Falha ao processar ${sourceFile.name}: ${e.message}",
                success = false
            )
        } finally {
            taskManager.releaseLock(finalDestFile)
        }
    }

    // =========================================================================
    // Notificação Persistente com Controles Independentes (Item 12)
    // =========================================================================

    private fun buildForegroundNotification(): Notification {
        val interceptorOn = _interceptorRunningFlow.value
        val serverOn = _webServerRunningFlow.value
        val subfolder = settings.getTargetSubfolder()
        val prefix = settings.getFilePrefix()
        val sessionCount = settings.getSessionPhotoCount()
        val folderCount = settings.getActiveFolderFileCount()

        val title = buildString {
            append("IFGestor • ")
            append(if (interceptorOn) "Câmera: ON" else "Câmera: OFF")
            append(" | ")
            append(if (serverOn) "Web HTTPS: ON" else "Web: OFF")
        }

        val contentText = buildString {
            append("Pasta: $subfolder ($folderCount) • Prefixo: $prefix • Sessão: $sessionCount")
            if (serverOn) {
                append("\n")
                append(mdnsManager.getPrimaryHttpsDomainUrl())
            }
        }

        val openAppIntent = PendingIntent.getActivity(
            this,
            100,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val toggleInterceptorPending = PendingIntent.getService(
            this,
            101,
            Intent(this, MediaInterceptorService::class.java).apply { action = ACTION_TOGGLE_INTERCEPTOR },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val toggleServerPending = PendingIntent.getService(
            this,
            102,
            Intent(this, MediaInterceptorService::class.java).apply { action = ACTION_TOGGLE_SERVER },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopAllPending = PendingIntent.getService(
            this,
            103,
            Intent(this, MediaInterceptorService::class.java).apply { action = ACTION_STOP_ALL },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val interceptorActionLabel = if (interceptorOn) "Pausar Fotos" else "Ligar Fotos"
        val serverActionLabel = if (serverOn) "Parar Web" else "Ligar Web"

        return NotificationCompat.Builder(this, CHANNEL_ID_FOREGROUND)
            .setSmallIcon(R.drawable.ic_ifgestor_logo)
            .setContentTitle(title)
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(0, interceptorActionLabel, toggleInterceptorPending)
            .addAction(0, serverActionLabel, toggleServerPending)
            .addAction(0, "Desligar Tudo", stopAllPending)
            .build()
    }

    private fun ensureForegroundChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID_FOREGROUND,
                "Motores IFGestor (Interceptador e Servidor HTTPS)",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Mantém o interceptador de fotos e o servidor HTTPS ativos em segundo plano"
            }
            nm.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        unregisterMediaContentObserver()
        if (webServer.isServerRunning()) {
            webServer.stopServer()
        }
        FloatingOverlayManager.getInstance(applicationContext).hideOverlay()
        _interceptorRunningFlow.value = false
        _webServerRunningFlow.value = false
        IFGestorHomeWidgetProvider.updateAllWidgets(applicationContext)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL_ID_FOREGROUND = "ifgestor_engines_foreground_channel"
        private const val NOTIFICATION_ID_FOREGROUND = 9100

        const val ACTION_SYNC_ENGINES = "com.ifgestor.app.ACTION_SYNC_ENGINES"
        const val ACTION_START_INTERCEPTOR = "com.ifgestor.app.ACTION_START_INTERCEPTOR"
        const val ACTION_STOP_INTERCEPTOR = "com.ifgestor.app.ACTION_STOP_INTERCEPTOR"
        const val ACTION_TOGGLE_INTERCEPTOR = "com.ifgestor.app.ACTION_TOGGLE_INTERCEPTOR"

        const val ACTION_START_SERVER = "com.ifgestor.app.ACTION_START_SERVER"
        const val ACTION_STOP_SERVER = "com.ifgestor.app.ACTION_STOP_SERVER"
        const val ACTION_TOGGLE_SERVER = "com.ifgestor.app.ACTION_TOGGLE_SERVER"

        const val ACTION_START_BOTH = "com.ifgestor.app.ACTION_START_BOTH"
        const val ACTION_STOP_ALL = "com.ifgestor.app.ACTION_STOP_ALL"
        const val ACTION_REFRESH_UI = "com.ifgestor.app.ACTION_REFRESH_UI"

        private val _interceptorRunningFlow = MutableStateFlow(false)
        val interceptorRunningFlow: StateFlow<Boolean> = _interceptorRunningFlow.asStateFlow()

        private val _webServerRunningFlow = MutableStateFlow(false)
        val webServerRunningFlow: StateFlow<Boolean> = _webServerRunningFlow.asStateFlow()

        private val _lastInterceptedFileFlow = MutableStateFlow("")
        val lastInterceptedFileFlow: StateFlow<String> = _lastInterceptedFileFlow.asStateFlow()

        private val _sessionCountFlow = MutableStateFlow(0)
        val sessionCountFlow: StateFlow<Int> = _sessionCountFlow.asStateFlow()

        fun sendAction(context: Context, action: String) {
            val appContext = context.applicationContext
            val intent = Intent(appContext, MediaInterceptorService::class.java).apply {
                this.action = action
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    appContext.startForegroundService(intent)
                } else {
                    appContext.startService(intent)
                }
            } catch (_: Exception) {
                try {
                    appContext.startService(intent)
                } catch (_: Exception) {
                }
            }
        }
    }
}
