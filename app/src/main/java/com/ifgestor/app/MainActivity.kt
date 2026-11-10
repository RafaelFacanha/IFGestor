package com.ifgestor.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SettingsManager.syncStateFromPrefs(this)
        setContent {
            val themeMode by SettingsManager.themeMode.collectAsState()
            val isDark = when (themeMode) {
                1 -> false // Claro
                2 -> true  // Escuro
                else -> isSystemInDarkTheme()
            }

            MaterialTheme(
                colorScheme = if (isDark) darkColorScheme(
                    primary = Color(0xFF38BDF8),
                    onPrimary = Color(0xFF0F172A),
                    primaryContainer = Color(0xFF0284C7),
                    secondaryContainer = Color(0xFF1E293B),
                    surface = Color(0xFF151F32),
                    background = Color(0xFF0B1120),
                    onSurface = Color(0xFFF8FAFC)
                ) else lightColorScheme(
                    primary = Color(0xFF0F2942),
                    onPrimary = Color(0xFFFFFFFF),
                    primaryContainer = Color(0xFF0284C7),
                    secondaryContainer = Color(0xFFE2E8F0),
                    surface = Color(0xFFFFFFFF),
                    background = Color(0xFFF1F5F9),
                    onSurface = Color(0xFF0F172A)
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    IFGestorMainApp()
                }
            }
        }
    }
}

@Composable
fun isSystemInDarkTheme(): Boolean {
    val context = LocalContext.current
    val uiMode = context.resources.configuration.uiMode
    return (uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IFGestorMainApp() {
    var selectedTab by remember { mutableStateOf(0) }
    val context = LocalContext.current
    val themeMode by SettingsManager.themeMode.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("IFGestor", fontWeight = FontWeight.Bold)
                        Text(
                            when (selectedTab) {
                                0 -> "Dashboard & Câmera"
                                1 -> "Gestor de Fotos e Pastas"
                                else -> "Segurança & Servidor Web"
                            },
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                },
                actions = {
                    IconButton(onClick = {
                        val next = (themeMode + 1) % 3
                        SettingsManager.setThemeMode(context, next)
                        Toast.makeText(context, when(next) { 1 -> "Tema Claro" 2 -> "Tema Escuro" else -> "Tema Automático" }, Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(
                            imageVector = when(themeMode) { 1 -> Icons.Default.ThumbUp else -> Icons.Default.Refresh },
                            contentDescription = "Mudar Tema"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.Home, contentDescription = null) },
                    label = { Text("Dashboard") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.Folder, contentDescription = null) },
                    label = { Text("Gestor & Pastas") }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Default.Share, contentDescription = null) },
                    label = { Text("Acesso PC") }
                )
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when (selectedTab) {
                0 -> DashboardTabScreen(onOpenFolderPicker = { selectedTab = 1 })
                1 -> MediaGalleryExplorerTabScreen()
                else -> WebServerAndSecurityTabScreen()
            }
        }
    }
}

