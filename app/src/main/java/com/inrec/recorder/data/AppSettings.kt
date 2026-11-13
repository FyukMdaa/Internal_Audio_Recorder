package com.inrec.recorder.data

import android.content.Context

/**
 * Lightweight settings backed by SharedPreferences.
 *
 * These parameters drive the silence detection that powers the editor's
 * "cut suggestions" feature.
 */
class AppSettings(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** RMS threshold in dBFS below which audio counts as silence. */
    var thresholdDb: Int
        get() = prefs.getInt(KEY_THRESHOLD, DEFAULT_THRESHOLD)
        set(value) = prefs.edit().putInt(KEY_THRESHOLD, value).apply()

    /** Minimum duration of silence, in milliseconds, before a cut is proposed. */
    var minSilenceMs: Int
        get() = prefs.getInt(KEY_MIN_SILENCE, DEFAULT_MIN_SILENCE_MS)
        set(value) = prefs.edit().putInt(KEY_MIN_SILENCE, value).apply()

    /** Audio kept before/after a silence cut so speech is not clipped, in ms. */
    var paddingMs: Int
        get() = prefs.getInt(KEY_PADDING, DEFAULT_PADDING_MS)
        set(value) = prefs.edit().putInt(KEY_PADDING, value).apply()

    fun resetToDefaults() {
        thresholdDb = DEFAULT_THRESHOLD
        minSilenceMs = DEFAULT_MIN_SILENCE_MS
        paddingMs = DEFAULT_PADDING_MS
    }

    fun toSilenceParams() = com.inrec.recorder.audio.SilenceDetector.SilenceParams(
        thresholdDb = thresholdDb,
        minSilenceMs = minSilenceMs.toLong(),
        paddingMs = paddingMs.toLong(),
    )

    private companion object {
        const val PREFS_NAME = "inrec_settings"
        const val KEY_THRESHOLD = "threshold_db"
        const val KEY_MIN_SILENCE = "min_silence_ms"
        const val KEY_PADDING = "padding_ms"

        const val DEFAULT_THRESHOLD = -45
        const val DEFAULT_MIN_SILENCE_MS = 800
        const val DEFAULT_PADDING_MS = 150
    }
}
