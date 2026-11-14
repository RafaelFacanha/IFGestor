package com.ifgestor.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderSpecial
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SendToMobile
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private lateinit var settings: SettingsManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = SettingsManager(applicationContext)
        applyKeepScreenOnFlag(settings.isKeepScreenOn())

        setContent {
            var isDarkTheme by remember { mutableStateOf(settings.isDarkMode()) }
            var showSplash by remember { mutableStateOf(true) }

            val colorScheme = if (isDarkTheme) {
                darkColorScheme(
                    primary = Color(0xFF3B82F6),
                    secondary = Color(0xFF10B981),
                    tertiary = Color(0xFF38BDF8),
                    background = Color(0xFF0F172A),
                    surface = Color(0xFF1E293B),
                    onBackground = Color(0xFFF8FAFC),
                    onSurface = Color(0xFFF8FAFC)
                )
            } else {
                lightColorScheme(
                    primary = Color(0xFF2563EB),
                    secondary = Color(0xFF059669),
                    tertiary = Color(0xFF0284C7),
                    background = Color(0xFFF1F5F9),
                    surface = Color(0xFFFFFFFF),
                    onBackground = Color(0xFF0F172A),
                    onSurface = Color(0xFF0F172A)
                )
            }

            LaunchedEffect(Unit) {
                delay(900L)
                showSplash = false
            }

            MaterialTheme(colorScheme = colorScheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    if (showSplash) {
                        AnimatedSplashScreen()
                    } else {
                        IFGestorMainApp(
                            isDarkTheme = isDarkTheme,
                            onToggleTheme = {
                                isDarkTheme = !isDarkTheme
                                settings.setDarkMode(isDarkTheme)
                            },
                            onToggleKeepScreenOn = { keepOn ->
                                settings.setKeepScreenOn(keepOn)
                                applyKeepScreenOnFlag(keepOn)
                            }
                        )
                    }
                }
            }
        }
    }

    private fun applyKeepScreenOnFlag(enable: Boolean) {
        if (enable) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}

@Composable
private fun AnimatedSplashScreen() {
    var startAnim by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (startAnim) 1f else 0.72f,
        animationSpec = tween(durationMillis = 650, easing = FastOutSlowInEasing),
        label = "splash_scale"
    )
    LaunchedEffect(Unit) { startAnim = true }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F172A)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(
                painter = painterResource(id = R.drawable.ic_ifgestor_logo),
                contentDescription = "IFGestor Logo",
                modifier = Modifier
                    .size(92.dp)
                    .scale(scale)
            )
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = "IFGestor Enterprise",
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Sandbox • HTTPS/TLS • mDNS • P2P 2FA",
                color = Color(0xFF38BDF8),
                fontSize = 12.5.sp
            )
        }
    }
}

