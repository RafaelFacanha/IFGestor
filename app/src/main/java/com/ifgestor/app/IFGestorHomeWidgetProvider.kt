package com.ifgestor.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.provider.MediaStore
import android.widget.RemoteViews
import android.widget.Toast

class IFGestorHomeWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (widgetId in appWidgetIds) {
            updateSingleWidget(context, appWidgetManager, widgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val appContext = context.applicationContext

        when (intent.action) {
            ACTION_WIDGET_REFRESH -> {
                updateAllWidgets(appContext)
            }

            ACTION_WIDGET_TOGGLE_INTERCEPTOR -> {
                MediaInterceptorService.sendAction(
                    appContext,
                    MediaInterceptorService.ACTION_TOGGLE_INTERCEPTOR
                )
            }

            ACTION_WIDGET_TOGGLE_SERVER -> {
                MediaInterceptorService.sendAction(
                    appContext,
                    MediaInterceptorService.ACTION_TOGGLE_SERVER
                )
            }

            ACTION_WIDGET_OPEN_CAMERA -> {
                // Garante que o motor de interceptação esteja ligado ao abrir a câmera pelo widget
                if (!MediaInterceptorService.interceptorRunningFlow.value) {
                    MediaInterceptorService.sendAction(
                        appContext,
                        MediaInterceptorService.ACTION_START_INTERCEPTOR
                    )
                }
                launchNativeCamera(appContext)
            }

            ACTION_WIDGET_OPEN_WEB -> {
                // Garante que o Servidor HTTPS esteja ligado e abre/copia o endereço local
                if (!MediaInterceptorService.webServerRunningFlow.value) {
                    MediaInterceptorService.sendAction(
                        appContext,
                        MediaInterceptorService.ACTION_START_SERVER
                    )
                }
                openOrCopyWebServerUrl(appContext)
            }
        }
    }

