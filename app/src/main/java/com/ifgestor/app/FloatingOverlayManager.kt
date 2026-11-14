package com.ifgestor.app

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs

class FloatingOverlayManager private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val settings = SettingsManager(appContext)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val windowManager =
        appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var rootOverlayView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private var tvHeaderSummary: TextView? = null
    private var tvDualCounter: TextView? = null
    private var tvDropdownSelected: TextView? = null
    private var dropdownListContainer: LinearLayout? = null
    private var dropdownScrollWrapper: ScrollView? = null
    private var expandedContentLayout: LinearLayout? = null
    private var tvPrefixValue: TextView? = null
    private var btnToggleInterceptor: TextView? = null
    private var btnToggleWebServer: TextView? = null
    private var tvExpandArrow: TextView? = null

    private var isPanelExpanded = false
    private var isDropdownOpen = false

    fun canDrawOverlays(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(appContext)
        } else {
            true
        }
    }

    fun isOverlayVisible(): Boolean = rootOverlayView != null

    /**
     * Exibe o Widget Flutuante sobre a câmera (caso ainda não esteja na tela) ou atualiza seus contadores e pastas.
     */
    fun showOrUpdateOverlay() {
        mainHandler.post {
            if (!canDrawOverlays() || !settings.isFloatingWidgetEnabled()) {
                hideOverlayInternal()
                return@post
            }

            if (rootOverlayView == null) {
                createAndAttachOverlay()
            }
            refreshOverlayData()
        }
    }

    /**
     * Remove o Widget Flutuante da tela.
     */
    fun hideOverlay() {
        mainHandler.post {
            hideOverlayInternal()
        }
    }

    private fun hideOverlayInternal() {
        val view = rootOverlayView ?: return
        try {
            windowManager.removeView(view)
        } catch (_: Exception) {
        }
        rootOverlayView = null
        isDropdownOpen = false
    }

    private fun createAndAttachOverlay() {
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12)
            y = dp(90)
        }
        layoutParams = params

        val container = LinearLayout(appContext).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(
                fillColor = Color.parseColor("#E60F172A"),
                strokeColor = Color.parseColor("#3B82F6"),
                strokeWidthDp = 1,
                radiusDp = 16f
            )
            setPadding(dp(12), dp(8), dp(12), dp(8))
            elevation = dp(8).toFloat()
        }

        // 1. Barra Superior Compacta (Arrastável + Contador Duplo + Botão Expandir)
        val headerRow = LinearLayout(appContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val summaryCol = LinearLayout(appContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, dp(10), 0)
        }

        val headerTitle = TextView(appContext).apply {
            text = "📁 Geral • Vistoria_"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setTypeface(typeface, Typeface.BOLD)
        }
        tvHeaderSummary = headerTitle

        val counterBadge = TextView(appContext).apply {
            text = "Sessão: 0  |  Pasta: 0"
            setTextColor(Color.parseColor("#38BDF8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTypeface(typeface, Typeface.BOLD)
        }
        tvDualCounter = counterBadge

        summaryCol.addView(headerTitle)
        summaryCol.addView(counterBadge)

        val expandArrow = TextView(appContext).apply {
            text = "▼"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = roundedBg(Color.parseColor("#334155"), Color.TRANSPARENT, 0, 8f)
            setOnClickListener {
                isPanelExpanded = !isPanelExpanded
                if (!isPanelExpanded) {
                    isDropdownOpen = false
                }
                refreshOverlayData()
            }
        }
        tvExpandArrow = expandArrow

        headerRow.addView(summaryCol)
        headerRow.addView(expandArrow)

        // Permite arrastar o widget pela tela e alternar expansão com toque simples
        attachDragAndClickBehaviour(headerRow, params, container) {
            isPanelExpanded = !isPanelExpanded
            if (!isPanelExpanded) isDropdownOpen = false
            refreshOverlayData()
        }

        container.addView(headerRow)

        // 2. Painel Expandido (Dropdown de Subpastas + Prefixo + Motores Independentes)
        val bodyLayout = LinearLayout(appContext).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, dp(8), 0, 0)
        }
        expandedContentLayout = bodyLayout

        // Rótulo Subpasta
        val lblFolder = TextView(appContext).apply {
            text = "Subpasta ativa (DCIM/Camera/IFgestor):"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
        }
        bodyLayout.addView(lblFolder)

        // Botão Selecionador Dropdown (Item 13)
        val dropdownSelector = TextView(appContext).apply {
            text = "📁 Geral  ▾"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(10), dp(7), dp(10), dp(7))
            background = roundedBg(
                fillColor = Color.parseColor("#1E293B"),
                strokeColor = Color.parseColor("#475569"),
                strokeWidthDp = 1,
                radiusDp = 10f
            )
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(4)
            layoutParams = lp
            setOnClickListener {
                isDropdownOpen = !isDropdownOpen
                refreshOverlayData()
            }
        }
        tvDropdownSelected = dropdownSelector
        bodyLayout.addView(dropdownSelector)

        // Lista Suspensa (Dropdown) Rolável de Subpastas
        val scrollDropdown = ScrollView(appContext).apply {
            visibility = View.GONE
            background = roundedBg(
                fillColor = Color.parseColor("#0F172A"),
                strokeColor = Color.parseColor("#3B82F6"),
                strokeWidthDp = 1,
                radiusDp = 10f
            )
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(135)
            )
            lp.topMargin = dp(4)
            layoutParams = lp
        }
        dropdownScrollWrapper = scrollDropdown

        val listInner = LinearLayout(appContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        dropdownListContainer = listInner
        scrollDropdown.addView(listInner)
        bodyLayout.addView(scrollDropdown)

        // Linha de Prefixo + Botão Editar Rápido
        val prefixRow = LinearLayout(appContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(8)
            layoutParams = lp
        }

        val prefixText = TextView(appContext).apply {
            text = "🏷️ Prefixo: Vistoria_"
            setTextColor(Color.parseColor("#E2E8F0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        tvPrefixValue = prefixText

        val btnEditQuick = TextView(appContext).apply {
            text = "✎ Prefixo / +Pasta"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(8), dp(5), dp(8), dp(5))
            background = roundedBg(Color.parseColor("#2563EB"), Color.TRANSPARENT, 0, 8f)
            setOnClickListener {
                openQuickDialog(QuickWidgetDialogActivity.MODE_BOTH)
            }
        }

        prefixRow.addView(prefixText)
        prefixRow.addView(btnEditQuick)
        bodyLayout.addView(prefixRow)

        // Linha de Controles Independentes dos 2 Motores (Item 12)
        val enginesRow = LinearLayout(appContext).apply {
            orientation = LinearLayout.HORIZONTAL
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(8)
            layoutParams = lp
        }

        val toggleInterceptorBtn = TextView(appContext).apply {
            text = "📸 Fotos: ON"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(8), dp(6), dp(8), dp(6))
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            lp.rightMargin = dp(4)
            layoutParams = lp
            setOnClickListener {
                MediaInterceptorService.sendAction(
                    appContext,
                    MediaInterceptorService.ACTION_TOGGLE_INTERCEPTOR
                )
            }
        }
        btnToggleInterceptor = toggleInterceptorBtn

        val toggleServerBtn = TextView(appContext).apply {
            text = "🌐 Web: OFF"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(8), dp(6), dp(8), dp(6))
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            lp.leftMargin = dp(4)
            layoutParams = lp
            setOnClickListener {
                MediaInterceptorService.sendAction(
                    appContext,
                    MediaInterceptorService.ACTION_TOGGLE_SERVER
                )
            }
        }
        btnToggleWebServer = toggleServerBtn

        enginesRow.addView(toggleInterceptorBtn)
        enginesRow.addView(toggleServerBtn)
        bodyLayout.addView(enginesRow)

        // Linha Inferior: Zerar Contador da Sessão + Abrir Painel + Ocultar
        val bottomActionsRow = LinearLayout(appContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(8)
            layoutParams = lp
        }

        val btnResetSession = TextView(appContext).apply {
            text = "↺ Zerar Sessão"
            setTextColor(Color.parseColor("#CBD5E1"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = roundedBg(Color.parseColor("#334155"), Color.TRANSPARENT, 0, 8f)
            setOnClickListener {
                settings.resetSessionPhotoCount()
                refreshOverlayData()
                MediaInterceptorService.sendAction(
                    appContext,
                    MediaInterceptorService.ACTION_REFRESH_UI
                )
            }
        }

        val spacer = View(appContext).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        }

        val btnOpenApp = TextView(appContext).apply {
            text = "App"
            setTextColor(Color.parseColor("#93C5FD"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener {
                val intent = Intent(appContext, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                appContext.startActivity(intent)
            }
        }

        val btnCloseOverlay = TextView(appContext).apply {
            text = "✕"
            setTextColor(Color.parseColor("#FCA5A5"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(8), dp(4), dp(4), dp(4))
            setOnClickListener {
                hideOverlay()
            }
        }

        bottomActionsRow.addView(btnResetSession)
        bottomActionsRow.addView(spacer)
        bottomActionsRow.addView(btnOpenApp)
        bottomActionsRow.addView(btnCloseOverlay)
        bodyLayout.addView(bottomActionsRow)

        container.addView(bodyLayout)

        try {
            windowManager.addView(container, params)
            rootOverlayView = container
        } catch (_: Exception) {
            rootOverlayView = null
        }
    }

    private fun refreshOverlayData() {
        val activeSub = settings.getTargetSubfolder()
        val activePrefix = settings.getFilePrefix()
        val sessionCount = settings.getSessionPhotoCount()
        val folderCount = settings.getActiveFolderFileCount()
        val interceptorOn = MediaInterceptorService.interceptorRunningFlow.value
        val webServerOn = MediaInterceptorService.webServerRunningFlow.value

        tvHeaderSummary?.text = "📁 $activeSub • $activePrefix"
        tvDualCounter?.text = "Sessão: $sessionCount  |  Pasta: $folderCount"
        tvExpandArrow?.text = if (isPanelExpanded) "▲" else "▼"

        expandedContentLayout?.visibility = if (isPanelExpanded) View.VISIBLE else View.GONE

        tvDropdownSelected?.text =
            if (isDropdownOpen) "📁 $activeSub ($folderCount arq.)  ▴" else "📁 $activeSub ($folderCount arq.)  ▾"
        tvPrefixValue?.text = "🏷️ $activePrefix"

        btnToggleInterceptor?.apply {
            text = if (interceptorOn) "📸 Fotos: ON" else "📸 Fotos: OFF"
            background = roundedBg(
                fillColor = if (interceptorOn) Color.parseColor("#059669") else Color.parseColor("#475569"),
                strokeColor = Color.TRANSPARENT,
                strokeWidthDp = 0,
                radiusDp = 8f
            )
        }

        btnToggleWebServer?.apply {
            text = if (webServerOn) "🌐 Web: ON" else "🌐 Web: OFF"
            background = roundedBg(
                fillColor = if (webServerOn) Color.parseColor("#2563EB") else Color.parseColor("#475569"),
                strokeColor = Color.TRANSPARENT,
                strokeWidthDp = 0,
                radiusDp = 8f
            )
        }

        dropdownScrollWrapper?.visibility =
            if (isPanelExpanded && isDropdownOpen) View.VISIBLE else View.GONE

        if (isPanelExpanded && isDropdownOpen) {
            populateSubfolderDropdown(activeSub)
        }
    }

    private fun populateSubfolderDropdown(activeSubfolder: String) {
        val listLayout = dropdownListContainer ?: return
        listLayout.removeAllViews()

        val folders = settings.listSandboxSubfolders()
        for (folder in folders) {
            val isSelected = folder.equals(activeSubfolder, ignoreCase = true)
            val itemView = TextView(appContext).apply {
                text = if (isSelected) "✓ 📁 $folder (Ativa)" else "   📁 $folder"
                setTextColor(if (isSelected) Color.parseColor("#38BDF8") else Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTypeface(typeface, if (isSelected) Typeface.BOLD else Typeface.NORMAL)
                setPadding(dp(8), dp(6), dp(8), dp(6))
                if (isSelected) {
                    background = roundedBg(Color.parseColor("#1E293B"), Color.TRANSPARENT, 0, 6f)
                }
                setOnClickListener {
                    settings.setTargetSubfolder(folder)
                    isDropdownOpen = false
                    refreshOverlayData()
                    MediaInterceptorService.sendAction(
                        appContext,
                        MediaInterceptorService.ACTION_REFRESH_UI
                    )
                }
            }
            listLayout.addView(itemView)
        }

        // Opção rápida no final do Dropdown para criar uma nova subpasta
        val createNewItem = TextView(appContext).apply {
            text = "+ Criar Nova Subpasta..."
            setTextColor(Color.parseColor("#10B981"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(8), dp(7), dp(8), dp(6))
            setOnClickListener {
                isDropdownOpen = false
                refreshOverlayData()
                openQuickDialog(QuickWidgetDialogActivity.MODE_FOLDER)
            }
        }
        listLayout.addView(createNewItem)
    }

    private fun openQuickDialog(mode: String) {
        val intent = Intent(appContext, QuickWidgetDialogActivity::class.java).apply {
            putExtra(QuickWidgetDialogActivity.EXTRA_DIALOG_MODE, mode)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        appContext.startActivity(intent)
    }

    private fun attachDragAndClickBehaviour(
        handleView: View,
        params: WindowManager.LayoutParams,
        rootView: View,
        onSingleClick: () -> Unit
    ) {
        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false

        handleView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    moved = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (abs(dx) > dp(6) || abs(dy) > dp(6)) {
                        moved = true
                    }
                    if (moved) {
                        params.x = initialX + dx
                        params.y = initialY + dy
                        try {
                            windowManager.updateViewLayout(rootView, params)
                        } catch (_: Exception) {
                        }
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        onSingleClick()
                    }
                    true
                }

                else -> false
            }
        }
    }

    private fun roundedBg(
        fillColor: Int,
        strokeColor: Int,
        strokeWidthDp: Int,
        radiusDp: Float
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fillColor)
            cornerRadius = dp(radiusDp.toInt()).toFloat()
            if (strokeWidthDp > 0) {
                setStroke(dp(strokeWidthDp), strokeColor)
            }
        }
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            appContext.resources.displayMetrics
        ).toInt()
    }

    companion object {
        @Volatile
        private var INSTANCE: FloatingOverlayManager? = null

        fun getInstance(context: Context): FloatingOverlayManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: FloatingOverlayManager(context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }
    }
}
