package com.ifgestor.app

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
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
    private var layoutParams: WindowManager.LayoutParams? = null
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

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 260
        }

        rootContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        bubbleView = createBubbleView()
        rootContainer?.addView(bubbleView)

        try {
            windowManager.addView(rootContainer, layoutParams)
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
        bubbleView?.text = "📷 IFG: $prefix"
    }

    private fun createBubbleView(): TextView {
        val prefix = SettingsManager.getPrefix(context)
        val bg = GradientDrawable().apply {
            setColor(Color.parseColor("#E63F51B5"))
            cornerRadius = dpToPx(24).toFloat()
            setStroke(dpToPx(2), Color.WHITE)
        }

        return TextView(context).apply {
            text = "📷 IFG: $prefix"
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            background = bg
            setPadding(dpToPx(14), dpToPx(10), dpToPx(14), dpToPx(10))

            var initialX = 0
            var initialY = 0
            var initialTouchX = 0f
            var initialTouchY = 0f

            setOnTouchListener { _, event ->
                val params = layoutParams ?: return@setOnTouchListener false
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
        val params = layoutParams ?: return

        // Libera o foco de teclado para permitir digitar o prefixo ou subpasta sobre a camera
        params.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        params.width = dpToPx(310)

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
        val params = layoutParams ?: return

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
            setColor(Color.parseColor("#F51E1E2E"))
            cornerRadius = dpToPx(16).toFloat()
            setStroke(dpToPx(2), Color.parseColor("#9FA8DA"))
        }

        val mainLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg
            setPadding(dpToPx(14), dpToPx(14), dpToPx(14), dpToPx(14))
        }

        val title = TextView(context).apply {
            text = "Ajuste Rápido IFGestor"
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
        }
        mainLayout.addView(title)

        val currentFolder = SettingsManager.getTargetFolderPath(context)
        val folderInfo = TextView(context).apply {
            text = "Pasta: ${SettingsManager.formatShortPath(currentFolder)}"
            setTextColor(Color.parseColor("#B0BEC5"))
            textSize = 12f
            setPadding(0, dpToPx(4), 0, dpToPx(8))
        }
        mainLayout.addView(folderInfo)

        val prefixLabel = TextView(context).apply {
            text = "Prefixo do Arquivo:"
            setTextColor(Color.WHITE)
            textSize = 12f
        }
        mainLayout.addView(prefixLabel)

        val inputBg = GradientDrawable().apply {
            setColor(Color.parseColor("#2D2D44"))
            cornerRadius = dpToPx(8).toFloat()
        }

        val prefixInput = EditText(context).apply {
            setText(SettingsManager.getPrefix(context))
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            hint = "Ex: Vistoria_"
            textSize = 14f
            background = inputBg
            setPadding(dpToPx(10), dpToPx(8), dpToPx(10), dpToPx(8))
            setSingleLine(true)
        }
        mainLayout.addView(prefixInput)

        val subfolderLabel = TextView(context).apply {
            text = "Criar/Entrar em Subpasta (Opcional):"
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding(0, dpToPx(8), 0, 0)
        }
        mainLayout.addView(subfolderLabel)

        val subfolderInput = EditText(context).apply {
            setText("")
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            hint = "Nome da nova subpasta"
            textSize = 13f
            background = inputBg
            setPadding(dpToPx(10), dpToPx(8), dpToPx(10), dpToPx(8))
            setSingleLine(true)
        }
        mainLayout.addView(subfolderInput)

        // Lista de Pastas Recentes para troca com 1 toque
        val recents = SettingsManager.getRecentFolders(context)
        if (recents.isNotEmpty()) {
            val recentLabel = TextView(context).apply {
                text = "Trocar para Pasta Recente:"
                setTextColor(Color.parseColor("#9FA8DA"))
                textSize = 12f
                setPadding(0, dpToPx(10), 0, dpToPx(4))
            }
            mainLayout.addView(recentLabel)

            val recentScroll = ScrollView(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dpToPx(95)
                )
            }
            val recentListLayout = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
            }

            var selectedPath = currentFolder
            for (path in recents) {
                val itemBtn = TextView(context).apply {
                    text = "📁 ${SettingsManager.formatShortPath(path)}"
                    setTextColor(if (path == selectedPath) Color.parseColor("#80CBC4") else Color.WHITE)
                    textSize = 12f
                    setPadding(dpToPx(6), dpToPx(6), dpToPx(6), dpToPx(6))
                    setOnClickListener {
                        selectedPath = path
                        SettingsManager.setTargetFolderPath(context, path)
                        folderInfo.text = "Pasta: ${SettingsManager.formatShortPath(path)}"
                        Toast.makeText(context, "Pasta alterada!", Toast.LENGTH_SHORT).show()
                    }
                }
                recentListLayout.addView(itemBtn)
            }
            recentScroll.addView(recentListLayout)
            mainLayout.addView(recentScroll)
        }

        // Botoes de Acao (Salvar / Minimizar)
        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dpToPx(12), 0, 0)
        }

        val cancelBtn = Button(context).apply {
            text = "Fechar"
            textSize = 12f
            setOnClickListener { collapseToBubble() }
        }

        val saveBtn = Button(context).apply {
            text = "Salvar"
            textSize = 12f
            setOnClickListener {
                val newPrefix = prefixInput.text.toString()
                SettingsManager.setPrefix(context, newPrefix)

                val subName = subfolderInput.text.toString().trim()
                    .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                if (subName.isNotEmpty()) {
                    val base = File(SettingsManager.getTargetFolderPath(context))
                    val newSub = File(base, subName)
                    newSub.mkdirs()
                    SettingsManager.setTargetFolderPath(context, newSub.absolutePath)
                }

                onSettingsUpdated()
                Toast.makeText(context, "Configuração atualizada!", Toast.LENGTH_SHORT).show()
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