@Composable
private fun IFGestorMainApp(
    isDarkTheme: Boolean,
    onToggleTheme: () -> Unit,
    onToggleKeepScreenOn: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val settings = remember { SettingsManager(context) }
    val security = remember { SecurityManager.getInstance(context) }
    val audit = remember { AuditLogManager.getInstance(context) }
    val taskManager = remember { FileLockAndTaskManager.getInstance(context) }
    val mdnsManager = remember { MdnsDiscoveryManager.getInstance(context) }
    val p2pManager = remember { P2PTransferManager.getInstance(context) }

    var selectedTab by remember { mutableIntStateOf(0) }

    val interceptorRunning by MediaInterceptorService.interceptorRunningFlow.collectAsState()
    val webServerRunning by MediaInterceptorService.webServerRunningFlow.collectAsState()
    val lastIntercepted by MediaInterceptorService.lastInterceptedFileFlow.collectAsState()
    val tasks by taskManager.tasksFlow.collectAsState()
    val peers by mdnsManager.peersFlow.collectAsState()
    val activeHostname by mdnsManager.activeHostnameFlow.collectAsState()
    val pendingIncomingP2p by p2pManager.pendingIncomingFlow.collectAsState()
    val auditLogs by audit.logsFlow.collectAsState()

    var activeSubfolder by remember { mutableStateOf(settings.getTargetSubfolder()) }
    var activePrefix by remember { mutableStateOf(settings.getFilePrefix()) }
    var subfoldersList by remember { mutableStateOf(settings.listSandboxSubfolders()) }
    var sessionCount by remember { mutableIntStateOf(settings.getSessionPhotoCount()) }
    var folderFileCount by remember { mutableIntStateOf(settings.getActiveFolderFileCount()) }
    var mustChangeDefaultPin by remember { mutableStateOf(security.isMustChangeDefaultPin()) }

    var showChangePinDialog by remember { mutableStateOf(false) }
    var incomingP2pCodeInput by remember { mutableStateOf("") }

    // Permissão de notificações e armazenamento básico
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> }

    LaunchedEffect(Unit) {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
            perms.add(Manifest.permission.READ_MEDIA_IMAGES)
            perms.add(Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            perms.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    // Atualiza contadores quando uma nova foto é interceptada ou ao alternar abas
    LaunchedEffect(lastIntercepted, selectedTab, activeSubfolder) {
        activeSubfolder = settings.getTargetSubfolder()
        activePrefix = settings.getFilePrefix()
        subfoldersList = settings.listSandboxSubfolders()
        sessionCount = settings.getSessionPhotoCount()
        folderFileCount = settings.getActiveFolderFileCount()
        mustChangeDefaultPin = security.isMustChangeDefaultPin()
    }

    Scaffold(
        topBar = {
            Surface(
                tonalElevation = 4.dp,
                color = MaterialTheme.colorScheme.surface
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painter = painterResource(id = R.drawable.ic_ifgestor_logo),
                            contentDescription = "Logo",
                            modifier = Modifier.size(30.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "IFGestor v2.0",
                                fontWeight = FontWeight.Bold,
                                fontSize = 17.sp
                            )
                            Text(
                                text = "DCIM/Camera/IFgestor/$activeSubfolder",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { showChangePinDialog = true }) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = "Segurança PIN",
                                tint = if (mustChangeDefaultPin) Color(0xFFF59E0B)
                                else MaterialTheme.colorScheme.secondary
                            )
                        }
                        IconButton(onClick = onToggleTheme) {
                            Icon(
                                imageVector = if (isDarkTheme) Icons.Default.LightMode else Icons.Default.DarkMode,
                                contentDescription = "Alternar Tema"
                            )
                        }
                    }
                }
            }
        },
        bottomBar = {
            Column {
                // Central de Tarefas Paralelas e Recolhível no Mobile (Item 10)
                CollapsibleMobileTaskCenter(
                    tasks = tasks,
                    onClearCompleted = { taskManager.clearFinishedTasks() }
                )

                NavigationBar {
                    NavigationBarItem(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        icon = { Icon(Icons.Default.Tune, contentDescription = "Painel") },
                        label = { Text("Motores & Captura") }
                    )
                    NavigationBarItem(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        icon = { Icon(Icons.Default.PhotoLibrary, contentDescription = "Galeria") },
                        label = { Text("Galeria Sandbox") }
                    )
                    NavigationBarItem(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        icon = { Icon(Icons.Default.Security, contentDescription = "Servidor e Auditoria") },
                        label = { Text("Servidor PC & Log") }
                    )
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Alerta de Recebimento P2P aguardando código 2FA neste celular (Item 8)
            val firstIncoming = pendingIncomingP2p.firstOrNull { !it.receiver2faVerified }
            if (firstIncoming != null) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF78350F))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "📲 Recebimento P2P de ${firstIncoming.senderNickname} (${firstIncoming.fileCount} arq.)",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.5.sp
                        )
                        Text(
                            text = "Digite o código 2FA exibido na notificação deste aparelho para autorizar:",
                            color = Color(0xFFFDE68A),
                            fontSize = 11.5.sp
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = incomingP2pCodeInput,
                                onValueChange = { incomingP2pCodeInput = it },
                                placeholder = { Text("Código 2FA (6 dígitos)") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            Button(
                                onClick = {
                                    val res = p2pManager.verifyIncoming2fa(
                                        firstIncoming.transferId,
                                        incomingP2pCodeInput
                                    )
                                    if (res.isSuccess) {
                                        incomingP2pCodeInput = ""
                                        Toast.makeText(
                                            context,
                                            "Recebimento P2P autorizado!",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    } else {
                                        Toast.makeText(
                                            context,
                                            res.exceptionOrNull()?.message ?: "Código inválido",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981))
                            ) {
                                Text("Aprovar 2FA", color = Color.White)
                            }
                        }
                    }
                }
            }

            when (selectedTab) {
                0 -> DashboardEnginesTab(
                    settings = settings,
                    interceptorRunning = interceptorRunning,
                    webServerRunning = webServerRunning,
                    activeSubfolder = activeSubfolder,
                    activePrefix = activePrefix,
                    subfoldersList = subfoldersList,
                    sessionCount = sessionCount,
                    folderFileCount = folderFileCount,
                    mustChangeDefaultPin = mustChangeDefaultPin,
                    onOpenChangePin = { showChangePinDialog = true },
                    onToggleKeepScreenOn = onToggleKeepScreenOn,
                    onStateChanged = {
                        activeSubfolder = settings.getTargetSubfolder()
                        activePrefix = settings.getFilePrefix()
                        subfoldersList = settings.listSandboxSubfolders()
                        sessionCount = settings.getSessionPhotoCount()
                        folderFileCount = settings.getActiveFolderFileCount()
                        MediaInterceptorService.sendAction(
                            context,
                            MediaInterceptorService.ACTION_REFRESH_UI
                        )
                    }
                )

                1 -> SandboxGalleryTab(
                    settings = settings,
                    taskManager = taskManager,
                    p2pManager = p2pManager,
                    peers = peers,
                    initialSubfolder = activeSubfolder
                )

                2 -> ServerAndAuditTab(
                    settings = settings,
                    security = security,
                    mdnsManager = mdnsManager,
                    webServerRunning = webServerRunning,
                    activeHostname = activeHostname,
                    peers = peers,
                    auditLogs = auditLogs,
                    mustChangeDefaultPin = mustChangeDefaultPin,
                    onOpenChangePin = { showChangePinDialog = true },
                    onClearAuditLogs = { audit.clearLogs("Admin Mobile") }
                )
            }
        }
    }

    if (showChangePinDialog) {
        ChangePinSecurityDialog(
            security = security,
            mustChangeDefault = mustChangeDefaultPin,
            onDismiss = { showChangePinDialog = false },
            onPinUpdated = {
                mustChangeDefaultPin = security.isMustChangeDefaultPin()
                showChangePinDialog = false
                Toast.makeText(
                    context,
                    "PIN PBKDF2 atualizado com sucesso!",
                    Toast.LENGTH_SHORT
                ).show()
            }
        )
    }
}

