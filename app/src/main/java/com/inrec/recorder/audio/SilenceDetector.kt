package com.inrec.recorder.audio

import java.io.File
import java.io.RandomAccessFile
import kotlin.math.log10
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** A contiguous [startMs, endMs) region of the source recording. */
data class TimeRange(val startMs: Long, val endMs: Long) {
    val durationMs: Long get() = endMs - startMs

    fun intersects(other: TimeRange): Boolean =
        startMs < other.endMs && other.startMs < endMs

    fun contains(ms: Long): Boolean = ms in startMs until endMs
}

/**
 * Streaming silence detector.
 *
 * Splits the WAV into 20 ms windows, computes the per-channel RMS of each
 * window, and reports windows whose loudness stays below [SilenceParams.thresholdDb]
 * for at least [SilenceParams.minSilenceMs]. Each reported range is shrunk by
 * [SilenceParams.paddingMs] on both sides so that the beginning/end of speech
 * is not clipped.
 */
object SilenceDetector {

    data class SilenceParams(
        val thresholdDb: Int = -45,
        val minSilenceMs: Long = 800L,
        val paddingMs: Long = 150L,
    )

    const val ANALYSIS_WINDOW_MS: Long = 20L

    suspend fun detect(
        wavFile: File,
        channels: Int,
        params: SilenceParams,
        onProgress: (Float) -> Unit = {},
    ): List<TimeRange> = withContext(Dispatchers.IO) {
        val totalPcmBytes = (wavFile.length() - WAV_HEADER_BYTES).coerceAtLeast(0L)
        val bytesPerMs = channels * 2L
        val windowBytes = bytesPerMs * ANALYSIS_WINDOW_MS
        val windowCount = ((totalPcmBytes + windowBytes - 1) / windowBytes).toInt()

        // Loudness (dBFS, max of channel RMS) of every window.
        val dB = FloatArray(windowCount)
        val frameSize = channels * 2
        val windowFrames = (windowBytes / frameSize).toInt()

        RandomAccessFile(wavFile, "r").use { raf ->
            raf.seek(WAV_HEADER_BYTES)
            val buffer = ByteArray(1 shl 20)
            var window = 0
            var framesInWindow = 0
            val sumSq = DoubleArray(channels)
            var i = 0

            fun flushWindow() {
                if (window >= windowCount) return
                var best = -200f
                for (ch in 0 until channels) {
                    val mean = sumSq[ch] / windowFrames.coerceAtLeast(1)
                    val rms = sqrt(mean)
                    val db = if (rms < 1.0) -200f else (20.0 * log10(rms)).toFloat()
                    if (db > best) best = db
                }
                dB[window++] = best
                for (ch in 0 until channels) sumSq[ch] = 0.0
                framesInWindow = 0
            }

            while (true) {
                currentCoroutineContext().ensureActive()
                val read = raf.read(buffer)
                if (read <= 0) break
                i = 0
                while (i + frameSize <= read) {
                    for (ch in 0 until channels) {
                        val lo = buffer[i + ch * 2].toInt() and 0xFF
                        val hi = buffer[i + ch * 2 + 1].toInt()
                        val raw = (hi shl 8) or lo
                        val signed = if (raw >= 0x8000) raw - 0x10000 else raw
                        sumSq[ch] += signed.toDouble() * signed
                    }
                    i += frameSize
                    framesInWindow++
                    if (framesInWindow >= windowFrames) flushWindow()
                }
                onProgress((i.toFloat() / totalPcmBytes.coerceAtLeast(1L)).coerceIn(0f, 1f))
            }
            if (framesInWindow > 0) flushWindow()
        }

        // Find runs of windows below the threshold.
        val result = ArrayList<TimeRange>()
        var runStart = -1L
        for (w in 0 until windowCount) {
            val silent = dB[w] < params.thresholdDb
            if (silent && runStart < 0L) {
                runStart = w.toLong() * ANALYSIS_WINDOW_MS
            } else if (!silent && runStart >= 0L) {
                val runEnd = w.toLong() * ANALYSIS_WINDOW_MS
                addIfLongEnough(result, runStart, runEnd, params)
                runStart = -1L
            }
        }
        if (runStart >= 0L) {
            addIfLongEnough(result, runStart, windowCount.toLong() * ANALYSIS_WINDOW_MS, params)
        }
        result
    }

    private fun addIfLongEnough(
        out: MutableList<TimeRange>,
        rawStartMs: Long,
        rawEndMs: Long,
        params: SilenceParams,
    ) {
        if (rawEndMs - rawStartMs < params.minSilenceMs) return
        val start = (rawStartMs + params.paddingMs).coerceAtMost(rawEndMs)
        val end = (rawEndMs - params.paddingMs).coerceAtLeast(start)
        if (end - start > 0L) out.add(TimeRange(start, end))
    }

    private const val WAV_HEADER_BYTES = 44L
}
