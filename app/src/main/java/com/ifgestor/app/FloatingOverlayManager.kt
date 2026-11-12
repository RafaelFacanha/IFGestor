package com.ifgestor.app

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import kotlin.math.abs

class FloatingOverlayManager(
    private val context: Context,
    private val onSettingsUpdated: () -> Unit
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var rootContainer: LinearLayout? = null
    private var bubbleView: TextView? = null
    private var expandedPanel: View? = null
    private var windowParams: WindowManager.LayoutParams? = null
    private var isShowing = false
    private var isExpanded = false

    fun show() {
        if (isShowing) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
            return
        }

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        windowParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 240
        }

        rootContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        bubbleView = createBubbleView()
        rootContainer?.addView(bubbleView)

        try {
            windowManager.addView(rootContainer, windowParams)
            isShowing = true
            isExpanded = false
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun hide() {
        if (!isShowing) return
        try {
            rootContainer?.let { windowManager.removeView(it) }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            rootContainer = null
            bubbleView = null
            expandedPanel = null
            isShowing = false
            isExpanded = false
        }
    }

    fun toggleVisibility() {
        if (isShowing) hide() else show()
    }

    fun refreshBubbleLabel() {
        val prefix = SettingsManager.getPrefix(context)
        val count = SettingsManager.interceptedCount.value
        bubbleView?.text = "🟢 IFG: $prefix ($count)"
    }

    private fun createBubbleView(): TextView {
        val prefix = SettingsManager.getPrefix(context)
        val count = SettingsManager.interceptedCount.value

        val pillBg = GradientDrawable().apply {
            setColor(Color.parseColor("#EB0B192C"))
            cornerRadius = dpToPx(28).toFloat()
            setStroke(dpToPx(2), Color.parseColor("#38BDF8"))
        }

        return TextView(context).apply {
            text = "🟢 IFG: $prefix ($count)"
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            background = pillBg
            setPadding(dpToPx(16), dpToPx(10), dpToPx(16), dpToPx(10))

            var initialX = 0
            var initialY = 0
            var initialTouchX = 0f
            var initialTouchY = 0f

            setOnTouchListener { _, event ->
                val params = this@FloatingOverlayManager.windowParams ?: return@setOnTouchListener false
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = initialX + (event.rawX - initialTouchX).toInt()
                        params.y = initialY + (event.rawY - initialTouchY).toInt()
                        try {
                            windowManager.updateViewLayout(rootContainer, params)
                        } catch (_: Exception) {}
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        val dx = abs(event.rawX - initialTouchX)
                        val dy = abs(event.rawY - initialTouchY)
                        if (dx < dpToPx(10) && dy < dpToPx(10)) {
                            expandQuickPanel()
                        }
                        true
                    }
                    else -> false
                }
            }
        }
    }

    private fun expandQuickPanel() {
        if (isExpanded) return
        val container = rootContainer ?: return
        val params = windowParams ?: return

        params.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        params.width = dpToPx(325)

        container.removeAllViews()
        val panel = buildExpandedPanelView()
        expandedPanel = panel
        container.addView(panel)

        try {
            windowManager.updateViewLayout(container, params)
            isExpanded = true
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun collapseToBubble() {
        if (!isExpanded) return
        val container = rootContainer ?: return
        val params = windowParams ?: return

        params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        params.width = WindowManager.LayoutParams.WRAP_CONTENT

        container.removeAllViews()
        bubbleView = createBubbleView()
        container.addView(bubbleView)

        try {
            windowManager.updateViewLayout(container, params)
            isExpanded = false
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun buildExpandedPanelView(): View {
        val cardBg = GradientDrawable().apply {
            setColor(Color.parseColor("#F50F172A"))
            cornerRadius = dpToPx(20).toFloat()
            setStroke(dpToPx(2), Color.parseColor("#334155"))
        }

        val mainLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg
            setPadding(dpToPx(16), dpToPx(14), dpToPx(16), dpToPx(16))
        }

        // Cabecalho Corporativo com contador e botao de fechar
        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val count = SettingsManager.interceptedCount.value
        val titleText = TextView(context).apply {
            text = "🟢 IFGestor • Capturados: $count"
            setTextColor(Color.parseColor("#38BDF8"))
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val closeIcon = TextView(context).apply {
            text = "✖"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 15f
            setPadding(dpToPx(8), dpToPx(2), dpToPx(4), dpToPx(2))
            setOnClickListener { collapseToBubble() }
        }

        headerRow.addView(titleText)
        headerRow.addView(closeIcon)
        mainLayout.addView(headerRow)

        var selectedFolderPath = SettingsManager.getTargetFolderPath(context)
        val folderInfo = TextView(context).apply {
            text = "📂 ${SettingsManager.formatShortPath(selectedFolderPath)}"
            setTextColor(Color.parseColor("#E2E8F0"))
            textSize = 12f
            setPadding(0, dpToPx(6), 0, dpToPx(10))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.START
        }
        mainLayout.addView(folderInfo)

        // Campo de Prefixo Atual
        val inputBg = GradientDrawable().apply {
            setColor(Color.parseColor("#1E293B"))
            cornerRadius = dpToPx(10).toFloat()
            setStroke(dpToPx(1), Color.parseColor("#475569"))
        }

        val prefixInput = EditText(context).apply {
            setText(SettingsManager.getPrefix(context))
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#64748B"))
            hint = "Digite o prefixo..."
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            background = inputBg
            setPadding(dpToPx(12), dpToPx(8), dpToPx(12), dpToPx(8))
            setSingleLine(true)
        }

        // Secao de Chips de Prefixos Rapidos (1 Toque)
        val presetsLabel = TextView(context).apply {
            text = "Prefixos Rápidos (1 toque):"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 11f
            setPadding(0, 0, 0, dpToPx(6))
        }
        mainLayout.addView(presetsLabel)

        val chipsScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, 0, 0, dpToPx(10))
        }
        val chipsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val presets = SettingsManager.getQuickPresets(context)
        for (preset in presets) {
            val chipBg = GradientDrawable().apply {
                setColor(Color.parseColor("#1E293B"))
                cornerRadius = dpToPx(16).toFloat()
                setStroke(dpToPx(1), Color.parseColor("#0284C7"))
            }
            val chip = TextView(context).apply {
                text = preset
                setTextColor(Color.parseColor("#38BDF8"))
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                background = chipBg
                setPadding(dpToPx(12), dpToPx(6), dpToPx(12), dpToPx(6))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginEnd = dpToPx(6)
                }
                setOnClickListener {
                    prefixInput.setText(preset)
                    SettingsManager.setPrefix(context, preset)
                    onSettingsUpdated()
                    Toast.makeText(context, "Prefixo: $preset", Toast.LENGTH_SHORT).show()
                }
            }
            chipsRow.addView(chip)
        }
        chipsScroll.addView(chipsRow)
        mainLayout.addView(chipsScroll)

        // Adiciona o campo de edicao de prefixo logo abaixo dos chips
        mainLayout.addView(prefixInput)

        // Secao de Pastas Recentes
        val recents = SettingsManager.getRecentFolders(context)
        if (recents.isNotEmpty()) {
            val recentLabel = TextView(context).apply {
                text = "Alternar Pasta Recente:"
                setTextColor(Color.parseColor("#94A3B8"))
                textSize = 11f
                setPadding(0, dpToPx(10), 0, dpToPx(4))
            }
            mainLayout.addView(recentLabel)

            val recentScroll = ScrollView(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dpToPx(84)
                )
            }
            val recentListLayout = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
            }

            for (path in recents) {
                val itemBtn = TextView(context).apply {
                    val isCurrent = path == selectedFolderPath
                    text = if (isCurrent) "✔ ${SettingsManager.formatShortPath(path)}"
                    else "📁 ${SettingsManager.formatShortPath(path)}"
                    setTextColor(if (isCurrent) Color.parseColor("#10B981") else Color.parseColor("#CBD5E1"))
                    textSize = 12f
                    typeface = if (isCurrent) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                    setPadding(dpToPx(8), dpToPx(5), dpToPx(8), dpToPx(5))
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.START
                    setOnClickListener {
                        selectedFolderPath = path
                        SettingsManager.setTargetFolderPath(context, path)
                        folderInfo.text = "📂 ${SettingsManager.formatShortPath(path)}"
                        onSettingsUpdated()
                        Toast.makeText(context, "Pasta alterada!", Toast.LENGTH_SHORT).show()
                    }
                }
                recentListLayout.addView(itemBtn)
            }
            recentScroll.addView(recentListLayout)
            mainLayout.addView(recentScroll)
        }

        // Criar Nova Subpasta na hora
        val subfolderLabel = TextView(context).apply {
            text = "Nova Subpasta (Opcional):"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 11f
            setPadding(0, dpToPx(8), 0, dpToPx(4))
        }
        mainLayout.addView(subfolderLabel)

        val subfolderBg = GradientDrawable().apply {
            setColor(Color.parseColor("#1E293B"))
            cornerRadius = dpToPx(10).toFloat()
            setStroke(dpToPx(1), Color.parseColor("#334155"))
        }

        val subfolderInput = EditText(context).apply {
            setText("")
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#64748B"))
            hint = "+ Criar e entrar em subpasta..."
            textSize = 13f
            background = subfolderBg
            setPadding(dpToPx(12), dpToPx(7), dpToPx(12), dpToPx(7))
            setSingleLine(true)
        }
        mainLayout.addView(subfolderInput)

        // Botoes Inferiores Estilizados
        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dpToPx(14), 0, 0)
        }

        val cancelBg = GradientDrawable().apply {
            setColor(Color.parseColor("#334155"))
            cornerRadius = dpToPx(10).toFloat()
        }

        val cancelBtn = TextView(context).apply {
            text = "Minimizar"
            setTextColor(Color.WHITE)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = cancelBg
            setPadding(dpToPx(14), dpToPx(10), dpToPx(14), dpToPx(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = dpToPx(8)
            }
            setOnClickListener { collapseToBubble() }
        }

        val saveBg = GradientDrawable().apply {
            setColor(Color.parseColor("#10B981"))
            cornerRadius = dpToPx(10).toFloat()
        }

        val saveBtn = TextView(context).apply {
            text = "✓ SALVAR NA CÂMERA"
            setTextColor(Color.WHITE)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = saveBg
            setPadding(dpToPx(14), dpToPx(10), dpToPx(14), dpToPx(10))
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
            setOnClickListener {
                val newPrefix = prefixInput.text.toString().trim()
                SettingsManager.setPrefix(context, newPrefix)
                if (newPrefix.isNotEmpty()) {
                    SettingsManager.addQuickPreset(context, newPrefix)
                }

                val subName = subfolderInput.text.toString().trim()
                    .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                if (subName.isNotEmpty()) {
                    val base = File(SettingsManager.getTargetFolderPath(context))
                    val newSub = File(base, subName)
                    newSub.mkdirs()
                    SettingsManager.setTargetFolderPath(context, newSub.absolutePath)
                }

                onSettingsUpdated()
                Toast.makeText(context, "Pronto para fotografar!", Toast.LENGTH_SHORT).show()
                collapseToBubble()
            }
        }

        btnRow.addView(cancelBtn)
        btnRow.addView(saveBtn)
        mainLayout.addView(btnRow)

        return mainLayout
    }

    private fun dpToPx(dp: Int): Int {
        val density = context.resources.displayMetrics.density
        return (dp * density).toInt()
    }
}
