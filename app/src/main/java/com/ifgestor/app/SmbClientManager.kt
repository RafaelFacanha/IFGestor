package com.ifgestor.app

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.EnumSet
import java.util.concurrent.TimeUnit

data class SmbConnectionConfig(
    val host: String,
    val port: Int = 445,
    val shareName: String,
    val username: String,
    val password: String
)

data class SmbFileItem(
    val name: String,
    val relativePath: String,
    val isDirectory: Boolean,
    val sizeBytes: Long
)

object SmbClientManager {

    private fun buildClient(): SMBClient {
        val config = SmbConfig.builder()
            .withTimeout(10, TimeUnit.SECONDS)
            .withSoTimeout(15, TimeUnit.SECONDS)
            .build()
        return SMBClient(config)
    }

    private fun toSmbPath(path: String): String {
        return path.trim()
            .replace("/", "\\")
            .trimStart('\\')
            .trimEnd('\\')
    }

    private inline fun <T> executeOnShare(
        config: SmbConnectionConfig,
        block: (DiskShare) -> T
    ): Result<T> {
        if (config.host.isBlank() || config.shareName.isBlank()) {
            return Result.failure(IllegalArgumentException("Preencha o IP do PC e o Nome da Partilha."))
        }

        return try {
            buildClient().use { client ->
                val connection = if (config.port == 445) {
                    client.connect(config.host.trim())
                } else {
                    client.connect(config.host.trim(), config.port)
                }

                connection.use { conn ->
                    val auth = if (config.username.isBlank()) {
                        AuthenticationContext.anonymous()
                    } else {
                        AuthenticationContext(
                            config.username.trim(),
                            config.password.toCharArray(),
                            null
                        )
                    }

                    conn.authenticate(auth).use { session ->
                        val share = session.connectShare(config.shareName.trim()) as? DiskShare
                            ?: throw IllegalStateException("Partilha '${config.shareName}' não encontrada.")
                        share.use { diskShare ->
                            Result.success(block(diskShare))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun listDirectory(
        config: SmbConnectionConfig,
        relativePath: String
    ): Result<List<SmbFileItem>> = withContext(Dispatchers.IO) {
        executeOnShare(config) { share ->
            val smbFolder = toSmbPath(relativePath)
            val entries = share.list(smbFolder)
            val items = mutableListOf<SmbFileItem>()

            for (entry in entries) {
                val name = entry.fileName
                if (name == "." || name == "..") continue

                val isDir = (entry.fileAttributes and 0x10L) != 0L
                val itemPath = if (smbFolder.isEmpty()) name else "$smbFolder\\$name"

                items.add(
                    SmbFileItem(
                        name = name,
                        relativePath = itemPath,
                        isDirectory = isDir,
                        sizeBytes = entry.endOfFile
                    )
                )
            }

            items.sortedWith(compareByDescending<SmbFileItem> { it.isDirectory }.thenBy { it.name.lowercase() })
        }
    }

    suspend fun createFolder(
        config: SmbConnectionConfig,
        parentRelativePath: String,
        newFolderName: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        executeOnShare(config) { share ->
            val sanitizedName = newFolderName.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
            require(sanitizedName.isNotEmpty()) { "Nome de pasta inválido." }

            val parentSmb = toSmbPath(parentRelativePath)
            val targetSmb = if (parentSmb.isEmpty()) sanitizedName else "$parentSmb\\$sanitizedName"

            if (!share.folderExists(targetSmb)) {
                share.mkdir(targetSmb)
            }
        }
    }

    suspend fun renameItem(
        config: SmbConnectionConfig,
        item: SmbFileItem,
        newName: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        executeOnShare(config) { share ->
            val sanitizedName = newName.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
            require(sanitizedName.isNotEmpty()) { "Novo nome inválido." }

            val oldSmbPath = toSmbPath(item.relativePath)
            val parentSmb = oldSmbPath.substringBeforeLast('\\', "")
            val newSmbPath = if (parentSmb.isEmpty()) sanitizedName else "$parentSmb\\$sanitizedName"

            val entry = share.open(
                oldSmbPath,
                EnumSet.of(AccessMask.DELETE, AccessMask.GENERIC_WRITE),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                null
            )
            entry.use { diskEntry ->
                diskEntry.rename(newSmbPath, false)
            }
        }
    }

    suspend fun deleteItem(
        config: SmbConnectionConfig,
        item: SmbFileItem
    ): Result<Unit> = withContext(Dispatchers.IO) {
        executeOnShare(config) { share ->
            val targetSmb = toSmbPath(item.relativePath)
            if (item.isDirectory) {
                share.rmdir(targetSmb, true)
            } else {
                share.rm(targetSmb)
            }
        }
    }

    suspend fun downloadFileToPhone(
        context: Context,
        config: SmbConnectionConfig,
        item: SmbFileItem
    ): Result<String> = withContext(Dispatchers.IO) {
        executeOnShare(config) { share ->
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val targetDir = File(downloadsDir, "IFGestor")
            if (!targetDir.exists()) {
                targetDir.mkdirs()
            }

            val destFile = File(targetDir, item.name)
            val remoteFile = share.openFile(
                toSmbPath(item.relativePath),
                EnumSet.of(AccessMask.GENERIC_READ),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                null
            )

            remoteFile.use { smbFile ->
                smbFile.inputStream.use { input ->
                    destFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }

            MediaScannerConnection.scanFile(
                context,
                arrayOf(destFile.absolutePath),
                null,
                null
            )

            "Download/IFGestor/${item.name}"
        }
    }

    suspend fun uploadLocalFile(
        config: SmbConnectionConfig,
        localFile: File,
        remoteFolderPath: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        executeOnShare<Unit>(config) { share ->
            val folderSmb = toSmbPath(remoteFolderPath)
            if (folderSmb.isNotEmpty()) {
                ensureRemoteDirectories(share, folderSmb)
            }

            val targetSmbFile = if (folderSmb.isEmpty()) {
                localFile.name
            } else {
                "$folderSmb\\${localFile.name}"
            }

            val remoteFile = share.openFile(
                targetSmbFile,
                EnumSet.of(AccessMask.GENERIC_WRITE),
                EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OVERWRITE_IF,
                EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE)
            )

            remoteFile.use { smbFile ->
                localFile.inputStream().use { input ->
                    smbFile.outputStream.use { output ->
                        input.copyTo(output)
                    }
                }
            }
            Unit
        }
    }

    private fun ensureRemoteDirectories(share: DiskShare, folderSmb: String) {
        val parts = folderSmb.split("\\").filter { it.isNotEmpty() }
        var current = ""
        for (part in parts) {
            current = if (current.isEmpty()) part else "$current\\$part"
            if (!share.folderExists(current)) {
                share.mkdir(current)
            }
        }
    }
}