@Composable
fun DashboardTabScreen(onOpenFolderPicker: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val activePrefix by SettingsManager.activePrefix.collectAsState()
    val quickPresets by SettingsManager.quickPresets.collectAsState()
    val activeFolderPath by SettingsManager.activeFolderPath.collectAsState()
    val isServiceRunning by SettingsManager.isServiceRunning.collectAsState()
    val isWebServerRunning by SettingsManager.isWebServerRunning.collectAsState()
    val interceptedCount by SettingsManager.interceptedCount.collectAsState()
    val lastFile by SettingsManager.lastInterceptedFile.collectAsState()

    var prefixInput by remember(activePrefix) { mutableStateOf(activePrefix) }
    var overlayEnabled by remember { mutableStateOf(SettingsManager.isOverlayEnabled(context)) }
    var hasAllFilesPermission by remember { mutableStateOf(checkStoragePermission()) }
    var hasOverlayPermission by remember { mutableStateOf(checkOverlayPermission(context)) }
    var deviceIp by remember { mutableStateOf(WebServerManager.getDeviceIpAddress(context)) }
    val storageStats = remember { SettingsManager.getStorageStats() }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasAllFilesPermission = checkStoragePermission()
                hasOverlayPermission = checkOverlayPermission(context)
                deviceIp = WebServerManager.getDeviceIpAddress(context)
                SettingsManager.syncStateFromPrefs(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        if (!hasAllFilesPermission) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Permissão de Arquivos Necessária", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
                    Text("Autorize o acesso completo para mover fotos e gerenciar pastas.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                    Button(onClick = { requestAllFilesPermission(context) }) { Text("LIBERAR ARQUIVOS") }
                }
            }
        }

        if (!hasOverlayPermission) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Permissão do Widget Flutuante", fontWeight = FontWeight.Bold)
                    Text("Autorize sobrepor a outros apps para usar o botão rápido na câmera.", style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { requestOverlayPermission(context) }) { Text("LIBERAR WIDGET") }
                }
            }
        }

        // Card de Status Corporativo
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (isServiceRunning) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(text = if (isServiceRunning) "🟢 SERVIÇO ATIVO" else "⚪ SERVIÇO PARADO", fontWeight = FontWeight.Bold)
                    Text(text = "Sessão: $interceptedCount fotos", style = MaterialTheme.typography.labelMedium)
                }

                // Barra de Armazenamento
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Armazenamento do Aparelho", style = MaterialTheme.typography.labelSmall)
                        Text("${storageStats.usedPercent}%", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    }
                    LinearProgressIndicator(
                        progress = { storageStats.usedPercent / 100f },
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(4.dp)),
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(storageStats.formattedSummary, style = MaterialTheme.typography.labelSmall)
                }

                Text("Último arquivo: $lastFile", style = MaterialTheme.typography.bodySmall)
                if (isWebServerRunning) {
                    Text("Servidor Web: http://$deviceIp:${SettingsManager.getServerPort(context)}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                }
            }
        }

        // Card de Configuracao com Chips de Prefixos Rapidos
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Etiquetas e Prefixos de Captura", fontWeight = FontWeight.Bold)

                // Chips horizontais de prefixo
                Text("Prefixos Rápidos (1 toque):", style = MaterialTheme.typography.labelSmall)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    for (preset in quickPresets.take(5)) {
                        FilterChip(
                            selected = prefixInput == preset,
                            onClick = {
                                prefixInput = preset
                                SettingsManager.setPrefix(context, preset)
                                notifyServiceConfigChanged(context, isServiceRunning)
                            },
                            label = { Text(preset, fontSize = 11.sp) }
                        )
                    }
                }

                OutlinedTextField(
                    value = prefixInput,
                    onValueChange = {
                        prefixInput = it
                        SettingsManager.setPrefix(context, it)
                        if (it.isNotBlank()) SettingsManager.addQuickPreset(context, it)
                        notifyServiceConfigChanged(context, isServiceRunning)
                    },
                    label = { Text("Prefixo Ativo (Ex: Vistoria_)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text("Destino: ${SettingsManager.formatShortPath(activeFolderPath)}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)

                OutlinedButton(
                    onClick = onOpenFolderPicker,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Folder, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("ESCOLHER / CRIAR PASTA")
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Widget Flutuante sobre a Câmera", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text("Ajuste prefixos e pastas sem sair da câmera", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(
                        checked = overlayEnabled,
                        onCheckedChange = {
                            overlayEnabled = it
                            SettingsManager.setOverlayEnabled(context, it)
                            if (isServiceRunning) startInterceptorServiceOnly(context)
                        }
                    )
                }
            }
        }

        Button(
            onClick = {
                if (!hasAllFilesPermission) { requestAllFilesPermission(context); return@Button }
                if (overlayEnabled && !hasOverlayPermission) { requestOverlayPermission(context); return@Button }
                requestNotificationPermissionIfNeeded(context, notificationPermissionLauncher)
                startInterceptorAndOpenNativeCamera(context)
            },
            modifier = Modifier.fillMaxWidth().height(56.dp)
        ) {
            Text("ATIVAR E ABRIR CÂMERA", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }

        OutlinedButton(
            onClick = {
                if (isServiceRunning) {
                    val stopIntent = Intent(context, MediaInterceptorService::class.java).apply { action = MediaInterceptorService.ACTION_STOP }
                    context.startService(stopIntent)
                } else {
                    if (!hasAllFilesPermission) { requestAllFilesPermission(context); return@OutlinedButton }
                    requestNotificationPermissionIfNeeded(context, notificationPermissionLauncher)
                    startInterceptorServiceOnly(context)
                }
            },
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) {
            Text(if (isServiceRunning) "PARAR INTERCEPTADOR EM SEGUNDO PLANO" else "ATIVAR APENAS EM SEGUNDO PLANO")
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaGalleryExplorerTabScreen() {
    val context = LocalContext.current
    val rootPath = remember { SettingsManager.getStorageRootPath() }
    val activeFolderPath by SettingsManager.activeFolderPath.collectAsState()

    var currentDir by remember {
        val initial = File(SettingsManager.getTargetFolderPath(context))
        if (!initial.exists()) initial.mkdirs()
        mutableStateOf(if (initial.exists() && initial.isDirectory) initial else File(rootPath))
    }

    var itemsList by remember { mutableStateOf<List<File>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf("all") } // all, image, video, folder

    var showNewFolderDialog by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }

    var selectedFileForAction by remember { mutableStateOf<File?>(null) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameInput by remember { mutableStateOf("") }

    var lightboxFile by remember { mutableStateOf<File?>(null) }

    fun refreshDirectory(dir: File) {
        if (!dir.exists()) dir.mkdirs()
        currentDir = dir
        val children = dir.listFiles()?.toList() ?: emptyList()
        itemsList = children
            .filter { !it.name.startsWith(".") }
            .sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
    }

    LaunchedEffect(currentDir) {
        refreshDirectory(currentDir)
    }

    val filteredList = itemsList.filter { item ->
        val matchQuery = searchQuery.isBlank() || item.name.lowercase().contains(searchQuery.lowercase())
        val matchType = when (selectedFilter) {
            "folder" -> item.isDirectory
            "image" -> !item.isDirectory && isImageFile(item.name)
            "video" -> !item.isDirectory && isVideoFile(item.name)
            else -> true
        }
        matchQuery && matchType
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Barra de Busca e Filtros Rapidos
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Pesquisar na pasta atual...") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FilterChip(selected = selectedFilter == "all", onClick = { selectedFilter = "all" }, label = { Text("Todos") })
            FilterChip(selected = selectedFilter == "image", onClick = { selectedFilter = "image" }, label = { Text("Fotos") })
            FilterChip(selected = selectedFilter == "video", onClick = { selectedFilter = "video" }, label = { Text("Vídeos") })
            FilterChip(selected = selectedFilter == "folder", onClick = { selectedFilter = "folder" }, label = { Text("Pastas") })
        }

        // Card de Cabecalho com Navegacao e Acoes
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = "📂 ${SettingsManager.formatShortPath(currentDir.absolutePath)}", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text(text = "Destino da câmera: ${SettingsManager.formatShortPath(activeFolderPath)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val canGoUp = currentDir.absolutePath != rootPath && currentDir.parentFile != null
                    OutlinedButton(onClick = { currentDir.parentFile?.let { refreshDirectory(it) } }, enabled = canGoUp, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.ArrowBack, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Acima")
                    }
                    OutlinedButton(onClick = { newFolderName = ""; showNewFolderDialog = true }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Nova Pasta")
                    }
                    IconButton(onClick = { refreshDirectory(currentDir) }) { Icon(Icons.Default.Refresh, contentDescription = null) }
                }

                Button(
                    onClick = {
                        SettingsManager.setTargetFolderPath(context, currentDir.absolutePath)
                        notifyServiceConfigChanged(context, SettingsManager.isServiceRunning.value)
                        Toast.makeText(context, "Pasta definida como destino da câmera!", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Check, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("USAR ESTA PASTA NA CÂMERA")
                }
            }
        }

        // Grade Visual de Miniaturas e Pastas
        LazyVerticalGrid(
            columns = GridCells.Adaptive(110.dp),
            modifier = Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(filteredList) { item ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(130.dp)
                        .combinedClickable(
                            onClick = {
                                if (item.isDirectory) {
                                    refreshDirectory(item)
                                } else if (isImageFile(item.name) || isVideoFile(item.name)) {
                                    lightboxFile = item
                                } else {
                                    selectedFileForAction = item
                                }
                            },
                            onLongClick = { selectedFileForAction = item }
                        ),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        if (item.isDirectory) {
                            Column(
                                modifier = Modifier.fillMaxSize().padding(8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text("📁", fontSize = 36.sp)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(item.name, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1)
                            }
                        } else if (isImageFile(item.name)) {
                            androidx.compose.foundation.Image(
                                painter = androidx.compose.ui.res.painterResource(id = android.R.drawable.ic_menu_gallery),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize()
                            )
                            // Exibe o nome no rodape do cartao
                            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)), contentAlignment = Alignment.BottomStart) {
                                Text(item.name, color = Color.White, fontSize = 10.sp, modifier = Modifier.padding(6.dp), maxLines = 1)
                            }
                        } else {
                            Column(
                                modifier = Modifier.fillMaxSize().padding(8.dp).background(Color.DarkGray),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text("🎬", fontSize = 32.sp)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(item.name, color = Color.White, fontSize = 11.sp, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
    }

    // Modal de Nova Pasta
    if (showNewFolderDialog) {
        AlertDialog(
            onDismissRequest = { showNewFolderDialog = false },
            title = { Text("Criar Nova Pasta") },
            text = {
                OutlinedTextField(value = newFolderName, onValueChange = { newFolderName = it }, label = { Text("Nome da pasta") }, singleLine = true)
            },
            confirmButton = {
                Button(onClick = {
                    val clean = newFolderName.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
                    if (clean.isNotEmpty()) {
                        val newDir = File(currentDir, clean)
                        if (newDir.mkdirs() || newDir.exists()) refreshDirectory(newDir)
                    }
                    showNewFolderDialog = false
                }) { Text("Criar") }
            },
            dismissButton = { TextButton(onClick = { showNewFolderDialog = false }) { Text("Cancelar") } }
        )
    }

    // Modal de Acoes do Arquivo / Pasta
    selectedFileForAction?.let { item ->
        AlertDialog(
            onDismissRequest = { selectedFileForAction = null },
            title = { Text(item.name) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (item.isDirectory) {
                        Button(onClick = {
                            SettingsManager.setTargetFolderPath(context, item.absolutePath)
                            notifyServiceConfigChanged(context, SettingsManager.isServiceRunning.value)
                            selectedFileForAction = null
                            Toast.makeText(context, "Definida como pasta da câmera!", Toast.LENGTH_SHORT).show()
                        }, modifier = Modifier.fillMaxWidth()) { Text("USAR COMO DESTINO DA CÂMERA") }
                    }
                    OutlinedButton(onClick = { renameInput = item.name; showRenameDialog = true }, modifier = Modifier.fillMaxWidth()) { Text("RENOMEAR") }
                    Button(onClick = {
                        item.deleteRecursively()
                        selectedFileForAction = null
                        refreshDirectory(currentDir)
                        Toast.makeText(context, "Excluído com sucesso!", Toast.LENGTH_SHORT).show()
                    }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error), modifier = Modifier.fillMaxWidth()) { Text("EXCLUIR") }
                }
            },
            confirmButton = { TextButton(onClick = { selectedFileForAction = null }) { Text("Fechar") } }
        )
    }

    // Modal de Renomear
    if (showRenameDialog && selectedFileForAction != null) {
        val target = selectedFileForAction!!
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Renomear") },
            text = { OutlinedTextField(value = renameInput, onValueChange = { renameInput = it }, singleLine = true) },
            confirmButton = {
                Button(onClick = {
                    val clean = renameInput.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
                    if (clean.isNotEmpty()) {
                        val newFile = File(target.parentFile, clean)
                        if (target.renameTo(newFile)) refreshDirectory(currentDir)
                    }
                    showRenameDialog = false
                    selectedFileForAction = null
                }) { Text("Salvar") }
            },
            dismissButton = { TextButton(onClick = { showRenameDialog = false }) { Text("Cancelar") } }
        )
    }

    // Lightbox Mobile Tela Cheia
    lightboxFile?.let { file ->
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black).clickable { lightboxFile = null },
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(file.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Button(onClick = { lightboxFile = null }, colors = ButtonDefaults.buttonColors(containerColor = Color.Red)) { Text("Fechar") }
                }
                Text("🖼️ Visualização em Tela Cheia", color = Color.LightGray, fontSize = 16.sp)
                Button(onClick = {
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(Uri.fromFile(file), if (isVideoFile(file.name)) "video/*" else "image/*")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(intent)
                }) { Text("ABRIR NO REPRODUTOR EXTERNO") }
            }
        }
    }
}

