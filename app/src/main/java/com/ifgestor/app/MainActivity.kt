package com.ifgestor.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.Settings
import android.util.LruCache
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val DarkCorpColorScheme = darkColorScheme(
    primary = Color(0xFF38BDF8),
    onPrimary = Color(0xFF061523),
    primaryContainer = Color(0xFF0F2942),
    onPrimaryContainer = Color(0xFFE0F2FE),
    secondary = Color(0xFF10B981),
    onSecondary = Color(0xFF052E16),
    secondaryContainer = Color(0xFF064E3B),
    onSecondaryContainer = Color(0xFFD1FAE5),
    tertiary = Color(0xFF6366F1),
    background = Color(0xFF0B1120),
    onBackground = Color(0xFFF8FAFC),
    surface = Color(0xFF151F32),
    onSurface = Color(0xFFF8FAFC),
    surfaceVariant = Color(0xFF1E293B),
    onSurfaceVariant = Color(0xFFCBD5E1),
    error = Color(0xFFEF4444)
)

private val LightCorpColorScheme = lightColorScheme(
    primary = Color(0xFF0F2942),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF0F2942),
    onPrimaryContainer = Color.White,
    secondary = Color(0xFF059669),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD1FAE5),
    onSecondaryContainer = Color(0xFF064E3B),
    tertiary = Color(0xFF0284C7),
    background = Color(0xFFF1F5F9),
    onBackground = Color(0xFF0F172A),
    surface = Color.White,
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFE2E8F0),
    onSurfaceVariant = Color(0xFF334155),
    error = Color(0xFFDC2626)
)