// =============================================================================
// ABA 1: Painel de Motores Independentes, Subpasta, Prefixo e Contador Duplo
// =============================================================================

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DashboardEnginesTab(
    settings: SettingsManager,
    interceptorRunning: Boolean,
    webServerRunning: Boolean,
    activeSubfolder: String,
    activePrefix: String,
    subfoldersList: List<String>,
    sessionCount: Int,
    folderFileCount: Int,
    mustChangeDefaultPin: Boolean,
    onOpenChangePin: () -> Unit,
    onToggleKeepScreenOn: (Boolean) -> Unit,
    onStateChanged: () -> Unit
) {
    val context = LocalContext.current
    var prefixDraft by remember(activePrefix) { mutableStateOf(activePrefix) }
    var newFolderDraft by remember { mutableStateOf("") }
    var floatingEnabled by remember { mutableStateOf(settings.isFloatingWidgetEnabled()) }
    var keepScreenOn by remember { mutableStateOf(settings.isKeepScreenOn()) }

    val hasAllFilesPermission = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else true
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Alerta de Troca Obrigatória do PIN Padrão 1234 (Item 1)
        if (mustChangeDefaultPin) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF7C2D12)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "⚠️ PIN Padrão (1234) Ativo",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Text(
                            text = "Por compliance (Item 1), substitua o PIN 1234 para liberar acesso completo ao Servidor Web.",
                            color = Color(0xFFFED7AA),
                            fontSize = 11.5.sp
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = onOpenChangePin,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF59E0B))
                    ) {
                        Text("Trocar PIN", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Permissão de Gerenciamento de Arquivos (Android 11+) e Sobreposição
        if (!hasAllFilesPermission || !FloatingOverlayManager.getInstance(context).canDrawOverlays()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Permissões Operacionais Recomendadas",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
                            OutlinedButton(
                                onClick = {
                                    try {
                                        val intent = Intent(
                                            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                            Uri.parse("package:${context.packageName}")
                                        )
                                        context.startActivity(intent)
                                    } catch (_: Exception) {
                                        context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Liberar Arquivos", fontSize = 12.sp)
                            }
                        }
                        if (!FloatingOverlayManager.getInstance(context).canDrawOverlays()) {
                            OutlinedButton(
                                onClick = {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                        val intent = Intent(
                                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                            Uri.parse("package:${context.packageName}")
                                        )
                                        context.startActivity(intent)
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Liberar Widget Flutuante", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }

        // ITEM 12: Acionamento Independente dos Dois Motores
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Motores Independentes (Item 12)",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.5.sp
                )

                // Motor 1: Interceptador de Fotos e Vídeos
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "📸 Interceptação Automática da Câmera",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp
                        )
                        Text(
                            text = if (interceptorRunning) "ATIVO • Movendo para DCIM/Camera/IFgestor/$activeSubfolder"
                            else "PAUSADO • Fotos da câmera permanecem no padrão",
                            fontSize = 11.5.sp,
                            color = if (interceptorRunning) Color(0xFF10B981) else Color(0xFF94A3B8)
                        )
                    }
                    Switch(
                        checked = interceptorRunning,
                        onCheckedChange = { enable ->
                            MediaInterceptorService.sendAction(
                                context,
                                if (enable) MediaInterceptorService.ACTION_START_INTERCEPTOR
                                else MediaInterceptorService.ACTION_STOP_INTERCEPTOR
                            )
                        }
                    )
                }

                Divider(color = Color(0xFF334155).copy(alpha = 0.5f))

                // Motor 2: Servidor Web HTTPS / mDNS / P2P
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "🌐 Servidor Web HTTPS & Rede P2P",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp
                        )
                        val mdnsUrl = MdnsDiscoveryManager.getInstance(context).getPrimaryHttpsDomainUrl()
                        Text(
                            text = if (webServerRunning) "ATIVO • $mdnsUrl"
                            else "DESLIGADO • Ligue para acessar no PC ou receber P2P",
                            fontSize = 11.5.sp,
                            color = if (webServerRunning) Color(0xFF38BDF8) else Color(0xFF94A3B8)
                        )
                    }
                    Switch(
                        checked = webServerRunning,
                        onCheckedChange = { enable ->
                            MediaInterceptorService.sendAction(
                                context,
                                if (enable) MediaInterceptorService.ACTION_START_SERVER
                                else MediaInterceptorService.ACTION_STOP_SERVER
                            )
                        }
                    )
                }

                Divider(color = Color(0xFF334155).copy(alpha = 0.5f))

                // Opções Rápidas: Widget Flutuante sobre a Câmera + Manter Tela Acesa
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Exibir Widget Flutuante (Dropdown) sobre a Câmera",
                        fontSize = 12.5.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = floatingEnabled,
                        onCheckedChange = { enabled ->
                            floatingEnabled = enabled
                            settings.setFloatingWidgetEnabled(enabled)
                            onStateChanged()
                        }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Manter tela acesa durante transferências longas",
                        fontSize = 12.5.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = keepScreenOn,
                        onCheckedChange = { enabled ->
                            keepScreenOn = enabled
                            onToggleKeepScreenOn(enabled)
                        }
                    )
                }

                // Atalho Rápido para Abrir a Câmera Nativa já com Interceptação Ligada
                Button(
                    onClick = {
                        if (!interceptorRunning) {
                            MediaInterceptorService.sendAction(
                                context,
                                MediaInterceptorService.ACTION_START_INTERCEPTOR
                            )
                        }
                        try {
                            val camIntent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(camIntent)
                        } catch (_: Exception) {
                            context.startActivity(Intent(MediaStore.ACTION_IMAGE_CAPTURE))
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669))
                ) {
                    Icon(Icons.Default.CameraAlt, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Abrir Câmera Nativa com Widget Flutuante",
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
        }

        // ITEM 13: Contador Duplo (Sessão e Pasta Atual)
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Contador da Sessão",
                        fontSize = 12.sp,
                        color = Color(0xFF94A3B8)
                    )
                    Text(
                        text = "$sessionCount foto(s)",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "Total na Pasta ($activeSubfolder)",
                        fontSize = 12.sp,
                        color = Color(0xFF94A3B8)
                    )
                    Text(
                        text = "$folderFileCount arq.",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }

                OutlinedButton(
                    onClick = {
                        settings.resetSessionPhotoCount()
                        onStateChanged()
                    }
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Zerar Sessão", fontSize = 12.sp)
                }
            }
        }

        // ITEM 3 e ITEM 13: Subpasta no Sandbox DCIM/Camera/IFgestor e Prefixo
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Destino no Sandbox & Prefixo de Captura",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
                Text(
                    text = "Caminho isolado: /storage/emulated/0/DCIM/Camera/IFgestor/$activeSubfolder",
                    fontSize = 11.5.sp,
                    color = Color(0xFF94A3B8)
                )

                // Pílulas de Subpastas Existentes
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    subfoldersList.forEach { folder ->
                        val isSelected = folder.equals(activeSubfolder, ignoreCase = true)
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(99.dp))
                                .background(
                                    if (isSelected) MaterialTheme.colorScheme.primary
                                    else Color(0xFF334155).copy(alpha = 0.4f)
                                )
                                .clickable {
                                    settings.setTargetSubfolder(folder)
                                    onStateChanged()
                                }
                                .padding(horizontal = 12.dp, vertical = 7.dp)
                        ) {
                            Text(
                                text = "📁 $folder",
                                fontSize = 12.5.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }

                // Criar Nova Subpasta
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = newFolderDraft,
                        onValueChange = { newFolderDraft = it },
                        label = { Text("Criar nova subpasta no Sandbox", fontSize = 12.sp) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = {
                            if (newFolderDraft.isNotBlank()) {
                                settings.createSubfolder(newFolderDraft)
                                settings.setTargetSubfolder(newFolderDraft)
                                newFolderDraft = ""
                                onStateChanged()
                            }
                        }
                    ) {
                        Text("+ Criar")
                    }
                }

                Divider(color = Color(0xFF334155).copy(alpha = 0.5f))

                // Configurar Prefixo
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = prefixDraft,
                        onValueChange = { prefixDraft = it },
                        label = { Text("Prefixo automático (ex: Vistoria_)", fontSize = 12.sp) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = {
                            settings.setFilePrefix(prefixDraft)
                            prefixDraft = settings.getFilePrefix()
                            onStateChanged()
                            Toast.makeText(
                                context,
                                "Prefixo salvo: $prefixDraft",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    ) {
                        Text("Aplicar")
                    }
                }
            }
        }
    }
}

