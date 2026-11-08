package com.ifgestor.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
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
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
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
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SettingsManager.refreshQueueState(this)
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
                            if (selectedTab == 0) "Interceptador da Câmera Nativa"
                            else "Gerenciador de Rede (HD Externo / PC)",
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
                    icon = { Icon(Icons.Default.List, contentDescription = null) },
                    label = { Text("HD Externo (PC)") }
                )
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (selectedTab == 0) {
                InterceptorTabScreen()
            } else {
                SmbExplorerTabScreen()
            }
        }
    }
}

@Composable
fun InterceptorTabScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var prefixText by remember { mutableStateOf(SettingsManager.getPrefix(context)) }
    var localFolderText by remember { mutableStateOf(SettingsManager.getLocalFolder(context)) }
    var smbEnabled by remember { mutableStateOf(SettingsManager.isSmbSyncEnabled(context)) }
    var deleteLocalAfterSync by remember { mutableStateOf(SettingsManager.getDeleteLocalAfterSync(context)) }
    var activeRemoteFolder by remember { mutableStateOf(SettingsManager.getSmbRemoteFolder(context)) }
    var hasAllFilesPermission by remember { mutableStateOf(checkStoragePermission()) }
    var isManualSyncing by remember { mutableStateOf(false) }

    val isServiceRunning by SettingsManager.isServiceRunning.collectAsState()
    val interceptedCount by SettingsManager.interceptedCount.collectAsState()
    val syncedCount by SettingsManager.syncedCount.collectAsState()
    val pendingQueueCount by SettingsManager.pendingQueueCount.collectAsState()
    val lastFile by SettingsManager.lastInterceptedFile.collectAsState()
    val syncStatus by SettingsManager.syncStatus.collectAsState()

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasAllFilesPermission = checkStoragePermission()
                activeRemoteFolder = SettingsManager.getSmbRemoteFolder(context)
                smbEnabled = SettingsManager.isSmbSyncEnabled(context)
                SettingsManager.refreshQueueState(context)
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
                        "Permissão Necessária",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Text(
                        "Autorize o 'Acesso a todos os arquivos' para renomear e mover fotos sem pop-ups.",
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

        // Card de Status e Fila de Sincronizacao
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
                    text = "Capturados: $interceptedCount  |  Enviados ao PC: $syncedCount  |  Na Fila: $pendingQueueCount",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Último arquivo: $lastFile",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = "Status SMB: $syncStatus",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = "Pasta destino no PC: ${if (activeRemoteFolder.isEmpty()) "Raiz do Compartilhamento" else activeRemoteFolder}",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium
                )

                if (pendingQueueCount > 0) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                isManualSyncing = true
                                scope.launch {
                                    SmbClientManager.processPendingQueue(context)
                                    isManualSyncing = false
                                }
                            },
                            enabled = !isManualSyncing,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                if (isManualSyncing) "ENVIANDO..."
                                else "SINCRONIZAR FILA ($pendingQueueCount)"
                            )
                        }

                        OutlinedButton(
                            onClick = {
                                SettingsManager.clearPendingQueue(context)
                                SettingsManager.updateSyncStatus("Fila pendente limpa manualmente")
                            },
                            enabled = !isManualSyncing
                        ) {
                            Text("Limpar Fila")
                        }
                    }
                }
            }
        }

        // Card de Configuracao de Captura e Rede
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "Regras de Captura e Rede",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )

                OutlinedTextField(
                    value = prefixText,
                    onValueChange = {
                        prefixText = it
                        SettingsManager.setPrefix(context, it)
                    },
                    label = { Text("Prefixo dos arquivos (Ex: Vistoria_)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = localFolderText,
                    onValueChange = {
                        localFolderText = it
                        SettingsManager.setLocalFolder(context, it)
                    },
                    label = { Text("Pasta local em DCIM/") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Enviar automaticamente para o PC (SMB)",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            "Se estiver no 4G/offline, guarda na fila e envia ao conectar na rede do PC",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Switch(
                        checked = smbEnabled,
                        onCheckedChange = {
                            smbEnabled = it
                            SettingsManager.setSmbSyncEnabled(context, it)
                        }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Apagar do celular após enviar ao PC",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            if (deleteLocalAfterSync) "Modo Transferência (remove cópia local após confirmar envio)"
                            else "Modo Espelhamento (mantém cópia no celular e no PC)",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Switch(
                        checked = deleteLocalAfterSync,
                        onCheckedChange = {
                            deleteLocalAfterSync = it
                            SettingsManager.setDeleteLocalAfterSync(context, it)
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
                if (isServiceRunning) "PARAR INTERCEPTADOR EM SEGUNDO PLANO"
                else "APENAS ATIVAR EM SEGUNDO PLANO"
            )
        }
    }
}

@Composable
fun SmbExplorerTabScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val savedConfig = remember { SettingsManager.getSmbConfig(context) }
    var host by remember { mutableStateOf(savedConfig.host) }
    var shareName by remember { mutableStateOf(savedConfig.shareName) }
    var username by remember { mutableStateOf(savedConfig.username) }
    var password by remember { mutableStateOf(savedConfig.password) }

    var showSettingsCard by remember { mutableStateOf(savedConfig.host.isBlank()) }
    var currentPath by remember { mutableStateOf(SettingsManager.getSmbRemoteFolder(context)) }
    var activeCameraFolder by remember { mutableStateOf(SettingsManager.getSmbRemoteFolder(context)) }

    var fileList by remember { mutableStateOf<List<SmbFileItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("Toque em Conectar / Atualizar para listar os arquivos do PC.") }

    var showNewFolderDialog by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }

    var selectedItemForAction by remember { mutableStateOf<SmbFileItem?>(null) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }

    fun currentConfig(): SmbConnectionConfig {
        val cfg = SmbConnectionConfig(
            host = host,
            shareName = shareName,
            username = username,
            password = password
        )
        SettingsManager.saveSmbConfig(context, cfg)
        return cfg
    }

    fun loadFolder(pathToLoad: String) {
        val cfg = currentConfig()
        isLoading = true
        statusMessage = "Conectando a \\\\${cfg.host}\\${cfg.shareName}..."
        scope.launch {
            val result = SmbClientManager.listDirectory(cfg, pathToLoad)
            isLoading = false
            result.onSuccess { items ->
                currentPath = pathToLoad
                fileList = items
                statusMessage = if (items.isEmpty()) "Pasta vazia." else "${items.size} itens encontrados."
            }.onFailure { err ->
                statusMessage = "Erro: ${err.message ?: "Falha na conexão SMB"}"
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(onClick = { showSettingsCard = !showSettingsCard }) {
                Icon(Icons.Default.Settings, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text(if (showSettingsCard) "Ocultar Conexão" else "Configurar PC")
            }

            Button(
                onClick = { loadFolder(currentPath) },
                enabled = !isLoading
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text("Conectar / Atualizar")
            }
        }

        if (showSettingsCard) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "Dados do Compartilhamento Windows (SMB)",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    OutlinedTextField(
                        value = host,
                        onValueChange = { host = it },
                        label = { Text("IP do Computador (Ex: 192.168.1.11)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = shareName,
                        onValueChange = { shareName = it },
                        label = { Text("Nome do Compartilhamento (Ex: ifg)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = username,
                            onValueChange = { username = it },
                            label = { Text("Usuário") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text("Senha") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Button(
                        onClick = {
                            showSettingsCard = false
                            loadFolder(currentPath)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("SALVAR E ABRIR COMPARTILHAMENTO")
                    }
                }
            }
        }

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
                    text = "Local atual: \\\\${host.ifBlank { "IP" }}\\${shareName.ifBlank { "Share" }}\\${currentPath}",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Destino ativo da câmera: ${if (activeCameraFolder.isEmpty()) "Raiz do Compartilhamento" else activeCameraFolder}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            val parent = currentPath.replace("/", "\\")
                                .substringBeforeLast('\\', "")
                            loadFolder(parent)
                        },
                        enabled = currentPath.isNotEmpty() && !isLoading,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.ArrowBack, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Voltar")
                    }

                    OutlinedButton(
                        onClick = {
                            newFolderName = ""
                            showNewFolderDialog = true
                        },
                        enabled = !isLoading,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Nova Pasta")
                    }
                }

                Button(
                    onClick = {
                        SettingsManager.setSmbRemoteFolder(context, currentPath)
                        SettingsManager.setSmbSyncEnabled(context, true)
                        activeCameraFolder = currentPath
                        Toast.makeText(
                            context,
                            "Pasta definida como destino automático da câmera!",
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Check, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("USAR ESTA PASTA NA CÂMERA")
                }
            }
        }

        if (isLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        Text(
            text = statusMessage,
            style = MaterialTheme.typography.bodySmall
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(fileList) { item ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (item.isDirectory) {
                                loadFolder(item.relativePath)
                            } else {
                                selectedItemForAction = item
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
                                text = if (item.isDirectory) "[PASTA]  ${item.name}" else item.name,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (item.isDirectory) FontWeight.Bold else FontWeight.Normal
                            )
                            Text(
                                text = if (item.isDirectory) "Toque para abrir a pasta"
                                else "Tamanho: ${formatFileSize(item.sizeBytes)}",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }

                        IconButton(onClick = { selectedItemForAction = item }) {
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
            title = { Text("Criar Nova Pasta no HD Externo") },
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
                        val folderToCreate = newFolderName
                        showNewFolderDialog = false
                        isLoading = true
                        scope.launch {
                            val res = SmbClientManager.createFolder(currentConfig(), currentPath, folderToCreate)
                            res.onSuccess {
                                Toast.makeText(context, "Pasta criada!", Toast.LENGTH_SHORT).show()
                                loadFolder(currentPath)
                            }.onFailure { err ->
                                isLoading = false
                                statusMessage = "Erro ao criar pasta: ${err.message}"
                            }
                        }
                    }
                ) {
                    Text("Criar")
                }
            },
            dismissButton = {
                TextButton(onClick = { showNewFolderDialog = false }) {
                    Text("Cancelar")
                }
            }
        )
    }

    selectedItemForAction?.let { item ->
        AlertDialog(
            onDismissRequest = { selectedItemForAction = null },
            title = { Text(item.name) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (item.isDirectory) "Escolha uma ação para esta pasta no PC:"
                        else "Escolha uma ação para este arquivo no PC:"
                    )

                    if (!item.isDirectory) {
                        Button(
                            onClick = {
                                val target = item
                                selectedItemForAction = null
                                isLoading = true
                                statusMessage = "Baixando ${target.name}..."
                                scope.launch {
                                    val res = SmbClientManager.downloadFileToPhone(context, currentConfig(), target)
                                    isLoading = false
                                    res.onSuccess { savedPath ->
                                        statusMessage = "Salvo no celular em: $savedPath"
                                        Toast.makeText(context, "Baixado em $savedPath", Toast.LENGTH_LONG).show()
                                    }.onFailure { err ->
                                        statusMessage = "Erro no download: ${err.message}"
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("BAIXAR PARA O CELULAR")
                        }
                    }

                    OutlinedButton(
                        onClick = {
                            renameText = item.name
                            showRenameDialog = true
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("RENOMEAR")
                    }

                    Button(
                        onClick = {
                            val target = item
                            selectedItemForAction = null
                            isLoading = true
                            scope.launch {
                                val res = SmbClientManager.deleteItem(currentConfig(), target)
                                res.onSuccess {
                                    Toast.makeText(context, "Excluído com sucesso!", Toast.LENGTH_SHORT).show()
                                    loadFolder(currentPath)
                                }.onFailure { err ->
                                    isLoading = false
                                    statusMessage = "Erro ao excluir: ${err.message}"
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("EXCLUIR DO HD EXTERNO")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedItemForAction = null }) {
                    Text("Fechar")
                }
            }
        )
    }

    if (showRenameDialog && selectedItemForAction != null) {
        val itemToRename = selectedItemForAction!!
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Renomear ${itemToRename.name}") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text("Novo nome") },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val newNameVal = renameText
                        showRenameDialog = false
                        selectedItemForAction = null
                        isLoading = true
                        scope.launch {
                            val res = SmbClientManager.renameItem(currentConfig(), itemToRename, newNameVal)
                            res.onSuccess {
                                Toast.makeText(context, "Renomeado com sucesso!", Toast.LENGTH_SHORT).show()
                                loadFolder(currentPath)
                            }.onFailure { err ->
                                isLoading = false
                                statusMessage = "Erro ao renomear: ${err.message}"
                            }
                        }
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
