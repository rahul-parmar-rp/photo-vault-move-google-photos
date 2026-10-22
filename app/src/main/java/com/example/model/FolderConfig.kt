package com.example.model

/**
 * Represents a monitored source folder configuration.
 */
data class FolderConfig(
    val relativePath: String, // e.g. "DCIM/Camera"
    val displayName: String,
    val isEnabled: Boolean = true,
    val isDefault: Boolean = false
)
