package com.example.engine

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import com.example.model.MoveLogEntry
import com.example.model.VaultFolderInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object MediaMoverEngine {

    private const val TAG = "MediaMoverEngine"

    private val MEDIA_EXTENSIONS = setOf(
        "jpg", "jpeg", "png", "heic", "heif", "webp", "dng", "raw",
        "mp4", "mov", "3gp", "mkv"
    )

    fun isMediaFile(file: File): Boolean {
        if (file.isDirectory || file.name.startsWith(".")) return false
        val extension = file.extension.lowercase()
        return MEDIA_EXTENSIONS.contains(extension)
    }

    /**
     * Resolves absolute File path for a relative folder like "DCIM/Camera".
     */
    fun resolveFolderPath(folderSpec: String): File {
        return if (folderSpec.startsWith("/")) {
            File(folderSpec)
        } else {
            val root = Environment.getExternalStorageDirectory() ?: File("/storage/emulated/0")
            File(root, folderSpec)
        }
    }

    /**
     * Maps a source media file or folder to its dedicated vault subfolder.
     * e.g.:
     *   DCIM/Camera -> _PhotoVault_Local/Vault_Camera/
     *   Pictures/Screenshots -> _PhotoVault_Local/Vault_Screenshots/
     *   Download -> _PhotoVault_Local/Vault_Downloads/
     *   WhatsApp -> _PhotoVault_Local/Vault_WhatsApp/
     *   Custom -> _PhotoVault_Local/Vault_[OriginalName]/
     */
    fun resolveVaultSubfolder(baseTargetDir: File, sourceFileOrDir: File): File {
        val path = sourceFileOrDir.absolutePath.replace('\\', '/')
        val subfolderName = when {
            path.contains("DCIM/Camera", ignoreCase = true) || path.contains("/Camera", ignoreCase = true) -> "Vault_Camera"
            path.contains("Screenshots", ignoreCase = true) -> "Vault_Screenshots"
            path.contains("/Download", ignoreCase = true) -> "Vault_Downloads"
            path.contains("WhatsApp", ignoreCase = true) -> "Vault_WhatsApp"
            else -> {
                val parentName = if (sourceFileOrDir.isFile) sourceFileOrDir.parentFile?.name else sourceFileOrDir.name
                val clean = parentName?.replace(Regex("[^a-zA-Z0-9_]"), "_") ?: "Misc"
                "Vault_$clean"
            }
        }
        return File(baseTargetDir, subfolderName)
    }

    /**
     * Resolves the original folder to restore a vaulted file back to.
     */
    fun resolveOriginalFolder(vaultFile: File): File {
        val parentName = vaultFile.parentFile?.name ?: ""
        val root = Environment.getExternalStorageDirectory() ?: File("/storage/emulated/0")
        return when {
            parentName.contains("Camera", ignoreCase = true) -> File(root, "DCIM/Camera")
            parentName.contains("Screenshots", ignoreCase = true) -> File(root, "Pictures/Screenshots")
            parentName.contains("Download", ignoreCase = true) -> File(root, "Download")
            parentName.contains("WhatsApp", ignoreCase = true) -> File(root, "Pictures/WhatsApp")
            else -> File(root, "DCIM/Camera")
        }
    }

    /**
     * Ensures target directory exists and manages root .nomedia file
     * so that media scanners ignore all sub-vaults at once.
     */
    fun prepareTargetDirectory(baseTargetDir: File, hideWithNoMedia: Boolean): Boolean {
        if (!baseTargetDir.exists()) {
            val created = baseTargetDir.mkdirs()
            if (!created && !baseTargetDir.exists()) {
                Log.e(TAG, "Failed to create target directory: ${baseTargetDir.absolutePath}")
                return false
            }
        }

        val noMediaFile = File(baseTargetDir, ".nomedia")
        if (hideWithNoMedia) {
            if (!noMediaFile.exists()) {
                try {
                    noMediaFile.createNewFile()
                    Log.d(TAG, "Created root .nomedia in ${baseTargetDir.absolutePath}")
                } catch (e: IOException) {
                    Log.e(TAG, "Failed creating .nomedia: ${e.message}")
                }
            }
        } else {
            if (noMediaFile.exists()) {
                noMediaFile.delete()
                Log.d(TAG, "Removed .nomedia from ${baseTargetDir.absolutePath}")
            }
        }
        return true
    }

    /**
     * Safely moves a media file from source to target with subfolder segregation,
     * integrity verification, EXIF timestamp preservation, and MediaStore cleanup
     * to prevent Google Photos from syncing it.
     */
    suspend fun moveMediaFile(
        context: Context,
        sourceFile: File,
        baseTargetDir: File,
        hideWithNoMedia: Boolean,
        waitIfWriting: Boolean = true
    ): MoveLogEntry = withContext(Dispatchers.IO) {
        val fileName = sourceFile.name
        val sourcePath = sourceFile.absolutePath

        if (!sourceFile.exists()) {
            return@withContext MoveLogEntry(
                fileName = fileName,
                sourcePath = sourcePath,
                destinationPath = baseTargetDir.absolutePath,
                fileSizeBytes = 0L,
                success = false,
                errorMessage = "Source file does not exist"
            )
        }

        // Wait for file writing stabilization if captured right away by Camera
        if (waitIfWriting) {
            stabilizeFile(sourceFile)
        }

        val originalSize = sourceFile.length()
        val originalTimestamp = sourceFile.lastModified()
        if (originalSize <= 0) {
            return@withContext MoveLogEntry(
                fileName = fileName,
                sourcePath = sourcePath,
                destinationPath = baseTargetDir.absolutePath,
                fileSizeBytes = 0L,
                success = false,
                errorMessage = "File is empty (0 bytes) or still locking"
            )
        }

        // Prepare base target directory and root .nomedia
        prepareTargetDirectory(baseTargetDir, hideWithNoMedia)

        // Resolve segregated subfolder (e.g. Vault_Camera, Vault_Screenshots)
        val targetSubfolder = resolveVaultSubfolder(baseTargetDir, sourceFile)
        if (!targetSubfolder.exists()) {
            targetSubfolder.mkdirs()
        }

        // Also ensure subfolder inherits .nomedia if enabled
        if (hideWithNoMedia) {
            val subNoMedia = File(targetSubfolder, ".nomedia")
            if (!subNoMedia.exists()) {
                try { subNoMedia.createNewFile() } catch (_: Exception) {}
            }
        }

        // Resolve destination filename without collisions
        val destFile = getNonCollidingDestFile(targetSubfolder, fileName)
        val destPath = destFile.absolutePath

        var moveSuccess = false
        var failureReason: String? = null

        try {
            // First attempt: atomic filesystem rename
            moveSuccess = sourceFile.renameTo(destFile)

            // Second attempt: stream copy with verification if rename returned false
            if (!moveSuccess) {
                moveSuccess = copyAndVerify(sourceFile, destFile, originalSize)
                if (moveSuccess) {
                    // Only delete source after byte-count verification succeeds
                    sourceFile.delete()
                } else {
                    failureReason = "Stream copy or integrity verification failed"
                }
            } else {
                // Rename succeeded; verify destination exists and size matches
                if (!destFile.exists() || destFile.length() != originalSize) {
                    moveSuccess = false
                    failureReason = "Renamed file length mismatch"
                }
            }

            if (moveSuccess) {
                // Preserve original timestamp / EXIF date
                destFile.setLastModified(originalTimestamp)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error moving file $sourcePath: ${e.message}", e)
            moveSuccess = false
            failureReason = e.message ?: "Unknown I/O error"
        }

        if (moveSuccess) {
            Log.i(TAG, "Successfully moved $fileName ($originalSize bytes) -> $destPath")

            // Remove source file from MediaStore so Google Photos cannot sync it!
            cleanupMediaStore(context, sourcePath)

            // If .nomedia is NOT used, scan new destination into MediaStore.
            // If .nomedia IS enabled, avoid scanning so it remains hidden.
            if (!hideWithNoMedia) {
                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(destPath),
                    null
                ) { path, uri ->
                    Log.d(TAG, "Scanned new destination $path -> $uri")
                }
            } else {
                // Ensure MediaStore unindexes if previously noticed
                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(sourcePath, File(baseTargetDir, ".nomedia").absolutePath),
                    null,
                    null
                )
            }

            MoveLogEntry(
                fileName = fileName,
                sourcePath = sourcePath,
                destinationPath = destPath,
                fileSizeBytes = originalSize,
                success = true
            )
        } else {
            MoveLogEntry(
                fileName = fileName,
                sourcePath = sourcePath,
                destinationPath = destPath,
                fileSizeBytes = originalSize,
                success = false,
                errorMessage = failureReason ?: "File move failed"
            )
        }
    }

    /**
     * Restores a single file from the vault back to its original location (e.g. DCIM/Camera)
     * and triggers immediate MediaStore re-indexing so Google Photos/Instagram detect it.
     */
    suspend fun restoreFile(
        context: Context,
        vaultFile: File,
        targetDirOverride: File? = null
    ): MoveLogEntry = withContext(Dispatchers.IO) {
        val fileName = vaultFile.name
        val vaultPath = vaultFile.absolutePath

        if (!vaultFile.exists()) {
            return@withContext MoveLogEntry(
                fileName = fileName,
                sourcePath = vaultPath,
                destinationPath = "",
                fileSizeBytes = 0L,
                success = false,
                errorMessage = "Vault file not found"
            )
        }

        val destFolder = targetDirOverride ?: resolveOriginalFolder(vaultFile)
        if (!destFolder.exists()) {
            destFolder.mkdirs()
        }

        val originalSize = vaultFile.length()
        val originalTimestamp = vaultFile.lastModified()
        val destFile = getNonCollidingDestFile(destFolder, fileName)

        var moveSuccess = vaultFile.renameTo(destFile)
        if (!moveSuccess) {
            moveSuccess = copyAndVerify(vaultFile, destFile, originalSize) && vaultFile.delete()
        }

        if (moveSuccess) {
            destFile.setLastModified(originalTimestamp)
            // Immediately re-index with MediaStore so standard gallery and apps detect it!
            MediaScannerConnection.scanFile(
                context,
                arrayOf(destFile.absolutePath, vaultPath),
                null
            ) { path, uri ->
                Log.d(TAG, "MediaScanner indexed restored file: $path -> $uri")
            }

            MoveLogEntry(
                fileName = fileName,
                sourcePath = vaultPath,
                destinationPath = destFile.absolutePath,
                fileSizeBytes = originalSize,
                success = true
            )
        } else {
            MoveLogEntry(
                fileName = fileName,
                sourcePath = vaultPath,
                destinationPath = destFile.absolutePath,
                fileSizeBytes = originalSize,
                success = false,
                errorMessage = "Failed to move file back to original location"
            )
        }
    }

    /**
     * Restores all media files inside a segregated vault subfolder back to their original folder.
     */
    suspend fun restoreSubfolder(
        context: Context,
        vaultSubfolder: File,
        onProgress: ((current: Int, total: Int, name: String) -> Unit)? = null
    ): List<MoveLogEntry> = withContext(Dispatchers.IO) {
        val files = vaultSubfolder.listFiles()?.filter { isMediaFile(it) } ?: emptyList()
        val results = mutableListOf<MoveLogEntry>()
        val total = files.size

        files.forEachIndexed { index, file ->
            onProgress?.invoke(index + 1, total, file.name)
            val log = restoreFile(context, file)
            results.add(log)
        }
        results
    }

    /**
     * Restores all files across all vault subfolders back to original camera / screenshot paths.
     */
    suspend fun restoreAllVaults(
        context: Context,
        baseTargetDir: File,
        onProgress: ((current: Int, total: Int, name: String) -> Unit)? = null
    ): List<MoveLogEntry> = withContext(Dispatchers.IO) {
        val allFiles = mutableListOf<File>()
        if (baseTargetDir.exists()) {
            baseTargetDir.walkTopDown().forEach { file ->
                if (isMediaFile(file)) {
                    allFiles.add(file)
                }
            }
        }
        val results = mutableListOf<MoveLogEntry>()
        val total = allFiles.size

        allFiles.forEachIndexed { index, file ->
            onProgress?.invoke(index + 1, total, file.name)
            val log = restoreFile(context, file)
            results.add(log)
        }
        results
    }

    /**
     * Gathers statistics and information for each segregated vault subfolder.
     */
    suspend fun getVaultFolderInfos(baseTargetDir: File): List<VaultFolderInfo> = withContext(Dispatchers.IO) {
        if (!baseTargetDir.exists() || !baseTargetDir.isDirectory) return@withContext emptyList()

        val subfolders = baseTargetDir.listFiles()?.filter { it.isDirectory } ?: emptyList<File>()
        val result = mutableListOf<VaultFolderInfo>()

        for (sub in subfolders) {
            val files = sub.listFiles()?.filter { isMediaFile(it) }?.sortedByDescending { it.lastModified() } ?: emptyList<File>()
            val totalSize = files.sumOf { it.length() }
            val displayName = when (sub.name) {
                "Vault_Camera" -> "Camera Vault"
                "Vault_Screenshots" -> "Screenshots Vault"
                "Vault_Downloads" -> "Downloads Vault"
                "Vault_WhatsApp" -> "WhatsApp Vault"
                else -> sub.name.replace("Vault_", "") + " Vault"
            }
            val originalSource = when (sub.name) {
                "Vault_Camera" -> "DCIM/Camera"
                "Vault_Screenshots" -> "Pictures/Screenshots"
                "Vault_Downloads" -> "Download"
                "Vault_WhatsApp" -> "WhatsApp Media"
                else -> sub.name.replace("Vault_", "")
            }

            result.add(
                VaultFolderInfo(
                    name = sub.name,
                    displayName = displayName,
                    folder = sub,
                    fileCount = files.size,
                    totalSizeBytes = totalSize,
                    originalSourceCategory = originalSource,
                    sampleFiles = files.take(10)
                )
            )
        }

        // Sort by folder name
        result.sortedBy { it.displayName }
    }

    /**
     * Calculates total files and total bytes saved across all vaults.
     */
    suspend fun getVaultTotalStats(baseTargetDir: File): Pair<Int, Long> = withContext(Dispatchers.IO) {
        if (!baseTargetDir.exists()) return@withContext Pair(0, 0L)
        var count = 0
        var totalBytes = 0L
        baseTargetDir.walkTopDown().forEach { file ->
            if (isMediaFile(file)) {
                count++
                totalBytes += file.length()
            }
        }
        Pair(count, totalBytes)
    }

    /**
     * Exports the vault into a ZIP archive saved in the device's public Download folder.
     */
    suspend fun exportVaultToZip(
        baseTargetDir: File,
        outputZipFile: File,
        onProgress: ((current: Int, total: Int, name: String) -> Unit)? = null
    ): Int = withContext(Dispatchers.IO) {
        if (!baseTargetDir.exists()) return@withContext 0

        val mediaFiles = mutableListOf<File>()
        baseTargetDir.walkTopDown().forEach {
            if (isMediaFile(it)) {
                mediaFiles.add(it)
            }
        }

        if (outputZipFile.parentFile?.exists() == false) {
            outputZipFile.parentFile?.mkdirs()
        }

        val total = mediaFiles.size
        ZipOutputStream(BufferedOutputStream(FileOutputStream(outputZipFile))).use { zos ->
            val buffer = ByteArray(64 * 1024)
            mediaFiles.forEachIndexed { index, file ->
                onProgress?.invoke(index + 1, total, file.name)
                val relativePath = file.relativeTo(baseTargetDir).path
                val zipEntry = ZipEntry(relativePath).apply {
                    time = file.lastModified()
                }
                zos.putNextEntry(zipEntry)
                FileInputStream(file).use { fis ->
                    var bytesRead: Int
                    while (fis.read(buffer).also { bytesRead = it } != -1) {
                        zos.write(buffer, 0, bytesRead)
                    }
                }
                zos.closeEntry()
            }
        }
        total
    }

    /**
     * Creates an Android Share Sheet Intent for manual on-demand cloud share without auto-sync.
     */
    fun createShareIntent(context: Context, files: List<File>): Intent {
        if (files.isEmpty()) return Intent()

        if (files.size == 1) {
            val file = files.first()
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val isVideo = file.extension.lowercase() in setOf("mp4", "mov", "3gp", "mkv")
            return Intent(Intent.ACTION_SEND).apply {
                type = if (isVideo) "video/*" else "image/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, "Shared from PhotoVault: ${file.name}")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } else {
            val uris = ArrayList<Uri>()
            for (f in files) {
                uris.add(FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f))
            }
            return Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "*/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                putExtra(Intent.EXTRA_TEXT, "Shared ${files.size} items from PhotoVault")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }

    /**
     * Waits for the camera app or screenshot service to finish writing the file.
     */
    private suspend fun stabilizeFile(file: File) {
        var previousLength = -1L
        for (i in 0 until 5) {
            if (!file.exists()) break
            val currentLength = file.length()
            if (currentLength > 0 && currentLength == previousLength) {
                break
            }
            previousLength = currentLength
            delay(250)
        }
    }

    /**
     * Copies source to dest using buffered streams and verifies size before deletion.
     */
    private fun copyAndVerify(source: File, dest: File, expectedSize: Long): Boolean {
        try {
            FileInputStream(source).use { input ->
                FileOutputStream(dest).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                    }
                    output.flush()
                }
            }
            return dest.exists() && dest.length() == expectedSize
        } catch (e: Exception) {
            Log.e(TAG, "Copy and verify failed: ${e.message}", e)
            if (dest.exists()) {
                dest.delete()
            }
            return false
        }
    }

    /**
     * Resolves unique destination file to prevent overwriting.
     */
    private fun getNonCollidingDestFile(targetDir: File, originalName: File): File {
        return getNonCollidingDestFile(targetDir, originalName.name)
    }

    private fun getNonCollidingDestFile(targetDir: File, name: String): File {
        var dest = File(targetDir, name)
        if (!dest.exists()) return dest

        val dotIndex = name.lastIndexOf('.')
        val baseName = if (dotIndex > 0) name.substring(0, dotIndex) else name
        val extension = if (dotIndex > 0) name.substring(dotIndex) else ""

        var counter = 1
        while (dest.exists()) {
            dest = File(targetDir, "${baseName}_$counter$extension")
            counter++
        }
        return dest
    }

    /**
     * Queries and deletes MediaStore entries matching the source file path
     * to immediately invalidate Google Photos cached items and prevent upload.
     */
    private fun cleanupMediaStore(context: Context, filePath: File) {
        cleanupMediaStore(context, filePath.absolutePath)
    }

    private fun cleanupMediaStore(context: Context, filePath: String) {
        val resolver: ContentResolver = context.contentResolver

        // MediaStore.Images
        deleteFromMediaUri(resolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, filePath)
        // MediaStore.Video
        deleteFromMediaUri(resolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, filePath)

        // Force media scanner to forget the old file path
        MediaScannerConnection.scanFile(
            context,
            arrayOf(filePath),
            null
        ) { path, uri ->
            Log.d(TAG, "Scan cleanup for $path: uri=$uri")
        }
    }

    private fun deleteFromMediaUri(resolver: ContentResolver, contentUri: Uri, filePath: String) {
        try {
            val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATA)
            val selection = "${MediaStore.MediaColumns.DATA} = ?"
            val selectionArgs = arrayOf(filePath)

            resolver.query(contentUri, projection, selection, selectionArgs, null)?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val itemUri = Uri.withAppendedPath(contentUri, id.toString())
                    try {
                        val rows = resolver.delete(itemUri, null, null)
                        Log.d(TAG, "Deleted MediaStore row $itemUri ($rows rows affected)")
                    } catch (e: Exception) {
                        Log.w(TAG, "MediaStore deletion failed for $itemUri: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error querying MediaStore for $filePath: ${e.message}")
        }
    }

    /**
     * Scans all watched source folders and moves any existing media files.
     */
    suspend fun runManualScanAndMove(
        context: Context,
        sourceFolders: List<File>,
        baseTargetDir: File,
        hideWithNoMedia: Boolean,
        onProgress: ((current: Int, total: Int, currentFile: String) -> Unit)? = null
    ): List<MoveLogEntry> = withContext(Dispatchers.IO) {
        val foundFiles = mutableListOf<File>()

        for (folder in sourceFolders) {
            if (folder.exists() && folder.isDirectory) {
                val files = folder.listFiles() ?: emptyArray()
                for (file in files) {
                    if (isMediaFile(file)) {
                        foundFiles.add(file)
                    }
                }
            }
        }

        val total = foundFiles.size
        val results = mutableListOf<MoveLogEntry>()

        foundFiles.forEachIndexed { index, file ->
            onProgress?.invoke(index + 1, total, file.name)
            val log = moveMediaFile(
                context = context,
                sourceFile = file,
                baseTargetDir = baseTargetDir,
                hideWithNoMedia = hideWithNoMedia,
                waitIfWriting = false
            )
            results.add(log)
        }

        results
    }
}
