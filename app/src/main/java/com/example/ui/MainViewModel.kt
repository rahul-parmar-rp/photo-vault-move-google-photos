package com.example.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppPreferences
import com.example.engine.MediaMoverEngine
import com.example.model.FolderConfig
import com.example.model.MoveLogEntry
import com.example.model.VaultFolderInfo
import com.example.service.MediaObserverService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class MainUiState(
    val isServiceEnabled: Boolean = false,
    val isServiceRunning: Boolean = false,
    val targetDirectory: String = AppPreferences.getDefaultTargetDirectory(),
    val monitoredFolders: List<FolderConfig> = emptyList(),
    val customFolders: List<String> = emptyList(),
    val hideFromGalleryNoMedia: Boolean = true,
    val notifyOnMove: Boolean = true,
    val recentLogs: List<MoveLogEntry> = emptyList(),
    val totalMovedCount: Int = 0,
    val hasManageAllFilesPermission: Boolean = false,
    val hasNotificationPermission: Boolean = false,
    val isBatteryOptimizationIgnored: Boolean = false,
    val isManualMoveInProgress: Boolean = false,
    val manualMoveProgressText: String? = null,
    val userNotice: String? = null,
    // Storage Insights & Vault Subfolder Segregation
    val vaultFolders: List<VaultFolderInfo> = emptyList(),
    val savedQuotaBytes: Long = 0L,
    val totalGuardedFiles: Int = 0,
    val isExportingZip: Boolean = false,
    val isRestoring: Boolean = false,
    val restoreProgressText: String? = null
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val preferences = AppPreferences(application)

    private val _manualMoveInProgress = MutableStateFlow(false)
    private val _manualMoveProgressText = MutableStateFlow<String?>(null)
    private val _userNotice = MutableStateFlow<String?>(null)

    private val _vaultFolders = MutableStateFlow<List<VaultFolderInfo>>(emptyList())
    private val _savedQuotaBytes = MutableStateFlow(0L)
    private val _totalGuardedFiles = MutableStateFlow(0)
    private val _isExportingZip = MutableStateFlow(false)
    private val _isRestoring = MutableStateFlow(false)
    private val _restoreProgressText = MutableStateFlow<String?>(null)

    private val _permissionState = MutableStateFlow(checkSystemPermissions())

    val uiState: StateFlow<MainUiState> = combine(
        combine(
            preferences.isServiceEnabledFlow,
            MediaObserverService.isRunning,
            preferences.targetDirectoryFlow,
            preferences.monitoredFoldersFlow,
            preferences.customFoldersFlow
        ) { isEnabled, isRunning, targetDir, monitored, custom ->
            val allFolderConfigs = mutableListOf<FolderConfig>()
            AppPreferences.DEFAULT_SOURCE_FOLDERS.forEach { defaultFolder ->
                allFolderConfigs.add(
                    defaultFolder.copy(isEnabled = monitored.contains(defaultFolder.relativePath))
                )
            }
            custom.forEach { customPath ->
                allFolderConfigs.add(
                    FolderConfig(
                        relativePath = customPath,
                        displayName = "Custom: $customPath",
                        isEnabled = monitored.contains(customPath),
                        isDefault = false
                    )
                )
            }
            StateTuple1(isEnabled, isRunning, targetDir, allFolderConfigs, custom.toList())
        },
        combine(
            preferences.hideFromGalleryFlow,
            preferences.notifyOnMoveFlow,
            preferences.recentLogsFlow,
            preferences.totalMovedCountFlow,
            _permissionState
        ) { hideNoMedia, notify, logs, totalCount, permissions ->
            StateTuple2(hideNoMedia, notify, logs, totalCount, permissions)
        },
        combine(
            _manualMoveInProgress,
            _manualMoveProgressText,
            _userNotice,
            _vaultFolders,
            _savedQuotaBytes
        ) { inProgress, progressText, notice, vaults, quotaBytes ->
            StateTuple3(inProgress, progressText, notice, vaults, quotaBytes)
        },
        combine(
            _totalGuardedFiles,
            _isExportingZip,
            _isRestoring,
            _restoreProgressText
        ) { totalGuarded, exporting, restoring, restProgress ->
            StateTuple4(totalGuarded, exporting, restoring, restProgress)
        }
    ) { tuple1, tuple2, tuple3, tuple4 ->
        MainUiState(
            isServiceEnabled = tuple1.isEnabled,
            isServiceRunning = tuple1.isRunning,
            targetDirectory = tuple1.targetDir,
            monitoredFolders = tuple1.folders,
            customFolders = tuple1.customList,
            hideFromGalleryNoMedia = tuple2.hideNoMedia,
            notifyOnMove = tuple2.notify,
            recentLogs = tuple2.logs,
            totalMovedCount = tuple2.totalCount,
            hasManageAllFilesPermission = tuple2.perms.allFiles,
            hasNotificationPermission = tuple2.perms.notifications,
            isBatteryOptimizationIgnored = tuple2.perms.batteryIgnored,
            isManualMoveInProgress = tuple3.inProgress,
            manualMoveProgressText = tuple3.progressText,
            userNotice = tuple3.notice,
            vaultFolders = tuple3.vaults,
            savedQuotaBytes = tuple3.quotaBytes,
            totalGuardedFiles = tuple4.totalGuarded,
            isExportingZip = tuple4.isExporting,
            isRestoring = tuple4.isRestoring,
            restoreProgressText = tuple4.restoreProgress
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        MainUiState()
    )

    private data class StateTuple1(
        val isEnabled: Boolean,
        val isRunning: Boolean,
        val targetDir: String,
        val folders: List<FolderConfig>,
        val customList: List<String>
    )

    private data class StateTuple2(
        val hideNoMedia: Boolean,
        val notify: Boolean,
        val logs: List<MoveLogEntry>,
        val totalCount: Int,
        val perms: PermissionSnapshot
    )

    private data class StateTuple3(
        val inProgress: Boolean,
        val progressText: String?,
        val notice: String?,
        val vaults: List<VaultFolderInfo>,
        val quotaBytes: Long
    )

    private data class StateTuple4(
        val totalGuarded: Int,
        val isExporting: Boolean,
        val isRestoring: Boolean,
        val restoreProgress: String?
    )

    data class PermissionSnapshot(
        val allFiles: Boolean,
        val notifications: Boolean,
        val batteryIgnored: Boolean
    )

    init {
        refreshVaultStats()
    }

    fun refreshPermissions() {
        _permissionState.value = checkSystemPermissions()
        refreshVaultStats()
    }

    fun refreshVaultStats() {
        viewModelScope.launch {
            val targetDirStr = preferences.targetDirectoryFlow.first()
            val targetDir = File(targetDirStr)
            val subfolders = MediaMoverEngine.getVaultFolderInfos(targetDir)
            val (totalFiles, totalBytes) = MediaMoverEngine.getVaultTotalStats(targetDir)
            _vaultFolders.value = subfolders
            _totalGuardedFiles.value = totalFiles
            _savedQuotaBytes.value = totalBytes
        }
    }

    private fun checkSystemPermissions(): PermissionSnapshot {
        val app = getApplication<Application>()
        val allFiles = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                app,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }

        val notifications = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                app,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        val powerManager = app.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val batteryIgnored = powerManager?.isIgnoringBatteryOptimizations(app.packageName) ?: false

        return PermissionSnapshot(allFiles, notifications, batteryIgnored)
    }

    fun toggleService(enable: Boolean) {
        viewModelScope.launch {
            preferences.setServiceEnabled(enable)
            if (enable) {
                MediaObserverService.startService(getApplication())
            } else {
                MediaObserverService.stopService(getApplication())
            }
        }
    }

    fun setTargetDirectory(path: String) {
        viewModelScope.launch {
            val cleanPath = path.trim().trimEnd('/')
            if (cleanPath.isNotEmpty()) {
                preferences.setTargetDirectory(cleanPath)
                withContext(Dispatchers.IO) {
                    val targetDir = File(cleanPath)
                    val hide = uiState.value.hideFromGalleryNoMedia
                    MediaMoverEngine.prepareTargetDirectory(targetDir, hide)
                }
                refreshVaultStats()
            }
        }
    }

    fun toggleMonitoredFolder(relativePath: String, isEnabled: Boolean) {
        viewModelScope.launch {
            preferences.toggleMonitoredFolder(relativePath, isEnabled)
            if (uiState.value.isServiceRunning) {
                MediaObserverService.startService(getApplication())
            }
        }
    }

    fun addCustomFolder(folderPath: String) {
        viewModelScope.launch {
            preferences.addCustomFolder(folderPath)
            if (uiState.value.isServiceRunning) {
                MediaObserverService.startService(getApplication())
            }
        }
    }

    fun removeCustomFolder(folderPath: String) {
        viewModelScope.launch {
            preferences.removeCustomFolder(folderPath)
            if (uiState.value.isServiceRunning) {
                MediaObserverService.startService(getApplication())
            }
        }
    }

    fun setHideFromGallery(hide: Boolean) {
        viewModelScope.launch {
            preferences.setHideFromGallery(hide)
            withContext(Dispatchers.IO) {
                val targetDir = File(uiState.value.targetDirectory)
                MediaMoverEngine.prepareTargetDirectory(targetDir, hide)
            }
        }
    }

    fun setNotifyOnMove(notify: Boolean) {
        viewModelScope.launch {
            preferences.setNotifyOnMove(notify)
        }
    }

    fun clearLogs() {
        viewModelScope.launch {
            preferences.clearLogs()
        }
    }

    fun dismissNotice() {
        _userNotice.value = null
    }

    fun runManualMoveNow() {
        if (_manualMoveInProgress.value) return

        viewModelScope.launch {
            _manualMoveInProgress.value = true
            _manualMoveProgressText.value = "Scanning monitored folders..."

            val state = uiState.value
            val activeFolders = state.monitoredFolders
                .filter { it.isEnabled }
                .map { MediaMoverEngine.resolveFolderPath(it.relativePath) }

            val targetDir = File(state.targetDirectory)
            val hide = state.hideFromGalleryNoMedia

            val results = MediaMoverEngine.runManualScanAndMove(
                context = getApplication(),
                sourceFolders = activeFolders,
                baseTargetDir = targetDir,
                hideWithNoMedia = hide,
                onProgress = { current, total, name ->
                    _manualMoveProgressText.value = "Moving $current of $total: $name"
                }
            )

            results.forEach { entry ->
                preferences.addLog(entry)
            }

            _manualMoveInProgress.value = false
            _manualMoveProgressText.value = null
            _userNotice.value = if (results.isEmpty()) {
                "Scan complete: No pending images or videos found in selected source folders."
            } else {
                "Manual move complete: Successfully segregated and protected ${results.size} items."
            }

            refreshVaultStats()
        }
    }

    /**
     * Restores a single file back to DCIM/Camera (or original folder)
     * and forces MediaStore re-indexing so Google Photos / Instagram detect it.
     */
    fun restoreSingleFile(file: File) {
        viewModelScope.launch {
            val log = MediaMoverEngine.restoreFile(getApplication(), file)
            preferences.addLog(log)
            if (log.success) {
                _userNotice.value = "Restored ${file.name} to Camera/Original folder and rescanned."
            } else {
                _userNotice.value = "Failed restoring ${file.name}: ${log.errorMessage}"
            }
            refreshVaultStats()
        }
    }

    /**
     * Batch restores an entire segregated vault subfolder.
     */
    fun restoreSubfolder(vaultFolderInfo: VaultFolderInfo) {
        if (_isRestoring.value) return
        viewModelScope.launch {
            _isRestoring.value = true
            _restoreProgressText.value = "Restoring ${vaultFolderInfo.displayName}..."

            val results = MediaMoverEngine.restoreSubfolder(
                context = getApplication(),
                vaultSubfolder = vaultFolderInfo.folder,
                onProgress = { current, total, name ->
                    _restoreProgressText.value = "Restoring $current of $total: $name"
                }
            )

            results.forEach { preferences.addLog(it) }
            _isRestoring.value = false
            _restoreProgressText.value = null
            _userNotice.value = "Restored ${results.size} files from ${vaultFolderInfo.displayName} to original location."
            refreshVaultStats()
        }
    }

    /**
     * Batch restores all files in all segregated vaults.
     */
    fun restoreAllVaults() {
        if (_isRestoring.value) return
        viewModelScope.launch {
            _isRestoring.value = true
            _restoreProgressText.value = "Restoring all vaulted media..."

            val targetDir = File(uiState.value.targetDirectory)
            val results = MediaMoverEngine.restoreAllVaults(
                context = getApplication(),
                baseTargetDir = targetDir,
                onProgress = { current, total, name ->
                    _restoreProgressText.value = "Restoring $current of $total: $name"
                }
            )

            results.forEach { preferences.addLog(it) }
            _isRestoring.value = false
            _restoreProgressText.value = null
            _userNotice.value = "Restored ${results.size} total files across all vaults back to device."
            refreshVaultStats()
        }
    }

    /**
     * Compresses the entire vault into a ZIP archive saved in public Downloads for USB transfer.
     */
    fun exportVaultToZip() {
        if (_isExportingZip.value) return
        viewModelScope.launch {
            _isExportingZip.value = true
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                ?: File("/storage/emulated/0/Download")
            val zipFile = File(downloadDir, "PhotoVault_Backup_$timestamp.zip")

            val targetDir = File(uiState.value.targetDirectory)
            val count = MediaMoverEngine.exportVaultToZip(
                baseTargetDir = targetDir,
                outputZipFile = zipFile
            )

            _isExportingZip.value = false
            if (count > 0) {
                _userNotice.value = "Exported $count files to ZIP: ${zipFile.absolutePath}"
            } else {
                _userNotice.value = "Vault is empty. No files to export."
            }
        }
    }

    /**
     * Triggers native Android share sheet for a single file or folder.
     */
    fun shareSingleFile(context: Context, file: File) {
        val intent = MediaMoverEngine.createShareIntent(context, listOf(file))
        context.startActivity(Intent.createChooser(intent, "Share from PhotoVault"))
    }

    fun shareSubfolder(context: Context, vaultFolderInfo: VaultFolderInfo) {
        val files = vaultFolderInfo.folder.listFiles()?.filter { MediaMoverEngine.isMediaFile(it) } ?: emptyList()
        if (files.isEmpty()) {
            _userNotice.value = "Folder is empty, nothing to share."
            return
        }
        val intent = MediaMoverEngine.createShareIntent(context, files)
        context.startActivity(Intent.createChooser(intent, "Share ${vaultFolderInfo.displayName}"))
    }

    /**
     * Helper to create a test photo in DCIM/Camera to immediately verify the auto-move functionality.
     */
    fun createTestCapture() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val cameraFolder = MediaMoverEngine.resolveFolderPath("DCIM/Camera")
                if (!cameraFolder.exists()) {
                    cameraFolder.mkdirs()
                }
                val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val testFile = File(cameraFolder, "IMG_${timeStamp}_TEST.jpg")

                // Write a dummy JPEG header / bytes
                FileOutputStream(testFile).use { fos ->
                    val dummyBytes = "PHOTO_VAULT_TEST_CAPTURE_$timeStamp".toByteArray()
                    fos.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()))
                    fos.write(dummyBytes)
                    fos.write(byteArrayOf(0xFF.toByte(), 0xD9.toByte()))
                    fos.flush()
                }

                _userNotice.value = "Created test photo: ${testFile.name} in DCIM/Camera. Watch it auto-move into Vault_Camera!"
                refreshVaultStats()
            } catch (e: Exception) {
                _userNotice.value = "Failed creating test photo: ${e.message}"
            }
        }
    }
}
