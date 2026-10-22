package com.example.model

/**
 * Represents a logged file move event.
 */
data class MoveLogEntry(
    val id: String = System.currentTimeMillis().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val fileName: String,
    val sourcePath: String,
    val destinationPath: String,
    val fileSizeBytes: Long = 0L,
    val success: Boolean = true,
    val errorMessage: String? = null
)