object LocalThumbnailCache {
    private val cache = LruCache<String, ImageBitmap>(80)
    fun get(key: String): ImageBitmap? = cache.get(key)
    fun put(key: String, bitmap: ImageBitmap) { cache.put(key, bitmap) }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SettingsManager.syncStateFromPrefs(this)
        setContent {
            val themeMode by SettingsManager.themeMode.collectAsState()
            val systemDark = isSystemInDarkTheme()
            val useDark = when (themeMode) {
                1 -> false
                2 -> true
                else -> systemDark
            }

            var showSplash by remember { mutableStateOf(true) }
            LaunchedEffect(Unit) {
                delay(1300)
                showSplash = false
            }

            MaterialTheme(
                colorScheme = if (useDark) DarkCorpColorScheme else LightCorpColorScheme
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        IFGestorMainApp(useDark = useDark, themeMode = themeMode)
                        if (showSplash) {
                            SplashScreenOverlay()
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SplashScreenOverlay() {
    val infiniteTransition = rememberInfiniteTransition(label = "SplashSpin")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "SpinAngle"
    )

    val scaleAnim = remember { Animatable(0.75f) }
    LaunchedEffect(Unit) {
        scaleAnim.animateTo(
            targetValue = 1.0f,
            animationSpec = tween(700, easing = FastOutSlowInEasing)
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(Color(0xFF0F2942), Color(0xFF070B14)),
                    radius = 900f
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.scale(scaleAnim.value)
        ) {
            Box(
                modifier = Modifier
                    .size(110.dp)
                    .clip(RoundedCornerShape(26.dp))
                    .background(Color(0xFF0B1120))
                    .border(1.5.dp, Color(0xFF38BDF8).copy(alpha = 0.4f), RoundedCornerShape(26.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_ifgestor_logo),
                    contentDescription = null,
                    tint = Color.Unspecified,
                    modifier = Modifier
                        .size(72.dp)
                        .rotate(rotation)
                )
            }
            Spacer(modifier = Modifier.height(18.dp))
            Text(
                "IFGestor",
                color = Color.White,
                fontSize = 28.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 0.5.sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "Gerenciador Corporativo de Arquivos",
                color = Color(0xFF94A3B8),
                fontSize = 13.sp
            )
            Spacer(modifier = Modifier.height(28.dp))
            LinearProgressIndicator(
                modifier = Modifier
                    .width(180.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = Color(0xFF38BDF8),
                trackColor = Color(0xFF1E293B)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IFGestorMainApp(useDark: Boolean, themeMode: Int) {
    val context = LocalContext.current
    var selectedTab by remember { mutableStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFF0284C7)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_ifgestor_logo),
                                contentDescription = null,
                                tint = Color.Unspecified,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Column {
                            Text("IFGestor", fontWeight = FontWeight.ExtraBold, fontSize = 19.sp)
                            Text(
                                when (selectedTab) {
                                    0 -> "Interceptador & Captura Rápida"
                                    1 -> "Gestor Puro de Arquivos"
                                    else -> "Servidor Web & Segurança 2FA"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                            )
                        }
                    }
                },
                actions = {
                    FilledTonalButton(
                        onClick = {
                            val next = (themeMode + 1) % 3
                            SettingsManager.setThemeMode(context, next)
                        },
                        shape = RoundedCornerShape(20.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Icon(
                            imageVector = when (themeMode) {
                                1 -> Icons.Default.LightMode
                                2 -> Icons.Default.DarkMode
                                else -> Icons.Default.BrightnessAuto
                            },
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            when (themeMode) { 1 -> "Claro" ; 2 -> "Escuro" ; else -> "Auto" },
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 8.dp) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.Dashboard, contentDescription = null) },
                    label = { Text("Painel", fontWeight = FontWeight.SemiBold) }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.Folder, contentDescription = null) },
                    label = { Text("Arquivos", fontWeight = FontWeight.SemiBold) }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Default.Security, contentDescription = null) },
                    label = { Text("Servidor PC", fontWeight = FontWeight.SemiBold) }
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
                0 -> InterceptorTabScreen(onOpenFolderPicker = { selectedTab = 1 })
                1 -> LocalFolderExplorerTabScreen()
                else -> WebServerTabScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InterceptorTabScreen(onOpenFolderPicker: () -> Unit) {
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
    var storageStats by remember { mutableStateOf(SettingsManager.getStorageStats()) }
    var showAddPresetDialog by remember { mutableStateOf(false) }
    var newPresetText by remember { mutableStateOf("") }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasAllFilesPermission = checkStoragePermission()
                hasOverlayPermission = checkOverlayPermission(context)
                deviceIp = WebServerManager.getDeviceIpAddress(context)
                storageStats = SettingsManager.getStorageStats()
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
        if (!hasAllFilesPermission || !hasOverlayPermission) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Permissões Pendentes do Sistema",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    if (!hasAllFilesPermission) {
                        Button(
                            onClick = { requestAllFilesPermission(context) },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.FolderOpen, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("1. LIBERAR ACESSO TOTAL AOS ARQUIVOS")
                        }
                    }
                    if (!hasOverlayPermission) {
                        Button(
                            onClick = { requestOverlayPermission(context) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Layers, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("2. LIBERAR WIDGET FLUTUANTE DA CÂMERA")
                        }
                    }
                }
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.5.dp, if (isServiceRunning) Color(0xFF10B981) else MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(if (isServiceRunning) Color(0xFF10B981) else Color(0xFF94A3B8))
                        )
                        Text(
                            text = if (isServiceRunning) "INTERCEPTADOR ATIVO" else "SERVIÇO EM ESPERA",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = if (isServiceRunning) Color(0xFF10B981) else MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(20.dp)) {
                        Text(
                            text = "$interceptedCount capturados",
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Divider(color = MaterialTheme.colorScheme.surfaceVariant)

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Armazenamento Interno", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                        Text("${storageStats.usedPercent}% ocupado", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                    LinearProgressIndicator(
                        progress = storageStats.usedPercent / 100f,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(8.dp)),
                        color = Color(0xFF0284C7),
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                    Text(storageStats.formattedSummary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                if (isWebServerRunning) {
                    Text(
                        text = "🌐 Servidor PC: http://$deviceIp:${SettingsManager.getServerPort(context)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF0284C7),
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Prefixos Rápidos de Captura", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    TextButton(onClick = {
                        newPresetText = ""
                        showAddPresetDialog = true
                    }) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Novo")
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    quickPresets.forEach { preset ->
                        val isSelected = activePrefix == preset
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                prefixInput = preset
                                SettingsManager.setPrefix(context, preset)
                                notifyServiceConfigChanged(context, isServiceRunning)
                            },
                            label = { Text(preset, fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Medium) },
                            leadingIcon = if (isSelected) { { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) } } else null,
                            trailingIcon = if (quickPresets.size > 1) {
                                {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = null,
                                        modifier = Modifier
                                            .size(15.dp)
                                            .clickable { SettingsManager.removeQuickPreset(context, preset) }
                                    )
                                }
                            } else null
                        )
                    }
                }

                OutlinedTextField(
                    value = prefixInput,
                    onValueChange = {
                        prefixInput = it
                        SettingsManager.setPrefix(context, it)
                        notifyServiceConfigChanged(context, isServiceRunning)
                    },
                    label = { Text("Prefixo Ativo") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Pasta de Destino & Widget Flutuante", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)

                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenFolderPicker() }
                ) {
                    Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.FolderSpecial, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Column {
                                Text("Salvando fotos e vídeos em:", style = MaterialTheme.typography.labelSmall)
                                Text(
                                    SettingsManager.formatShortPath(activeFolderPath),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        Icon(Icons.Default.Edit, contentDescription = null)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Widget Flutuante sobre a Câmera", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Text("Pílula translúcida com contador e troca rápida", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                if (!hasAllFilesPermission) {
                    requestAllFilesPermission(context)
                    return@Button
                }
                if (overlayEnabled && !hasOverlayPermission) {
                    requestOverlayPermission(context)
                    return@Button
                }
                requestNotificationPermissionIfNeeded(context, notificationPermissionLauncher)
                startInterceptorAndOpenNativeCamera(context)
            },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981), contentColor = Color.White),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp)
        ) {
            Icon(Icons.Default.PhotoCamera, contentDescription = null)
            Spacer(modifier = Modifier.width(10.dp))
            Text("ATIVAR E ABRIR CÂMERA NATIVA", fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
        }

        OutlinedButton(
            onClick = {
                if (isServiceRunning) {
                    val stopIntent = Intent(context, MediaInterceptorService::class.java).apply {
                        action = MediaInterceptorService.ACTION_STOP
                    }
                    context.startService(stopIntent)
                } else {
                    if (!hasAllFilesPermission) {
                        requestAllFilesPermission(context)
                        return@OutlinedButton
                    }
                    requestNotificationPermissionIfNeeded(context, notificationPermissionLauncher)
                    startInterceptorServiceOnly(context)
                }
            },
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        ) {
            Icon(if (isServiceRunning) Icons.Default.StopCircle else Icons.Default.PlayCircle, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(if (isServiceRunning) "PARAR INTERCEPTADOR E SERVIDOR" else "INICIAR APENAS EM SEGUNDO PLANO", fontWeight = FontWeight.Bold)
        }
    }

    if (showAddPresetDialog) {
        AlertDialog(
            onDismissRequest = { showAddPresetDialog = false },
            title = { Text("Nova Etiqueta de Prefixo") },
            text = {
                OutlinedTextField(
                    value = newPresetText,
                    onValueChange = { newPresetText = it },
                    label = { Text("Ex: Carreta_ / Vistoria_") },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(onClick = {
                    if (newPresetText.isNotBlank()) {
                        SettingsManager.addQuickPreset(context, newPresetText)
                        SettingsManager.setPrefix(context, newPresetText)
                        notifyServiceConfigChanged(context, isServiceRunning)
                    }
                    showAddPresetDialog = false
                }) {
                    Text("Adicionar")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddPresetDialog = false }) { Text("Cancelar") }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun LocalFolderExplorerTabScreen() {
    val context = LocalContext.current
    val rootPath = remember { SettingsManager.getStorageRootPath() }
    val activeFolderPath by SettingsManager.activeFolderPath.collectAsState()

    var currentDir by remember {
        val initial = File(SettingsManager.getTargetFolderPath(context))
        if (!initial.exists()) initial.mkdirs()
        mutableStateOf(if (initial.exists() && initial.isDirectory) initial else File(rootPath))
    }

    var allItems by remember { mutableStateOf<List<File>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    var categoryFilter by remember { mutableStateOf("all") }
    var gridColumns by remember { mutableStateOf(3) }

    var selectedPaths by remember { mutableStateOf<Set<String>>(emptySet()) }
    val isSelectionMode = selectedPaths.isNotEmpty()

    var showNewFolderDialog by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }

    var selectedFileForAction by remember { mutableStateOf<File?>(null) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameInput by remember { mutableStateOf("") }

    var showMovePickerForFile by remember { mutableStateOf<File?>(null) }
    var isBatchMoveActive by remember { mutableStateOf(false) }

    var fullScreenMediaIndex by remember { mutableStateOf<Int?>(null) }

    fun refreshDirectory(dir: File) {
        if (!dir.exists()) dir.mkdirs()
        currentDir = dir
        selectedPaths = emptySet()
        val children = dir.listFiles()?.toList() ?: emptyList()
        allItems = children
            .filter { !it.name.startsWith(".") }
            .sortedWith(compareByDescending<File> { it.isDirectory }.thenByDescending { it.lastModified() })
    }

    LaunchedEffect(currentDir) {
        refreshDirectory(currentDir)
    }

    val filteredItems = remember(allItems, searchQuery, categoryFilter) {
        allItems.filter { file ->
            val matchQuery = searchQuery.isBlank() || file.name.contains(searchQuery.trim(), ignoreCase = true)
            val matchCat = when (categoryFilter) {
                "image" -> !file.isDirectory && isImageFileName(file.name)
                "video" -> !file.isDirectory && isVideoFileName(file.name)
                "folder" -> file.isDirectory
                else -> true
            }
            matchQuery && matchCat
        }
    }

    val mediaOnlyList = remember(filteredItems) {
        filteredItems.filter { !it.isDirectory && (isImageFileName(it.name) || isVideoFileName(it.name)) }
    }

    fun toggleSelect(path: String) {
        selectedPaths = if (selectedPaths.contains(path)) selectedPaths - path else selectedPaths + path
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Buscar em ${currentDir.name.ifEmpty { "Memória" }}...", fontSize = 13.sp) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = if (searchQuery.isNotEmpty()) {
                        { IconButton(onClick = { searchQuery = "" }) { Icon(Icons.Default.Clear, contentDescription = null) } }
                    } else null,
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f)
                )

                FilledTonalIconButton(
                    onClick = {
                        gridColumns = when (gridColumns) {
                            3 -> 2
                            2 -> 4
                            4 -> 1
                            else -> 3
                        }
                    }
                ) {
                    Icon(
                        imageVector = if (gridColumns == 1) Icons.Default.ViewList else Icons.Default.GridView,
                        contentDescription = null
                    )
                }
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    BreadcrumbBar(rootPath = rootPath, currentDir = currentDir, onNavigate = { refreshDirectory(it) })

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val canGoUp = currentDir.absolutePath != rootPath && currentDir.parentFile != null
                        OutlinedButton(
                            onClick = { currentDir.parentFile?.let { refreshDirectory(it) } },
                            enabled = canGoUp,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.ArrowUpward, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Acima", fontSize = 12.sp)
                        }

                        OutlinedButton(
                            onClick = {
                                newFolderName = ""
                                showNewFolderDialog = true
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.CreateNewFolder, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Nova Pasta", fontSize = 12.sp)
                        }

                        val isCurrentTarget = activeFolderPath == currentDir.absolutePath
                        Button(
                            onClick = {
                                SettingsManager.setTargetFolderPath(context, currentDir.absolutePath)
                                notifyServiceConfigChanged(context, SettingsManager.isServiceRunning.value)
                                Toast.makeText(context, "Destino da câmera atualizado!", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isCurrentTarget) Color(0xFF10B981) else MaterialTheme.colorScheme.primary
                            ),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            modifier = Modifier.weight(1.3f)
                        ) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(if (isCurrentTarget) "Pasta Ativa" else "Usar na Câmera", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(selected = categoryFilter == "all", onClick = { categoryFilter = "all" }, label = { Text("Todos (${allItems.size})") })
                FilterChip(selected = categoryFilter == "image", onClick = { categoryFilter = "image" }, label = { Text("Fotos (${allItems.count { !it.isDirectory && isImageFileName(it.name) }})") })
                FilterChip(selected = categoryFilter == "video", onClick = { categoryFilter = "video" }, label = { Text("Vídeos (${allItems.count { !it.isDirectory && isVideoFileName(it.name) }})") })
                FilterChip(selected = categoryFilter == "folder", onClick = { categoryFilter = "folder" }, label = { Text("Pastas (${allItems.count { it.isDirectory }})") })

                if (filteredItems.isNotEmpty()) {
                    AssistChip(
                        onClick = {
                            selectedPaths = if (selectedPaths.size == filteredItems.size) emptySet() else filteredItems.map { it.absolutePath }.toSet()
                        },
                        label = { Text(if (selectedPaths.size == filteredItems.size) "Desmarcar" else "Selecionar Tudo") }
                    )
                }
            }

            if (filteredItems.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Pasta vazia.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else if (gridColumns == 1) {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(bottom = if (isSelectionMode) 80.dp else 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredItems, key = { it.absolutePath }) { item ->
                        ExplorerListItemCard(
                            file = item,
                            isSelected = selectedPaths.contains(item.absolutePath),
                            onClick = {
                                if (isSelectionMode) toggleSelect(item.absolutePath)
                                else if (item.isDirectory) refreshDirectory(item)
                                else {
                                    val mIdx = mediaOnlyList.indexOfFirst { it.absolutePath == item.absolutePath }
                                    if (mIdx >= 0) fullScreenMediaIndex = mIdx
                                    else selectedFileForAction = item
                                }
                            },
                            onLongClick = { toggleSelect(item.absolutePath) },
                            onMoreClick = { selectedFileForAction = item }
                        )
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(gridColumns),
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(bottom = if (isSelectionMode) 80.dp else 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredItems, key = { it.absolutePath }) { item ->
                        ExplorerGridItemCard(
                            file = item,
                            isSelected = selectedPaths.contains(item.absolutePath),
                            onClick = {
                                if (isSelectionMode) toggleSelect(item.absolutePath)
                                else if (item.isDirectory) refreshDirectory(item)
                                else {
                                    val mIdx = mediaOnlyList.indexOfFirst { it.absolutePath == item.absolutePath }
                                    if (mIdx >= 0) fullScreenMediaIndex = mIdx
                                    else selectedFileForAction = item
                                }
                            },
                            onLongClick = { toggleSelect(item.absolutePath) },
                            onToggleCheck = { toggleSelect(item.absolutePath) }
                        )
                    }
                }
            }
        }

        // Barra Flutuante de Selecao em Lote com Dupla Confirmacao
        if (isSelectionMode) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0F2942), contentColor = Color.White),
                shape = RoundedCornerShape(18.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 10.dp),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("${selectedPaths.size} selecionado(s)", fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, color = Color(0xFF38BDF8))

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        IconButton(onClick = {
                            isBatchMoveActive = true
                            showMovePickerForFile = null
                        }) {
                            Icon(Icons.Default.DriveFileMove, contentDescription = "Mover", tint = Color.White)
                        }
                        IconButton(onClick = {
                            val count = selectedPaths.size
                            selectedFileForAction = null
                            // Dupla confirmacao para excluir lote
                            android.app.AlertDialog.Builder(context)
                                .setTitle("Excluir $count item(ns)?")
                                .setMessage("Esta ação removerá permanentemente os itens selecionados da memória do celular.")
                                .setPositiveButton("Sim, Excluir Definitivamente") { _, _ ->
                                    val toDel = selectedPaths.toList()
                                    toDel.forEach { File(it).deleteRecursively() }
                                    MediaScannerConnection.scanFile(context, toDel.toTypedArray(), null, null)
                                    Toast.makeText(context, "$count item(ns) excluído(s)!", Toast.LENGTH_SHORT).show()
                                    refreshDirectory(currentDir)
                                }
                                .setNegativeButton("Cancelar", null)
                                .show()
                        }) {
                            Icon(Icons.Default.Delete, contentDescription = "Excluir", tint = Color(0xFFF87171))
                        }
                        IconButton(onClick = { selectedPaths = emptySet() }) {
                            Icon(Icons.Default.Close, contentDescription = "Cancelar", tint = Color.White)
                        }
                    }
                }
            }
        }
    }

    if (fullScreenMediaIndex != null && mediaOnlyList.isNotEmpty()) {
        val safeIdx = fullScreenMediaIndex!!.coerceIn(0, mediaOnlyList.size - 1)
        val currentMediaFile = mediaOnlyList[safeIdx]

        FullScreenMediaDialog(
            file = currentMediaFile,
            index = safeIdx,
            total = mediaOnlyList.size,
            onPrev = { fullScreenMediaIndex = (safeIdx - 1 + mediaOnlyList.size) % mediaOnlyList.size },
            onNext = { fullScreenMediaIndex = (safeIdx + 1) % mediaOnlyList.size },
            onMove = {
                fullScreenMediaIndex = null
                showMovePickerForFile = currentMediaFile
                isBatchMoveActive = false
            },
            onDelete = {
                android.app.AlertDialog.Builder(context)
                    .setTitle("Excluir ${currentMediaFile.name}?")
                    .setMessage("Deseja apagar este arquivo definitivamente?")
                    .setPositiveButton("Sim, Excluir") { _, _ ->
                        val path = currentMediaFile.absolutePath
                        if (currentMediaFile.delete()) {
                            MediaScannerConnection.scanFile(context, arrayOf(path), null, null)
                            fullScreenMediaIndex = null
                            refreshDirectory(currentDir)
                        }
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            },
            onDismiss = { fullScreenMediaIndex = null }
        )
    }

    if (showMovePickerForFile != null || isBatchMoveActive) {
        MoveFolderPickerDialog(
            rootPath = rootPath,
            onDismiss = {
                showMovePickerForFile = null
                isBatchMoveActive = false
            },
            onFolderSelected = { targetDir ->
                if (isBatchMoveActive) {
                    var moved = 0
                    selectedPaths.forEach { p ->
                        val src = File(p)
                        val dst = File(targetDir, src.name)
                        if (src.absolutePath != dst.absolutePath && src.exists()) {
                            if (src.renameTo(dst)) moved++
                        }
                    }
                    Toast.makeText(context, "$moved item(ns) movido(s)!", Toast.LENGTH_SHORT).show()
                } else if (showMovePickerForFile != null) {
                    val src = showMovePickerForFile!!
                    val dst = File(targetDir, src.name)
                    if (src.absolutePath != dst.absolutePath && src.exists()) {
                        if (src.renameTo(dst)) {
                            Toast.makeText(context, "Movido com sucesso!", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                showMovePickerForFile = null
                isBatchMoveActive = false
                refreshDirectory(currentDir)
            }
        )
    }

    if (showNewFolderDialog) {
        AlertDialog(
            onDismissRequest = { showNewFolderDialog = false },
            title = { Text("Criar Nova Pasta") },
            text = {
                OutlinedTextField(
                    value = newFolderName,
                    onValueChange = { newFolderName = it },
                    label = { Text("Nome da pasta") },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(onClick = {
                    val clean = newFolderName.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
                    if (clean.isNotEmpty()) {
                        val newDir = File(currentDir, clean)
                        if (newDir.mkdirs() || newDir.exists()) {
                            refreshDirectory(newDir)
                            Toast.makeText(context, "Pasta criada!", Toast.LENGTH_SHORT).show()
                        }
                    }
                    showNewFolderDialog = false
                }) { Text("Criar") }
            },
            dismissButton = { TextButton(onClick = { showNewFolderDialog = false }) { Text("Cancelar") } }
        )
    }

    selectedFileForAction?.let { item ->
        AlertDialog(
            onDismissRequest = { selectedFileForAction = null },
            title = { Text(item.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            selectedFileForAction = null
                            showMovePickerForFile = item
                            isBatchMoveActive = false
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.DriveFileMove, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("MOVER PARA OUTRA PASTA")
                    }

                    if (item.isDirectory) {
                        Button(
                            onClick = {
                                SettingsManager.setTargetFolderPath(context, item.absolutePath)
                                notifyServiceConfigChanged(context, SettingsManager.isServiceRunning.value)
                                selectedFileForAction = null
                                Toast.makeText(context, "Definida como destino da câmera!", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("USAR ESTA PASTA NA CÂMERA")
                        }
                    }

                    OutlinedButton(
                        onClick = {
                            renameInput = item.name
                            showRenameDialog = true
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("RENOMEAR")
                    }

                    Button(
                        onClick = {
                            // Dupla confirmacao para excluir arquivo individual
                            val fileToDel = item
                            selectedFileForAction = null
                            android.app.AlertDialog.Builder(context)
                                .setTitle("Excluir ${fileToDel.name}?")
                                .setMessage("Esta ação removerá o arquivo permanentemente do celular.")
                                .setPositiveButton("Sim, Excluir") { _, _ ->
                                    val path = fileToDel.absolutePath
                                    if (fileToDel.deleteRecursively()) {
                                        MediaScannerConnection.scanFile(context, arrayOf(path), null, null)
                                        refreshDirectory(currentDir)
                                        Toast.makeText(context, "Excluído com sucesso!", Toast.LENGTH_SHORT).show()
                                    }
                                }
                                .setNegativeButton("Cancelar", null)
                                .show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("EXCLUIR DO CELULAR")
                    }
                }
            },
            confirmButton = { TextButton(onClick = { selectedFileForAction = null }) { Text("Fechar") } }
        )
    }

    if (showRenameDialog && selectedFileForAction != null) {
        val target = selectedFileForAction!!
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Renomear Item") },
            text = {
                OutlinedTextField(
                    value = renameInput,
                    onValueChange = { renameInput = it },
                    label = { Text("Novo nome") },
                    singleLine = true
                )
            },
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
}

@Composable
fun MoveFolderPickerDialog(
    rootPath: String,
    onDismiss: () -> Unit,
    onFolderSelected: (String) -> Unit
) {
    var currentBrowseDir by remember { mutableStateOf(File(rootPath)) }
    val subFolders = remember(currentBrowseDir.absolutePath) {
        currentBrowseDir.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }?.sortedBy { it.name.lowercase() } ?: emptyList()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Selecionar Pasta de Destino") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.height(300.dp)) {
                Text(
                    text = "📂 ${SettingsManager.formatShortPath(currentBrowseDir.absolutePath)}",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 13.sp
                )

                if (currentBrowseDir.absolutePath != rootPath && currentBrowseDir.parentFile != null) {
                    OutlinedButton(
                        onClick = { currentBrowseDir = currentBrowseDir.parentFile!! },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.ArrowUpward, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Subir para pasta acima")
                    }
                }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
                        .padding(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (subFolders.isEmpty()) {
                        item {
                            Text(
                                "Nenhuma subpasta aqui. Toque no botão abaixo para mover para esta pasta.",
                                modifier = Modifier.padding(12.dp),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        items(subFolders) { folder ->
                            Surface(
                                color = MaterialTheme.colorScheme.surface,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { currentBrowseDir = folder }
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(Icons.Default.Folder, contentDescription = null, tint = Color(0xFF0284C7), modifier = Modifier.size(20.dp))
                                    Text(folder.name, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onFolderSelected(currentBrowseDir.absolutePath) }) {
                Text("✓ Mover para Cá")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        }
    )
}

@Composable
fun BreadcrumbBar(rootPath: String, currentDir: File, onNavigate: (File) -> Unit) {
    val segments = remember(currentDir.absolutePath) {
        val list = mutableListOf<Pair<String, File>>()
        val rootFile = File(rootPath)
        list.add("Memória" to rootFile)
        if (currentDir.absolutePath.startsWith(rootPath) && currentDir.absolutePath != rootPath) {
            val rel = currentDir.absolutePath.removePrefix(rootPath).trimStart('/')
            val parts = rel.split("/").filter { it.isNotEmpty() }
            var acc = rootFile
            for (p in parts) {
                acc = File(acc, p)
                list.add(p to acc)
            }
        }
        list
    }

    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        segments.forEachIndexed { index, pair ->
            val isLast = index == segments.size - 1
            Surface(
                color = if (isLast) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.clickable { onNavigate(pair.second) }
            ) {
                Text(
                    text = pair.first,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (isLast) FontWeight.ExtraBold else FontWeight.Medium,
                    color = if (isLast) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary
                )
            }
            if (!isLast) Text("›", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ExplorerGridItemCard(file: File, isSelected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit, onToggleCheck: () -> Unit) {
    Card(
        shape = RoundedCornerShape(12.dp),
        border = if (isSelected) BorderStroke(2.5.dp, Color(0xFF0284C7)) else null,
        modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Column {
            Box(
                modifier = Modifier.fillMaxWidth().aspectRatio(1f).background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                if (file.isDirectory) {
                    val count = remember(file.absolutePath) { file.listFiles()?.size ?: 0 }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(44.dp), tint = Color(0xFF0284C7))
                        Text("$count itens", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    MediaThumbnailImage(file = file, modifier = Modifier.fillMaxSize())
                }

                if (isVideoFileName(file.name)) {
                    Surface(
                        color = Color.Black.copy(alpha = 0.7f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp)
                    ) {
                        Row(modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp))
                            Text("VÍDEO", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Surface(
                    color = if (isSelected) Color(0xFF0284C7) else Color.Black.copy(alpha = 0.45f),
                    shape = CircleShape,
                    border = BorderStroke(1.5.dp, Color.White),
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(24.dp).clickable { onToggleCheck() }
                ) {
                    if (isSelected) {
                        Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.padding(3.dp))
                    }
                }
            }

            Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                Text(text = file.name, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ExplorerListItemCard(file: File, isSelected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit, onMoreClick: () -> Unit) {
    val dateFormat = remember { SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()) }

    Card(
        shape = RoundedCornerShape(12.dp),
        border = if (isSelected) BorderStroke(2.dp, Color(0xFF0284C7)) else null,
        modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier.size(54.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                if (file.isDirectory) {
                    Icon(Icons.Default.Folder, contentDescription = null, tint = Color(0xFF0284C7), modifier = Modifier.size(32.dp))
                } else {
                    MediaThumbnailImage(file = file, modifier = Modifier.fillMaxSize())
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(text = file.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    text = if (file.isDirectory) "Pasta • ${dateFormat.format(Date(file.lastModified()))}"
                    else "${formatFileSize(file.length())} • ${dateFormat.format(Date(file.lastModified()))}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            IconButton(onClick = onMoreClick) {
                Icon(Icons.Default.MoreVert, contentDescription = null)
            }
        }
    }
}

@Composable
fun MediaThumbnailImage(file: File, modifier: Modifier = Modifier) {
    val key = "${file.absolutePath}_${file.lastModified()}"
    var bitmap by remember(key) { mutableStateOf(LocalThumbnailCache.get(key)) }

    LaunchedEffect(key) {
        if (bitmap == null) {
            val loaded = withContext(Dispatchers.IO) { decodeThumbnailBitmap(file, 260) }
            if (loaded != null) {
                val imgBmp = loaded.asImageBitmap()
                LocalThumbnailCache.put(key, imgBmp)
                bitmap = imgBmp
            }
        }
    }

    if (bitmap != null) {
        Image(bitmap = bitmap!!, contentDescription = file.name, contentScale = ContentScale.Crop, modifier = modifier)
    } else {
        Icon(
            imageVector = if (isVideoFileName(file.name)) Icons.Default.Videocam else Icons.Default.Image,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(32.dp)
        )
    }
}

@Composable
fun FullScreenMediaDialog(
    file: File,
    index: Int,
    total: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    var highResBitmap by remember(file.absolutePath) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(file.absolutePath) {
        highResBitmap = withContext(Dispatchers.IO) {
            decodeThumbnailBitmap(file, 1280)?.asImageBitmap()
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF050A14)) {
            Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                Row(
                    modifier = Modifier.fillMaxWidth().background(Color(0xFF0F172A)).padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "${index + 1} / $total", color = Color(0xFF38BDF8), fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
                        Text(text = file.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }

                    Row {
                        IconButton(onClick = onMove) { Icon(Icons.Default.DriveFileMove, contentDescription = "Mover", tint = Color.White) }
                        IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = "Excluir", tint = Color(0xFFF87171)) }
                        IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "Fechar", tint = Color.White) }
                    }
                }

                Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    if (highResBitmap != null) {
                        Image(bitmap = highResBitmap!!, contentDescription = file.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                    } else {
                        CircularProgressIndicator(color = Color(0xFF38BDF8))
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth().background(Color(0xFF0F172A)).padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(onClick = onPrev, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B))) {
                        Icon(Icons.Default.NavigateBefore, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Anterior")
                    }

                    Text(formatFileSize(file.length()), color = Color(0xFF94A3B8), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)

                    Button(onClick = onNext, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7))) {
                        Text("Próximo")
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(Icons.Default.NavigateNext, contentDescription = null)
                    }
                }
            }
        }
    }
}

@Composable
fun WebServerTabScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val isServiceRunning by SettingsManager.isServiceRunning.collectAsState()
    val isWebServerRunning by SettingsManager.isWebServerRunning.collectAsState()
    val active2faCode by SettingsManager.current2faCode.collectAsState()
    val active2faIp by SettingsManager.current2faClientIp.collectAsState()
    val active2faAction by SettingsManager.current2faActionDesc.collectAsState()
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
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (active2faCode != null) Color(0xFF064E3B) else MaterialTheme.colorScheme.surface
            ),
            border = BorderStroke(1.5.dp, if (active2faCode != null) Color(0xFF10B981) else MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = if (active2faCode != null) "📲 SOLICITAÇÃO 2FA DO PC" else "🛡️ VERIFICAÇÃO EM 2 ETAPAS (2FA)",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = if (active2faCode != null) Color(0xFF10B981) else MaterialTheme.colorScheme.onSurface
                )

                if (active2faAction != null) {
                    Text(
                        text = "Ação: ${active2faAction}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF38BDF8)
                    )
                }

                val displayDigits = (active2faCode ?: "------").toCharArray()
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    displayDigits.forEach { ch ->
                        Surface(
                            color = MaterialTheme.colorScheme.background,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, Color(0xFF0284C7))
                        ) {
                            Text(
                                text = ch.toString(),
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                fontSize = 22.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color(0xFF38BDF8)
                            )
                        }
                    }
                }

                if (active2faCode != null) {
                    Button(
                        onClick = { SettingsManager.approvePendingTwoFactor() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("✓ APROVAR AGORA", fontWeight = FontWeight.Bold)
                    }
                } else {
                    Text(
                        "O código de 6 dígitos aparecerá aqui quando o PC solicitar uma operação de arquivo.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.5.dp, if (isWebServerRunning) Color(0xFF10B981) else MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (isWebServerRunning) "SERVIDOR WEB ONLINE" else "SERVIDOR WEB PARADO",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = if (isWebServerRunning) Color(0xFF10B981) else MaterialTheme.colorScheme.onSurface
                    )
                    IconButton(onClick = {
                        ipAddress = WebServerManager.getDeviceIpAddress(context)
                        Toast.makeText(context, "IP atualizado!", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                    }
                }

                Surface(
                    color = MaterialTheme.colorScheme.background,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(text = fullUrl, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, color = Color(0xFF0284C7))
                        FilledTonalButton(onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("URL IFGestor", fullUrl))
                            Toast.makeText(context, "Endereço copiado!", Toast.LENGTH_SHORT).show()
                        }) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Copiar")
                        }
                    }
                }

                Button(
                    onClick = {
                        if (isServiceRunning && isWebServerRunning) {
                            val stopIntent = Intent(context, MediaInterceptorService::class.java).apply {
                                action = MediaInterceptorService.ACTION_STOP
                            }
                            context.startService(stopIntent)
                        } else {
                            SettingsManager.setWebServerEnabled(context, true)
                            startInterceptorServiceOnly(context)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isWebServerRunning) MaterialTheme.colorScheme.error else Color(0xFF10B981),
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) {
                    Text(if (isWebServerRunning) "DESLIGAR SERVIDOR WEB" else "ATIVAR SERVIDOR WEB EM SEGUNDO PLANO", fontWeight = FontWeight.ExtraBold)
                }
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Lock, contentDescription = null, tint = Color(0xFF0284C7))
                    Text("Segurança Corporativa (PIN + 2FA)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }

                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Exigir PIN no navegador do PC", fontWeight = FontWeight.SemiBold)
                        Text(if (hasCustomPin) "PIN personalizado ativo (SHA-256)" else "PIN padrão inicial: 1234", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = authEnabled, onCheckedChange = { authEnabled = it; SettingsManager.setWebAuthEnabled(context, it) })
                }

                if (authEnabled) {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Verificação em Duas Etapas (2FA)", fontWeight = FontWeight.SemiBold)
                            Text("Exige código a cada operação de arquivo no PC", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = twoFactorEnabled, onCheckedChange = { twoFactorEnabled = it; SettingsManager.setTwoFactorEnabled(context, it) })
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = newPinInput,
                            onValueChange = { newPinInput = it },
                            label = { Text("Alterar PIN (mín. 4 dígitos)") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        )
                        Button(onClick = {
                            if (newPinInput.trim().length < 4) {
                                Toast.makeText(context, "Mínimo de 4 dígitos.", Toast.LENGTH_SHORT).show()
                            } else {
                                SettingsManager.setWebPin(context, newPinInput)
                                hasCustomPin = true
                                newPinInput = ""
                                WebServerManager.disconnectAllSessions()
                                Toast.makeText(context, "Novo PIN salvo!", Toast.LENGTH_SHORT).show()
                            }
                        }) { Text("Salvar") }
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Sessões ativas no PC: $activeSessionsCount", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                        OutlinedButton(onClick = {
                            WebServerManager.disconnectAllSessions()
                            Toast.makeText(context, "Sessões encerradas!", Toast.LENGTH_SHORT).show()
                        }) { Text("Desconectar Todos") }
                    }
                }
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Operação com Tela Apagada", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(
                    if (isIgnoringBattery) "✅ Proteção ativa! O servidor continua acessível com a tela bloqueada."
                    else "⚠️ Desative a restrição de bateria para impedir que o Android suspenda o Wi-Fi.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (!isIgnoringBattery) {
                    Button(onClick = { requestIgnoreBatteryOptimization(context) }, modifier = Modifier.fillMaxWidth()) {
                        Text("MANTER ATIVO COM A TELA APAGADA")
                    }
                }
            }
        }
    }
}

private fun decodeThumbnailBitmap(file: File, targetSizePx: Int): Bitmap? {
    return try {
        if (isVideoFileName(file.name)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ThumbnailUtils.createVideoThumbnail(file, android.util.Size(targetSizePx, targetSizePx), null)
            } else {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(file.absolutePath)
                val frame = retriever.frameAtTime
                retriever.release()
                frame
            }
        } else if (isImageFileName(file.name)) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            var sampleSize = 1
            while (bounds.outWidth / sampleSize > targetSizePx * 2 ||
                bounds.outHeight / sampleSize > targetSizePx * 2
            ) {
                sampleSize *= 2
            }
            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            BitmapFactory.decodeFile(file.absolutePath, decodeOpts)
        } else {
            null
        }
    } catch (_: Exception) {
        null
    }
}