// =============================================================================
// ABA 2: Galeria Isolada no Sandbox (Lote, WhatsApp, P2P Duplo 2FA, Lightbox)
// =============================================================================

@Composable
private fun SandboxGalleryTab(
    settings: SettingsManager,
    taskManager: FileLockAndTaskManager,
    p2pManager: P2PTransferManager,
    peers: List<MdnsDiscoveryManager.PeerDevice>,
    initialSubfolder: String
) {
    val context = LocalContext.current
    var currentSubfolder by remember { mutableStateOf(initialSubfolder) }
    var subfolders by remember { mutableStateOf(settings.listSandboxSubfolders()) }
    var filesInFolder by remember { mutableStateOf<List<File>>(emptyList()) }
    var selectedPaths by remember { mutableStateOf(setOf<String>()) }
    var refreshTick by remember { mutableIntStateOf(0) }

    var showMoveDialog by remember { mutableStateOf(false) }
    var showP2pDialog by remember { mutableStateOf(false) }
    var lightboxFileIndex by remember { mutableStateOf<Int?>(null) }

    fun reloadGallery() {
        subfolders = settings.listSandboxSubfolders()
        val dir = settings.resolveSafeSubfolder(currentSubfolder)
        val list = (dir.listFiles() ?: emptyArray())
            .filter { it.isFile && !it.name.startsWith(".") && !it.name.endsWith(".part") }
            .sortedByDescending { it.lastModified() }
        filesInFolder = list
        selectedPaths = selectedPaths.intersect(list.map { it.absolutePath }.toSet())
    }

    LaunchedEffect(currentSubfolder, refreshTick) {
        reloadGallery()
    }

    val selectedFiles = remember(filesInFolder, selectedPaths) {
        filesInFolder.filter { it.absolutePath in selectedPaths }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Barra Horizontal de Subpastas do Sandbox
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(subfolders) { folder ->
                val active = folder.equals(currentSubfolder, ignoreCase = true)
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(99.dp))
                        .background(
                            if (active) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surface
                        )
                        .border(1.dp, Color(0xFF475569), RoundedCornerShape(99.dp))
                        .clickable {
                            currentSubfolder = folder
                            selectedPaths = emptySet()
                        }
                        .padding(horizontal = 14.dp, vertical = 7.dp)
                ) {
                    Text(
                        text = "📁 $folder",
                        fontSize = 12.5.sp,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                        color = if (active) Color.White else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        // Barra de Ações em Lote (Selecionar Todos, WhatsApp, P2P 2FA, Mover, Excluir)
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = {
                            selectedPaths = if (selectedPaths.size == filesInFolder.size) {
                                emptySet()
                            } else {
                                filesInFolder.map { it.absolutePath }.toSet()
                            }
                        }
                    ) {
                        Text(
                            text = if (selectedPaths.size == filesInFolder.size && filesInFolder.isNotEmpty())
                                "Desmarcar (${selectedPaths.size})"
                            else "Todos (${selectedPaths.size}/${filesInFolder.size})",
                            fontSize = 12.sp
                        )
                    }
                    IconButton(onClick = { refreshTick++ }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Atualizar", modifier = Modifier.size(18.dp))
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    // ITEM 16: Compartilhar no WhatsApp via FileProvider
                    IconButton(
                        onClick = {
                            if (selectedFiles.isEmpty()) {
                                Toast.makeText(context, "Selecione ao menos 1 arquivo.", Toast.LENGTH_SHORT).show()
                            } else {
                                WhatsAppShareHelper.shareFilesToWhatsApp(
                                    context = context,
                                    files = selectedFiles,
                                    actor = "Galeria Mobile"
                                )
                            }
                        }
                    ) {
                        Icon(
                            Icons.Default.Share,
                            contentDescription = "WhatsApp",
                            tint = Color(0xFF10B981)
                        )
                    }

                    // ITEM 8: Transferir P2P entre Celulares com Duplo 2FA
                    IconButton(
                        onClick = {
                            if (selectedFiles.isEmpty()) {
                                Toast.makeText(context, "Selecione arquivos para envio P2P.", Toast.LENGTH_SHORT).show()
                            } else {
                                showP2pDialog = true
                            }
                        }
                    ) {
                        Icon(
                            Icons.Default.SendToMobile,
                            contentDescription = "Enviar P2P",
                            tint = Color(0xFFF59E0B)
                        )
                    }

                    // Mover
                    IconButton(
                        onClick = {
                            if (selectedFiles.isEmpty()) {
                                Toast.makeText(context, "Selecione arquivos para mover.", Toast.LENGTH_SHORT).show()
                            } else {
                                showMoveDialog = true
                            }
                        }
                    ) {
                        Icon(Icons.Default.DriveFileMove, contentDescription = "Mover")
                    }

                    // Excluir respeitando File Lock (Item 11)
                    IconButton(
                        onClick = {
                            if (selectedFiles.isEmpty()) {
                                Toast.makeText(context, "Selecione arquivos para excluir.", Toast.LENGTH_SHORT).show()
                            } else {
                                val (deleted, errors) = taskManager.deleteFilesSafely(
                                    selectedFiles,
                                    "Galeria Mobile"
                                )
                                Toast.makeText(
                                    context,
                                    "Excluídos: $deleted" + if (errors.isNotEmpty()) " (${errors.size} em uso)" else "",
                                    Toast.LENGTH_SHORT
                                ).show()
                                refreshTick++
                            }
                        }
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Excluir",
                            tint = Color(0xFFEF4444)
                        )
                    }
                }
            }
        }

        if (filesInFolder.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Nenhum arquivo em DCIM/Camera/IFgestor/$currentSubfolder",
                    color = Color(0xFF94A3B8),
                    fontSize = 13.5.sp
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 115.dp),
                contentPadding = PaddingValues(10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                itemsIndexed(filesInFolder, key = { _, f -> f.absolutePath }) { index, file ->
                    val isSelected = file.absolutePath in selectedPaths
                    val isLocked = taskManager.isFileLocked(file)

                    GalleryThumbnailCard(
                        file = file,
                        isSelected = isSelected,
                        isLocked = isLocked,
                        onClick = { lightboxFileIndex = index },
                        onToggleSelect = {
                            selectedPaths = if (isSelected) {
                                selectedPaths - file.absolutePath
                            } else {
                                selectedPaths + file.absolutePath
                            }
                        }
                    )
                }
            }
        }
    }

    // Modal Mover Arquivos
    if (showMoveDialog) {
        AlertDialog(
            onDismissRequest = { showMoveDialog = false },
            title = { Text("Mover ${selectedFiles.size} arquivo(s)") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Escolha a subpasta de destino no Sandbox:", fontSize = 13.sp)
                    subfolders.forEach { target ->
                        OutlinedButton(
                            onClick = {
                                val (moved, _) = taskManager.moveFilesSafely(
                                    selectedFiles,
                                    target,
                                    "Galeria Mobile"
                                )
                                Toast.makeText(
                                    context,
                                    "$moved arquivo(s) movido(s) para $target",
                                    Toast.LENGTH_SHORT
                                ).show()
                                showMoveDialog = false
                                refreshTick++
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("📁 $target")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showMoveDialog = false }) { Text("Cancelar") }
            }
        )
    }

    // Modal Transferência P2P entre Celulares com Duplo 2FA (Item 8)
    if (showP2pDialog) {
        MobileP2pTransferDialog(
            filesToSend = selectedFiles,
            defaultSubfolder = currentSubfolder,
            peers = peers.filter { !it.isSelf },
            p2pManager = p2pManager,
            onDismiss = {
                showP2pDialog = false
                selectedPaths = emptySet()
            }
        )
    }

    // Visualizador em Tela Cheia (Lightbox)
    lightboxFileIndex?.let { idx ->
        val currentFile = filesInFolder.getOrNull(idx)
        if (currentFile != null) {
            AlertDialog(
                onDismissRequest = { lightboxFileIndex = null },
                title = {
                    Text(
                        text = currentFile.name,
                        fontSize = 13.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                text = {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(280.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color.Black),
                            contentAlignment = Alignment.Center
                        ) {
                            AsyncMediaThumbnail(file = currentFile, maxDimension = 720)
                        }
                        Text(
                            text = "${FileLockAndTaskManager.formatSize(currentFile.length())} • Pasta: $currentSubfolder",
                            fontSize = 11.5.sp,
                            color = Color(0xFF94A3B8)
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            OutlinedButton(
                                onClick = {
                                    if (filesInFolder.isNotEmpty()) {
                                        lightboxFileIndex = (idx - 1 + filesInFolder.size) % filesInFolder.size
                                    }
                                }
                            ) { Text("◀ Ant.") }

                            Button(
                                onClick = {
                                    WhatsAppShareHelper.shareFilesToWhatsApp(
                                        context = context,
                                        files = listOf(currentFile),
                                        actor = "Lightbox Mobile"
                                    )
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981))
                            ) {
                                Text("WhatsApp", color = Color.White)
                            }

                            OutlinedButton(
                                onClick = {
                                    if (filesInFolder.isNotEmpty()) {
                                        lightboxFileIndex = (idx + 1) % filesInFolder.size
                                    }
                                }
                            ) { Text("Próx. ▶") }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { lightboxFileIndex = null }) { Text("Fechar") }
                }
            )
        }
    }
}

