package com.ifgestor.app

import android.app.Activity
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RemoteViews
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class IFGestorHomeWidget : AppWidgetProvider() {

    companion object {
        const val ACTION_WIDGET_REFRESH = "com.ifgestor.app.ACTION_WIDGET_REFRESH"
        const val ACTION_WIDGET_TOGGLE_CAMERA = "com.ifgestor.app.ACTION_WIDGET_TOGGLE_CAMERA"
        const val ACTION_WIDGET_TOGGLE_WEB = "com.ifgestor.app.ACTION_WIDGET_TOGGLE_WEB"

        fun requestWidgetUpdate(context: Context) {
            try {
                val appCtx = context.applicationContext
                val manager = AppWidgetManager.getInstance(appCtx)
                val component = ComponentName(appCtx, IFGestorHomeWidget::class.java)
                val ids = manager.getAppWidgetIds(component)
                if (ids != null && ids.isNotEmpty()) {
                    for (id in ids) {
                        updateAppWidget(appCtx, manager, id)
                    }
                }
            } catch (_: Exception) {
            }
        }

        internal fun updateAppWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_ifgestor_home)

            val camActive = SettingsManager.isCameraInterceptorEnabled(context)
            val webActive = SettingsManager.isWebServerEnabled(context)
            val mdnsActive = SettingsManager.isMdnsEnabled(context)
            val domain = SettingsManager.getActiveMdnsDomain(context)
            val ip = NetworkDiscoveryManager.getLocalIpAddress(context)
            val port = SettingsManager.getServerPort(context)

            val displayPath = SettingsManager.getSandboxDisplayPath(context)
            val prefix = SettingsManager.getFilePrefix(context)
            val sessionCount = SettingsManager.getSessionInterceptCount(context)
            val totalInFolder = SettingsManager.getFolderMediaCount(context)

            val hostLabel = if (mdnsActive) domain else "$ip:$port"
            val statusText = when {
                camActive && webActive -> "🟢 ATIVO (mDNS) - $hostLabel"
                webActive -> "🟢 WEB ATIVO - $hostLabel"
                camActive -> "🟢 CÂMERA ATIVA • WEB: OFF"
                else -> "⚪ CÂMERA: OFF • WEB: OFF"
            }

            val statusColor = if (camActive || webActive) {
                Color.parseColor("#10B981")
            } else {
                Color.parseColor("#94A3B8")
            }

            views.setTextViewText(R.id.widget_status_badge, statusText)
            views.setTextColor(R.id.widget_status_badge, statusColor)
            views.setTextViewText(R.id.widget_folder_text, "Pasta: $displayPath ($prefix)")
            views.setTextViewText(R.id.widget_counter_text, "Sessão: $sessionCount | Total: $totalInFolder")

            // Destaque dinamico dos botoes Camera e Site Web conforme estado real
            views.setTextViewText(
                R.id.widget_btn_camera_label,
                if (camActive) "Câmera ON" else "Câmera"
            )
            views.setInt(
                R.id.widget_btn_camera,
                "setBackgroundColor",
                Color.parseColor(if (camActive) "#059669" else "#047857")
            )

            views.setTextViewText(
                R.id.widget_btn_web_label,
                if (webActive) "Web: ON" else "Site Web"
            )
            views.setInt(
                R.id.widget_btn_web,
                "setBackgroundColor",
                Color.parseColor(if (webActive) "#0284C7" else "#0F2942")
            )

            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }

            // Cabecalho abre o painel completo do aplicativo
            val openAppIntent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            val openAppPending = PendingIntent.getActivity(context, 100, openAppIntent, flags)
            views.setOnClickPendingIntent(R.id.widget_header_box, openAppPending)

            // Botao 1: Escolher Pasta (abre dialogo flutuante rapido sobre a tela inicial)
            val folderIntent = Intent(context, QuickWidgetDialogActivity::class.java).apply {
                putExtra(QuickWidgetDialogActivity.EXTRA_MODE, QuickWidgetDialogActivity.MODE_FOLDER)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            val folderPending = PendingIntent.getActivity(context, 101, folderIntent, flags)
            views.setOnClickPendingIntent(R.id.widget_btn_folder, folderPending)

            // Botao 2: Editar Prefixo (abre dialogo flutuante rapido sobre a tela inicial)
            val prefixIntent = Intent(context, QuickWidgetDialogActivity::class.java).apply {
                putExtra(QuickWidgetDialogActivity.EXTRA_MODE, QuickWidgetDialogActivity.MODE_PREFIX)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            val prefixPending = PendingIntent.getActivity(context, 102, prefixIntent, flags)
            views.setOnClickPendingIntent(R.id.widget_btn_prefix, prefixPending)

            // Botao 3: Iniciar Camera com Interceptacao Ativa
            val cameraIntent = Intent(context, QuickWidgetDialogActivity::class.java).apply {
                putExtra(QuickWidgetDialogActivity.EXTRA_MODE, QuickWidgetDialogActivity.MODE_LAUNCH_CAMERA)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            val cameraPending = PendingIntent.getActivity(context, 103, cameraIntent, flags)
            views.setOnClickPendingIntent(R.id.widget_btn_camera, cameraPending)

            // Botao 4: Ligar / Desligar Compartilhamento via Site Web
            val webToggleIntent = Intent(context, IFGestorHomeWidget::class.java).apply {
                action = ACTION_WIDGET_TOGGLE_WEB
            }
            val webTogglePending = PendingIntent.getBroadcast(context, 104, webToggleIntent, flags)
            views.setOnClickPendingIntent(R.id.widget_btn_web, webTogglePending)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val appCtx = context.applicationContext

        when (intent.action) {
            ACTION_WIDGET_REFRESH -> {
                requestWidgetUpdate(appCtx)
            }

            ACTION_WIDGET_TOGGLE_CAMERA -> {
                val nextState = !SettingsManager.isCameraInterceptorEnabled(appCtx)
                SettingsManager.setCameraInterceptorEnabled(appCtx, nextState)
                val svcIntent = Intent(appCtx, MediaInterceptorService::class.java).apply {
                    action = if (nextState) {
                        MediaInterceptorService.ACTION_START_CAMERA_INTERCEPTOR
                    } else {
                        MediaInterceptorService.ACTION_STOP_CAMERA_INTERCEPTOR
                    }
                }
                startServiceSafely(appCtx, svcIntent)
                requestWidgetUpdate(appCtx)
                FloatingOverlayManager.refresh(appCtx)
            }

            ACTION_WIDGET_TOGGLE_WEB -> {
                val nextWebState = !SettingsManager.isWebServerEnabled(appCtx)
                if (nextWebState && SettingsManager.isPinChangeRequired(appCtx)) {
                    Toast.makeText(
                        appCtx,
                        "Por segurança, altere o PIN padrão (1234) no app antes de ligar o servidor web.",
                        Toast.LENGTH_LONG
                    ).show()
                    val openApp = Intent(appCtx, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    appCtx.startActivity(openApp)
                    return
                }

                SettingsManager.setWebServerEnabled(appCtx, nextWebState)
                val svcIntent = Intent(appCtx, MediaInterceptorService::class.java).apply {
                    action = if (nextWebState) {
                        MediaInterceptorService.ACTION_START_WEB_SERVER
                    } else {
                        MediaInterceptorService.ACTION_STOP_WEB_SERVER
                    }
                }
                startServiceSafely(appCtx, svcIntent)
                requestWidgetUpdate(appCtx)

                val msg = if (nextWebState) {
                    "🌐 Site Web iniciado: https://${SettingsManager.getActiveMdnsDomain(appCtx)}:${SettingsManager.getServerPort(appCtx)}"
                } else {
                    "🌐 Compartilhamento via Site Web encerrado."
                }
                Toast.makeText(appCtx, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startServiceSafely(context: Context, intent: Intent) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (_: Exception) {
            try {
                context.startService(intent)
            } catch (_: Exception) {
            }
        }
    }
}

/**
 * Janela Rapida Flutuante (QuickWidgetDialogActivity)
 * Permite escolher a subpasta, editar o prefixo ou disparar a camera com interceptacao
 * diretamente a partir do Widget de Tela Inicial sem precisar carregar a tela inteira do app.
 */
class QuickWidgetDialogActivity : Activity() {

    companion object {
        const val EXTRA_MODE = "extra_widget_dialog_mode"
        const val MODE_FOLDER = "mode_folder"
        const val MODE_PREFIX = "mode_prefix"
        const val MODE_LAUNCH_CAMERA = "mode_launch_camera"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val mode = intent?.getStringExtra(EXTRA_MODE) ?: MODE_FOLDER

        if (mode == MODE_LAUNCH_CAMERA) {
            activateInterceptorAndLaunchCamera()
            finish()
            return
        }

        setContentView(buildDialogView(mode))
    }

    private fun activateInterceptorAndLaunchCamera() {
        SettingsManager.setCameraInterceptorEnabled(this, true)
        val svcIntent = Intent(this, MediaInterceptorService::class.java).apply {
            action = MediaInterceptorService.ACTION_START_CAMERA_INTERCEPTOR
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(svcIntent)
            } else {
                startService(svcIntent)
            }
        } catch (_: Exception) {
        }

        IFGestorHomeWidget.requestWidgetUpdate(this)
        FloatingOverlayManager.show(this)
        FloatingOverlayManager.refresh(this)

        try {
            val camIntent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(camIntent)
        } catch (_: Exception) {
            try {
                val fallback = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(fallback)
            } catch (_: Exception) {
                Toast.makeText(this, "Interceptador ativado!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun buildDialogView(mode: String): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(18).toFloat()
                setColor(Color.parseColor("#0F172A"))
                setStroke(dp(1), Color.parseColor("#38BDF8"))
            }
            setPadding(dp(18), dp(16), dp(18), dp(16))
            layoutParams = LinearLayout.LayoutParams(dp(320), LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        val title = TextView(this).apply {
            text = if (mode == MODE_PREFIX) {
                "✏️ Editar Prefixo dos Arquivos"
            } else {
                "📂 Escolher Pasta (Sandbox)"
            }
            setTextColor(Color.parseColor("#F8FAFC"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTypeface(typeface, Typeface.BOLD)
        }
        card.addView(title)

        val subtitle = TextView(this).apply {
            text = "Escopo seguro: ${SettingsManager.getSandboxDisplayPath(this@QuickWidgetDialogActivity)}"
            setTextColor(Color.parseColor("#38BDF8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(0, dp(4), 0, dp(12))
        }
        card.addView(subtitle)

        if (mode == MODE_FOLDER) {
            buildFolderSelectorContent(card)
        } else {
            buildPrefixEditorContent(card)
        }

        return card
    }

    private fun buildFolderSelectorContent(parent: LinearLayout) {
        val folders = SettingsManager.getSandboxSubfolders(this)
        val currentSub = SettingsManager.getTargetSubfolder(this)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(190)
            )
        }

        val listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        for (sub in folders) {
            val isSelected = (sub == currentSub)
            val count = SettingsManager.getFolderMediaCount(this, sub)
            val folderLabel = if (sub.isEmpty()) "📁 IFgestor (Raiz Principal)" else "📁 $sub"

            val row = TextView(this).apply {
                text = if (isSelected) "✓ $folderLabel  •  $count arq." else "$folderLabel  •  $count arq."
                setTextColor(if (isSelected) Color.parseColor("#38BDF8") else Color.parseColor("#F8FAFC"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTypeface(typeface, if (isSelected) Typeface.BOLD else Typeface.NORMAL)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(10).toFloat()
                    setColor(Color.parseColor(if (isSelected) "#0F2942" else "#1E293B"))
                    setStroke(dp(1), Color.parseColor(if (isSelected) "#38BDF8" else "#334155"))
                }
                setPadding(dp(12), dp(10), dp(12), dp(10))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(6)
                }

                setOnClickListener {
                    SettingsManager.setTargetSubfolder(this@QuickWidgetDialogActivity, sub)
                    IFGestorHomeWidget.requestWidgetUpdate(this@QuickWidgetDialogActivity)
                    FloatingOverlayManager.refresh(this@QuickWidgetDialogActivity)
                    Toast.makeText(
                        this@QuickWidgetDialogActivity,
                        "Pasta ativa: ${SettingsManager.getSandboxDisplayPath(this@QuickWidgetDialogActivity)}",
                        Toast.LENGTH_SHORT
                    ).show()
                    finish()
                }
            }
            listContainer.addView(row)
        }

        scroll.addView(listContainer)
        parent.addView(scroll)

        val newFolderLabel = TextView(this).apply {
            text = "Ou criar/usar nova subpasta:"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(10), 0, dp(4))
        }
        parent.addView(newFolderLabel)

        val inputNewFolder = EditText(this).apply {
            setText(currentSub)
            hint = "Ex: Vistoria_Lote1 (vazio = Raiz)"
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            inputType = InputType.TYPE_CLASS_TEXT
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(Color.parseColor("#1E293B"))
                setStroke(dp(1), Color.parseColor("#475569"))
            }
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        parent.addView(inputNewFolder)

        val actionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(14), 0, 0)
        }

        val btnCancel = createActionButton("Cancelar", "#334155", "#F8FAFC") {
            finish()
        }

        val btnSave = createActionButton("Confirmar Pasta", "#0284C7", "#FFFFFF") {
            val typed = inputNewFolder.text?.toString() ?: ""
            SettingsManager.setTargetSubfolder(this, typed)
            IFGestorHomeWidget.requestWidgetUpdate(this)
            FloatingOverlayManager.refresh(this)
            Toast.makeText(
                this,
                "Pasta definida: ${SettingsManager.getSandboxDisplayPath(this)}",
                Toast.LENGTH_SHORT
            ).show()
            finish()
        }

        actionsRow.addView(btnCancel)
        actionsRow.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(8), dp(1))
        })
        actionsRow.addView(btnSave)
        parent.addView(actionsRow)
    }

    private fun buildPrefixEditorContent(parent: LinearLayout) {
        window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)

        val currentPrefix = SettingsManager.getFilePrefix(this)
        val inputPrefix = EditText(this).apply {
            setText(currentPrefix)
            setSelection(text.length)
            hint = "Ex: Vistoria_ ou IFG_"
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            inputType = InputType.TYPE_CLASS_TEXT
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(Color.parseColor("#1E293B"))
                setStroke(dp(1), Color.parseColor("#38BDF8"))
            }
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        parent.addView(inputPrefix)

        val btnResetCounter = createActionButton(
            "🔄 Zerar Contador da Sessão Atual (${SettingsManager.getSessionInterceptCount(this)})",
            "#1E293B",
            "#38BDF8"
        ) {
            SettingsManager.resetSessionInterceptCount(this)
            IFGestorHomeWidget.requestWidgetUpdate(this)
            FloatingOverlayManager.refresh(this)
            Toast.makeText(this, "Contador da sessão zerado!", Toast.LENGTH_SHORT).show()
            finish()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(38)
            ).apply {
                topMargin = dp(10)
            }
        }
        parent.addView(btnResetCounter)

        val actionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(14), 0, 0)
        }

        val btnCancel = createActionButton("Cancelar", "#334155", "#F8FAFC") {
            finish()
        }

        val btnSave = createActionButton("Salvar Prefixo", "#059669", "#FFFFFF") {
            val typed = inputPrefix.text?.toString() ?: "IFG_"
            SettingsManager.setFilePrefix(this, typed)
            IFGestorHomeWidget.requestWidgetUpdate(this)
            FloatingOverlayManager.refresh(this)
            Toast.makeText(
                this,
                "Prefixo atualizado: ${SettingsManager.getFilePrefix(this)}",
                Toast.LENGTH_SHORT
            ).show()
            finish()
        }

        actionsRow.addView(btnCancel)
        actionsRow.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(8), dp(1))
        })
        actionsRow.addView(btnSave)
        parent.addView(actionsRow)
    }

    private fun createActionButton(
        label: String,
        bgHex: String,
        textHex: String,
        onClick: () -> Unit
    ): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            setTextColor(Color.parseColor(textHex))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(typeface, Typeface.BOLD)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(Color.parseColor(bgHex))
            }
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setOnClickListener { onClick() }
        }
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()
    }
}
