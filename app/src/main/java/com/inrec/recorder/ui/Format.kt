package com.inrec.recorder.ui

import java.util.Locale

/** Small formatting helpers shared by the screens. */
object Format {

    /** "1:23" / "12:05" / "1:02:03" (used for durations and positions). */
    fun clock(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec / 60) % 60
        val s = totalSec % 60
        return if (h > 0) {
            String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.US, "%d:%02d", m, s)
        }
    }

    /** Clock plus tenths, used under the waveform. */
    fun clockPrecise(ms: Long): String {
        val tenths = (ms % 1000) / 100
        return clock(ms) + String.format(Locale.US, ".%d", tenths)
    }

    /** Human readable file size. */
    fun size(bytes: Long): String = when {
        bytes >= 1 shl 30 -> String.format(Locale.US, "%.1f GB", bytes / 1073741824.0)
        bytes >= 1 shl 20 -> String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
        bytes >= 1 shl 10 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    /** "2026/11/13 10:45" style local date/time. */
    fun dateTime(epochMs: Long): String =
        java.text.SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())
            .format(java.util.Date(epochMs))
}
