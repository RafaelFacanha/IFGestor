package com.ifgestor.app

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Environment
import java.io.File

class SettingsManager(context: Context) {

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        const val PREFS_NAME = "ifgestor_secure_prefs"
        const val SANDBOX_RELATIVE_PATH = "DCIM/Camera/IFgestor"

        private const val KEY_PREFIX = "file_prefix"
        private const val KEY_SUBFOLDER = "target_subfolder"
        private const val KEY_DEVICE_NICKNAME = "device_nickname"
        private const val KEY_MDNS_HOSTNAME = "mdns_hostname"
        private const val KEY_SERVER_PORT = "server_port"
        private const val KEY_DARK_MODE = "dark_mode"
        private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        private const val KEY_FLOATING_WIDGET = "floating_widget_enabled"

        // Estados independentes dos dois motores (Item 12)
        private const val KEY_INTERCEPTOR_ACTIVE = "engine_interceptor_active"
        private const val KEY_WEBSERVER_ACTIVE = "engine_webserver_active"

        // Contador de fotos da sessão atual (Item 13)
        private const val KEY_SESSION_PHOTO_COUNT = "session_photo_count"

        // Chaves de credenciais PBKDF2 + Salt (Item 1)
        const val KEY_PIN_HASH = "pin_pbkdf2_hash"
        const val KEY_PIN_SALT = "pin_pbkdf2_salt"
        const val KEY_MUST_CHANGE_DEFAULT_PIN = "must_change_default_pin"
    }

    init {
        ensureSandboxStructure()
    }

    /**
     * Garante que a raiz do Sandbox (DCIM/Camera/IFgestor) e a subpasta ativa existam.
     */
    fun ensureSandboxStructure(): File {
        val dcimDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
        val sandboxRoot = File(dcimDir, "Camera/IFgestor")
        if (!sandboxRoot.exists()) {
            sandboxRoot.mkdirs()
        }
        val currentSub = getTargetSubfolder()
        if (currentSub.isNotBlank()) {
            val subDir = File(sandboxRoot, sanitizeFolderName(currentSub))
            if (!subDir.exists()) {
                subDir.mkdirs()
            }
        }
        return sandboxRoot
    }

    /**
     * Retorna o diretório raiz exclusivo do Sandbox: /storage/emulated/0/DCIM/Camera/IFgestor (Item 3).
     */
    fun getSandboxRootDir(): File {
        val dcimDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
        val root = File(dcimDir, "Camera/IFgestor")
        if (!root.exists()) {
            root.mkdirs()
        }
        return root
    }

    /**
     * Verifica por CanonicalPath se o arquivo ou pasta está estritamente dentro de DCIM/Camera/IFgestor (Item 3).
     * Bloqueia ataques de Path Traversal (ex: ../../).
     */
    fun isInsideSandbox(file: File): Boolean {
        return try {
            val rootCanonical = getSandboxRootDir().canonicalPath
            val targetCanonical = file.canonicalPath
            targetCanonical == rootCanonical ||
                targetCanonical.startsWith(rootCanonical + File.separator)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Resolve uma subpasta dentro do Sandbox com validação anti-traversal.
     */
    fun resolveSafeSubfolder(relativePath: String?): File {
        val root = getSandboxRootDir()
        val cleaned = (relativePath ?: "")
            .replace("\\", "/")
            .split("/")
            .map { sanitizeFolderName(it) }
            .filter { it.isNotBlank() && it != "." && it != ".." }
            .joinToString(File.separator)

        if (cleaned.isBlank()) {
            return root
        }

        val candidate = File(root, cleaned)
        return if (isInsideSandbox(candidate)) {
            if (!candidate.exists()) candidate.mkdirs()
            candidate
        } else {
            root
        }
    }

    /**
     * Retorna o diretório completo onde as fotos interceptadas serão salvas no momento.
     */
    fun getActiveCaptureDirectory(): File {
        return resolveSafeSubfolder(getTargetSubfolder())
    }

    /**
     * Lista todas as subpastas existentes dentro de DCIM/Camera/IFgestor para o Dropdown (Item 13 e 15) e Web.
     */
    fun listSandboxSubfolders(): List<String> {
        val root = getSandboxRootDir()
        val result = mutableSetOf<String>()
        result.add("Geral")

        val current = getTargetSubfolder()
        if (current.isNotBlank()) {
            result.add(current)
        }

        root.listFiles()?.forEach { file ->
            if (file.isDirectory && !file.name.startsWith(".")) {
                result.add(file.name)
            }
        }
        return result.sorted()
    }

    /**
     * Cria uma nova subpasta dentro do Sandbox e retorna true se bem-sucedido.
     */
    fun createSubfolder(folderName: String): Boolean {
        val clean = sanitizeFolderName(folderName)
        if (clean.isBlank()) return false
        val dir = resolveSafeSubfolder(clean)
        return dir.exists() && dir.isDirectory
    }

    // =========================================================================
    // Prefixo e Subpasta Ativa
    // =========================================================================

    fun getFilePrefix(): String {
        return prefs.getString(KEY_PREFIX, "Vistoria_") ?: "Vistoria_"
    }

    fun setFilePrefix(prefix: String) {
        val sanitized = sanitizePrefix(prefix)
        prefs.edit().putString(KEY_PREFIX, sanitized).apply()
    }

    fun getTargetSubfolder(): String {
        return prefs.getString(KEY_SUBFOLDER, "Geral") ?: "Geral"
    }

    fun setTargetSubfolder(subfolder: String) {
        val clean = sanitizeFolderName(subfolder).ifBlank { "Geral" }
        prefs.edit().putString(KEY_SUBFOLDER, clean).apply()
        resolveSafeSubfolder(clean)
    }

    // =========================================================================
    // Contadores Duplos: Sessão e Pasta Atual (Item 13)
    // =========================================================================

    fun getSessionPhotoCount(): Int {
        return prefs.getInt(KEY_SESSION_PHOTO_COUNT, 0)
    }

    fun incrementSessionPhotoCount(): Int {
        val next = getSessionPhotoCount() + 1
        prefs.edit().putInt(KEY_SESSION_PHOTO_COUNT, next).apply()
        return next
    }

    fun resetSessionPhotoCount() {
        prefs.edit().putInt(KEY_SESSION_PHOTO_COUNT, 0).apply()
    }

    /**
     * Conta quantos arquivos de mídia válidos (excluindo temporários .part) existem na subpasta ativa.
     */
    fun getActiveFolderFileCount(): Int {
        val dir = getActiveCaptureDirectory()
        val files = dir.listFiles() ?: return 0
        return files.count { file ->
            file.isFile && !file.name.startsWith(".") && !file.name.endsWith(".part")
        }
    }

    // =========================================================================
    // Motores Independentes: Interceptador vs Servidor Web (Item 12)
    // =========================================================================

    fun isInterceptorActive(): Boolean {
        return prefs.getBoolean(KEY_INTERCEPTOR_ACTIVE, false)
    }

    fun setInterceptorActive(active: Boolean) {
        prefs.edit().putBoolean(KEY_INTERCEPTOR_ACTIVE, active).apply()
    }

    fun isWebServerActive(): Boolean {
        return prefs.getBoolean(KEY_WEBSERVER_ACTIVE, false)
    }

    fun setWebServerActive(active: Boolean) {
        prefs.edit().putBoolean(KEY_WEBSERVER_ACTIVE, active).apply()
    }

    fun isFloatingWidgetEnabled(): Boolean {
        return prefs.getBoolean(KEY_FLOATING_WIDGET, true)
    }

    fun setFloatingWidgetEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_FLOATING_WIDGET, enabled).apply()
    }

    // =========================================================================
    // Rede mDNS, Apelido do Dispositivo e Porta (Itens 6 e 7)
    // =========================================================================

    fun getDeviceNickname(): String {
        val defaultName = (Build.MODEL ?: "Android").replace(Regex("[^a-zA-Z0-9_-]"), "-")
        return prefs.getString(KEY_DEVICE_NICKNAME, defaultName) ?: defaultName
    }

    fun setDeviceNickname(nickname: String) {
        val clean = nickname.trim().replace(Regex("[<>\"'`]"), "").ifBlank { "IFGestor-Mobile" }
        prefs.edit().putString(KEY_DEVICE_NICKNAME, clean).apply()
    }

    fun getActiveMdnsHostname(): String {
        return prefs.getString(KEY_MDNS_HOSTNAME, "ifgestor.local") ?: "ifgestor.local"
    }

    fun setActiveMdnsHostname(hostname: String) {
        prefs.edit().putString(KEY_MDNS_HOSTNAME, hostname).apply()
    }

    fun getServerPort(): Int {
        return prefs.getInt(KEY_SERVER_PORT, 8080)
    }

    fun setServerPort(port: Int) {
        val validPort = if (port in 1024..65535) port else 8080
        prefs.edit().putInt(KEY_SERVER_PORT, validPort).apply()
    }

    // =========================================================================
    // Preferências Visuais e Energia
    // =========================================================================

    fun isDarkMode(): Boolean {
        return prefs.getBoolean(KEY_DARK_MODE, true)
    }

    fun setDarkMode(dark: Boolean) {
        prefs.edit().putBoolean(KEY_DARK_MODE, dark).apply()
    }

    fun isKeepScreenOn(): Boolean {
        return prefs.getBoolean(KEY_KEEP_SCREEN_ON, false)
    }

    fun setKeepScreenOn(keepOn: Boolean) {
        prefs.edit().putBoolean(KEY_KEEP_SCREEN_ON, keepOn).apply()
    }

    // =========================================================================
    // Sanitização contra Injeção e Nomes Inválidos
    // =========================================================================

    fun sanitizePrefix(input: String): String {
        val cleaned = input.trim().replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
        if (cleaned.isBlank()) return "IMG_"
        return if (cleaned.endsWith("_") || cleaned.endsWith("-")) cleaned else "${cleaned}_"
    }

    fun sanitizeFolderName(input: String): String {
        return input.trim()
            .replace("..", "")
            .replace("/", "")
            .replace("\\", "")
            .replace(Regex("[^a-zA-Z0-9_\\- ]"), "")
            .trim()
    }

    fun getRawPreferences(): SharedPreferences = prefs
}