private fun isImageFileName(name: String): Boolean {
    val low = name.lowercase()
    return low.endsWith(".jpg") || low.endsWith(".jpeg") || low.endsWith(".png") || low.endsWith(".webp")
}

private fun isVideoFileName(name: String): Boolean {
    val low = name.lowercase()
    return low.endsWith(".mp4") || low.endsWith(".mov") || low.endsWith(".mkv") || low.endsWith(".3gp")
}

private fun formatFileSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    return String.format(Locale.US, "%.2f MB", mb)
}

private fun checkStoragePermission(): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager() else true
}

private fun checkOverlayPermission(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(context) else true
}

private fun checkBatteryOptimizationIgnored(context: Context): Boolean {
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

private fun requestIgnoreBatteryOptimization(context: Context) {
    try {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        context.startActivity(intent)
    }
}

private fun requestAllFilesPermission(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        try {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:${context.packageName}")
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
            context.startActivity(intent)
        }
    }
}

private fun requestOverlayPermission(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
        context.startActivity(intent)
    }
}

private fun requestNotificationPermissionIfNeeded(context: Context, launcher: ManagedActivityResultLauncher<String, Boolean>) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!granted) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

private fun notifyServiceConfigChanged(context: Context, isRunning: Boolean) {
    if (!isRunning) return
    val intent = Intent(context, MediaInterceptorService::class.java).apply {
        action = MediaInterceptorService.ACTION_REFRESH_CONFIG
    }
    context.startService(intent)
}

private fun startInterceptorServiceOnly(context: Context) {
    val serviceIntent = Intent(context, MediaInterceptorService::class.java).apply {
        action = MediaInterceptorService.ACTION_START
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(serviceIntent)
    } else {
        context.startService(serviceIntent)
    }
}

private fun startInterceptorAndOpenNativeCamera(context: Context) {
    startInterceptorServiceOnly(context)
    try {
        val cameraIntent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(cameraIntent)
    } catch (e: Exception) {
        Toast.makeText(context, "Não foi possível abrir a câmera automaticamente.", Toast.LENGTH_SHORT).show()
    }
}