    private fun launchNativeCamera(context: Context) {
        try {
            val stillCameraIntent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            context.startActivity(stillCameraIntent)
        } catch (_: Exception) {
            try {
                val fallbackIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallbackIntent)
            } catch (_: Exception) {
                Toast.makeText(
                    context,
                    "Não foi possível abrir o aplicativo de câmera.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun openOrCopyWebServerUrl(context: Context) {
        val mdns = MdnsDiscoveryManager.getInstance(context)
        val domainUrl = mdns.getPrimaryHttpsDomainUrl()
        val ipUrl = mdns.getFallbackHttpsIpUrl()

        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("IFGestor HTTPS URL", domainUrl))
            Toast.makeText(
                context,
                "Servidor HTTPS ativo!\nCopiado: $domainUrl ($ipUrl)",
                Toast.LENGTH_LONG
            ).show()
        } catch (_: Exception) {
        }

        try {
            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(ipUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(browserIntent)
        } catch (_: Exception) {
            val appIntent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(appIntent)
        }
    }

    companion object {
        const val ACTION_WIDGET_REFRESH = "com.ifgestor.app.ACTION_WIDGET_REFRESH"
        const val ACTION_WIDGET_OPEN_CAMERA = "com.ifgestor.app.ACTION_WIDGET_OPEN_CAMERA"
        const val ACTION_WIDGET_OPEN_WEB = "com.ifgestor.app.ACTION_WIDGET_OPEN_WEB"
        const val ACTION_WIDGET_TOGGLE_INTERCEPTOR = "com.ifgestor.app.ACTION_WIDGET_TOGGLE_INTERCEPTOR"
        const val ACTION_WIDGET_TOGGLE_SERVER = "com.ifgestor.app.ACTION_WIDGET_TOGGLE_SERVER"

        fun updateAllWidgets(context: Context) {
            val appContext = context.applicationContext
            val manager = AppWidgetManager.getInstance(appContext)
            val component = ComponentName(appContext, IFGestorHomeWidgetProvider::class.java)
            val ids = manager.getAppWidgetIds(component)
            if (ids != null && ids.isNotEmpty()) {
                for (id in ids) {
                    updateSingleWidget(appContext, manager, id)
                }
            }
        }

        private fun updateSingleWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val settings = SettingsManager(context)
            val mdns = MdnsDiscoveryManager.getInstance(context)

            val interceptorOn = MediaInterceptorService.interceptorRunningFlow.value
            val serverOn = MediaInterceptorService.webServerRunningFlow.value
            val subfolder = settings.getTargetSubfolder()
            val prefix = settings.getFilePrefix()
            val sessionCount = settings.getSessionPhotoCount()
            val folderCount = settings.getActiveFolderFileCount()

            val views = RemoteViews(context.packageName, R.layout.widget_home_ifgestor)

            // Status dos 2 motores independentes (Item 12)
            views.setTextViewText(
                R.id.btn_widget_toggle_interceptor,
                if (interceptorOn) "📸 Fotos: ON" else "📸 Fotos: OFF"
            )
            views.setInt(
                R.id.btn_widget_toggle_interceptor,
                "setBackgroundColor",
                if (interceptorOn) Color.parseColor("#059669") else Color.parseColor("#334155")
            )

            views.setTextViewText(
                R.id.btn_widget_toggle_server,
                if (serverOn) "🌐 Web: ON" else "🌐 Web: OFF"
            )
            views.setInt(
                R.id.btn_widget_toggle_server,
                "setBackgroundColor",
                if (serverOn) Color.parseColor("#2563EB") else Color.parseColor("#334155")
            )

            // Resumo ao vivo: Pasta, Prefixo, Contador Duplo e URL HTTPS (Itens 6, 13 e 14)
            val statusText = buildString {
                append("📁 $subfolder ($folderCount)  •  🏷️ $prefix  •  Sessão: $sessionCount")
                if (serverOn) {
                    append("\n🔗 ${mdns.getPrimaryHttpsDomainUrl()} (${mdns.getLocalIpv4Address()})")
                }
            }
            views.setTextViewText(R.id.tv_widget_status_summary, statusText)

            // Toque no título abre a MainActivity
            val openMainPending = PendingIntent.getActivity(
                context,
                200,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_title_app, openMainPending)

            // Botões de Ligar/Desligar Motores Independentes
            views.setOnClickPendingIntent(
                R.id.btn_widget_toggle_interceptor,
                buildBroadcastPendingIntent(context, ACTION_WIDGET_TOGGLE_INTERCEPTOR, 201)
            )
            views.setOnClickPendingIntent(
                R.id.btn_widget_toggle_server,
                buildBroadcastPendingIntent(context, ACTION_WIDGET_TOGGLE_SERVER, 202)
            )

            // Ação 1: Escolher/Criar Pasta via QuickWidgetDialogActivity (Itens 14 e 15)
            val folderDialogIntent = Intent(context, QuickWidgetDialogActivity::class.java).apply {
                putExtra(QuickWidgetDialogActivity.EXTRA_DIALOG_MODE, QuickWidgetDialogActivity.MODE_FOLDER)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            val folderPending = PendingIntent.getActivity(
                context,
                203,
                folderDialogIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.btn_widget_action_folder, folderPending)

            // Ação 2: Editar Prefixo via QuickWidgetDialogActivity (Itens 14 e 15)
            val prefixDialogIntent = Intent(context, QuickWidgetDialogActivity::class.java).apply {
                putExtra(QuickWidgetDialogActivity.EXTRA_DIALOG_MODE, QuickWidgetDialogActivity.MODE_PREFIX)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            val prefixPending = PendingIntent.getActivity(
                context,
                204,
                prefixDialogIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.btn_widget_action_prefix, prefixPending)

            // Ação 3: Abrir Câmera (ativando interceptador se necessário)
            views.setOnClickPendingIntent(
                R.id.btn_widget_action_camera,
                buildBroadcastPendingIntent(context, ACTION_WIDGET_OPEN_CAMERA, 205)
            )

            // Ação 4: Ligar e Abrir/Copiar Site Web HTTPS
            views.setOnClickPendingIntent(
                R.id.btn_widget_action_web,
                buildBroadcastPendingIntent(context, ACTION_WIDGET_OPEN_WEB, 206)
            )

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        private fun buildBroadcastPendingIntent(
            context: Context,
            action: String,
            requestCode: Int
        ): PendingIntent {
            val intent = Intent(context, IFGestorHomeWidgetProvider::class.java).apply {
                this.action = action
            }
            return PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }
}