@Composable
fun WebServerAndSecurityTabScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val isServiceRunning by SettingsManager.isServiceRunning.collectAsState()
    val isWebServerRunning by SettingsManager.isWebServerRunning.collectAsState()
    val active2faCode by SettingsManager.current2faCode.collectAsState()
    val active2faIp by SettingsManager.current2faClientIp.collectAsState()
    val activeSessionsCount by SettingsManager.activeSessionsCount.collectAsState()

    var authEnabled by remember { mutableStateOf(SettingsManager.isWebAuthEnabled(context)) }
    var twoFactorEnabled by remember { mutableStateOf(SettingsManager.isTwoFactorEnabled(context)) }
    var newPinInput by remember { mutableStateOf("") }
    var hasCustomPin by remember { mutableStateOf(SettingsManager.hasCustomPin(context)) }

    var ipAddress by remember { mutableStateOf(WebServerManager.getDeviceIpAddress(context)) }
    val port = remember { SettingsManager.getServerPort(context) }
    var isIgnoringBattery by remember { mutableStateOf(checkBatteryOptimizationIgnored(context)) }

    val fullUrl = "http://$ipAddress:$port"

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                ipAddress = WebServerManager.getDeviceIpAddress(context)
                isIgnoringBattery = checkBatteryOptimizationIgnored(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (active2faCode != null) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("📲 CÓDIGO 2FA SOLICITADO NO PC", fontWeight = FontWeight.Bold)
                    Text(text = active2faCode ?: "", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary)
                    Text("Computador ($active2faIp) aguardando este código.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = if (isWebServerRunning) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(text = if (isWebServerRunning) "🟢 SERVIDOR WEB ONLINE" else "⚪ SERVIDOR WEB PARADO", fontWeight = FontWeight.Bold)
                Text(text = "Acesse no navegador do computador:", style = MaterialTheme.typography.bodySmall)

                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.fillMaxWidth()) {
                    Box(modifier = Modifier.fillMaxWidth().padding(14.dp), contentAlignment = Alignment.Center) {
                        Text(text = fullUrl, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("URL", fullUrl))
                        Toast.makeText(context, "Copiado!", Toast.LENGTH_SHORT).show()
                    }, modifier = Modifier.weight(1f)) { Text("Copiar Link") }

                    OutlinedButton(onClick = { ipAddress = WebServerManager.getDeviceIpAddress(context) }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Atualizar IP")
                    }
                }

                Button(
                    onClick = {
                        if (isServiceRunning && isWebServerRunning) {
                            val stopIntent = Intent(context, MediaInterceptorService::class.java).apply { action = MediaInterceptorService.ACTION_STOP }
                            context.startService(stopIntent)
                        } else {
                            SettingsManager.setWebServerEnabled(context, true)
                            startInterceptorServiceOnly(context)
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) {
                    Text(if (isWebServerRunning) "DESLIGAR SERVIDOR WEB" else "LIGAR SERVIDOR WEB", fontWeight = FontWeight.Bold)
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Lock, contentDescription = null)
                    Text("Segurança (PIN + 2FA)", fontWeight = FontWeight.SemiBold)
                }

                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Exigir PIN no PC", fontWeight = FontWeight.Medium)
                        Text(if (hasCustomPin) "PIN customizado ativo" else "PIN padrão: 1234", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = authEnabled, onCheckedChange = { authEnabled = it; SettingsManager.setWebAuthEnabled(context, it) })
                }

                if (authEnabled) {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Verificação em 2 Etapas (2FA)", fontWeight = FontWeight.Medium)
                            Text("Gera código de 6 dígitos no celular ao logar no PC", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = twoFactorEnabled, onCheckedChange = { twoFactorEnabled = it; SettingsManager.setTwoFactorEnabled(context, it) })
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = newPinInput,
                            onValueChange = { newPinInput = it },
                            label = { Text("Novo PIN (mín. 4)") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Button(onClick = {
                            if (newPinInput.trim().length >= 4) {
                                SettingsManager.setWebPin(context, newPinInput)
                                hasCustomPin = true
                                newPinInput = ""
                                WebServerManager.disconnectAllSessions()
                                Toast.makeText(context, "PIN salvo!", Toast.LENGTH_SHORT).show()
                            }
                        }) { Text("Salvar") }
                    }
                }
            }
        }
    }
}

