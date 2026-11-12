package com.ifgestor.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

@SuppressLint("StaticFieldLeak")
object FloatingOverlayManager {

    private var windowManager: WindowManager? = null
    private var rootView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var tvHeaderFolderPrefix: TextView? = null
    private var tvSessionBadge: TextView? = null
    private var tvFolderTotalBadge: TextView? = null

    private var dropdownContainer: LinearLayout? = null
    private var dropdownItemsLayout: LinearLayout? = null
    private var configPanelContainer: LinearLayout? = null
    private var etNewFolder: EditText? = null
    private var etNewPrefix: EditText? = null

    private var isDropdownOpen = false
    private var isConfigPanelOpen = false

    fun isShowing(): Boolean = rootView != null

    fun show(context: Context) {
        val appCtx = context.applicationContext
        mainHandler.post {
            if (!Settings.canDrawOverlays(appCtx) || !SettingsManager.isFloatingWidgetEnabled(appCtx)) {
                return@post
            }
            if (rootView != null) {
                refresh(appCtx)
                return@post
            }

            try {
                val wm = appCtx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                windowManager = wm

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
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    x = dp(appCtx, 20)
                    y = dp(appCtx, 420)
                }
                layoutParams = params

                val view = buildOverlayView(appCtx)
                rootView = view
                wm.addView(view, params)
                refresh(appCtx)
            } catch (_: Exception) {
            }
        }
    }

    fun hide() {
        mainHandler.post {
            try {
                rootView?.let { view ->
                    windowManager?.removeView(view)
                }
            } catch (_: Exception) {
            } finally {
                rootView = null
                tvHeaderFolderPrefix = null
                tvSessionBadge = null
                tvFolderTotalBadge = null
                dropdownContainer = null
                dropdownItemsLayout = null
                configPanelContainer = null
                etNewFolder = null
                etNewPrefix = null
                isDropdownOpen = false
                isConfigPanelOpen = false
            }
        }
    }

    fun refresh(context: Context) {
        val appCtx = context.applicationContext
        mainHandler.post {
            if (rootView == null) return@post

            val sub = SettingsManager.getTargetSubfolder(appCtx)
            val prefix = SettingsManager.getFilePrefix(appCtx)
            val sessionCount = SettingsManager.getSessionInterceptCount(appCtx)
            val folderTotal = SettingsManager.getFolderMediaCount(appCtx, sub)
            val interceptorOn = SettingsManager.isCameraInterceptorEnabled(appCtx)

            val displayFolder = if (sub.isEmpty()) "IFgestor (Raiz)" else sub
            val statusDot = if (interceptorOn) "🟢" else "⚪"

            tvHeaderFolderPrefix?.text = "$statusDot 📁 $displayFolder • $prefix ▾"
            tvSessionBadge?.text = "Sessão: $sessionCount"
            tvFolderTotalBadge?.text = "Pasta: $folderTotal"

            if (isDropdownOpen) {
                populateDropdownList(appCtx)
            }
        }
    }

    // Compatibilidade com chamadas existentes no servico
    fun updateCounter(context: Context) = refresh(context)

    private fun setWindowFocusable(context: Context, focusable: Boolean) {
        val wm = windowManager ?: return
        val view = rootView ?: return
        val params = layoutParams ?: return

        try {
            params.flags = if (focusable) {
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            } else {
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            }
            wm.updateViewLayout(view, params)

            if (!focusable) {
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(view.windowToken, 0)
            }
        } catch (_: Exception) {
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildOverlayView(context: Context): View {
        val outerCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg("#F20F172A", "#334155", dp(context, 18), dp(context, 1))
            setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10))
            elevation = dp(context, 10).toFloat()
        }

        // =====================================================================
        // 1. LINHA PRINCIPAL COMPACTA (DROPDOWN + CONTADOR DUPLO + BOTOES)
        // =====================================================================
        val mainRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val leftInfoColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, dp(context, 10), 0)
        }

        // Seletor Suspenso de Pasta/Prefixo
        val headerSelector = TextView(context).apply {
            text = "🟢 📁 IFgestor • IFG_ ▾"
            setTextColor(Color.parseColor("#F8FAFC"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(typeface, Typeface.BOLD)
            background = roundedBg("#1E293B", "#475569", dp(context, 10), dp(context, 1))
            setPadding(dp(context, 10), dp(context, 6), dp(context, 10), dp(context, 6))
        }
        tvHeaderFolderPrefix = headerSelector

        // Linha do Contador Duplo: Sessao: X | Pasta: Y
        val countersRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(context, 6), 0, 0)
        }

        val sessionPill = TextView(context).apply {
            text = "Sessão: 0"
            setTextColor(Color.parseColor("#E0F2FE"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTypeface(typeface, Typeface.BOLD)
            background = roundedBg("#0369A1", "#38BDF8", dp(context, 12), dp(context, 1))
            setPadding(dp(context, 9), dp(context, 3), dp(context, 9), dp(context, 3))
        }
        tvSessionBadge = sessionPill

        val spacer = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(context, 6), dp(context, 1))
        }

        val folderTotalPill = TextView(context).apply {
            text = "Pasta: 0"
            setTextColor(Color.parseColor("#D1FAE5"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTypeface(typeface, Typeface.BOLD)
            background = roundedBg("#047857", "#10B981", dp(context, 12), dp(context, 1))
            setPadding(dp(context, 9), dp(context, 3), dp(context, 9), dp(context, 3))
        }
        tvFolderTotalBadge = folderTotalPill

        countersRow.addView(sessionPill)
        countersRow.addView(spacer)
        countersRow.addView(folderTotalPill)

        leftInfoColumn.addView(headerSelector)
        leftInfoColumn.addView(countersRow)

        // Coluna Lateral Direita: Botao Camera e Botao Configuracoes/Prefixo
        val rightActionsColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = roundedBg("#1E293B", "#334155", dp(context, 12), dp(context, 1))
            setPadding(dp(context, 4), dp(context, 4), dp(context, 4), dp(context, 4))
        }

        val btnQuickCamera = TextView(context).apply {
            text = "📷"
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            background = roundedBg("#059669", "#10B981", dp(context, 8), 0)
            setPadding(dp(context, 8), dp(context, 4), dp(context, 8), dp(context, 4))
            setOnClickListener {
                launchCameraFromOverlay(context)
            }
        }

        val btnGap = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(context, 1), dp(context, 4))
        }

        val btnToggleSettings = TextView(context).apply {
            text = "⚙️"
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            background = roundedBg("#334155", "#475569", dp(context, 8), 0)
            setPadding(dp(context, 8), dp(context, 4), dp(context, 8), dp(context, 4))
            setOnClickListener {
                toggleConfigPanel(context)
            }
        }

        rightActionsColumn.addView(btnQuickCamera)
        rightActionsColumn.addView(btnGap)
        rightActionsColumn.addView(btnToggleSettings)

        mainRow.addView(leftInfoColumn)
        mainRow.addView(rightActionsColumn)
        outerCard.addView(mainRow)

        // =====================================================================
        // 2. MENU SUSPENSO (DROPDOWN) DE SUBPASTAS DA SANDBOX (ITEM 13)
        // =====================================================================
        val dropBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = roundedBg("#0F172A", "#38BDF8", dp(context, 12), dp(context, 1))
            setPadding(dp(context, 8), dp(context, 8), dp(context, 8), dp(context, 8))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(context, 8)
            layoutParams = lp
        }
        dropdownContainer = dropBox

        val dropTitle = TextView(context).apply {
            text = "Selecionar Pasta (DCIM/Camera/IFgestor):"
            setTextColor(Color.parseColor("#38BDF8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(context, 4), 0, dp(context, 4), dp(context, 6))
        }
        dropBox.addView(dropTitle)

        val scrollFolders = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(context, 150)
            )
        }

        val itemsLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        dropdownItemsLayout = itemsLayout
        scrollFolders.addView(itemsLayout)
        dropBox.addView(scrollFolders)
        outerCard.addView(dropBox)

        // =====================================================================
        // 3. PAINEL DE EDICAO DE NOVA PASTA E PREFIXO (ACIONADO PELO ⚙️)
        // =====================================================================
        val cfgBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = roundedBg("#1E293B", "#475569", dp(context, 12), dp(context, 1))
            setPadding(dp(context, 10), dp(context, 10), dp(context, 10), dp(context, 10))
            val lp = LinearLayout.LayoutParams(
                dp(context, 250),
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(context, 8)
            layoutParams = lp
        }
        configPanelContainer = cfgBox

        val cfgTitle = TextView(context).apply {
            text = "Criar Subpasta / Editar Prefixo"
            setTextColor(Color.parseColor("#F8FAFC"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(context, 6))
        }
        cfgBox.addView(cfgTitle)

        val inputFolder = EditText(context).apply {
            hint = "Subpasta (vazio = Raiz IFgestor)"
            setHintTextColor(Color.parseColor("#94A3B8"))
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            inputType = InputType.TYPE_CLASS_TEXT
            background = roundedBg("#0F172A", "#334155", dp(context, 8), dp(context, 1))
            setPadding(dp(context, 10), dp(context, 8), dp(context, 10), dp(context, 8))
        }
        etNewFolder = inputFolder
        cfgBox.addView(inputFolder)

        val inputGap = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(context, 1), dp(context, 6))
        }
        cfgBox.addView(inputGap)

        val inputPrefix = EditText(context).apply {
            hint = "Prefixo (ex: Vistoria_)"
            setHintTextColor(Color.parseColor("#94A3B8"))
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            inputType = InputType.TYPE_CLASS_TEXT
            background = roundedBg("#0F172A", "#334155", dp(context, 8), dp(context, 1))
            setPadding(dp(context, 10), dp(context, 8), dp(context, 10), dp(context, 8))
        }
        etNewPrefix = inputPrefix
        cfgBox.addView(inputPrefix)

        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(context, 8), 0, 0)
        }

        val btnSaveCfg = Button(context).apply {
            text = "Salvar"
            isAllCaps = false
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            background = roundedBg("#0284C7", "#38BDF8", dp(context, 8), 0)
            layoutParams = LinearLayout.LayoutParams(0, dp(context, 36), 1f).apply {
                marginEnd = dp(context, 4)
            }
            setOnClickListener {
                val folderVal = inputFolder.text?.toString() ?: ""
                val prefixVal = inputPrefix.text?.toString() ?: "IFG_"
                SettingsManager.setTargetSubfolder(context, folderVal)
                SettingsManager.setFilePrefix(context, prefixVal)
                closePanels(context)
                refresh(context)
                Toast.makeText(
                    context,
                    "Ativo: ${SettingsManager.getSandboxDisplayPath(context)}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        val btnResetSession = Button(context).apply {
            text = "Zerar Sessão"
            isAllCaps = false
            setTextColor(Color.parseColor("#F8FAFC"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            background = roundedBg("#334155", "#475569", dp(context, 8), 0)
            layoutParams = LinearLayout.LayoutParams(0, dp(context, 36), 1f).apply {
                marginStart = dp(context, 4)
            }
            setOnClickListener {
                SettingsManager.resetSessionInterceptCount(context)
                refresh(context)
            }
        }

        btnRow.addView(btnSaveCfg)
        btnRow.addView(btnResetSession)
        cfgBox.addView(btnRow)

        val btnOpenApp = Button(context).apply {
            text = "Abrir Painel IFGestor"
            isAllCaps = false
            setTextColor(Color.parseColor("#38BDF8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            background = roundedBg("#0F172A", "#334155", dp(context, 8), dp(context, 1))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(context, 34)
            )
            lp.topMargin = dp(context, 6)
            layoutParams = lp
            setOnClickListener {
                closePanels(context)
                val intent = Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                context.startActivity(intent)
            }
        }
        cfgBox.addView(btnOpenApp)
        outerCard.addView(cfgBox)

        // =====================================================================
        // 4. SUPORTE A ARRASTAR O WIDGET NA TELA + CLIQUE NO DROPDOWN
        // =====================================================================
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false

        val dragAndClickListener = View.OnTouchListener { v, event ->
            val params = layoutParams ?: return@OnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (abs(dx) > dp(context, 6) || abs(dy) > dp(context, 6)) {
                        moved = true
                        params.x = startX + dx
                        params.y = startY + dy
                        try {
                            windowManager?.updateViewLayout(outerCard, params)
                        } catch (_: Exception) {
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved && v == headerSelector) {
                        toggleDropdown(context)
                    }
                    true
                }
                else -> false
            }
        }

        headerSelector.setOnTouchListener(dragAndClickListener)
        outerCard.setOnTouchListener(dragAndClickListener)

        return outerCard
    }

    private fun toggleDropdown(context: Context) {
        isDropdownOpen = !isDropdownOpen
        if (isDropdownOpen) {
            isConfigPanelOpen = false
            configPanelContainer?.visibility = View.GONE
            setWindowFocusable(context, false)
            populateDropdownList(context)
            dropdownContainer?.visibility = View.VISIBLE
        } else {
            dropdownContainer?.visibility = View.GONE
        }
    }

    private fun toggleConfigPanel(context: Context) {
        isConfigPanelOpen = !isConfigPanelOpen
        if (isConfigPanelOpen) {
            isDropdownOpen = false
            dropdownContainer?.visibility = View.GONE
            etNewFolder?.setText(SettingsManager.getTargetSubfolder(context))
            etNewPrefix?.setText(SettingsManager.getFilePrefix(context))
            configPanelContainer?.visibility = View.VISIBLE
            setWindowFocusable(context, true)
            etNewFolder?.requestFocus()
        } else {
            closePanels(context)
        }
    }

    private fun closePanels(context: Context) {
        isDropdownOpen = false
        isConfigPanelOpen = false
        dropdownContainer?.visibility = View.GONE
        configPanelContainer?.visibility = View.GONE
        setWindowFocusable(context, false)
    }

    private fun populateDropdownList(context: Context) {
        val listLayout = dropdownItemsLayout ?: return
        listLayout.removeAllViews()

        val folders = SettingsManager.getSandboxSubfolders(context)
        val currentSub = SettingsManager.getTargetSubfolder(context)

        for (sub in folders) {
            val isSelected = (sub == currentSub)
            val count = SettingsManager.getFolderMediaCount(context, sub)
            val label = if (sub.isEmpty()) "📂 IFgestor (Raiz)" else "📂 $sub"

            val itemRow = TextView(context).apply {
                text = if (isSelected) "✓ $label  •  $count arq." else "$label  •  $count arq."
                setTextColor(if (isSelected) Color.parseColor("#38BDF8") else Color.parseColor("#F8FAFC"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTypeface(typeface, if (isSelected) Typeface.BOLD else Typeface.NORMAL)
                background = roundedBg(
                    if (isSelected) "#0F2942" else "#1E293B",
                    if (isSelected) "#38BDF8" else "#334155",
                    dp(context, 8),
                    dp(context, 1)
                )
                setPadding(dp(context, 10), dp(context, 8), dp(context, 10), dp(context, 8))
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.bottomMargin = dp(context, 5)
                layoutParams = lp

                setOnClickListener {
                    SettingsManager.setTargetSubfolder(context, sub)
                    closePanels(context)
                    refresh(context)
                }
            }
            listLayout.addView(itemRow)
        }

        // Opcao final na lista suspensa para criar nova subpasta rapidamente
        val addFolderBtn = TextView(context).apply {
            text = "➕ Nova Subpasta ou Prefixo..."
            setTextColor(Color.parseColor("#10B981"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = roundedBg("#064E3B", "#10B981", dp(context, 8), dp(context, 1))
            setPadding(dp(context, 10), dp(context, 8), dp(context, 10), dp(context, 8))
            setOnClickListener {
                toggleConfigPanel(context)
            }
        }
        listLayout.addView(addFolderBtn)
    }

    private fun launchCameraFromOverlay(context: Context) {
        try {
            closePanels(context)
            if (!SettingsManager.isCameraInterceptorEnabled(context)) {
                SettingsManager.setCameraInterceptorEnabled(context, true)
                val svcIntent = Intent(context, MediaInterceptorService::class.java).apply {
                    action = MediaInterceptorService.ACTION_START_CAMERA_INTERCEPTOR
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(svcIntent)
                } else {
                    context.startService(svcIntent)
                }
            }
            val camIntent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(camIntent)
        } catch (_: Exception) {
            try {
                val fallback = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallback)
            } catch (_: Exception) {
            }
        }
    }

    private fun roundedBg(
        fillHex: String,
        strokeHex: String,
        radiusPx: Int,
        strokeWidthPx: Int
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx.toFloat()
            setColor(Color.parseColor(fillHex))
            if (strokeWidthPx > 0) {
                setStroke(strokeWidthPx, Color.parseColor(strokeHex))
            }
        }
    }

    private fun dp(context: Context, value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            context.resources.displayMetrics
        ).toInt()
    }
}
