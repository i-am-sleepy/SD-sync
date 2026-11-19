package com.sdsync.app.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object StorageUtils {

    fun humanSize(bytes: Long): String {
        if (bytes < 0) return "—"
        val kb = 1024.0
        val mb = kb * 1024
        val gb = mb * 1024
        return when {
            bytes >= gb -> String.format(Locale.US, "%.2f GB", bytes / gb)
            bytes >= mb -> String.format(Locale.US, "%.1f MB", bytes / mb)
            bytes >= kb -> String.format(Locale.US, "%.1f KB", bytes / kb)
            else -> "$bytes B"
        }
    }

    private val fmt = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())

    fun formatTime(ts: Long): String = fmt.format(Date(ts))
}
