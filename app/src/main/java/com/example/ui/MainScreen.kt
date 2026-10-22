package com.example.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderSpecial
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.model.FolderConfig
import com.example.model.MoveLogEntry
import com.example.model.VaultFolderInfo
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var showTargetDirDialog by remember { mutableStateOf(false) }
    var showAddFolderDialog by remember { mutableStateOf(false) }
    var showHelpDialog by remember { mutableStateOf(false) }
    var folderToRestore by remember { mutableStateOf<VaultFolderInfo?>(null) }
    var showRestoreAllConfirm by remember { mutableStateOf(false) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        viewModel.refreshPermissions()
    }

    LaunchedEffect(Unit) {
        viewModel.refreshPermissions()
    }

    LaunchedEffect(uiState.userNotice) {
        uiState.userNotice?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.dismissNotice()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "PhotoVault Auto-Move",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (uiState.isServiceRunning) "● Shield Active • Segregating Captures" else "○ Shield Paused",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (uiState.isServiceRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.exportVaultToZip() },
                        enabled = !uiState.isExportingZip,
                        modifier = Modifier.testTag("action_export_zip")
                    ) {
                        if (uiState.isExportingZip) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(imageVector = Icons.Default.Archive, contentDescription = "Export ZIP")
                        }
                    }
                    IconButton(
                        onClick = { viewModel.createTestCapture() },
                        modifier = Modifier.testTag("action_test_capture")
                    ) {
                        Icon(imageVector = Icons.Default.CameraAlt, contentDescription = "Test Camera Capture")
                    }
                    IconButton(
                        onClick = { viewModel.refreshPermissions() },
                        modifier = Modifier.testTag("action_refresh")
                    ) {
                        Icon(imageVector = Icons.Default.Refresh, contentDescription = "Refresh Stats")
                    }
                    IconButton(
                        onClick = { showHelpDialog = true },
                        modifier = Modifier.testTag("action_help")
                    ) {
                        Icon(imageVector = Icons.Default.Info, contentDescription = "Help")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            // 1. Storage Insights Metric Cards
            item {
                StorageInsightsCard(
                    savedQuotaBytes = uiState.savedQuotaBytes,
                    totalGuardedFiles = uiState.totalGuardedFiles,
                    vaultFolders = uiState.vaultFolders
                )
            }

            // 2. Status & Master Toggle Card
            item {
                StatusCard(
                    isServiceEnabled = uiState.isServiceEnabled,
                    isServiceRunning = uiState.isServiceRunning,
                    hasManageAllFilesPermission = uiState.hasManageAllFilesPermission,
                    onToggle = { enable ->
                        if (enable && !uiState.hasManageAllFilesPermission) {
                            scope.launch {
                                snackbarHostState.showSnackbar("Storage access required first. Grant below.")
                            }
                        } else {
                            viewModel.toggleService(enable)
                        }
                    }
                )
            }

            // 3. System Permissions (if any ungranted)
            item {
                PermissionsCard(
                    hasAllFiles = uiState.hasManageAllFilesPermission,
                    hasNotifications = uiState.hasNotificationPermission,
                    isBatteryIgnored = uiState.isBatteryOptimizationIgnored,
                    onRequestAllFiles = { openAllFilesAccessSettings(context) },
                    onRequestNotifications = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                    onRequestBatteryOptimization = { openBatteryOptimizationSettings(context) }
                )
            }

            // 4. Segregated Vault Folders & Cloud Integration
            item {
                SegregatedVaultsCard(
                    vaultFolders = uiState.vaultFolders,
                    isRestoring = uiState.isRestoring,
                    restoreProgressText = uiState.restoreProgressText,
                    onShareSubfolder = { viewModel.shareSubfolder(context, it) },
                    onRestoreSubfolder = { folderToRestore = it },
                    onRestoreAll = { showRestoreAllConfirm = true },
                    onExportZip = { viewModel.exportVaultToZip() }
                )
            }

            // 5. Target Directory & Monitored Source Folders
            item {
                FolderConfigurationCard(
                    targetDirectory = uiState.targetDirectory,
                    hideFromGallery = uiState.hideFromGalleryNoMedia,
                    folders = uiState.monitoredFolders,
                    onChangeTargetClick = { showTargetDirDialog = true },
                    onCopyTargetClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Vault Path", uiState.targetDirectory))
                        Toast.makeText(context, "Path copied", Toast.LENGTH_SHORT).show()
                    },
                    onToggleFolder = { path, enabled -> viewModel.toggleMonitoredFolder(path, enabled) },
                    onRemoveCustom = { viewModel.removeCustomFolder(it) },
                    onAddFolderClick = { showAddFolderDialog = true }
                )
            }

            // 6. Options & Manual Move Action
            item {
                OptionsAndActionsCard(
                    hideFromGallery = uiState.hideFromGalleryNoMedia,
                    notifyOnMove = uiState.notifyOnMove,
                    isManualMoveInProgress = uiState.isManualMoveInProgress,
                    manualProgressText = uiState.manualMoveProgressText,
                    onToggleHideNoMedia = { viewModel.setHideFromGallery(it) },
                    onToggleNotifyOnMove = { viewModel.setNotifyOnMove(it) },
                    onRunManualMove = { viewModel.runManualMoveNow() }
                )
            }

            // 7. Activity Log with inline Restore & Share
            item {
                ActivityLogCard(
                    logs = uiState.recentLogs,
                    onClearLogs = { viewModel.clearLogs() },
                    onRestoreFile = { logEntry ->
                        val file = File(logEntry.destinationPath)
                        viewModel.restoreSingleFile(file)
                    },
                    onShareFile = { logEntry ->
                        val file = File(logEntry.destinationPath)
                        if (file.exists()) {
                            viewModel.shareSingleFile(context, file)
                        } else {
                            Toast.makeText(context, "File not found", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            }

            item {
                Spacer(modifier = Modifier.height(20.dp))
            }
        }
    }

    // Confirmation Dialog: Restore Subfolder
    folderToRestore?.let { vault ->
        AlertDialog(
            onDismissRequest = { folderToRestore = null },
            title = { Text("Restore ${vault.displayName}?") },
            text = {
                Text(
                    text = "This will move all ${vault.fileCount} files back into ${vault.originalSourceCategory} and re-index with MediaStore so Google Photos and Instagram detect them immediately.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(onClick = {
                    val target = folderToRestore
                    folderToRestore = null
                    target?.let { viewModel.restoreSubfolder(it) }
                }) {
                    Text("Restore Now")
                }
            },
            dismissButton = {
                TextButton(onClick = { folderToRestore = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Confirmation Dialog: Restore All
    if (showRestoreAllConfirm) {
        AlertDialog(
            onDismissRequest = { showRestoreAllConfirm = false },
            title = { Text("Restore All Vaulted Files?") },
            text = {
                Text(
                    text = "This will restore all guarded files across Camera, Screenshots, and other sub-vaults back into their original phone directories. Media scanners will immediately re-index them.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(onClick = {
                    showRestoreAllConfirm = false
                    viewModel.restoreAllVaults()
                }) {
                    Text("Restore Everything")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreAllConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showTargetDirDialog) {
        ChangeTargetDirectoryDialog(
            currentPath = uiState.targetDirectory,
            onDismiss = { showTargetDirDialog = false },
            onConfirm = { newPath ->
                viewModel.setTargetDirectory(newPath)
                showTargetDirDialog = false
            }
        )
    }

    if (showAddFolderDialog) {
        AddMonitoredFolderDialog(
            onDismiss = { showAddFolderDialog = false },
            onConfirm = { path ->
                viewModel.addCustomFolder(path)
                showAddFolderDialog = false
            }
        )
    }

    if (showHelpDialog) {
        HelpAndHowItWorksDialog(onDismiss = { showHelpDialog = false })
    }
}

/**
 * Storage Insights Metric Cards:
 * Shows estimated cloud storage quota saved and count of guarded files segregated by subfolder.
 */
@Composable
private fun StorageInsightsCard(
    savedQuotaBytes: Long,
    totalGuardedFiles: Int,
    vaultFolders: List<VaultFolderInfo>
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("storage_insights_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Storage Insights",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "100% Offline • Zero Cloud Sync",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Metric 1: Saved Cloud Quota
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = formatFileSize(savedQuotaBytes),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            text = "Saved from cloud backup",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                        )
                    }
                }

                // Metric 2: Total Guarded Files
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "$totalGuardedFiles items",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Text(
                            text = "Total files guarded",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f)
                        )
                    }
                }
            }

            // Segregation Breakdown Chips
            if (vaultFolders.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    vaultFolders.forEach { vf ->
                        Surface(
                            color = MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(8.dp),
                            shadowElevation = 1.dp
                        ) {
                            Text(
                                text = "${vf.displayName.replace(" Vault", "")}: ${vf.fileCount}",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusCard(
    isServiceEnabled: Boolean,
    isServiceRunning: Boolean,
    hasManageAllFilesPermission: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("status_card"),
        colors = CardDefaults.cardColors(
            containerColor = if (isServiceRunning) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
            }
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(
                                if (isServiceRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isServiceRunning) Icons.Default.Security else Icons.Default.VisibilityOff,
                            contentDescription = null,
                            tint = if (isServiceRunning) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Column {
                        Text(
                            text = if (isServiceRunning) "Auto-Move Shield ACTIVE" else "Auto-Move Shield Paused",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (isServiceRunning) "Zero-delay subfolder segregation active" else "Watcher stopped",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Switch(
                    checked = isServiceEnabled,
                    onCheckedChange = onToggle,
                    modifier = Modifier.testTag("service_master_switch")
                )
            }

            if (!hasManageAllFilesPermission && isServiceEnabled) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "Storage access missing! Grant below to allow background moving.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
        }
    }
}

/**
 * Segregated Vault Subfolder Management Card:
 * Displays Vault_Camera, Vault_Screenshots, Vault_Downloads, with quick Share and Restore triggers.
 */
@Composable
private fun SegregatedVaultsCard(
    vaultFolders: List<VaultFolderInfo>,
    isRestoring: Boolean,
    restoreProgressText: String?,
    onShareSubfolder: (VaultFolderInfo) -> Unit,
    onRestoreSubfolder: (VaultFolderInfo) -> Unit,
    onRestoreAll: () -> Unit,
    onExportZip: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("segregated_vaults_card"),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(imageVector = Icons.Default.FolderSpecial, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text(
                        text = "Segregated Sub-Vaults",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Batch Actions
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onRestoreAll, enabled = !isRestoring) {
                        Icon(imageVector = Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Restore All", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            if (restoreProgressText != null) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(text = restoreProgressText, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            if (vaultFolders.isEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Sub-vaults will appear here as soon as files are moved (Vault_Camera, Vault_Screenshots, etc.)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    vaultFolders.forEach { vault ->
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "${vault.displayName} (${vault.name})",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "${vault.fileCount} files • ${formatFileSize(vault.totalSizeBytes)} • Source: ${vault.originalSourceCategory}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                    // Quick Share Sheet trigger for on-demand cloud upload
                                    IconButton(
                                        onClick = { onShareSubfolder(vault) },
                                        enabled = vault.fileCount > 0,
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Share,
                                            contentDescription = "Share / Manual Cloud Upload",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }

                                    // One-tap Restore to Camera/Original
                                    IconButton(
                                        onClick = { onRestoreSubfolder(vault) },
                                        enabled = vault.fileCount > 0 && !isRestoring,
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Restore,
                                            contentDescription = "Restore to Camera",
                                            tint = MaterialTheme.colorScheme.secondary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FolderConfigurationCard(
    targetDirectory: String,
    hideFromGallery: Boolean,
    folders: List<FolderConfig>,
    onChangeTargetClick: () -> Unit,
    onCopyTargetClick: () -> Unit,
    onToggleFolder: (String, Boolean) -> Unit,
    onRemoveCustom: (String) -> Unit,
    onAddFolderClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("folder_config_card"),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Target Base Folder
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(imageVector = Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text(
                        text = "Base Vault Root",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (hideFromGallery) {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = "Root .nomedia Active",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = targetDirectory,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onCopyTargetClick, modifier = Modifier.size(32.dp)) {
                        Icon(imageVector = Icons.Default.ContentCopy, contentDescription = "Copy", modifier = Modifier.size(16.dp))
                    }
                    IconButton(onClick = onChangeTargetClick, modifier = Modifier.size(32.dp)) {
                        Icon(imageVector = Icons.Default.Edit, contentDescription = "Edit", modifier = Modifier.size(16.dp))
                    }
                }
            }

            HorizontalDivider()

            // Watched Sources
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Monitored Source Folders",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                TextButton(onClick = onAddFolderClick) {
                    Icon(imageVector = Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Add Source")
                }
            }

            folders.forEach { folder ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Checkbox(
                            checked = folder.isEnabled,
                            onCheckedChange = { onToggleFolder(folder.relativePath, it) }
                        )
                        Column {
                            Text(
                                text = folder.displayName,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "-> Vault_${folder.relativePath.split("/").last()}/",
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    if (!folder.isDefault) {
                        IconButton(onClick = { onRemoveCustom(folder.relativePath) }, modifier = Modifier.size(32.dp)) {
                            Icon(imageVector = Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OptionsAndActionsCard(
    hideFromGallery: Boolean,
    notifyOnMove: Boolean,
    isManualMoveInProgress: Boolean,
    manualProgressText: String?,
    onToggleHideNoMedia: (Boolean) -> Unit,
    onToggleNotifyOnMove: (Boolean) -> Unit,
    onRunManualMove: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("options_actions_card"),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Options & Manual Sweep",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            // .nomedia switch
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Hide from Gallery (.nomedia)",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "Keeps .nomedia at the vault root so Google Photos and media scanners ignore all sub-vaults.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Switch(
                    checked = hideFromGallery,
                    onCheckedChange = onToggleHideNoMedia,
                    modifier = Modifier.testTag("switch_nomedia")
                )
            }

            HorizontalDivider()

            // Notification switch
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Notify on each capture move",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "Show status notification confirming file was moved to vault.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Switch(
                    checked = notifyOnMove,
                    onCheckedChange = onToggleNotifyOnMove,
                    modifier = Modifier.testTag("switch_notify")
                )
            }

            HorizontalDivider()

            // Run Manual Move
            Button(
                onClick = onRunManualMove,
                enabled = !isManualMoveInProgress,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("run_manual_move_button"),
                shape = RoundedCornerShape(10.dp)
            ) {
                if (isManualMoveInProgress) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Moving Existing Files...")
                } else {
                    Icon(imageVector = Icons.Default.Sync, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Run Manual Move Now", fontWeight = FontWeight.SemiBold)
                }
            }

            if (manualProgressText != null) {
                Text(
                    text = manualProgressText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun ActivityLogCard(
    logs: List<MoveLogEntry>,
    onClearLogs: () -> Unit,
    onRestoreFile: (MoveLogEntry) -> Unit,
    onShareFile: (MoveLogEntry) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("activity_log_card"),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Activity Log (${logs.size} items)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                if (logs.isNotEmpty()) {
                    TextButton(onClick = onClearLogs) {
                        Text("Clear")
                    }
                }
            }

            if (logs.isEmpty()) {
                Text(
                    text = "No files moved recently. Take a photo or screenshot to test zero-delay shielding.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    logs.take(15).forEach { entry ->
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = if (entry.success) Icons.Default.CheckCircle else Icons.Default.Warning,
                                        contentDescription = null,
                                        tint = if (entry.success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(16.dp)
                                    )

                                    Column {
                                        Text(
                                            text = entry.fileName,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "${entry.sourcePath.split("/").takeLast(2).joinToString("/")} -> ${entry.destinationPath.split("/").takeLast(2).joinToString("/")}",
                                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(
                                        text = timeFormat.format(Date(entry.timestamp)),
                                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )

                                    if (entry.success) {
                                        IconButton(
                                            onClick = { onShareFile(entry) },
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(imageVector = Icons.Default.Share, contentDescription = "Share", modifier = Modifier.size(14.dp))
                                        }

                                        IconButton(
                                            onClick = { onRestoreFile(entry) },
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(imageVector = Icons.Default.Restore, contentDescription = "Restore", modifier = Modifier.size(14.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionsCard(
    hasAllFiles: Boolean,
    hasNotifications: Boolean,
    isBatteryIgnored: Boolean,
    onRequestAllFiles: () -> Unit,
    onRequestNotifications: () -> Unit,
    onRequestBatteryOptimization: () -> Unit
) {
    val allGranted = hasAllFiles && hasNotifications && isBatteryIgnored
    if (allGranted) return

    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("permissions_card"),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Required Permissions",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.error
            )

            if (!hasAllFiles) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("All Files Access (MANAGE_EXTERNAL_STORAGE)", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                        Text("Needed to move files out of DCIM/Camera silently in background.", style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Button(onClick = onRequestAllFiles, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
                        Text("Grant", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            if (!hasNotifications) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Foreground Notifications", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                        Text("Keeps the background observer running on Android 13+.", style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Button(onClick = onRequestNotifications, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
                        Text("Allow", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            if (!isBatteryIgnored) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Battery Unrestricted", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                        Text("Prevents Android OEM from sleeping the observer.", style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Button(onClick = onRequestBatteryOptimization, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
                        Text("Exempt", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun ChangeTargetDirectoryDialog(
    currentPath: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var textInput by remember { mutableStateOf(currentPath) }

    val presetVaults = remember {
        val root = Environment.getExternalStorageDirectory()?.absolutePath ?: "/storage/emulated/0"
        listOf(
            "$root/_PhotoVault_Local",
            "$root/ManualBackup_Vault",
            "$root/Documents/_LocalVault",
            "$root/Download/_OfflineVault"
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set Base Destination Root") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Subfolders will be created automatically inside this directory (Vault_Camera, Vault_Screenshots, etc.):",
                    style = MaterialTheme.typography.bodySmall
                )

                OutlinedTextField(
                    value = textInput,
                    onValueChange = { textInput = it },
                    label = { Text("Base Directory Path") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Text("Quick Presets:", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)

                presetVaults.forEach { preset ->
                    OutlinedButton(
                        onClick = { textInput = preset },
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(text = preset.replace("/storage/emulated/0/", ".../"), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(textInput) }, enabled = textInput.isNotBlank()) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun AddMonitoredFolderDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var textInput by remember { mutableStateOf("") }
    val suggested = listOf("DCIM/Camera", "Pictures/Screenshots", "Download", "Android/media/com.whatsapp/WhatsApp/Media")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Monitored Source Folder") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Enter relative folder path from storage root:", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = textInput,
                    onValueChange = { textInput = it },
                    label = { Text("Folder Path") },
                    placeholder = { Text("e.g. Pictures/Telegram") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                suggested.forEach { sug ->
                    OutlinedButton(
                        onClick = { textInput = sug },
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(sug, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(textInput) }, enabled = textInput.isNotBlank()) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun HelpAndHowItWorksDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Subfolder Segregation & Privacy") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    Text("1. Why Segregate Outside DCIM?", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(
                        "Google Photos automatically syncs everything in DCIM without an opt-out toggle. Storing items inside /_PhotoVault_Local/ segregates them into Vault_Camera, Vault_Screenshots, and Vault_Downloads while guaranteeing Google Photos never automatically syncs them.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                item {
                    Text("2. Root .nomedia File", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(
                        "The .nomedia file at the root level completely hides all sub-vaults from system gallery scrapers and cloud background indexers.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                item {
                    Text("3. Restore & Manual Share", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(
                        "Use the Restore button to return items to DCIM/Camera anytime with automatic MediaStore re-indexing. Use the Share button to selectively upload photos to Google Drive or Google Photos on demand without turning on auto-backup.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text("Got It") }
        }
    )
}

private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 MB"
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
    val gb = mb / 1024.0
    return String.format(Locale.US, "%.2f GB", gb)
}

private fun openAllFilesAccessSettings(context: Context) {
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:${context.packageName}")
            }
            context.startActivity(intent)
        } else {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
            context.startActivity(intent)
        }
    } catch (_: Exception) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    }
}

private fun openBatteryOptimizationSettings(context: Context) {
    try {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
        context.startActivity(intent)
    } catch (_: Exception) {
        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }
}
