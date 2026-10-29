package com.keisha.tusur.timetable.domain.update

import java.io.File
import java.time.Instant

data class AppUpdate(
    val versionName: String,
    val tagName: String,
    val notes: String?,
    val publishedAt: Instant?,
    val downloadUrl: String,
    val sizeBytes: Long,
    val sha256: String?,
)

data class DownloadProgress(
    val bytesRead: Long,
    val totalBytes: Long?,
) {
    val fraction: Float
        get() = totalBytes?.takeIf { it > 0 }
            ?.let { (bytesRead.toFloat() / it).coerceIn(0f, 1f) }
            ?: 0f
}

data class DownloadedApk(
    val file: File,
    val update: AppUpdate,
)

sealed interface InstallResult {
    data object Started : InstallResult
    data object PermissionRequired : InstallResult
}
