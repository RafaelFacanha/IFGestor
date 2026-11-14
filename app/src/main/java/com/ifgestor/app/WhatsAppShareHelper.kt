package com.ifgestor.app

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.util.ArrayList
import java.util.Locale

object WhatsAppShareHelper {

    private const val WHATSAPP_PACKAGE = "com.whatsapp"
    private const val WHATSAPP_BUSINESS_PACKAGE = "com.whatsapp.w4b"

    /**
     * Compartilha 1 ou mais arquivos do Sandbox diretamente no WhatsApp (ou WhatsApp Business),
     * utilizando FileProvider seguro e verificando travas de File Lock.
     */
    fun shareFilesToWhatsApp(
        context: Context,
        files: List<File>,
        captionText: String? = null,
        actor: String = "Mobile Local"
    ): Result<Int> {
        val appContext = context.applicationContext
        val settings = SettingsManager(appContext)
        val security = SecurityManager.getInstance(appContext)
        val taskManager = FileLockAndTaskManager.getInstance(appContext)
        val audit = AuditLogManager.getInstance(appContext)

        val validFiles = files.filter { file ->
            file.exists() &&
                file.isFile &&
                !file.name.endsWith(".part") &&
                settings.isInsideSandbox(file) &&
                !taskManager.isFileLocked(file)
        }

        if (validFiles.isEmpty()) {
            return Result.failure(
                IllegalArgumentException("Nenhum arquivo disponível ou arquivos estão em transferência ativa.")
            )
        }

        return try {
            val authority = "${appContext.packageName}.fileprovider"
            val uris = ArrayList<Uri>(validFiles.size)

            for (file in validFiles) {
                security.assertInsideSandbox(file)
                val uri = FileProvider.getUriForFile(appContext, authority, file)
                uris.add(uri)
            }

            val mimeType = resolveCommonMimeType(validFiles)
            val targetPackage = resolveInstalledWhatsAppPackage(appContext)

            val shareIntent = if (uris.size == 1) {
                Intent(Intent.ACTION_SEND).apply {
                    type = mimeType
                    putExtra(Intent.EXTRA_STREAM, uris[0])
                    if (!captionText.isNullOrBlank()) {
                        putExtra(Intent.EXTRA_TEXT, captionText)
                    }
                    clipData = ClipData.newRawUri("IFGestor Media", uris[0])
                }
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = mimeType
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                    if (!captionText.isNullOrBlank()) {
                        putExtra(Intent.EXTRA_TEXT, captionText)
                    }
                    val clip = ClipData.newRawUri("IFGestor Media", uris[0])
                    for (i in 1 until uris.size) {
                        clip.addItem(ClipData.Item(uris[i]))
                    }
                    clipData = clip
                }
            }

            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

            val finalIntent = if (targetPackage != null) {
                shareIntent.setPackage(targetPackage)
                shareIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                shareIntent
            } else {
                Intent.createChooser(shareIntent, "Compartilhar via WhatsApp").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }

            context.startActivity(finalIntent)

            audit.logEvent(
                action = "WHATSAPP_COMPARTILHADO",
                actor = actor,
                details = "${validFiles.size} arquivo(s) enviado(s) ao WhatsApp (${targetPackage ?: "Seletor"})",
                success = true
            )

            Result.success(validFiles.size)
        } catch (e: Exception) {
            audit.logEvent(
                action = "WHATSAPP_ERRO",
                actor = actor,
                details = "Falha ao abrir compartilhamento: ${e.message}",
                success = false
            )
            try {
                Toast.makeText(
                    appContext,
                    "Erro ao compartilhar no WhatsApp: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            } catch (_: Exception) {
            }
            Result.failure(e)
        }
    }

    /**
     * Detecta se o WhatsApp padrão ou o WhatsApp Business está instalado no aparelho.
     */
    private fun resolveInstalledWhatsAppPackage(context: Context): String? {
        val pm = context.packageManager
        if (isPackageInstalled(pm, WHATSAPP_PACKAGE)) return WHATSAPP_PACKAGE
        if (isPackageInstalled(pm, WHATSAPP_BUSINESS_PACKAGE)) return WHATSAPP_BUSINESS_PACKAGE
        return null
    }

    private fun isPackageInstalled(pm: PackageManager, packageName: String): Boolean {
        return try {
            pm.getPackageInfo(packageName, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun resolveCommonMimeType(files: List<File>): String {
        var hasImage = false
        var hasVideo = false

        for (file in files) {
            val ext = file.extension.lowercase(Locale.ROOT)
            when (ext) {
                "jpg", "jpeg", "png", "webp", "gif", "heic" -> hasImage = true
                "mp4", "mkv", "mov", "3gp", "webm" -> hasVideo = true
                else -> {
                    val guessed = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
                    if (guessed.startsWith("image/")) hasImage = true
                    else if (guessed.startsWith("video/")) hasVideo = true
                }
            }
        }

        return when {
            hasImage && !hasVideo -> "image/*"
            !hasImage && hasVideo -> "video/*"
            else -> "*/*"
        }
    }
}
