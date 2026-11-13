package com.example.audiosecretrecorder.model

import android.net.Uri
import java.io.File

/**
 * Modelo de datos que representa una grabación de audio guardada en el dispositivo.
 */
data class RecordingModel(
    val id: Long = 0L,
    val name: String,
    val file: File?,
    val uri: Uri,
    val durationMs: Long,
    val sizeBytes: Long,
    val dateAdded: Long,
    var isPlaying: Boolean = false
) {
    val formattedDuration: String
        get() {
            val totalSeconds = durationMs / 1000
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            return String.format("%02d:%02d", minutes, seconds)
        }

    val formattedSize: String
        get() {
            return when {
                sizeBytes >= 1024 * 1024 -> String.format("%.2f MB", sizeBytes.toDouble() / (1024 * 1024))
                sizeBytes >= 1024 -> String.format("%.1f KB", sizeBytes.toDouble() / 1024)
                else -> "$sizeBytes B"
            }
        }
}