private fun isImageFile(name: String): Boolean {
    val low = name.lowercase()
    return low.endsWith(".jpg") || low.endsWith(".jpeg") || low.endsWith(".png") || low.endsWith(".webp")
}

private fun isVideoFile(name: String): Boolean {
    val low = name.lowercase()
    return low.endsWith(".mp4") || low.endsWith(".mov") || low.endsWith(".mkv") || low.endsWith(".3gp")
}

private fun checkStoragePermission(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager() else true
private fun checkOverlayPermission(context: Context): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(context) else true
private fun checkBatteryOptimizationIgnored(context: Context): Boolean = (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(context.packageName)

private fun requestIgnoreBatteryOptimization(context: Context) {
    try {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply { data = Uri.parse("package:${context.packageName}") }
        context.startActivity(intent)
    } catch (_: Exception) {}
}

private fun requestAllFilesPermission(context: Context) {
    try {
        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply { data = Uri.parse("package:${context.packageName}") }
        context.startActivity(intent)
    } catch (_: Exception) {}
}

private fun requestOverlayPermission(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
        context.startActivity(intent)
    }
}

private fun requestNotificationPermissionIfNeeded(context: Context, launcher: ManagedActivityResultLauncher<String, Boolean>) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

private fun notifyServiceConfigChanged(context: Context, isRunning: Boolean) {
    if (!isRunning) return
    val intent = Intent(context, MediaInterceptorService::class.java).apply { action = MediaInterceptorService.ACTION_REFRESH_CONFIG }
    context.startService(intent)
}

private fun startInterceptorServiceOnly(context: Context) {
    val serviceIntent = Intent(context, MediaInterceptorService::class.java).apply { action = MediaInterceptorService.ACTION_START }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(serviceIntent) else context.startService(serviceIntent)
}

private fun startInterceptorAndOpenNativeCamera(context: Context) {
    startInterceptorServiceOnly(context)
    try {
        val cameraIntent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        context.startActivity(cameraIntent)
    } catch (_: Exception) {}
}
