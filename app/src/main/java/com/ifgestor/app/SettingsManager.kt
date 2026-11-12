package com.ifgestor.app

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Environment
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object SettingsManager {

    private const val PREFS_NAME = "ifgestor_secure_prefs"

    // Chaves de Motores Independentes (Item 12)
    private const val KEY_CAMERA_INTERCEPTOR_ACTIVE = "camera_interceptor_active"
    private const val KEY_WEB_SERVER_ACTIVE = "web_server_active"
    private const val KEY_FLOATING_WIDGET_ENABLED = "floating_widget_enabled"
    private const val KEY_VOLUME_SHUTTER_ENABLED = "volume_shutter_enabled"

    // Chaves de Pasta, Prefixo e Contadores (Itens 3, 8 e 13)
    private const val KEY_TARGET_SUBFOLDER = "target_subfolder"
    private const val KEY_FILE_PREFIX = "file_prefix"
    private const val KEY_SESSION_COUNT = "session_intercept_count"

    // Chaves de Seguranca PBKDF2 e Rede Local (Itens 1, 5 e 6)
    private const val KEY_PIN_HASH_PBKDF2 = "pin_hash_pbkdf2"
    private const val KEY_PIN_SALT = "pin_salt_hex"
    private const val KEY_FORCE_PIN_CHANGE = "force_pin_change"
    private const val KEY_SERVER_PORT = "server_port"
    private const val KEY_HTTPS_ENABLED = "https_enabled"
    private const val KEY_MDNS_ENABLED = "mdns_enabled"
    private const val KEY_DEVICE_ALIAS = "device_alias"
    private const val KEY_ACTIVE_MDNS_DOMAIN = "active_mdns_domain"

    private const val PBKDF2_ITERATIONS = 15000
    private const val PBKDF2_KEY_LENGTH = 256
    private const val DEFAULT_PIN = "1234"
    private const val AUDIT_LOG_FILE = "ifgestor_audit.log"
    private const val MAX_AUDIT_ENTRIES = 400

    // Controle de Concorrencia (File Locking) e Central de Tarefas Multitarefa (Itens 10 e 11)
    private val lockedCanonicalPaths = ConcurrentHashMap.newKeySet<String>()
    private val activeTasks = ConcurrentHashMap<String, ActiveTask>()
    private val auditLock = Any()

    data class AuditEntry(
        val timestamp: String,
        val ip: String,
        val category: String,
        val action: String,
        val details: String,
        val success: Boolean
    )

    data class ActiveTask(
        val id: String,
        val title: String,
        val subtitle: String,
        val type: String, // UPLOAD, DOWNLOAD, P2P_SEND, P2P_RECEIVE, MOVE, DELETE, WHATSAPP
        val currentItems: Int,
        val totalItems: Int,
        val bytesProcessed: Long,
        val totalBytes: Long,
        val progressPercent: Int,
        val status: String, // RUNNING, COMPLETED, FAILED
        val updatedAt: Long = System.currentTimeMillis()
    )

    private fun prefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // =========================================================================
    // 1. CRIPTOGRAFIA PBKDF2 COM SALT E TROCA OBRIGATORIA DO PIN 1234 (ITEM 1)
    // =========================================================================

    private fun ensurePinInitialized(context: Context) {
        val p = prefs(context)
        if (!p.contains(KEY_PIN_HASH_PBKDF2) || !p.contains(KEY_PIN_SALT)) {
            val salt = generateRandomSalt()
            val hash = hashPinPbkdf2(DEFAULT_PIN, salt)
            p.edit()
                .putString(KEY_PIN_SALT, bytesToHex(salt))
                .putString(KEY_PIN_HASH_PBKDF2, bytesToHex(hash))
                .putBoolean(KEY_FORCE_PIN_CHANGE, true)
                .apply()
        }
    }

    fun verifyPin(context: Context, inputPin: String): Boolean {
        ensurePinInitialized(context)
        val cleanPin = inputPin.trim()
        if (cleanPin.isEmpty()) return false

        val p = prefs(context)
        val saltHex = p.getString(KEY_PIN_SALT, null) ?: return false
        val expectedHashHex = p.getString(KEY_PIN_HASH_PBKDF2, null) ?: return false

        return try {
            val salt = hexToBytes(saltHex)
            val expectedHash = hexToBytes(expectedHashHex)
            val actualHash = hashPinPbkdf2(cleanPin, salt)
            MessageDigest.isEqual(expectedHash, actualHash)
        } catch (e: Exception) {
            false
        }
    }

    fun updatePin(context: Context, newPin: String): Boolean {
        val clean = newPin.trim()
        if (clean.length < 4 || clean.length > 8 || !clean.all { it.isDigit() }) {
            return false
        }
        val salt = generateRandomSalt()
        val hash = hashPinPbkdf2(clean, salt)
        val isStillDefault = (clean == DEFAULT_PIN)

        prefs(context).edit()
            .putString(KEY_PIN_SALT, bytesToHex(salt))
            .putString(KEY_PIN_HASH_PBKDF2, bytesToHex(hash))
            .putBoolean(KEY_FORCE_PIN_CHANGE, isStillDefault)
            .apply()

        appendAuditLog(
            context = context,
            ip = "LOCAL/APP",
            category = "SEGURANCA",
            action = "ALTERACAO_PIN",
            details = if (isStillDefault) "PIN redefinido para o padrao" else "PIN personalizado atualizado com PBKDF2",
            success = true
        )
        return true
    }

    fun setServerPin(context: Context, newPin: String) {
        updatePin(context, newPin)
    }

    fun isPinChangeRequired(context: Context): Boolean {
        ensurePinInitialized(context)
        return prefs(context).getBoolean(KEY_FORCE_PIN_CHANGE, true)
    }

    private fun generateRandomSalt(): ByteArray {
        val salt = ByteArray(16)
        SecureRandom().nextBytes(salt)
        return salt
    }

    private fun hashPinPbkdf2(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, PBKDF2_ITERATIONS, PBKDF2_KEY_LENGTH)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return factory.generateSecret(spec).encoded
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(String.format("%02x", b))
        }
        return sb.toString()
    }

    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    // =========================================================================
    // 2. SANDBOX ESTRITO EM DCIM/Camera/IFgestor (ITEM 3)
    // =========================================================================

    /**
     * Detecta automaticamente se o aparelho utiliza "DCIM/Câmera" ou "DCIM/Camera"
     * e garante o isolamento de seguranca dentro da subpasta "IFgestor".
     */
    fun getPhysicalCameraRootDir(): File {
        val dcim = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
        val cameraWithAccent = File(dcim, "Câmera")
        val cameraStandard = File(dcim, "Camera")
        return when {
            cameraWithAccent.exists() && cameraWithAccent.isDirectory -> cameraWithAccent
            else -> {
                if (!cameraStandard.exists()) cameraStandard.mkdirs()
                cameraStandard
            }
        }
    }

    fun getSandboxRootDir(): File {
        val sandbox = File(getPhysicalCameraRootDir(), "IFgestor")
        if (!sandbox.exists()) {
            sandbox.mkdirs()
        }
        return try {
            sandbox.canonicalFile
        } catch (e: Exception) {
            sandbox
        }
    }

    fun getSandboxDisplayPath(context: Context): String {
        val camName = getPhysicalCameraRootDir().name
        val sub = getTargetSubfolder(context)
        return if (sub.isEmpty()) {
            "DCIM/$camName/IFgestor"
        } else {
            "DCIM/$camName/IFgestor/$sub"
        }
    }

    fun isInsideSandbox(file: File): Boolean {
        return try {
            val rootPath = getSandboxRootDir().canonicalPath
            val targetPath = file.canonicalPath
            targetPath == rootPath || targetPath.startsWith("$rootPath${File.separator}")
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Resolve qualquer caminho relativo vindo da Web, Galeria ou P2P garantindo
     * que jamais saia de DCIM/Camera/IFgestor (Bloqueio Total de Path Traversal).
     */
    fun resolveSafeSandboxFile(relativePath: String?): File? {
        val root = getSandboxRootDir()
        val cleanRel = (relativePath ?: "")
            .trim()
            .replace('\\', '/')
            .trimStart('/')

        val candidate = if (cleanRel.isEmpty()) root else File(root, cleanRel)
        return try {
            val canonical = candidate.canonicalFile
            if (isInsideSandbox(canonical)) canonical else null
        } catch (e: Exception) {
            null
        }
    }

    fun sanitizeSubfolderName(raw: String): String {
        return raw.trim()
            .replace('\\', '/')
            .split('/')
            .map { segment ->
                segment.replace(Regex("[^a-zA-Z0-9_\\-\\. ]"), "_").trim()
            }
            .filter { it.isNotEmpty() && it != "." && it != ".." }
            .joinToString("/")
    }

    fun sanitizeFileName(raw: String): String {
        val clean = raw.trim().replace(Regex("[^a-zA-Z0-9_\\-\\. ]"), "_")
        return if (clean.isEmpty() || clean == "." || clean == "..") {
            "arquivo_${System.currentTimeMillis()}"
        } else {
            clean
        }
    }

    fun getTargetSubfolder(context: Context): String {
        val raw = prefs(context).getString(KEY_TARGET_SUBFOLDER, "") ?: ""
        return sanitizeSubfolderName(raw)
    }

    fun setTargetSubfolder(context: Context, subfolder: String) {
        val clean = sanitizeSubfolderName(subfolder)
        prefs(context).edit().putString(KEY_TARGET_SUBFOLDER, clean).apply()
        val dir = getTargetDirectory(context)
        if (!dir.exists()) dir.mkdirs()
        IFGestorHomeWidget.requestWidgetUpdate(context)
    }

    // Compatibilidade direta com chamadas de nome de pasta
    fun getCustomFolderName(context: Context): String = getTargetSubfolder(context)
    fun setCustomFolderName(context: Context, folderName: String) = setTargetSubfolder(context, folderName)

    fun getTargetDirectory(context: Context): File {
        val root = getSandboxRootDir()
        val sub = getTargetSubfolder(context)
        val dir = if (sub.isEmpty()) root else File(root, sub)
        if (!dir.exists()) dir.mkdirs()
        return try {
            val canonical = dir.canonicalFile
            if (isInsideSandbox(canonical)) canonical else root
        } catch (e: Exception) {
            root
        }
    }

    /**
     * Lista todas as subpastas dentro de DCIM/Camera/IFgestor para o Dropdown
     * do Widget Flutuante, Dialogo do Home Widget, Galeria e Servidor Web.
     */
    fun getSandboxSubfolders(context: Context): List<String> {
        val root = getSandboxRootDir()
        val result = mutableListOf("") // "" representa a pasta Raiz (IFgestor)
        val currentActive = getTargetSubfolder(context)

        fun scanRecursive(dir: File, prefix: String, depth: Int) {
            if (depth > 3) return
            val children = dir.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }
                ?.sortedBy { it.name.lowercase(Locale.getDefault()) }
                ?: return
            for (child in children) {
                val rel = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
                result.add(rel)
                scanRecursive(child, rel, depth + 1)
            }
        }

        scanRecursive(root, "", 1)
        if (currentActive.isNotEmpty() && !result.contains(currentActive)) {
            result.add(1, currentActive)
        }
        return result.distinct()
    }

    fun getFilePrefix(context: Context): String {
        val raw = prefs(context).getString(KEY_FILE_PREFIX, "IFG_") ?: "IFG_"
        return raw.replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
    }

    fun setFilePrefix(context: Context, prefix: String) {
        val clean = prefix.trim().replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
        prefs(context).edit().putString(KEY_FILE_PREFIX, clean).apply()
        IFGestorHomeWidget.requestWidgetUpdate(context)
    }

    // =========================================================================
    // 3. CONTADOR DUPLO: SESSAO ATUAL E TOTAL NA PASTA (ITENS 13 E 14)
    // =========================================================================

    fun getSessionInterceptCount(context: Context): Int {
        return prefs(context).getInt(KEY_SESSION_COUNT, 0)
    }

    fun incrementSessionInterceptCount(context: Context): Int {
        val next = getSessionInterceptCount(context) + 1
        prefs(context).edit().putInt(KEY_SESSION_COUNT, next).apply()
        IFGestorHomeWidget.requestWidgetUpdate(context)
        return next
    }

    fun resetSessionInterceptCount(context: Context) {
        prefs(context).edit().putInt(KEY_SESSION_COUNT, 0).apply()
        IFGestorHomeWidget.requestWidgetUpdate(context)
    }

    fun getFolderMediaCount(context: Context, subfolder: String = getTargetSubfolder(context)): Int {
        val dir = resolveSafeSandboxFile(subfolder) ?: getSandboxRootDir()
        if (!dir.exists() || !dir.isDirectory) return 0
        return dir.listFiles { f ->
            f.isFile && !f.name.startsWith(".") && !f.name.endsWith(".part") && isMediaFile(f.name)
        }?.size ?: 0
    }

    fun isMediaFile(fileName: String): Boolean {
        val lower = fileName.lowercase(Locale.getDefault())
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
            lower.endsWith(".png") || lower.endsWith(".webp") ||
            lower.endsWith(".mp4") || lower.endsWith(".mov") ||
            lower.endsWith(".mkv") || lower.endsWith(".3gp") ||
            lower.endsWith(".pdf") || lower.endsWith(".txt")
    }

    // =========================================================================
    // 4. MOTORES INDEPENDENTES E CONFIGURACOES DE REDE / mDNS (ITENS 6 E 12)
    // =========================================================================

    fun isCameraInterceptorEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_CAMERA_INTERCEPTOR_ACTIVE, false)
    }

    fun setCameraInterceptorEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_CAMERA_INTERCEPTOR_ACTIVE, enabled).apply()
        IFGestorHomeWidget.requestWidgetUpdate(context)
    }

    fun isWebServerEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_WEB_SERVER_ACTIVE, false)
    }

    fun setWebServerEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_WEB_SERVER_ACTIVE, enabled).apply()
        IFGestorHomeWidget.requestWidgetUpdate(context)
    }

    fun isFloatingWidgetEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_FLOATING_WIDGET_ENABLED, true)
    }

    fun setFloatingWidgetEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_FLOATING_WIDGET_ENABLED, enabled).apply()
    }

    fun isVolumeShutterEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_VOLUME_SHUTTER_ENABLED, true)
    }

    fun setVolumeShutterEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_VOLUME_SHUTTER_ENABLED, enabled).apply()
    }

    fun getServerPort(context: Context): Int {
        return prefs(context).getInt(KEY_SERVER_PORT, 8080).coerceIn(1025, 65534)
    }

    fun setServerPort(context: Context, port: Int) {
        prefs(context).edit().putInt(KEY_SERVER_PORT, port.coerceIn(1025, 65534)).apply()
    }

    fun isHttpsEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_HTTPS_ENABLED, true)
    }

    fun setHttpsEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_HTTPS_ENABLED, enabled).apply()
        IFGestorHomeWidget.requestWidgetUpdate(context)
    }

    fun isMdnsEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_MDNS_ENABLED, true)
    }

    fun setMdnsEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_MDNS_ENABLED, enabled).apply()
        IFGestorHomeWidget.requestWidgetUpdate(context)
    }

    fun getDeviceAlias(context: Context): String {
        val raw = prefs(context).getString(KEY_DEVICE_ALIAS, "ifgestor") ?: "ifgestor"
        val clean = raw.lowercase(Locale.getDefault())
            .replace(Regex("[^a-z0-9\\-]"), "")
            .trim('-')
        return if (clean.isEmpty()) "ifgestor" else clean
    }

    fun setDeviceAlias(context: Context, alias: String) {
        val clean = alias.lowercase(Locale.getDefault())
            .replace(Regex("[^a-z0-9\\-]"), "")
            .trim('-')
            .ifEmpty { "ifgestor" }
        prefs(context).edit().putString(KEY_DEVICE_ALIAS, clean).apply()
        IFGestorHomeWidget.requestWidgetUpdate(context)
    }

    fun getActiveMdnsDomain(context: Context): String {
        val saved = prefs(context).getString(KEY_ACTIVE_MDNS_DOMAIN, null)
        return if (!saved.isNullOrBlank()) saved else "${getDeviceAlias(context)}.local"
    }

    fun setActiveMdnsDomain(context: Context, domain: String) {
        prefs(context).edit().putString(KEY_ACTIVE_MDNS_DOMAIN, domain).apply()
        IFGestorHomeWidget.requestWidgetUpdate(context)
    }

    // =========================================================================
    // 5. TRILHA DE AUDITORIA (AUDIT LOG - COMPLIANCE & LGPD - ITEM 4)
    // =========================================================================

    fun appendAuditLog(
        context: Context,
        ip: String,
        category: String,
        action: String,
        details: String,
        success: Boolean = true
    ) {
        synchronized(auditLock) {
            try {
                val logFile = File(context.filesDir, AUDIT_LOG_FILE)
                val timestamp = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(Date())
                val item = JSONObject().apply {
                    put("timestamp", timestamp)
                    put("ip", ip.ifBlank { "LOCAL" })
                    put("category", category)
                    put("action", action)
                    put("details", details.take(300))
                    put("success", success)
                }

                val existingLines = if (logFile.exists()) {
                    logFile.readLines().toMutableList()
                } else {
                    mutableListOf()
                }
                existingLines.add(item.toString())
                val trimmed = if (existingLines.size > MAX_AUDIT_ENTRIES) {
                    existingLines.takeLast(MAX_AUDIT_ENTRIES)
                } else {
                    existingLines
                }
                logFile.writeText(trimmed.joinToString("\n"))
            } catch (_: Exception) {
            }
        }
    }

    fun getAuditLogs(context: Context): List<AuditEntry> {
        synchronized(auditLock) {
            val logFile = File(context.filesDir, AUDIT_LOG_FILE)
            if (!logFile.exists()) return emptyList()
            val list = mutableListOf<AuditEntry>()
            try {
                logFile.readLines().asReversed().forEach { line ->
                    if (line.isNotBlank()) {
                        val obj = JSONObject(line)
                        list.add(
                            AuditEntry(
                                timestamp = obj.optString("timestamp", "-"),
                                ip = obj.optString("ip", "-"),
                                category = obj.optString("category", "GERAL"),
                                action = obj.optString("action", "-"),
                                details = obj.optString("details", ""),
                                success = obj.optBoolean("success", true)
                            )
                        )
                    }
                }
            } catch (_: Exception) {
            }
            return list
        }
    }

    fun clearAuditLogs(context: Context) {
        synchronized(auditLock) {
            try {
                File(context.filesDir, AUDIT_LOG_FILE).delete()
                appendAuditLog(
                    context = context,
                    ip = "LOCAL/APP",
                    category = "AUDITORIA",
                    action = "LIMPEZA_LOGS",
                    details = "Historico de auditoria reiniciado pelo usuario no aparelho",
                    success = true
                )
            } catch (_: Exception) {
            }
        }
    }

    // =========================================================================
    // 6. BLOQUEIO DE ARQUIVOS EM TRANSITO (FILE LOCK) E DUPLICADOS (ITEM 11)
    // =========================================================================

    fun tryLockFile(file: File): Boolean {
        return try {
            lockedCanonicalPaths.add(file.canonicalPath)
        } catch (e: Exception) {
            false
        }
    }

    fun unlockFile(file: File) {
        try {
            lockedCanonicalPaths.remove(file.canonicalPath)
        } catch (_: Exception) {
        }
    }

    fun isFileLocked(file: File): Boolean {
        return try {
            lockedCanonicalPaths.contains(file.canonicalPath)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Caso ja exista um arquivo com o mesmo nome na pasta de destino,
     * gera automaticamente Foto_1.jpg, Foto_2.jpg, etc. sem travar lotes paralelos.
     */
    fun resolveUniqueFile(directory: File, desiredFileName: String): File {
        if (!directory.exists()) directory.mkdirs()
        val safeName = sanitizeFileName(desiredFileName)
        var candidate = File(directory, safeName)
        if (!candidate.exists() && !File(directory, "$safeName.part").exists()) {
            return candidate
        }

        val dotIndex = safeName.lastIndexOf('.')
        val baseName = if (dotIndex > 0) safeName.substring(0, dotIndex) else safeName
        val extension = if (dotIndex > 0) safeName.substring(dotIndex) else ""

        var counter = 1
        while (true) {
            val numberedName = "${baseName}_$counter$extension"
            candidate = File(directory, numberedName)
            if (!candidate.exists() && !File(directory, "$numberedName.part").exists()) {
                return candidate
            }
            counter++
        }
    }

    // =========================================================================
    // 7. CENTRAL DE TAREFAS MULTITAREFA (WEB & MOBILE - ITEM 10)
    // =========================================================================

    fun upsertTask(
        id: String,
        title: String,
        subtitle: String,
        type: String,
        currentItems: Int,
        totalItems: Int,
        bytesProcessed: Long = 0L,
        totalBytes: Long = 0L,
        status: String = "RUNNING"
    ): ActiveTask {
        val percent = when {
            status == "COMPLETED" -> 100
            totalBytes > 0L -> ((bytesProcessed * 100L) / totalBytes).toInt().coerceIn(0, 99)
            totalItems > 0 -> ((currentItems * 100) / totalItems).coerceIn(0, 99)
            else -> 0
        }
        val task = ActiveTask(
            id = id,
            title = title,
            subtitle = subtitle,
            type = type,
            currentItems = currentItems,
            totalItems = totalItems,
            bytesProcessed = bytesProcessed,
            totalBytes = totalBytes,
            progressPercent = percent,
            status = status,
            updatedAt = System.currentTimeMillis()
        )
        activeTasks[id] = task
        return task
    }

    fun getTasksSnapshot(): List<ActiveTask> {
        val now = System.currentTimeMillis()
        // Remove tarefas finalizadas ha mais de 3 minutos para manter a fila limpa
        activeTasks.entries.removeIf { (_, task) ->
            task.status != "RUNNING" && (now - task.updatedAt) > 180_000L
        }
        return activeTasks.values.sortedByDescending { it.updatedAt }
    }

    fun clearCompletedTasks() {
        activeTasks.entries.removeIf { (_, task) -> task.status != "RUNNING" }
    }

    fun getTasksJsonArray(): JSONArray {
        val arr = JSONArray()
        for (t in getTasksSnapshot()) {
            arr.put(
                JSONObject().apply {
                    put("id", t.id)
                    put("title", t.title)
                    put("subtitle", t.subtitle)
                    put("type", t.type)
                    put("currentItems", t.currentItems)
                    put("totalItems", t.totalItems)
                    put("bytesProcessed", t.bytesProcessed)
                    put("totalBytes", t.totalBytes)
                    put("progressPercent", t.progressPercent)
                    put("status", t.status)
                }
            )
        }
        return arr
    }

    // =========================================================================
    // 8. COMPARTILHAMENTO SEGURO VIA WHATSAPP / WHATSAPP BUSINESS (ITEM 16)
    // =========================================================================

    fun shareFilesToWhatsApp(
        context: Context,
        files: List<File>,
        specificPackage: String? = null
    ): Boolean {
        val validFiles = files.filter { it.exists() && it.isFile && isInsideSandbox(it) }
        if (validFiles.isEmpty()) return false

        return try {
            val authority = "${context.packageName}.fileprovider"
            val uris = ArrayList(validFiles.map { FileProvider.getUriForFile(context, authority, it) })

            val intent = if (uris.size == 1) {
                Intent(Intent.ACTION_SEND).apply {
                    type = getMimeType(validFiles.first())
                    putExtra(Intent.EXTRA_STREAM, uris.first())
                }
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "*/*"
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                }
            }

            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)

            // ClipData e exigido a partir do Android 11 para conceder permissao de leitura em lotes
            val clipData = ClipData.newUri(context.contentResolver, "IFGestor Media", uris.first())
            for (i in 1 until uris.size) {
                clipData.addItem(ClipData.Item(uris[i]))
            }
            intent.clipData = clipData

            val pm = context.packageManager
            val targetPkg = when {
                !specificPackage.isNullOrBlank() && isPackageInstalled(pm, specificPackage) -> specificPackage
                isPackageInstalled(pm, "com.whatsapp") -> "com.whatsapp"
                isPackageInstalled(pm, "com.whatsapp.w4b") -> "com.whatsapp.w4b"
                else -> null
            }

            if (targetPkg != null) {
                intent.setPackage(targetPkg)
                context.startActivity(intent)
            } else {
                val chooser = Intent.createChooser(intent, "Compartilhar arquivos via...").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(chooser)
            }

            appendAuditLog(
                context = context,
                ip = "LOCAL/WHATSAPP",
                category = "COMPARTILHAMENTO",
                action = "ENVIO_WHATSAPP",
                details = "Compartilhado(s) ${validFiles.size} arquivo(s) para ${targetPkg ?: "seletor nativo"}",
                success = true
            )
            true
        } catch (e: Exception) {
            appendAuditLog(
                context = context,
                ip = "LOCAL/WHATSAPP",
                category = "COMPARTILHAMENTO",
                action = "ERRO_WHATSAPP",
                details = "Falha ao compartilhar: ${e.message}",
                success = false
            )
            false
        }
    }

    private fun isPackageInstalled(pm: android.content.pm.PackageManager, pkg: String): Boolean {
        return try {
            pm.getPackageInfo(pkg, 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun getMimeType(file: File): String {
        val ext = file.extension.lowercase(Locale.getDefault())
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "mp4" -> "video/mp4"
            "mov" -> "video/quicktime"
            "pdf" -> "application/pdf"
            else -> "application/octet-stream"
        }
    }
}
