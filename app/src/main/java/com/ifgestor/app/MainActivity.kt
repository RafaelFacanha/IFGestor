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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SettingsManager.syncStateFromPrefs(this)
        setContent {
            MaterialTheme {
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IFGestorMainApp() {
    var selectedTab by remember { mutableStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("IFGestor", fontWeight = FontWeight.Bold)
                        Text(
                            when (selectedTab) {
                                0 -> "Interceptador & Widget da Câmera"
                                1 -> "Explorador de Pastas do Celular"
                                else -> "Servidor Web Seguro (PIN + 2FA)"
                            },
                            style = MaterialTheme.typography.labelSmall
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
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.Home, contentDescription = null) },
                    label = { Text("Interceptador") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.Folder, contentDescription = null) },
                    label = { Text("Pastas") }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Default.Share, contentDescription = null) },
                    label = { Text("Acesso no PC") }
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

@Composable
fun InterceptorTabScreen(onOpenFolderPicker: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val activePrefix by SettingsManager.activePrefix.collectAsState()
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
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (!hasAllFilesPermission) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "1. Permissão de Arquivos Necessária",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Text(
                        "Autorize o 'Acesso a todos os arquivos' para mover fotos e criar pastas.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Button(
                        onClick = { requestAllFilesPermission(context) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("LIBERAR ACESSO AOS ARQUIVOS")
                    }
                }
            }
        }

        if (!hasOverlayPermission) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "2. Permissão do Widget Flutuante",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                    Text(
                        "Autorize 'Sobrepor a outros apps' para exibir o botão flutuante sobre a câmera.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                    Button(onClick = { requestOverlayPermission(context) }) {
                        Text("LIBERAR WIDGET FLUTUANTE")
                    }
                }
            }
        }

        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (isServiceRunning)
                    MaterialTheme.colorScheme.secondaryContainer
                else
                    MaterialTheme.colorScheme.surfaceVariant
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = if (isServiceRunning) "STATUS: INTERCEPTADOR ATIVO" else "STATUS: PARADO",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Arquivos capturados nesta sessão: $interceptedCount",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Último arquivo: $lastFile",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = "Pasta atual: ${SettingsManager.formatShortPath(activeFolderPath)}",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium
                )
                if (isWebServerRunning) {
                    Text(
                        text = "Acesso Web no PC: http://$deviceIp:${SettingsManager.getServerPort(context)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "Destino e Nomenclatura",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )

                OutlinedTextField(
                    value = prefixInput,
                    onValueChange = {
                        prefixInput = it
                        SettingsManager.setPrefix(context, it)
                        notifyServiceConfigChanged(context, isServiceRunning)
                    },
                    label = { Text("Prefixo dos arquivos (Ex: Vistoria_)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    text = "Pasta onde os arquivos serão salvos:",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = SettingsManager.formatShortPath(activeFolderPath),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )

                OutlinedButton(
                    onClick = onOpenFolderPicker,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Folder, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("ESCOLHER / CRIAR PASTA NO CELULAR")
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Widget Flutuante sobre a Câmera",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            "Permite mudar prefixo e pasta sem sair da câmera",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Switch(
                        checked = overlayEnabled,
                        onCheckedChange = {
                            overlayEnabled = it
                            SettingsManager.setOverlayEnabled(context, it)
                            if (isServiceRunning) {
                                startInterceptorServiceOnly(context)
                            }
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
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Text("ATIVAR E ABRIR CÂMERA NATIVA", fontWeight = FontWeight.Bold)
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
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        ) {
            Text(
                if (isServiceRunning) "PARAR INTERCEPTADOR E SERVIDOR WEB"
                else "APENAS ATIVAR EM SEGUNDO PLANO"
            )
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
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Alerta em Tempo Real do Codigo de 2a Etapa (2FA) quando um PC solicita login
        if (active2faCode != null) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        "📲 CÓDIGO DE VERIFICAÇÃO EM 2 ETAPAS",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = active2faCode ?: "",
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        "Solicitado pelo computador ($active2faIp). Digite este código no navegador do PC.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        // Card Principal de Endereco e Controle do Servidor
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (isWebServerRunning)
                    MaterialTheme.colorScheme.secondaryContainer
                else
                    MaterialTheme.colorScheme.surfaceVariant
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = if (isWebServerRunning) "SERVIDOR WEB ONLINE" else "SERVIDOR WEB PARADO",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = "Digite este endereço no navegador do computador:",
                    style = MaterialTheme.typography.bodySmall
                )

                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = fullUrl,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("URL IFGestor", fullUrl))
                            Toast.makeText(context, "Endereço copiado!", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Copiar Link")
                    }

                    OutlinedButton(
                        onClick = {
                            ipAddress = WebServerManager.getDeviceIpAddress(context)
                            Toast.makeText(context, "IP atualizado!", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Atualizar IP")
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                ) {
                    Text(
                        if (isWebServerRunning) "DESLIGAR SERVIDOR E SEGUNDO PLANO"
                        else "LIGAR SERVIDOR WEB EM SEGUNDO PLANO",
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Card de Seguranca (PIN + 2FA + Sessoes Ativas)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.Lock, contentDescription = null)
                    Text(
                        "Segurança de Acesso (PIN + 2 Etapas)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Exigir PIN ao abrir no PC", fontWeight = FontWeight.Medium)
                        Text(
                            if (hasCustomPin) "PIN personalizado configurado (SHA-256)"
                            else "PIN padrão inicial: 1234",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Switch(
                        checked = authEnabled,
                        onCheckedChange = {
                            authEnabled = it
                            SettingsManager.setWebAuthEnabled(context, it)
                        }
                    )
                }

                if (authEnabled) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Verificação em Duas Etapas (2FA)", fontWeight = FontWeight.Medium)
                            Text(
                                "Envia código dinâmico de 6 dígitos na notificação do celular a cada login",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Switch(
                            checked = twoFactorEnabled,
                            onCheckedChange = {
                                twoFactorEnabled = it
                                SettingsManager.setTwoFactorEnabled(context, it)
                            }
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = newPinInput,
                            onValueChange = { newPinInput = it },
                            label = { Text("Novo PIN (mín. 4 dígitos)") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = {
                                if (newPinInput.trim().length < 4) {
                                    Toast.makeText(
                                        context,
                                        "Digite pelo menos 4 dígitos para o PIN.",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                } else {
                                    SettingsManager.setWebPin(context, newPinInput)
                                    hasCustomPin = true
                                    newPinInput = ""
                                    WebServerManager.disconnectAllSessions()
                                    Toast.makeText(
                                        context,
                                        "Novo PIN salvo com sucesso!",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        ) {
                            Text("Salvar PIN")
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Sessões autenticadas no PC: $activeSessionsCount",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium
                        )
                        OutlinedButton(
                            onClick = {
                                WebServerManager.disconnectAllSessions()
                                Toast.makeText(
                                    context,
                                    "Todos os computadores foram desconectados!",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        ) {
                            Text("Desconectar PCs")
                        }
                    }
                }
            }
        }

        // Protecao para funcionar com a Tela Apagada
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "Funcionamento com a Tela Apagada",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    if (isIgnoringBattery)
                        "✅ Proteção ativa! O servidor continuará acessível pelo PC mesmo quando a tela do celular apagar."
                    else
                        "⚠️ Para que o Android não desligue o Wi-Fi quando a tela apagar, desative a restrição de bateria do IFGestor.",
                    style = MaterialTheme.typography.bodySmall
                )

                if (!isIgnoringBattery) {
                    Button(
                        onClick = { requestIgnoreBatteryOptimization(context) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("MANTER ATIVO COM A TELA APAGADA")
                    }
                }
            }
        }
    }
}

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

    var itemsList by remember { mutableStateOf<List<File>>(emptyList()) }
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }

    var selectedFileForAction by remember { mutableStateOf<File?>(null) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameInput by remember { mutableStateOf("") }

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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Navegando em: ${SettingsManager.formatShortPath(currentDir.absolutePath)}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Pasta ativa da câmera: ${SettingsManager.formatShortPath(activeFolderPath)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val canGoUp = currentDir.absolutePath != rootPath && currentDir.parentFile != null
                    OutlinedButton(
                        onClick = {
                            currentDir.parentFile?.let { refreshDirectory(it) }
                        },
                        enabled = canGoUp,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.ArrowBack, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Acima")
                    }

                    OutlinedButton(
                        onClick = {
                            newFolderName = ""
                            showNewFolderDialog = true
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Nova Pasta")
                    }

                    IconButton(onClick = { refreshDirectory(currentDir) }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Atualizar")
                    }
                }

                Button(
                    onClick = {
                        SettingsManager.setTargetFolderPath(context, currentDir.absolutePath)
                        notifyServiceConfigChanged(context, SettingsManager.isServiceRunning.value)
                        Toast.makeText(
                            context,
                            "Pasta selecionada para salvar fotos e vídeos!",
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Check, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("SALVAR ARQUIVOS DA CÂMERA NESTA PASTA")
                }
            }
        }

        Text(
            text = "${itemsList.count { it.isDirectory }} pastas e ${itemsList.count { !it.isDirectory }} arquivos neste local",
            style = MaterialTheme.typography.labelSmall
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(itemsList) { item ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (item.isDirectory) {
                                refreshDirectory(item)
                            } else {
                                selectedFileForAction = item
                            }
                        }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (item.isDirectory) "📁 ${item.name}" else "📄 ${item.name}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (item.isDirectory) FontWeight.Bold else FontWeight.Normal
                            )
                            Text(
                                text = if (item.isDirectory) "Toque para abrir a pasta"
                                else "Tamanho: ${formatFileSize(item.length())}",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }

                        IconButton(onClick = { selectedFileForAction = item }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Opções")
                        }
                    }
                }
            }
        }
    }

    if (showNewFolderDialog) {
        AlertDialog(
            onDismissRequest = { showNewFolderDialog = false },
            title = { Text("Criar Nova Pasta / Subpasta") },
            text = {
                OutlinedTextField(
                    value = newFolderName,
                    onValueChange = { newFolderName = it },
                    label = { Text("Nome da pasta") },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val cleanName = newFolderName.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
                        if (cleanName.isNotEmpty()) {
                            val newDir = File(currentDir, cleanName)
                            if (newDir.mkdirs() || newDir.exists()) {
                                refreshDirectory(newDir)
                                Toast.makeText(context, "Pasta criada e aberta!", Toast.LENGTH_SHORT).show()
                            }
                        }
                        showNewFolderDialog = false
                    }
                ) {
                    Text("Criar e Abrir")
                }
            },
            dismissButton = {
                TextButton(onClick = { showNewFolderDialog = false }) {
                    Text("Cancelar")
                }
            }
        )
    }

    selectedFileForAction?.let { item ->
        AlertDialog(
            onDismissRequest = { selectedFileForAction = null },
            title = { Text(item.name) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (item.isDirectory) {
                        Button(
                            onClick = {
                                SettingsManager.setTargetFolderPath(context, item.absolutePath)
                                notifyServiceConfigChanged(context, SettingsManager.isServiceRunning.value)
                                selectedFileForAction = null
                                Toast.makeText(context, "Definida como pasta da câmera!", Toast.LENGTH_SHORT).show()
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
                            val deleted = item.deleteRecursively()
                            selectedFileForAction = null
                            refreshDirectory(currentDir)
                            Toast.makeText(
                                context,
                                if (deleted) "Excluído com sucesso!" else "Não foi possível excluir.",
                                Toast.LENGTH_SHORT
                            ).show()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("EXCLUIR DO CELULAR")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedFileForAction = null }) {
                    Text("Fechar")
                }
            }
        )
    }

    if (showRenameDialog && selectedFileForAction != null) {
        val target = selectedFileForAction!!
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Renomear ${target.name}") },
            text = {
                OutlinedTextField(
                    value = renameInput,
                    onValueChange = { renameInput = it },
                    label = { Text("Novo nome") },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val cleanName = renameInput.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
                        if (cleanName.isNotEmpty()) {
                            val newFile = File(target.parentFile, cleanName)
                            if (target.renameTo(newFile)) {
                                if (activeFolderPath == target.absolutePath) {
                                    SettingsManager.setTargetFolderPath(context, newFile.absolutePath)
                                }
                                refreshDirectory(currentDir)
                            }
                        }
                        showRenameDialog = false
                        selectedFileForAction = null
                    }
                ) {
                    Text("Salvar")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text("Cancelar")
                }
            }
        )
    }
}

private fun formatFileSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format("%.1f KB", kb)
    val mb = kb / 1024.0
    return String.format("%.2f MB", mb)
}

private fun checkStoragePermission(): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        true
    }
}

private fun checkOverlayPermission(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        Settings.canDrawOverlays(context)
    } else {
        true
    }
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
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )
        context.startActivity(intent)
    }
}

private fun requestNotificationPermissionIfNeeded(
    context: Context,
    launcher: ManagedActivityResultLauncher<String, Boolean>
) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
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
        Toast.makeText(
            context,
            "Não foi possível abrir o atalho da câmera automaticamente.",
            Toast.LENGTH_SHORT
        ).show()
    }
}
