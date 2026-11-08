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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    IFGestorHomeScreen()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IFGestorHomeScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var prefixText by remember { mutableStateOf(SettingsManager.getPrefix(context)) }
    var localFolderText by remember { mutableStateOf(SettingsManager.getLocalFolder(context)) }
    var deleteLocalAfterSync by remember { mutableStateOf(SettingsManager.getDeleteLocalAfterSync(context)) }
    var hasAllFilesPermission by remember { mutableStateOf(checkStoragePermission()) }

    val isServiceRunning by SettingsManager.isServiceRunning.collectAsState()
    val interceptedCount by SettingsManager.interceptedCount.collectAsState()
    val lastFile by SettingsManager.lastInterceptedFile.collectAsState()

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { }

    // Atualiza o status da permissao sempre que o usuario volta das Configuracoes
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasAllFilesPermission = checkStoragePermission()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("IFGestor", fontWeight = FontWeight.Bold)
                        Text(
                            "Intercept Files Gestor",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Alerta de Permissao Especial (se ainda nao concedida)
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
                            "Para renomear e mover fotos da câmera nativa sem pop-ups, autorize o 'Acesso a todos os arquivos'.",
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

            // Card de Status em Tempo Real
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
                        text = "Arquivos processados nesta sessão: $interceptedCount",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "Último arquivo: $lastFile",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            // Card de Configuracao de Interceptacao
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        "Regras de Captura e Nomenclatura",
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
                        label = { Text("Pasta local em DCIM/ (Ex: IFGestor_Capturas)") },
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
                                "Apagar do celular após enviar ao PC",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                if (deleteLocalAfterSync) "Modo Transferência ativo"
                                else "Modo Espelhamento (mantém cópia local)",
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

            Spacer(modifier = Modifier.height(8.dp))

            // Botao Principal: Ativa Interceptador + Abre Camera Nativa
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
                Text(
                    "ATIVAR E ABRIR CÂMERA NATIVA",
                    fontWeight = FontWeight.Bold
                )
            }

            // Botao Secundario: Ligar ou Parar apenas o Servico
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
    launcher: androidx.activity.result.ManagedActivityResultLauncher<String, Boolean>
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
        // Abre a camera nativa completa (permite fotos continuas, video, zoom e HDR)
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