@Composable
private fun GalleryThumbnailCard(
    file: File,
    isSelected: Boolean,
    isLocked: Boolean,
    onClick: () -> Unit,
    onToggleSelect: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = if (isSelected) 2.dp else 1.dp,
                color = if (isSelected) MaterialTheme.colorScheme.primary else Color(0xFF334155),
                shape = RoundedCornerShape(10.dp)
            )
            .clickable { onClick() },
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(105.dp)
                    .background(Color.Black)
            ) {
                AsyncMediaThumbnail(file = file, maxDimension = 300)

                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onToggleSelect() },
                    modifier = Modifier.align(Alignment.TopStart)
                )

                if (isLocked) {
                    Text(
                        text = "LOCK",
                        color = Color.Black,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(4.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFFF59E0B))
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
            }

            Column(modifier = Modifier.padding(6.dp)) {
                Text(
                    text = file.name,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = FileLockAndTaskManager.formatSize(file.length()),
                    fontSize = 10.sp,
                    color = Color(0xFF94A3B8)
                )
            }
        }
    }
}

@Composable
private fun AsyncMediaThumbnail(file: File, maxDimension: Int) {
    val bitmap by produceState<Bitmap?>(initialValue = null, key1 = file.absolutePath, key2 = maxDimension) {
        value = withContext(Dispatchers.IO) {
            try {
                val ext = file.extension.lowercase(Locale.ROOT)
                val isVideo = ext in listOf("mp4", "mkv", "mov", "3gp", "webm")
                @Suppress("DEPRECATION")
                if (isVideo) {
                    ThumbnailUtils.createVideoThumbnail(
                        file.absolutePath,
                        MediaStore.Images.Thumbnails.MINI_KIND
                    )
                } else {
                    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(file.absolutePath, opts)
                    var sample = 1
                    while (opts.outWidth / sample > maxDimension || opts.outHeight / sample > maxDimension) {
                        sample *= 2
                    }
                    BitmapFactory.decodeFile(
                        file.absolutePath,
                        BitmapFactory.Options().apply { inSampleSize = sample }
                    )
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    val bmp = bitmap
    if (bmp != null) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = file.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
    } else {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Default.FolderSpecial,
                contentDescription = null,
                tint = Color(0xFF64748B)
            )
        }
    }
}

@Composable
private fun MobileP2pTransferDialog(
    filesToSend: List<File>,
    defaultSubfolder: String,
    peers: List<MdnsDiscoveryManager.PeerDevice>,
    p2pManager: P2PTransferManager,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var step by remember { mutableIntStateOf(1) }
    var manualIp by remember { mutableStateOf(peers.firstOrNull()?.ipAddress ?: "") }
    var peerName by remember { mutableStateOf(peers.firstOrNull()?.nickname ?: "Celular Destino") }
    var targetFolder by remember { mutableStateOf(defaultSubfolder) }
    var transferId by remember { mutableStateOf("") }
    var source2faCode by remember { mutableStateOf("") }
    var receiver2faCode by remember { mutableStateOf("") }
    var statusMessage by remember {
        mutableStateOf("1. Escolha o celular na mesma rede Wi-Fi para gerar o 2FA de saída.")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Transferência P2P (Duplo 2FA)", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = statusMessage, fontSize = 12.sp, color = Color(0xFF38BDF8))

                when (step) {
                    1 -> {
                        if (peers.isNotEmpty()) {
                            Text("Dispositivos descobertos via mDNS:", fontSize = 11.5.sp)
                            peers.forEach { p ->
                                OutlinedButton(
                                    onClick = {
                                        manualIp = p.ipAddress
                                        peerName = p.nickname
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("📲 ${p.nickname} (${p.hostname} / ${p.ipAddress})", fontSize = 11.5.sp)
                                }
                            }
                        }
                        OutlinedTextField(
                            value = manualIp,
                            onValueChange = { manualIp = it },
                            label = { Text("IP do Celular de Destino") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = targetFolder,
                            onValueChange = { targetFolder = it },
                            label = { Text("Subpasta no Destino") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    2 -> {
                        OutlinedTextField(
                            value = source2faCode,
                            onValueChange = { source2faCode = it },
                            label = { Text("Código 2FA deste Celular (Origem)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    3 -> {
                        OutlinedTextField(
                            value = receiver2faCode,
                            onValueChange = { receiver2faCode = it },
                            label = { Text("Código 2FA do Celular de Destino") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    when (step) {
                        1 -> {
                            val res = p2pManager.initiateOutgoingTransfer(
                                files = filesToSend,
                                peerIp = manualIp.trim(),
                                peerPort = 8080,
                                peerNickname = peerName,
                                targetSubfolder = targetFolder
                            )
                            if (res.isSuccess) {
                                transferId = res.getOrThrow().transferId
                                statusMessage = "Confira a notificação 2FA neste celular de ORIGEM:"
                                step = 2
                            } else {
                                Toast.makeText(
                                    context,
                                    res.exceptionOrNull()?.message ?: "Erro ao iniciar P2P",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }

                        2 -> {
                            p2pManager.confirmSource2faAndRequestReceiver(transferId, source2faCode) { result ->
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    if (result.isSuccess) {
                                        statusMessage = "Origem aprovada! Digite o 2FA exibido no celular de DESTINO:"
                                        step = 3
                                    } else {
                                        Toast.makeText(
                                            context,
                                            result.exceptionOrNull()?.message ?: "Código 2FA inválido",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                            }
                        }

                        3 -> {
                            p2pManager.confirmReceiver2faAndStartStreaming(transferId, receiver2faCode) { result ->
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    if (result.isSuccess) {
                                        Toast.makeText(
                                            context,
                                            "Transferência P2P iniciada! Acompanhe na Central de Tarefas.",
                                            Toast.LENGTH_LONG
                                        ).show()
                                        onDismiss()
                                    } else {
                                        Toast.makeText(
                                            context,
                                            result.exceptionOrNull()?.message ?: "Código de destino inválido",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                            }
                        }
                    }
                }
            ) {
                Text(
                    when (step) {
                        1 -> "1. Gerar 2FA Origem"
                        2 -> "2. Validar Origem"
                        else -> "3. Confirmar Destino e Enviar"
                    }
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        }
    )
}

// =============================================================================
// ABA 3: Servidor PC HTTPS/TLS, Domínio mDNS e Trilha de Auditoria (Audit Log)
// =============================================================================

@Composable
private fun ServerAndAuditTab(
    settings: SettingsManager,
    security: SecurityManager,
    mdnsManager: MdnsDiscoveryManager,
    webServerRunning: Boolean,
    activeHostname: String,
    peers: List<MdnsDiscoveryManager.PeerDevice>,
    auditLogs: List<AuditLogManager.AuditEntry>,
    mustChangeDefaultPin: Boolean,
    onOpenChangePin: () -> Unit,
    onClearAuditLogs: () -> Unit
) {
    val context = LocalContext.current
    var nicknameInput by remember { mutableStateOf(settings.getDeviceNickname()) }
    val tlsFingerprint = remember { SelfSignedCertificateGenerator.getCertificateFingerprint(context) }

    val domainUrl = "https://$activeHostname:${settings.getServerPort()}"
    val ipUrl = mdnsManager.getFallbackHttpsIpUrl()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Card de Endereços HTTPS, Certificado Autoassinado e Apelido na Rede (Itens 5, 6 e 7)
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Servidor HTTPS/TLS & Domínio mDNS",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.5.sp
                        )
                        Switch(
                            checked = webServerRunning,
                            onCheckedChange = { enable ->
                                MediaInterceptorService.sendAction(
                                    context,
                                    if (enable) MediaInterceptorService.ACTION_START_SERVER
                                    else MediaInterceptorService.ACTION_STOP_SERVER
                                )
                            }
                        )
                    }

                    // Endereço Principal mDNS (Item 6)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF0F172A))
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Domínio Local mDNS (Item 6):",
                                fontSize = 11.sp,
                                color = Color(0xFF94A3B8)
                            )
                            Text(
                                text = domainUrl,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF38BDF8),
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = "IP Direto: $ipUrl",
                                fontSize = 12.sp,
                                color = Color(0xFF10B981),
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        IconButton(
                            onClick = {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("IFGestor HTTPS", domainUrl))
                                Toast.makeText(context, "Copiado: $domainUrl", Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copiar URL", tint = Color.White)
                        }
                    }

                    // Apelido Configurável do Dispositivo na Rede (Itens 6 e 7)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = nicknameInput,
                            onValueChange = { nicknameInput = it },
                            label = { Text("Apelido na Rede (Seletor Web e P2P)", fontSize = 12.sp) },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = {
                                settings.setDeviceNickname(nicknameInput)
                                nicknameInput = settings.getDeviceNickname()
                                mdnsManager.refreshDeviceIdentity()
                                Toast.makeText(
                                    context,
                                    "Apelido atualizado na rede: $nicknameInput",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        ) {
                            Text("Salvar")
                        }
                    }

                    // Fingerprint SHA-256 do Certificado X.509 Autoassinado (Item 5)
                    Text(
                        text = "🔒 Certificado TLS Autoassinado (SHA-256):\n$tlsFingerprint",
                        fontSize = 10.5.sp,
                        color = Color(0xFF94A3B8),
                        fontFamily = FontFamily.Monospace
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = onOpenChangePin,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(if (mustChangeDefaultPin) "Trocar PIN 1234!" else "Alterar PIN PBKDF2", fontSize = 12.sp)
                        }

                        OutlinedButton(
                            onClick = {
                                security.revokeAllSessions("Revogação manual pelo administrador no celular")
                                Toast.makeText(
                                    context,
                                    "Todas as sessões Web e 2FA foram revogadas.",
                                    Toast.LENGTH_SHORT
                                ).show()
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Revogar Sessões", fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        // Dispositivos Descobertos na Mesma Rede (Item 7)
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "Dispositivos IFGestor Ativos na Rede (${peers.size})",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    if (peers.isEmpty()) {
                        Text(
                            text = "Ligue o Servidor HTTPS para anunciar e descobrir aparelhos.",
                            fontSize = 12.sp,
                            color = Color(0xFF94A3B8)
                        )
                    } else {
                        peers.forEach { p ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "📲 ${p.nickname}",
                                    fontSize = 12.5.sp,
                                    fontWeight = if (p.isSelf) FontWeight.Bold else FontWeight.Normal
                                )
                                Text(
                                    text = "${p.hostname} (${p.ipAddress}:${p.port})",
                                    fontSize = 11.5.sp,
                                    color = Color(0xFF38BDF8),
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }
            }
        }

        // ITEM 4: Trilha de Auditoria (Audit Log) Persistente e Consultável
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Trilha de Auditoria (${auditLogs.size})",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.5.sp
                    )
                }
                TextButton(onClick = onClearAuditLogs) {
                    Text("Limpar Log", fontSize = 12.sp)
                }
            }
        }

        if (auditLogs.isEmpty()) {
            item {
                Text(
                    text = "Nenhum evento registrado até o momento.",
                    fontSize = 12.5.sp,
                    color = Color(0xFF94A3B8)
                )
            }
        } else {
            items(auditLogs, key = { it.id }) { entry ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Box(
                            modifier = Modifier
                                .padding(top = 4.dp)
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(
                                    if (entry.success) Color(0xFF10B981) else Color(0xFFEF4444)
                                )
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = entry.action,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.5.sp,
                                    color = if (entry.success) MaterialTheme.colorScheme.onSurface
                                    else Color(0xFFFCA5A5)
                                )
                                Text(
                                    text = entry.formattedTime,
                                    fontSize = 10.5.sp,
                                    color = Color(0xFF94A3B8)
                                )
                            }
                            Text(
                                text = "Origem: ${entry.actor}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                            Text(
                                text = entry.details,
                                fontSize = 11.5.sp,
                                color = Color(0xFFCBD5E1)
                            )
                        }
                    }
                }
            }
        }
    }
}

// =============================================================================
// ITEM 10: Central de Tarefas Paralelas e Recolhível no Mobile
// =============================================================================

@Composable
private fun CollapsibleMobileTaskCenter(
    tasks: List<FileLockAndTaskManager.TaskInfo>,
    onClearCompleted: () -> Unit
) {
    if (tasks.isEmpty()) return

    var expanded by remember { mutableStateOf(false) }
    val activeCount = tasks.count {
        it.status == FileLockAndTaskManager.TaskStatus.RUNNING ||
            it.status == FileLockAndTaskManager.TaskStatus.WAITING_2FA
    }

    Surface(
        color = Color(0xFF0F172A),
        tonalElevation = 8.dp,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color(0xFF3B82F6), RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "⚡ Central de Tarefas (${tasks.size} total • $activeCount ativa(s))",
                    color = Color.White,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Bold
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onClearCompleted) {
                        Text("Limpar", color = Color(0xFF38BDF8), fontSize = 11.5.sp)
                    }
                    Text(
                        text = if (expanded) "▼" else "▲",
                        color = Color.White,
                        fontSize = 13.sp
                    )
                }
            }

            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 180.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    tasks.forEach { task ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF1E293B))
                                .padding(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = task.title,
                                    color = Color.White,
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = "${task.progressPercent}%",
                                    color = Color(0xFF38BDF8),
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Text(
                                text = task.subtitle,
                                color = Color(0xFF94A3B8),
                                fontSize = 10.5.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            LinearProgressIndicator(
                                progress = { (task.progressPercent / 100f).coerceIn(0f, 1f) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(5.dp)
                                    .clip(RoundedCornerShape(99.dp))
                            )
                        }
                    }
                }
            }
        }
    }
}

// =============================================================================
// ITEM 1: Modal de Troca de PIN Criptografado (PBKDF2WithHmacSHA256 + Salt)
// =============================================================================

@Composable
private fun ChangePinSecurityDialog(
    security: SecurityManager,
    mustChangeDefault: Boolean,
    onDismiss: () -> Unit,
    onPinUpdated: () -> Unit
) {
    var currentPin by remember { mutableStateOf(if (mustChangeDefault) "1234" else "") }
    var newPin by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (mustChangeDefault) "Troca Obrigatória do PIN Padrão (1234)"
                else "Alterar PIN (PBKDF2 + Salt)",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "O PIN é derivado com PBKDF2WithHmacSHA256 (15.000 iterações + Salt de 16 bytes) e nunca pode ser 1234.",
                    fontSize = 12.sp,
                    color = Color(0xFF94A3B8)
                )
                OutlinedTextField(
                    value = currentPin,
                    onValueChange = { currentPin = it },
                    label = { Text("PIN Atual") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = newPin,
                    onValueChange = { newPin = it },
                    label = { Text("Novo PIN numérico (4 a 8 dígitos)") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (errorMsg.isNotBlank()) {
                    Text(text = errorMsg, color = Color(0xFFEF4444), fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val res = security.changePin(currentPin, newPin, "Mobile Admin")
                    if (res.isSuccess) {
                        onPinUpdated()
                    } else {
                        errorMsg = res.exceptionOrNull()?.message ?: "Erro ao atualizar PIN."
                    }
                }
            ) {
                Text("Salvar Novo PIN")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        }
    )
}
