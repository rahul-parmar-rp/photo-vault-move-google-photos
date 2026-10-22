package com.example.model

import java.io.File

/**
 * Represents a segregated vault subfolder (e.g. Vault_Camera, Vault_Screenshots).
 */
data class VaultFolderInfo(
    val name: String,
    val displayName: String,
    val folder: File,
    val fileCount: Int,
    val totalSizeBytes: Long,
    val originalSourceCategory: String,
    val sampleFiles: List<File> = emptyList()
)
