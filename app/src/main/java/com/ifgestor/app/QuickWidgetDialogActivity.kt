package com.ifgestor.app

import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class QuickWidgetDialogActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))

        val initialMode = intent?.getStringExtra(EXTRA_DIALOG_MODE) ?: MODE_BOTH
        val settings = SettingsManager(applicationContext)
        val audit = AuditLogManager.getInstance(applicationContext)

        setContent {
            QuickDialogScreen(
                mode = initialMode,
                settings = settings,
                onApplyAndClose = { newSubfolder, newPrefix, launchCameraAfter ->
                    if (!newSubfolder.isNullOrBlank()) {
                        settings.createSubfolder(newSubfolder)
                        settings.setTargetSubfolder(newSubfolder)
                    }
                    if (!newPrefix.isNullOrBlank()) {
                        settings.setFilePrefix(newPrefix)
                    }

                    audit.logEvent(
                        action = "AJUSTE_RAPIDO_WIDGET",
                        actor = "Widget Android",
                        details = "Pasta: ${settings.getTargetSubfolder()} | Prefixo: ${settings.getFilePrefix()}",
                        success = true
                    )

                    syncWidgetsAndOverlay()

                    if (launchCameraAfter) {
                        if (!MediaInterceptorService.interceptorRunningFlow.value) {
                            MediaInterceptorService.sendAction(
                                applicationContext,
                                MediaInterceptorService.ACTION_START_INTERCEPTOR
                            )
                        }
                        openNativeCamera()
                    }
                    finish()
                },
                onDismiss = {
                    finish()
                }
            )
        }
    }

    private fun syncWidgetsAndOverlay() {
        IFGestorHomeWidgetProvider.updateAllWidgets(applicationContext)
        if (MediaInterceptorService.interceptorRunningFlow.value &&
            SettingsManager(applicationContext).isFloatingWidgetEnabled()
        ) {
            FloatingOverlayManager.getInstance(applicationContext).showOrUpdateOverlay()
        }
        if (MediaInterceptorService.interceptorRunningFlow.value ||
            MediaInterceptorService.webServerRunningFlow.value
        ) {
            MediaInterceptorService.sendAction(
                applicationContext,
                MediaInterceptorService.ACTION_REFRESH_UI
            )
        }
    }

    private fun openNativeCamera() {
        try {
            val camIntent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(camIntent)
        } catch (_: Exception) {
            try {
                val fallback = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(fallback)
            } catch (_: Exception) {
                Toast.makeText(
                    applicationContext,
                    "Não foi possível abrir a câmera.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    companion object {
        const val EXTRA_DIALOG_MODE = "extra_dialog_mode"
        const val MODE_FOLDER = "mode_folder"
        const val MODE_PREFIX = "mode_prefix"
        const val MODE_BOTH = "mode_both"
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuickDialogScreen(
    mode: String,
    settings: SettingsManager,
    onApplyAndClose: (subfolder: String?, prefix: String?, launchCamera: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedSubfolder by remember { mutableStateOf(settings.getTargetSubfolder()) }
    var newFolderInput by remember { mutableStateOf("") }
    var prefixInput by remember { mutableStateOf(settings.getFilePrefix()) }
    var sessionCount by remember { mutableIntStateOf(settings.getSessionPhotoCount()) }
    val subfolders = remember(selectedSubfolder) { settings.listSandboxSubfolders() }

    val showFolderSection = (mode == QuickWidgetDialogActivity.MODE_FOLDER || mode == QuickWidgetDialogActivity.MODE_BOTH)
    val showPrefixSection = (mode == QuickWidgetDialogActivity.MODE_PREFIX || mode == QuickWidgetDialogActivity.MODE_BOTH)

    val bgDark = Color(0xFF0F172A)
    val cardSurface = Color(0xFF1E293B)
    val borderBlue = Color(0xFF3B82F6)
    val accentSky = Color(0xFF38BDF8)
    val emerald = Color(0xFF10B981)
    val textPrimary = Color(0xFFF8FAFC)
    val textMuted = Color(0xFF94A3B8)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.62f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onDismiss() },
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { /* Impede fechamento ao tocar dentro do card */ }
                .border(1.5.dp, borderBlue, RoundedCornerShape(20.dp)),
            shape = RoundedCornerShape(20.dp),
            color = bgDark,
            tonalElevation = 12.dp
        ) {
            Column(
                modifier = Modifier
                    .padding(18.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // Cabeçalho do Modal Rápido
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = when (mode) {
                                QuickWidgetDialogActivity.MODE_FOLDER -> "📁 Troca Rápida de Pasta"
                                QuickWidgetDialogActivity.MODE_PREFIX -> "🏷️ Editar Prefixo de Fotos"
                                else -> "⚡ Ajuste Rápido IFGestor"
                            },
                            color = textPrimary,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Sandbox: DCIM/Camera/IFgestor",
                            color = textMuted,
                            fontSize = 11.5.sp
                        )
                    }

                    TextButton(
                        onClick = {
                            settings.resetSessionPhotoCount()
                            sessionCount = 0
                        }
                    ) {
                        Text(
                            text = "Sessão: $sessionCount (↺ Zerar)",
                            color = accentSky,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // SEÇÃO 1: Escolha ou Criação de Subpasta (Item 15)
                if (showFolderSection) {
                    Text(
                        text = "Toque em uma subpasta existente ou crie uma nova:",
                        color = textMuted,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 175.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(cardSurface)
                            .padding(10.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        subfolders.forEach { folder ->
                            val isSelected = folder.equals(selectedSubfolder, ignoreCase = true)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        if (isSelected) borderBlue.copy(alpha = 0.25f)
                                        else Color.Transparent
                                    )
                                    .border(
                                        width = 1.dp,
                                        color = if (isSelected) accentSky else Color(0xFF334155),
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                    .clickable {
                                        selectedSubfolder = folder
                                        newFolderInput = ""
                                        // Se abriu apenas para trocar de pasta, aplica com 1 toque
                                        if (mode == QuickWidgetDialogActivity.MODE_FOLDER) {
                                            onApplyAndClose(folder, null, false)
                                        }
                                    }
                                    .padding(horizontal = 12.dp, vertical = 9.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "📁 $folder",
                                    color = if (isSelected) accentSky else textPrimary,
                                    fontSize = 13.5.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                                if (isSelected) {
                                    Text(
                                        text = "ATIVA",
                                        color = emerald,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = newFolderInput,
                        onValueChange = { newFolderInput = it },
                        label = { Text("Ou digite o nome de uma nova subpasta", fontSize = 12.sp) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = textPrimary,
                            unfocusedTextColor = textPrimary,
                            focusedBorderColor = accentSky,
                            unfocusedBorderColor = Color(0xFF475569),
                            focusedLabelColor = accentSky,
                            unfocusedLabelColor = textMuted
                        )
                    )

                    Spacer(modifier = Modifier.height(14.dp))
                }

                // SEÇÃO 2: Edição de Prefixo + Atalhos Rápidos (Item 15)
                if (showPrefixSection) {
                    Text(
                        text = "Prefixo aplicado às próximas fotos e vídeos:",
                        color = textMuted,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    OutlinedTextField(
                        value = prefixInput,
                        onValueChange = { prefixInput = it },
                        label = { Text("Prefixo (ex: Vistoria_, Balsa_, Lote1_)", fontSize = 12.sp) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = textPrimary,
                            unfocusedTextColor = textPrimary,
                            focusedBorderColor = accentSky,
                            unfocusedBorderColor = Color(0xFF475569),
                            focusedLabelColor = accentSky,
                            unfocusedLabelColor = textMuted
                        )
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    val quickPresets = listOf("Vistoria_", "Comboio_", "Carga_", "Avaria_", "Relatorio_")
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        quickPresets.forEach { preset ->
                            val activePreset = prefixInput.equals(preset, ignoreCase = true)
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(99.dp))
                                    .background(if (activePreset) borderBlue else cardSurface)
                                    .border(1.dp, Color(0xFF475569), RoundedCornerShape(99.dp))
                                    .clickable { prefixInput = preset }
                                    .padding(horizontal = 10.dp, vertical = 5.dp)
                            ) {
                                Text(
                                    text = preset,
                                    color = textPrimary,
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                }

                // Botões de Ação Final
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = textMuted)
                    ) {
                        Text("Cancelar", fontSize = 12.5.sp)
                    }

                    Button(
                        onClick = {
                            val finalFolder = if (showFolderSection) {
                                newFolderInput.trim().ifBlank { selectedSubfolder }
                            } else null
                            val finalPrefix = if (showPrefixSection) prefixInput.trim() else null
                            onApplyAndClose(finalFolder, finalPrefix, false)
                        },
                        modifier = Modifier.weight(1.2f),
                        colors = ButtonDefaults.buttonColors(containerColor = borderBlue)
                    ) {
                        Text("Salvar", color = Color.White, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                Button(
                    onClick = {
                        val finalFolder = if (showFolderSection) {
                            newFolderInput.trim().ifBlank { selectedSubfolder }
                        } else null
                        val finalPrefix = if (showPrefixSection) prefixInput.trim() else null
                        onApplyAndClose(finalFolder, finalPrefix, true)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = emerald)
                ) {
                    Text(
                        text = "📸 Salvar e Abrir Câmera Agora",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
