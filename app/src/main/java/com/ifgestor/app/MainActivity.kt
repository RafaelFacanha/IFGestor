package com.ifgestor.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaScannerConnection
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.LruCache
import android.util.Size
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val bgExecutor = Executors.newFixedThreadPool(4)

    // Cache em memoria RAM para miniaturas da Galeria (evita OutOfMemory)
    private val thumbCache = object : LruCache<String, Bitmap>(40) {}

    private var currentTab = 0 // 0 = Painel & Camera, 1 = Galeria Sandbox, 2 = Servidor PC & Auditoria
    private var currentGallerySubfolder = ""
    private var gallerySearchQuery = ""
    private val selectedGalleryPaths = LinkedHashSet<String>()
    private var isTaskDrawerExpanded = false

    // Referencias de UI atualizadas em tempo real
    private var tvHeaderStatus: TextView? = null
    private var tvHeaderCounters: TextView? = null
    private var contentContainer: FrameLayout? = null
    private var tabBtn0: Button? = null
    private var tabBtn1: Button? = null
    private var tabBtn2: Button? = null

    // Referencias da Central de Tarefas Recolhivel Mobile (Item 10)
    private var taskDrawerContainer: LinearLayout? = null
    private var tvTaskDrawerHeader: TextView? = null
    private var tvTaskDrawerArrow: TextView? = null
    private var taskDrawerListLayout: LinearLayout? = null

    // Referencias de atualizacao ao vivo nas abas
    private var tvLiveChallengeBox: LinearLayout? = null
    private var galleryGridContainer: LinearLayout? = null
    private var tvGallerySelectionInfo: TextView? = null

    private val livePoller = object : Runnable {
        override fun run() {
            refreshHeaderAndTaskDrawer()
            if (currentTab == 2) {
                refreshLive2faChallengeBanner()
            }
            mainHandler.postDelayed(this, 1200L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SettingsManager.getSandboxRootDir() // Garante criacao da Sandbox DCIM/Camera/IFgestor
        setContentView(buildMainScaffold())
        checkAndRequestInitialPermissions()
        switchTab(0)
    }

    override fun onResume() {
        super.onResume()
        mainHandler.removeCallbacks(livePoller)
        mainHandler.post(livePoller)
        IFGestorHomeWidget.requestWidgetUpdate(this)
        FloatingOverlayManager.refresh(this)
        renderActiveTab()
    }

    override fun onPause() {
        mainHandler.removeCallbacks(livePoller)
        super.onPause()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(livePoller)
        super.onDestroy()
    }

    /**
     * Atalho fisico de campo: se o disparo com Botao Volume (-) estiver ativo,
     * aciona a camera com o interceptador ligado imediatamente.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && SettingsManager.isVolumeShutterEnabled(this)) {
            startCameraWithInterceptor()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // =========================================================================
    // 1. ESTRUTURA VISUAL PRINCIPAL (CABECALHO + ABAS + CENTRAL DE TAREFAS)
    // =========================================================================

    private fun buildMainScaffold(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0F172A"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // Cabecalho Corporativo Fixo
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = cardDrawable("#1E293B", "#334155", 0, 1)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }

        val logo = ImageView(this).apply {
            setImageResource(R.drawable.ic_ifgestor_logo)
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                marginEnd = dp(12)
            }
        }
        header.addView(logo)

        val headerTextCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val title = TextView(this).apply {
            text = "IFGestor • Blindagem & Multitarefa"
            setTextColor(Color.parseColor("#F8FAFC"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTypeface(typeface, Typeface.BOLD)
        }

        val statusLine = TextView(this).apply {
            text = "⚪ Câmera: OFF • 🌐 Web: OFF"
            setTextColor(Color.parseColor("#10B981"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTypeface(typeface, Typeface.BOLD)
        }
        tvHeaderStatus = statusLine

        val countersLine = TextView(this).apply {
            text = "Pasta: DCIM/Camera/IFgestor • Sessão: 0 | Total: 0"
            setTextColor(Color.parseColor("#38BDF8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        }
        tvHeaderCounters = countersLine

        headerTextCol.addView(title)
        headerTextCol.addView(statusLine)
        headerTextCol.addView(countersLine)
        header.addView(headerTextCol)
        root.addView(header)

        // Barra de Navegacao das 3 Abas
        val tabsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#0F172A"))
            setPadding(dp(12), dp(8), dp(12), dp(8))
            weightSum = 3f
        }

        tabBtn0 = makeTabButton("📷 Controle", 0)
        tabBtn1 = makeTabButton("🖼️ Galeria", 1)
        tabBtn2 = makeTabButton("💻 Servidor & 2FA", 2)

        tabsRow.addView(tabBtn0)
        tabsRow.addView(SpaceView(dp(6), 1))
        tabsRow.addView(tabBtn1)
        tabsRow.addView(SpaceView(dp(6), 1))
        tabsRow.addView(tabBtn2)
        root.addView(tabsRow)

        // Container Central das Abas
        val frame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        contentContainer = frame
        root.addView(frame)

        // Central de Tarefas Paralelas e Recolhivel no Rodape (Item 10)
        val drawer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardDrawable("#1E293B", "#38BDF8", 0, 1)
            visibility = View.GONE
        }
        taskDrawerContainer = drawer

        val drawerHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#0F2942"))
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setOnClickListener {
                isTaskDrawerExpanded = !isTaskDrawerExpanded
                taskDrawerListLayout?.visibility = if (isTaskDrawerExpanded) View.VISIBLE else View.GONE
                tvTaskDrawerArrow?.text = if (isTaskDrawerExpanded) "▼ Recolher" else "▲ Expandir"
            }
        }

        val drawerTitle = TextView(this).apply {
            text = "🔄 Central de Tarefas (0 ativas)"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        tvTaskDrawerHeader = drawerTitle

        val btnClearDone = TextView(this).apply {
            text = "Limpar"
            setTextColor(Color.parseColor("#38BDF8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(dp(8), dp(2), dp(8), dp(2))
            setOnClickListener {
                SettingsManager.clearCompletedTasks()
                refreshHeaderAndTaskDrawer()
            }
        }

        val drawerArrow = TextView(this).apply {
            text = "▲ Expandir"
            setTextColor(Color.parseColor("#38BDF8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTypeface(typeface, Typeface.BOLD)
        }
        tvTaskDrawerArrow = drawerArrow

        drawerHeader.addView(drawerTitle)
        drawerHeader.addView(btnClearDone)
        drawerHeader.addView(SpaceView(dp(8), 1))
        drawerHeader.addView(drawerArrow)
        drawer.addView(drawerHeader)

        val drawerList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        taskDrawerListLayout = drawerList
        drawer.addView(drawerList)

        root.addView(drawer)
        return root
    }

    private fun makeTabButton(label: String, index: Int): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f)
            setOnClickListener { switchTab(index) }
        }
    }

    private fun switchTab(index: Int) {
        currentTab = index
        val buttons = listOf(tabBtn0, tabBtn1, tabBtn2)
        buttons.forEachIndexed { idx, btn ->
            val selected = (idx == index)
            btn?.background = cardDrawable(
                if (selected) "#0284C7" else "#1E293B",
                if (selected) "#38BDF8" else "#334155",
                10,
                1
            )
            btn?.setTextColor(if (selected) Color.WHITE else Color.parseColor("#94A3B8"))
        }
        renderActiveTab()
    }

    private fun renderActiveTab() {
        val container = contentContainer ?: return
        container.removeAllViews()
        refreshHeaderAndTaskDrawer()

        val view = when (currentTab) {
            0 -> buildTab0ControlAndCamera()
            1 -> buildTab1SandboxGallery()
            else -> buildTab2ServerSecurityAndAudit()
        }
        container.addView(view)
    }

    private fun refreshHeaderAndTaskDrawer() {
        val camOn = SettingsManager.isCameraInterceptorEnabled(this)
        val webOn = SettingsManager.isWebServerEnabled(this)
        val domain = SettingsManager.getActiveMdnsDomain(this)
        val port = SettingsManager.getServerPort(this)
        val scheme = if (SettingsManager.isHttpsEnabled(this)) "https" else "http"

        val statusStr = buildString {
            append(if (camOn) "🟢 Câmera: ATIVA" else "⚪ Câmera: OFF")
            append("  •  ")
            if (webOn) {
                append("🌐 Web: $scheme://$domain:$port")
            } else {
                append("🌐 Web: OFF")
            }
        }
        tvHeaderStatus?.text = statusStr
        tvHeaderStatus?.setTextColor(
            Color.parseColor(if (camOn || webOn) "#10B981" else "#94A3B8")
        )

        val displayPath = SettingsManager.getSandboxDisplayPath(this)
        val sessCount = SettingsManager.getSessionInterceptCount(this)
        val totalCount = SettingsManager.getFolderMediaCount(this)
        tvHeaderCounters?.text = "Pasta: $displayPath  •  Sessão: $sessCount | Pasta: $totalCount"

        // Atualiza a Central de Tarefas Recolhivel
        val tasks = SettingsManager.getTasksSnapshot()
        val drawer = taskDrawerContainer ?: return
        val listLayout = taskDrawerListLayout ?: return

        if (tasks.isEmpty()) {
            drawer.visibility = View.GONE
            return
        }

        drawer.visibility = View.VISIBLE
        val runningCount = tasks.count { it.status == "RUNNING" }
        val avgProgress = if (runningCount > 0) {
            tasks.filter { it.status == "RUNNING" }.map { it.progressPercent }.average().toInt()
        } else {
            100
        }

        tvTaskDrawerHeader?.text = if (runningCount > 0) {
            "🔄 $runningCount ação(ões) ativa(s) • $avgProgress%"
        } else {
            "✅ ${tasks.size} operação(ões) recente(s) concluída(s)"
        }

        listLayout.removeAllViews()
        for (t in tasks.take(6)) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = cardDrawable("#0F172A", "#334155", 8, 1)
                setPadding(dp(10), dp(6), dp(10), dp(6))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(6) }
            }

            val topLine = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            val tTitle = TextView(this).apply {
                text = t.title
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTypeface(typeface, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val tPct = TextView(this).apply {
                text = "${t.progressPercent}%"
                setTextColor(Color.parseColor("#38BDF8"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTypeface(typeface, Typeface.BOLD)
            }
            topLine.addView(tTitle)
            topLine.addView(tPct)

            val tSub = TextView(this).apply {
                text = t.subtitle
                setTextColor(Color.parseColor("#94A3B8"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            }

            val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                progress = t.progressPercent
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(8)
                ).apply { topMargin = dp(4) }
            }

            row.addView(topLine)
            row.addView(tSub)
            row.addView(bar)
            listLayout.addView(row)
        }
    }

    // =========================================================================
    // 2. ABA 1: MOTORES INDEPENDENTES, SANDBOX, PREFIXO E WIDGETS (ITENS 12-15)
    // =========================================================================

    private fun buildTab0ControlAndCamera(): View {
        val scroll = ScrollView(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(24))
        }
        scroll.addView(col)

        // Card 1: Acionamento Individual dos Motores (Item 12)
        val enginesCard = makeSectionCard("⚡ Motores Independentes (Câmera & Site Web)")

        val camActive = SettingsManager.isCameraInterceptorEnabled(this)
        val webActive = SettingsManager.isWebServerEnabled(this)

        val btnToggleCameraEngine = makePrimaryButton(
            if (camActive) "🟢 Interceptador de Fotos: ATIVO (Toque p/ Parar)"
            else "⚪ Iniciar Interceptação de Fotos",
            if (camActive) "#059669" else "#1E293B",
            "#10B981"
        ) {
            val next = !SettingsManager.isCameraInterceptorEnabled(this)
            SettingsManager.setCameraInterceptorEnabled(this, next)
            val svc = Intent(this, MediaInterceptorService::class.java).apply {
                action = if (next) {
                    MediaInterceptorService.ACTION_START_CAMERA_INTERCEPTOR
                } else {
                    MediaInterceptorService.ACTION_STOP_CAMERA_INTERCEPTOR
                }
            }
            startServiceCompat(svc)
            renderActiveTab()
        }
        enginesCard.addView(btnToggleCameraEngine)

        enginesCard.addView(SpaceView(1, dp(8)))

        val btnLaunchCameraNow = makePrimaryButton(
            "📷 Abrir Câmera Nativa + Interceptar Agora",
            "#0284C7",
            "#38BDF8"
        ) {
            startCameraWithInterceptor()
        }
        enginesCard.addView(btnLaunchCameraNow)

        enginesCard.addView(SpaceView(1, dp(8)))

        val btnToggleWebEngine = makePrimaryButton(
            if (webActive) "🌐 Compartilhamento Web: ATIVO (${WebServerManager.getMdnsUrl(this)})"
            else "🌐 Iniciar Compartilhamento via Site Web",
            if (webActive) "#0369A1" else "#1E293B",
            "#38BDF8"
        ) {
            toggleWebServerEngineWithPinCheck()
        }
        enginesCard.addView(btnToggleWebEngine)

        col.addView(enginesCard)

        // Card 2: Configuracao da Pasta Sandbox (DCIM/Camera/IFgestor) e Prefixo
        val folderCard = makeSectionCard("📂 Destino na Sandbox & Contador Duplo")

        val sandboxInfo = TextView(this).apply {
            text = "Escopo Isolado: ${SettingsManager.getSandboxDisplayPath(this@MainActivity)}"
            setTextColor(Color.parseColor("#38BDF8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(8))
        }
        folderCard.addView(sandboxInfo)

        // Seletor Dropdown de Subpastas Existentes
        val lblDropdown = makeFieldLabel("Trocar Subpasta Rápido (Lista Suspensa):")
        folderCard.addView(lblDropdown)

        val subfolders = SettingsManager.getSandboxSubfolders(this)
        val currentSub = SettingsManager.getTargetSubfolder(this)
        val spinnerLabels = subfolders.map { sub ->
            val cnt = SettingsManager.getFolderMediaCount(this, sub)
            if (sub.isEmpty()) "📁 IFgestor (Raiz Principal) • $cnt arq."
            else "📁 $sub • $cnt arq."
        }

        val folderSpinner = Spinner(this).apply {
            background = cardDrawable("#0F172A", "#334155", 10, 1)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                spinnerLabels
            )
            val currentIdx = subfolders.indexOf(currentSub).coerceAtLeast(0)
            setSelection(currentIdx, false)
        }
        folderCard.addView(folderSpinner)

        folderCard.addView(SpaceView(1, dp(8)))

        val etSubfolder = makeInputEdit(
            hint = "Subpasta personalizada (vazio = Raiz IFgestor)",
            initial = currentSub
        )
        folderCard.addView(makeFieldLabel("Ou digite nova Subpasta em DCIM/Camera/IFgestor:"))
        folderCard.addView(etSubfolder)

        folderSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, pos: Int, id: Long) {
                (view as? TextView)?.setTextColor(Color.WHITE)
                val chosen = subfolders.getOrElse(pos) { "" }
                if (chosen != SettingsManager.getTargetSubfolder(this@MainActivity)) {
                    SettingsManager.setTargetSubfolder(this@MainActivity, chosen)
                    etSubfolder.setText(chosen)
                    FloatingOverlayManager.refresh(this@MainActivity)
                    refreshHeaderAndTaskDrawer()
                }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        folderCard.addView(SpaceView(1, dp(8)))

        val etPrefix = makeInputEdit(
            hint = "Prefixo dos arquivos (ex: Vistoria_)",
            initial = SettingsManager.getFilePrefix(this)
        )
        folderCard.addView(makeFieldLabel("Prefixo Automático das Fotos/Vídeos:"))
        folderCard.addView(etPrefix)

        folderCard.addView(SpaceView(1, dp(10)))

        val saveRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f
        }

        val btnSaveFolderPrefix = makePrimaryButton("💾 Salvar Pasta & Prefixo", "#0284C7", "#38BDF8") {
            SettingsManager.setTargetSubfolder(this, etSubfolder.text?.toString() ?: "")
            SettingsManager.setFilePrefix(this, etPrefix.text?.toString() ?: "IFG_")
            FloatingOverlayManager.refresh(this)
            IFGestorHomeWidget.requestWidgetUpdate(this)
            Toast.makeText(
                this,
                "Salvo: ${SettingsManager.getSandboxDisplayPath(this)} (${SettingsManager.getFilePrefix(this)})",
                Toast.LENGTH_SHORT
            ).show()
            renderActiveTab()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(4) }
        }

        val btnResetSession = makePrimaryButton(
            "🔄 Zerar Sessão (${SettingsManager.getSessionInterceptCount(this)})",
            "#334155",
            "#475569"
        ) {
            SettingsManager.resetSessionInterceptCount(this)
            FloatingOverlayManager.refresh(this)
            renderActiveTab()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginStart = dp(4) }
        }

        saveRow.addView(btnSaveFolderPrefix)
        saveRow.addView(btnResetSession)
        folderCard.addView(saveRow)
        col.addView(folderCard)

        // Card 3: Widget Flutuante, Botao Volume (-) e Permissoes
        val optionsCard = makeSectionCard("🎛️ Widgets & Atalhos de Operação")

        val swFloating = Switch(this).apply {
            text = "Exibir Ícone Flutuante (Dropdown + Contador Duplo) sobre a câmera"
            setTextColor(Color.WHITE)
            textSize = 12f
            isChecked = SettingsManager.isFloatingWidgetEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, checked ->
                if (checked && !Settings.canDrawOverlays(this@MainActivity)) {
                    requestOverlayPermission()
                    isChecked = false
                    return@setOnCheckedChangeListener
                }
                SettingsManager.setFloatingWidgetEnabled(this@MainActivity, checked)
                if (checked && SettingsManager.isCameraInterceptorEnabled(this@MainActivity)) {
                    FloatingOverlayManager.show(this@MainActivity)
                } else {
                    FloatingOverlayManager.hide()
                }
            }
        }
        optionsCard.addView(swFloating)

        optionsCard.addView(SpaceView(1, dp(8)))

        val swVol = Switch(this).apply {
            text = "Atalho Botão Físico Volume (-) para abrir câmera no app"
            setTextColor(Color.WHITE)
            textSize = 12f
            isChecked = SettingsManager.isVolumeShutterEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, checked ->
                SettingsManager.setVolumeShutterEnabled(this@MainActivity, checked)
            }
        }
        optionsCard.addView(swVol)

        optionsCard.addView(SpaceView(1, dp(10)))

        val btnPermissions = makePrimaryButton(
            "🛡️ Verificar / Conceder Permissões (Arquivos, Sobreposição e Notificações)",
            "#1E293B",
            "#38BDF8"
        ) {
            checkAndRequestInitialPermissions(forcePrompt = true)
        }
        optionsCard.addView(btnPermissions)

        col.addView(optionsCard)
        return scroll
    }

    // =========================================================================
    // 3. ABA 2: GALERIA & PASTAS ISOLADA NA SANDBOX + WHATSAPP + P2P (ITENS 3, 8, 16)
    // =========================================================================

    private fun buildTab1SandboxGallery(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(12))
        }

        // Barra Superior da Galeria: Caminho na Sandbox + Nova Pasta + Voltar
        val pathRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = cardDrawable("#1E293B", "#334155", 10, 1)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }

        val currentDisplay = if (currentGallerySubfolder.isEmpty()) {
            "🔒 DCIM/Camera/IFgestor"
        } else {
            "🔒 DCIM/Camera/IFgestor/$currentGallerySubfolder"
        }

        val tvPath = TextView(this).apply {
            text = currentDisplay
            setTextColor(Color.parseColor("#38BDF8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        pathRow.addView(tvPath)

        if (currentGallerySubfolder.isNotEmpty()) {
            val btnUp = makeSmallButton("⬅️ Raiz/Acima", "#334155") {
                val parts = currentGallerySubfolder.split('/').toMutableList()
                parts.removeLastOrNull()
                currentGallerySubfolder = parts.joinToString("/")
                selectedGalleryPaths.clear()
                renderActiveTab()
            }
            pathRow.addView(btnUp)
            pathRow.addView(SpaceView(dp(6), 1))
        }

        val btnNewFolder = makeSmallButton("➕ Pasta", "#0284C7") {
            promptCreateSubfolderInGallery()
        }
        pathRow.addView(btnNewFolder)
        root.addView(pathRow)

        root.addView(SpaceView(1, dp(8)))

        // Busca e Selecao em Lote
        val filterRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val etSearch = makeInputEdit("🔍 Filtrar arquivos...", gallerySearchQuery).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginEnd = dp(6) }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    gallerySearchQuery = s?.toString() ?: ""
                    populateGalleryGrid()
                }
            })
        }
        filterRow.addView(etSearch)

        val btnSelectAll = makeSmallButton("☑️ Todos", "#1E293B") {
            val dir = SettingsManager.resolveSafeSandboxFile(currentGallerySubfolder)
                ?: SettingsManager.getSandboxRootDir()
            val files = dir.listFiles { f -> f.isFile && !f.name.startsWith(".") && !f.name.endsWith(".part") && SettingsManager.isMediaFile(f.name) }
                ?: emptyArray()
            if (selectedGalleryPaths.size == files.size) {
                selectedGalleryPaths.clear()
            } else {
                files.forEach { selectedGalleryPaths.add(it.absolutePath) }
            }
            populateGalleryGrid()
        }
        filterRow.addView(btnSelectAll)
        root.addView(filterRow)

        root.addView(SpaceView(1, dp(6)))

        // Barra de Acoes Multiplas: WhatsApp, P2P, Mover e Excluir
        val actionsScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
        }
        val actionsBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val tvSel = TextView(this).apply {
            text = "${selectedGalleryPaths.size} sel."
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(dp(4), 0, dp(8), 0)
        }
        tvGallerySelectionInfo = tvSel
        actionsBar.addView(tvSel)

        actionsBar.addView(makeSmallButton("📲 WhatsApp", "#128C7E") {
            shareSelectedGalleryFilesToWhatsApp()
        })
        actionsBar.addView(SpaceView(dp(6), 1))

        actionsBar.addView(makeSmallButton("📡 Enviar P2P", "#059669") {
            promptSendSelectedFilesP2pFromMobile()
        })
        actionsBar.addView(SpaceView(dp(6), 1))

        actionsBar.addView(makeSmallButton("📁 Mover", "#0284C7") {
            promptMoveSelectedGalleryFiles()
        })
        actionsBar.addView(SpaceView(dp(6), 1))

        actionsBar.addView(makeSmallButton("🗑️ Excluir", "#B91C1C") {
            promptDeleteSelectedGalleryFiles()
        })

        actionsScroll.addView(actionsBar)
        root.addView(actionsScroll)

        root.addView(SpaceView(1, dp(8)))

        // Lista de Subpastas + Grade de Arquivos da Sandbox
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        val gridWrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        galleryGridContainer = gridWrap
        scroll.addView(gridWrap)
        root.addView(scroll)

        populateGalleryGrid()
        return root
    }

    private fun populateGalleryGrid() {
        val container = galleryGridContainer ?: return
        container.removeAllViews()
        tvGallerySelectionInfo?.text = "${selectedGalleryPaths.size} sel."

        val currentDir = SettingsManager.resolveSafeSandboxFile(currentGallerySubfolder)
            ?: SettingsManager.getSandboxRootDir()
        if (!currentDir.exists()) currentDir.mkdirs()

        val children = currentDir.listFiles { f ->
            !f.name.startsWith(".") && !f.name.endsWith(".part") && !f.name.endsWith(".tmp")
        } ?: emptyArray()

        // 1. Subpastas dentro do diretorio atual
        val subDirs = children.filter { it.isDirectory }.sortedBy { it.name.lowercase(Locale.getDefault()) }
        if (subDirs.isNotEmpty()) {
            val foldersScroll = HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                setPadding(0, 0, 0, dp(8))
            }
            val foldersRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            for (folder in subDirs) {
                val rel = if (currentGallerySubfolder.isEmpty()) folder.name else "$currentGallerySubfolder/${folder.name}"
                val count = SettingsManager.getFolderMediaCount(this, rel)
                val chip = TextView(this).apply {
                    text = "📁 ${folder.name} ($count)"
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setTypeface(typeface, Typeface.BOLD)
                    background = cardDrawable("#1E293B", "#38BDF8", 10, 1)
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { marginEnd = dp(8) }
                    setOnClickListener {
                        currentGallerySubfolder = rel
                        selectedGalleryPaths.clear()
                        renderActiveTab()
                    }
                }
                foldersRow.addView(chip)
            }
            foldersScroll.addView(foldersRow)
            container.addView(foldersScroll)
        }

        // 2. Arquivos de midia filtrados
        val q = gallerySearchQuery.trim().lowercase(Locale.getDefault())
        val files = children.filter { f ->
            f.isFile && SettingsManager.isMediaFile(f.name) && (q.isEmpty() || f.name.lowercase(Locale.getDefault()).contains(q))
        }.sortedByDescending { it.lastModified() }

        if (files.isEmpty()) {
            val emptyTv = TextView(this).apply {
                text = "Nenhum arquivo nesta pasta da Sandbox.\nCapture fotos ou envie arquivos pelo Site Web."
                setTextColor(Color.parseColor("#94A3B8"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                gravity = Gravity.CENTER
                setPadding(dp(20), dp(40), dp(20), dp(40))
            }
            container.addView(emptyTv)
            return
        }

        val grid = GridLayout(this).apply {
            columnCount = 2
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val screenWidth = resources.displayMetrics.widthPixels
        val cardWidth = (screenWidth - dp(36)) / 2

        for (file in files) {
            val isSelected = selectedGalleryPaths.contains(file.absolutePath)
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = cardDrawable(
                    "#1E293B",
                    if (isSelected) "#38BDF8" else "#334155",
                    12,
                    if (isSelected) 2 else 1
                )
                setPadding(dp(6), dp(6), dp(6), dp(8))
                layoutParams = GridLayout.LayoutParams().apply {
                    width = cardWidth
                    setMargins(dp(3), dp(3), dp(3), dp(6))
                }
            }

            val thumbFrame = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(125)
                )
                setBackgroundColor(Color.parseColor("#090D16"))
                setOnClickListener {
                    showFullScreenMediaDialog(file)
                }
            }

            val imgView = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
            thumbFrame.addView(imgView)
            loadThumbnailAsync(file, imgView)

            val cb = CheckBox(this).apply {
                isChecked = isSelected
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.START
                )
                setOnCheckedChangeListener { _, checked ->
                    if (checked) selectedGalleryPaths.add(file.absolutePath)
                    else selectedGalleryPaths.remove(file.absolutePath)
                    tvGallerySelectionInfo?.text = "${selectedGalleryPaths.size} sel."
                    card.background = cardDrawable(
                        "#1E293B",
                        if (checked) "#38BDF8" else "#334155",
                        12,
                        if (checked) 2 else 1
                    )
                }
            }
            thumbFrame.addView(cb)

            val ext = file.extension.lowercase(Locale.getDefault())
            val badge = TextView(this).apply {
                text = if (ext in listOf("mp4", "mov", "mkv", "3gp")) "🎬 VÍDEO" else "📷 FOTO"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
                setTypeface(typeface, Typeface.BOLD)
                background = cardDrawable("#CC0F172A", "#334155", 6, 0)
                setPadding(dp(5), dp(2), dp(5), dp(2))
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.END
                ).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) }
            }
            thumbFrame.addView(badge)

            val tvName = TextView(this).apply {
                text = file.name
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTypeface(typeface, Typeface.BOLD)
                maxLines = 1
                setPadding(dp(2), dp(6), dp(2), dp(2))
            }

            val kb = (file.length() / 1024L).coerceAtLeast(1L)
            val tvMeta = TextView(this).apply {
                text = "${kb} KB • Toque p/ ampliar"
                setTextColor(Color.parseColor("#94A3B8"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                setPadding(dp(2), 0, dp(2), 0)
            }

            card.addView(thumbFrame)
            card.addView(tvName)
            card.addView(tvMeta)
            grid.addView(card)
        }

        container.addView(grid)
    }

    private fun loadThumbnailAsync(file: File, target: ImageView) {
        val key = "${file.absolutePath}_${file.lastModified()}"
        target.tag = key
        val cached = thumbCache.get(key)
        if (cached != null) {
            target.setImageBitmap(cached)
            return
        }

        bgExecutor.execute {
            try {
                val ext = file.extension.lowercase(Locale.getDefault())
                val bmp: Bitmap? = if (ext in listOf("mp4", "mov", "mkv", "3gp")) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        ThumbnailUtils.createVideoThumbnail(file, Size(240, 240), null)
                    } else {
                        @Suppress("DEPRECATION")
                        ThumbnailUtils.createVideoThumbnail(file.absolutePath, MediaStore.Video.Thumbnails.MINI_KIND)
                    }
                } else {
                    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(file.absolutePath, opts)
                    var sample = 1
                    while (opts.outWidth / sample > 240 || opts.outHeight / sample > 240) {
                        sample *= 2
                    }
                    BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
                }

                if (bmp != null) {
                    thumbCache.put(key, bmp)
                    mainHandler.post {
                        if (target.tag == key) {
                            target.setImageBitmap(bmp)
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun showFullScreenMediaDialog(file: File) {
        val dialogLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardDrawable("#0F172A", "#38BDF8", 16, 1)
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }

        val title = TextView(this).apply {
            text = file.name
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(8))
        }
        dialogLayout.addView(title)

        val preview = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(320)
            )
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.BLACK)
        }
        loadThumbnailAsync(file, preview)
        dialogLayout.addView(preview)

        val dateStr = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(Date(file.lastModified()))
        val meta = TextView(this).apply {
            text = "Tamanho: ${file.length() / 1024} KB  •  Data: $dateStr"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(0, dp(8), 0, dp(10))
        }
        dialogLayout.addView(meta)

        val dlg = AlertDialog.Builder(this)
            .setView(dialogLayout)
            .create()

        val btnsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        btnsRow.addView(makeSmallButton("📲 WhatsApp", "#128C7E") {
            dlg.dismiss()
            SettingsManager.shareFilesToWhatsApp(this, listOf(file))
        })
        btnsRow.addView(SpaceView(dp(6), 1))

        btnsRow.addView(makeSmallButton("🔍 Abrir Externo", "#0284C7") {
            dlg.dismiss()
            openFileExternally(file)
        })
        btnsRow.addView(SpaceView(dp(6), 1))

        btnsRow.addView(makeSmallButton("Fechar", "#334155") {
            dlg.dismiss()
        })

        dialogLayout.addView(btnsRow)
        dlg.show()
    }

    private fun openFileExternally(file: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, SettingsManager.getMimeType(file))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Abrir com..."))
        } catch (e: Exception) {
            Toast.makeText(this, "Não foi possível abrir externamente.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareSelectedGalleryFilesToWhatsApp() {
        val files = selectedGalleryPaths.map { File(it) }.filter { it.exists() }
        if (files.isEmpty()) {
            Toast.makeText(this, "Selecione ao menos uma foto ou vídeo na galeria.", Toast.LENGTH_SHORT).show()
            return
        }
        SettingsManager.shareFilesToWhatsApp(this, files)
    }

    private fun promptSendSelectedFilesP2pFromMobile() {
        val files = selectedGalleryPaths.map { File(it) }.filter { it.exists() }
        if (files.isEmpty()) {
            Toast.makeText(this, "Selecione os arquivos que deseja enviar para outro celular.", Toast.LENGTH_SHORT).show()
            return
        }

        val peers = NetworkDiscoveryManager.getDiscoveredPeers(this).filter { !it.isSelf }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(10))
            setBackgroundColor(Color.parseColor("#0F172A"))
        }

        val peerLabels = if (peers.isEmpty()) {
            listOf("Nenhum outro celular detectado automaticamente (use IP abaixo)")
        } else {
            peers.map { "📱 ${it.domain} (${it.ip}:${it.port})" }
        }

        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, peerLabels)
        }
        box.addView(makeFieldLabel("Aparelhos IFGestor encontrados no Wi-Fi:"))
        box.addView(spinner)
        box.addView(SpaceView(1, dp(8)))

        val etManualIp = makeInputEdit("Ex: 192.168.1.45:8080", "")
        box.addView(makeFieldLabel("Ou informe o IP:Porta manualmente:"))
        box.addView(etManualIp)
        box.addView(SpaceView(1, dp(8)))

        val etTargetSub = makeInputEdit("Ex: Recebidos_P2P (vazio = Raiz IFgestor)", currentGallerySubfolder)
        box.addView(makeFieldLabel("Subpasta de destino no outro celular:"))
        box.addView(etTargetSub)

        AlertDialog.Builder(this)
            .setTitle("📡 Enviar ${files.size} Arquivo(s) P2P")
            .setView(box)
            .setPositiveButton("Enviar P2P") { _, _ ->
                val manual = etManualIp.text?.toString()?.trim() ?: ""
                var targetIp = ""
                var targetPort = 8080
                var targetHttps = SettingsManager.isHttpsEnabled(this)

                if (manual.isNotEmpty()) {
                    val clean = manual.removePrefix("https://").removePrefix("http://")
                    val parts = clean.split(':')
                    targetIp = parts[0]
                    targetPort = parts.getOrNull(1)?.toIntOrNull() ?: 8080
                } else if (peers.isNotEmpty()) {
                    val chosen = peers[spinner.selectedItemPosition.coerceIn(0, peers.lastIndex)]
                    targetIp = chosen.ip
                    targetPort = chosen.port
                    targetHttps = chosen.https
                }

                if (targetIp.isEmpty()) {
                    Toast.makeText(this, "Informe ou selecione o aparelho de destino.", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                isTaskDrawerExpanded = true
                NetworkDiscoveryManager.sendFilesPeerToPeerAsync(
                    context = this,
                    targetIp = targetIp,
                    targetPort = targetPort,
                    targetHttps = targetHttps,
                    targetSubfolder = etTargetSub.text?.toString() ?: "",
                    files = files
                ) { ok, msg ->
                    mainHandler.post {
                        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                        refreshHeaderAndTaskDrawer()
                    }
                }
                Toast.makeText(
                    this,
                    "Solicitação P2P enviada! Confirme a notificação 2FA no celular de destino.",
                    Toast.LENGTH_LONG
                ).show()
                refreshHeaderAndTaskDrawer()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun promptCreateSubfolderInGallery() {
        val input = makeInputEdit("Nome da nova subpasta", "")
        AlertDialog.Builder(this)
            .setTitle("➕ Nova Subpasta na Sandbox")
            .setView(input)
            .setPositiveButton("Criar") { _, _ ->
                val name = SettingsManager.sanitizeSubfolderName(input.text?.toString() ?: "")
                if (name.isNotEmpty()) {
                    val combined = if (currentGallerySubfolder.isEmpty()) name else "$currentGallerySubfolder/$name"
                    val dir = SettingsManager.resolveSafeSandboxFile(combined)
                    dir?.mkdirs()
                    IFGestorHomeWidget.requestWidgetUpdate(this)
                    FloatingOverlayManager.refresh(this)
                    renderActiveTab()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun promptMoveSelectedGalleryFiles() {
        val files = selectedGalleryPaths.map { File(it) }.filter { it.exists() }
        if (files.isEmpty()) {
            Toast.makeText(this, "Selecione os arquivos para mover.", Toast.LENGTH_SHORT).show()
            return
        }

        val input = makeInputEdit("Subpasta destino (vazio = Raiz IFgestor)", currentGallerySubfolder)
        AlertDialog.Builder(this)
            .setTitle("📁 Mover ${files.size} Arquivo(s)")
            .setView(input)
            .setPositiveButton("Mover Agora") { _, _ ->
                val destRel = SettingsManager.sanitizeSubfolderName(input.text?.toString() ?: "")
                val destDir = SettingsManager.resolveSafeSandboxFile(destRel) ?: SettingsManager.getSandboxRootDir()
                destDir.mkdirs()

                val taskId = "mob_move_${System.currentTimeMillis()}"
                selectedGalleryPaths.clear()
                isTaskDrawerExpanded = true

                bgExecutor.execute {
                    var movedCount = 0
                    for ((idx, f) in files.withIndex()) {
                        if (SettingsManager.isFileLocked(f)) continue
                        SettingsManager.tryLockFile(f)
                        try {
                            SettingsManager.upsertTask(
                                id = taskId,
                                title = "📁 Movendo (${idx + 1}/${files.size})",
                                subtitle = f.name,
                                type = "MOVE",
                                currentItems = idx + 1,
                                totalItems = files.size,
                                status = "RUNNING"
                            )
                            val targetFile = SettingsManager.resolveUniqueFile(destDir, f.name)
                            val ok = if (f.renameTo(targetFile)) {
                                true
                            } else {
                                try {
                                    f.copyTo(targetFile, overwrite = false)
                                    f.delete()
                                    true
                                } catch (_: Exception) {
                                    false
                                }
                            }
                            if (ok) {
                                movedCount++
                                MediaScannerConnection.scanFile(
                                    applicationContext,
                                    arrayOf(f.absolutePath, targetFile.absolutePath),
                                    null,
                                    null
                                )
                            }
                        } finally {
                            SettingsManager.unlockFile(f)
                        }
                    }

                    SettingsManager.upsertTask(
                        id = taskId,
                        title = "✅ Movimentação Concluída ($movedCount/${files.size})",
                        subtitle = "Destino: ${destRel.ifEmpty { "IFgestor (Raiz)" }}",
                        type = "MOVE",
                        currentItems = movedCount,
                        totalItems = files.size,
                        status = "COMPLETED"
                    )

                    mainHandler.post {
                        IFGestorHomeWidget.requestWidgetUpdate(this)
                        FloatingOverlayManager.refresh(this)
                        populateGalleryGrid()
                        refreshHeaderAndTaskDrawer()
                    }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun promptDeleteSelectedGalleryFiles() {
        val files = selectedGalleryPaths.map { File(it) }.filter { it.exists() }
        if (files.isEmpty()) {
            Toast.makeText(this, "Selecione os arquivos para excluir.", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("🗑️ Excluir ${files.size} Arquivo(s)?")
            .setMessage("Os arquivos selecionados serão removidos permanentemente da pasta Sandbox.")
            .setPositiveButton("Excluir") { _, _ ->
                val taskId = "mob_del_${System.currentTimeMillis()}"
                selectedGalleryPaths.clear()
                isTaskDrawerExpanded = true

                bgExecutor.execute {
                    var delCount = 0
                    for ((idx, f) in files.withIndex()) {
                        if (SettingsManager.isFileLocked(f)) continue
                        SettingsManager.tryLockFile(f)
                        try {
                            SettingsManager.upsertTask(
                                id = taskId,
                                title = "🗑️ Excluindo (${idx + 1}/${files.size})",
                                subtitle = f.name,
                                type = "DELETE",
                                currentItems = idx + 1,
                                totalItems = files.size,
                                status = "RUNNING"
                            )
                            if (f.delete()) {
                                delCount++
                                MediaScannerConnection.scanFile(applicationContext, arrayOf(f.absolutePath), null, null)
                            }
                        } finally {
                            SettingsManager.unlockFile(f)
                        }
                    }

                    SettingsManager.upsertTask(
                        id = taskId,
                        title = "✅ Exclusão Concluída ($delCount/${files.size})",
                        subtitle = "Arquivos removidos com segurança",
                        type = "DELETE",
                        currentItems = delCount,
                        totalItems = files.size,
                        status = "COMPLETED"
                    )

                    mainHandler.post {
                        IFGestorHomeWidget.requestWidgetUpdate(this)
                        FloatingOverlayManager.refresh(this)
                        populateGalleryGrid()
                        refreshHeaderAndTaskDrawer()
                    }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // =========================================================================
    // 4. ABA 3: SERVIDOR PC, DOMINIO mDNS, PIN PBKDF2, 2FA E AUDIT LOG
    // =========================================================================

    private fun buildTab2ServerSecurityAndAudit(): View {
        val scroll = ScrollView(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(24))
        }
        scroll.addView(col)

        // Banner Dinamico de Desafio 2FA Pendente (Notificacao ou Codigo de 6 Digitos)
        val challengeBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        tvLiveChallengeBox = challengeBox
        col.addView(challengeBox)
        refreshLive2faChallengeBanner()

        // Card 1: Servidor Web HTTPS & Dominio Local Blindado (ifgestor.local)
        val netCard = makeSectionCard("🌐 Servidor Web HTTPS & Domínio Local (mDNS)")

        val mdnsUrl = WebServerManager.getMdnsUrl(this)
        val ipUrl = WebServerManager.getServerUrl(this)

        val tvUrls = TextView(this).apply {
            text = "🔗 Domínio Amigável: $mdnsUrl\n📍 Endereço IP Direto: $ipUrl"
            setTextColor(Color.parseColor("#38BDF8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(8))
        }
        netCard.addView(tvUrls)

        val copyRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f
        }
        copyRow.addView(makePrimaryButton("📋 Copiar Domínio .local", "#0284C7", "#38BDF8") {
            copyToClipboard("URL mDNS", mdnsUrl)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginEnd = dp(4) }
        })

        copyRow.addView(makePrimaryButton("📋 Copiar IP Numérico", "#334155", "#475569") {
            copyToClipboard("URL IP", ipUrl)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginStart = dp(4) }
        })
        netCard.addView(copyRow)

        netCard.addView(SpaceView(1, dp(10)))

        val etAlias = makeInputEdit("Ex: ifgestor ou vistoria-1", SettingsManager.getDeviceAlias(this))
        netCard.addView(makeFieldLabel("Nome do Domínio Local (.local):"))
        netCard.addView(etAlias)

        netCard.addView(SpaceView(1, dp(6)))

        val etPort = makeInputEdit("Porta (padrão 8080)", SettingsManager.getServerPort(this).toString()).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        netCard.addView(makeFieldLabel("Porta do Servidor:"))
        netCard.addView(etPort)

        netCard.addView(SpaceView(1, dp(6)))

        val swHttps = Switch(this).apply {
            text = "Criptografia HTTPS / TLS Local (Certificado Autoassinado)"
            setTextColor(Color.WHITE)
            textSize = 12f
            isChecked = SettingsManager.isHttpsEnabled(this@MainActivity)
        }
        netCard.addView(swHttps)

        val swMdns = Switch(this).apply {
            text = "Anunciar Domínio .local e Descoberta Multi-Aparelhos (mDNS)"
            setTextColor(Color.WHITE)
            textSize = 12f
            isChecked = SettingsManager.isMdnsEnabled(this@MainActivity)
        }
        netCard.addView(swMdns)

        netCard.addView(SpaceView(1, dp(10)))

        netCard.addView(makePrimaryButton("💾 Aplicar Configurações de Rede", "#059669", "#10B981") {
            SettingsManager.setDeviceAlias(this, etAlias.text?.toString() ?: "ifgestor")
            val portVal = etPort.text?.toString()?.toIntOrNull() ?: 8080
            SettingsManager.setServerPort(this, portVal)
            SettingsManager.setHttpsEnabled(this, swHttps.isChecked)
            SettingsManager.setMdnsEnabled(this, swMdns.isChecked)

            if (SettingsManager.isWebServerEnabled(this)) {
                WebServerManager.stopServer(this)
                val svc = Intent(this, MediaInterceptorService::class.java).apply {
                    action = MediaInterceptorService.ACTION_START_WEB_SERVER
                }
                startServiceCompat(svc)
            }
            Toast.makeText(this, "Rede atualizada!", Toast.LENGTH_SHORT).show()
            renderActiveTab()
        })

        col.addView(netCard)

        // Card 2: Seguranca do PIN (PBKDF2WithHmacSHA256) e Sessoes
        val pinCard = makeSectionCard("🔐 Credenciais PBKDF2 & Controle de Sessão")

        if (SettingsManager.isPinChangeRequired(this)) {
            val warnPin = TextView(this).apply {
                text = "⚠️ ATENÇÃO (OWASP): Seu PIN ainda é o padrão (1234). Cadastre um novo PIN abaixo para liberar o início do Servidor Web."
                setTextColor(Color.parseColor("#FCA5A5"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTypeface(typeface, Typeface.BOLD)
                background = cardDrawable("#450A0A", "#EF4444", 8, 1)
                setPadding(dp(10), dp(8), dp(10), dp(8))
            }
            pinCard.addView(warnPin)
            pinCard.addView(SpaceView(1, dp(8)))
        }

        val etCurrentPin = makeInputEdit("PIN Atual (padrão inicial: 1234)", "").apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        val etNewPin = makeInputEdit("Novo PIN numérico (4 a 8 dígitos, diferente de 1234)", "").apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }

        pinCard.addView(makeFieldLabel("PIN Atual:"))
        pinCard.addView(etCurrentPin)
        pinCard.addView(SpaceView(1, dp(6)))
        pinCard.addView(makeFieldLabel("Novo PIN Seguro:"))
        pinCard.addView(etNewPin)
        pinCard.addView(SpaceView(1, dp(10)))

        pinCard.addView(makePrimaryButton("🔑 Atualizar PIN (Hash PBKDF2 + Salt)", "#0284C7", "#38BDF8") {
            val cur = etCurrentPin.text?.toString()?.trim() ?: ""
            val nw = etNewPin.text?.toString()?.trim() ?: ""
            if (!SettingsManager.verifyPin(this, cur)) {
                Toast.makeText(this, "PIN atual incorreto!", Toast.LENGTH_SHORT).show()
                return@makePrimaryButton
            }
            if (nw == "1234" || nw.length < 4 || nw.length > 8 || !nw.all { it.isDigit() }) {
                Toast.makeText(this, "Escolha um PIN numérico de 4 a 8 dígitos diferente de 1234.", Toast.LENGTH_LONG).show()
                return@makePrimaryButton
            }
            SettingsManager.updatePin(this, nw)
            WebServerManager.revokeAllSessions(this, "Troca de PIN pelo usuário")
            Toast.makeText(this, "✅ Novo PIN criptografado com PBKDF2 salvo com sucesso!", Toast.LENGTH_SHORT).show()
            renderActiveTab()
        })

        pinCard.addView(SpaceView(1, dp(8)))

        pinCard.addView(makePrimaryButton("🚫 Revogar Todas as Sessões Web Ativas", "#B91C1C", "#EF4444") {
            WebServerManager.revokeAllSessions(this, "Encerramento manual no aparelho")
            Toast.makeText(this, "Todas as sessões web foram desconectadas.", Toast.LENGTH_SHORT).show()
            renderActiveTab()
        })

        col.addView(pinCard)

        // Card 3: Trilha de Auditoria (Audit Log - Item 4)
        val auditCard = makeSectionCard("📋 Trilha de Auditoria (Compliance & LGPD)")

        val auditActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f
            setPadding(0, 0, 0, dp(8))
        }
        auditActions.addView(makePrimaryButton("🔄 Atualizar Logs", "#1E293B", "#38BDF8") {
            renderActiveTab()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(36), 1f).apply { marginEnd = dp(4) }
        })

        auditActions.addView(makePrimaryButton("🧹 Limpar Histórico", "#334155", "#475569") {
            SettingsManager.clearAuditLogs(this)
            renderActiveTab()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(36), 1f).apply { marginStart = dp(4) }
        })
        auditCard.addView(auditActions)

        val logs = SettingsManager.getAuditLogs(this)
        if (logs.isEmpty()) {
            val emptyLog = TextView(this).apply {
                text = "Nenhum evento registrado ainda."
                setTextColor(Color.parseColor("#94A3B8"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            }
            auditCard.addView(emptyLog)
        } else {
            for (entry in logs.take(40)) {
                val itemBox = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    background = cardDrawable("#0F172A", if (entry.success) "#334155" else "#EF4444", 8, 1)
                    setPadding(dp(10), dp(6), dp(10), dp(6))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(6) }
                }

                val icon = if (entry.success) "✅" else "❌"
                val head = TextView(this).apply {
                    text = "$icon [${entry.category}] ${entry.action} • ${entry.timestamp}"
                    setTextColor(if (entry.success) Color.parseColor("#38BDF8") else Color.parseColor("#FCA5A5"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    setTypeface(typeface, Typeface.BOLD)
                }

                val sub = TextView(this).apply {
                    text = "Origem: ${entry.ip} — ${entry.details}"
                    setTextColor(Color.parseColor("#E2E8F0"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                }

                itemBox.addView(head)
                itemBox.addView(sub)
                auditCard.addView(itemBox)
            }
        }

        col.addView(auditCard)
        return scroll
    }

    private fun refreshLive2faChallengeBanner() {
        val box = tvLiveChallengeBox ?: return
        val ch = WebServerManager.getLatestPendingChallenge()
        if (ch == null) {
            box.visibility = View.GONE
            box.removeAllViews()
            return
        }

        box.visibility = View.VISIBLE
        box.removeAllViews()

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardDrawable("#064E3B", "#10B981", 14, 2)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        }

        val title = TextView(this).apply {
            text = "🔐 SOLICITAÇÃO 2FA AGUARDANDO APROVAÇÃO"
            setTextColor(Color.parseColor("#A7F3D0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(typeface, Typeface.BOLD)
        }

        val codeTv = TextView(this).apply {
            text = "Código de 6 dígitos: ${ch.code}"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(4), 0, dp(4))
        }

        val descTv = TextView(this).apply {
            text = "${ch.description}\nOrigem: ${ch.ip}"
            setTextColor(Color.parseColor("#E2E8F0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, 0, 0, dp(10))
        }

        val btns = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f
        }

        btns.addView(makePrimaryButton("✓ Aprovar Agora", "#059669", "#10B981") {
            WebServerManager.approveLatestChallengeFromApp(this)
            refreshLive2faChallengeBanner()
            renderActiveTab()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(4) }
        })

        btns.addView(makePrimaryButton("✕ Recusar", "#B91C1C", "#EF4444") {
            WebServerManager.rejectLatestChallengeFromApp(this)
            refreshLive2faChallengeBanner()
            renderActiveTab()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginStart = dp(4) }
        })

        card.addView(title)
        card.addView(codeTv)
        card.addView(descTv)
        card.addView(btns)
        box.addView(card)
    }

    // =========================================================================
    // 5. ACIONAMENTO DOS MOTORES E PERMISSOES DE SISTEMA
    // =========================================================================

    private fun startCameraWithInterceptor() {
        SettingsManager.setCameraInterceptorEnabled(this, true)
        val svc = Intent(this, MediaInterceptorService::class.java).apply {
            action = MediaInterceptorService.ACTION_START_CAMERA_INTERCEPTOR
        }
        startServiceCompat(svc)
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
                startActivity(Intent(MediaStore.ACTION_IMAGE_CAPTURE))
            } catch (_: Exception) {
                Toast.makeText(this, "Interceptador ativado!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun toggleWebServerEngineWithPinCheck() {
        val nextState = !SettingsManager.isWebServerEnabled(this)
        if (nextState && SettingsManager.isPinChangeRequired(this)) {
            Toast.makeText(
                this,
                "Por segurança, cadastre um novo PIN (diferente de 1234) na aba Servidor & 2FA antes de ligar o site web.",
                Toast.LENGTH_LONG
            ).show()
            switchTab(2)
            return
        }

        SettingsManager.setWebServerEnabled(this, nextState)
        val svc = Intent(this, MediaInterceptorService::class.java).apply {
            action = if (nextState) {
                MediaInterceptorService.ACTION_START_WEB_SERVER
            } else {
                MediaInterceptorService.ACTION_STOP_WEB_SERVER
            }
        }
        startServiceCompat(svc)
        renderActiveTab()
    }

    private fun checkAndRequestInitialPermissions(forcePrompt: Boolean = false) {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.READ_MEDIA_IMAGES)
            }
            if (checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.READ_MEDIA_VIDEO)
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q &&
                checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
            ) {
                needed.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }

        if (needed.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(needed.toTypedArray(), 501)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            if (forcePrompt) {
                try {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                } catch (_: Exception) {
                    startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
            }
        } else if (forcePrompt && !Settings.canDrawOverlays(this)) {
            requestOverlayPermission()
        } else if (forcePrompt) {
            Toast.makeText(this, "✅ Todas as permissões principais estão ativas!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            try {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            } catch (_: Exception) {
            }
        }
    }

    private fun startServiceCompat(intent: Intent) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (_: Exception) {
            try {
                startService(intent)
            } catch (_: Exception) {
            }
        }
    }

    private fun copyToClipboard(label: String, value: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, value))
        Toast.makeText(this, "Copiado: $value", Toast.LENGTH_SHORT).show()
    }

    // =========================================================================
    // 6. COMPONENTES VISUAIS REUTILIZAVEIS (ALTO CONTRASTE EM CAMPO)
    // =========================================================================

    private fun makeSectionCard(titleText: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardDrawable("#1E293B", "#334155", 14, 1)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }

            val header = TextView(this@MainActivity).apply {
                text = titleText
                setTextColor(Color.parseColor("#F8FAFC"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, 0, 0, dp(10))
            }
            addView(header)
        }
    }

    private fun makeFieldLabel(textStr: String): TextView {
        return TextView(this).apply {
            text = textStr
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(0, 0, 0, dp(4))
        }
    }

    private fun makeInputEdit(hint: String, initial: String): EditText {
        return EditText(this).apply {
            setText(initial)
            this.hint = hint
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            background = cardDrawable("#0F172A", "#475569", 10, 1)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
    }

    private fun makePrimaryButton(
        label: String,
        bgHex: String,
        strokeHex: String,
        onClick: () -> Unit
    ): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(typeface, Typeface.BOLD)
            background = cardDrawable(bgHex, strokeHex, 10, 1)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setOnClickListener { onClick() }
        }
    }

    private fun makeSmallButton(label: String, bgHex: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTypeface(typeface, Typeface.BOLD)
            background = cardDrawable(bgHex, "#334155", 8, 1)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(34)
            )
            setOnClickListener { onClick() }
        }
    }

    private fun cardDrawable(
        fillHex: String,
        strokeHex: String,
        radiusDp: Int,
        strokeWidthDp: Int
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radiusDp).toFloat()
            setColor(Color.parseColor(fillHex))
            if (strokeWidthDp > 0) {
                setStroke(dp(strokeWidthDp), Color.parseColor(strokeHex))
            }
        }
    }

    private fun SpaceView(wPx: Int, hPx: Int): View {
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(wPx, hPx)
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
