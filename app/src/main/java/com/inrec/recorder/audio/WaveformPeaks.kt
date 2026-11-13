package com.inrec.recorder.audio

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * One amplitude value (0f..1f) per [WINDOW_MS] of audio, computed as the peak
 * absolute sample across both channels. Cached next to the WAV as a .peaks file.
 */
object WaveformPeaks {

    const val WINDOW_MS: Long = 10L

    private const val CACHE_MAGIC = 0x50454B53 // "PEKS"
    private const val WAV_HEADER_BYTES = 44L

    fun cacheFileFor(wavFile: File): File =
        File(wavFile.parentFile, wavFile.nameWithoutExtension + ".peaks")

    /**
     * Returns the peak array, computing it on [Dispatchers.IO] if necessary.
     * The cache is invalidated when the WAV file changes (length / lastModified).
     */
    suspend fun loadOrCompute(
        wavFile: File,
        channels: Int,
        onProgress: (Float) -> Unit = {},
    ): FloatArray = withContext(Dispatchers.IO) {
        val cache = cacheFileFor(wavFile)
        readCache(cache)?.let { return@withContext it }
        val peaks = compute(wavFile, channels, onProgress)
        writeCache(cache, wavFile, peaks)
        peaks
    }

    suspend fun compute(
        wavFile: File,
        channels: Int,
        onProgress: (Float) -> Unit = {},
    ): FloatArray = withContext(Dispatchers.IO) {
        val bytesPerMs = channels * 2L // 16-bit samples
        val bytesPerWindow = bytesPerMs * WINDOW_MS
        val totalPcmBytes = (wavFile.length() - WAV_HEADER_BYTES).coerceAtLeast(0L)
        val windowCount = ((totalPcmBytes + bytesPerWindow - 1) / bytesPerWindow).toInt()

        val peaks = FloatArray(windowCount)
        val buffer = ByteArray(1 shl 20)

        var window = 0
        var windowPeak = 0
        var windowRemaining = bytesPerWindow.toInt()

        RandomAccessChunkReader(wavFile, WAV_HEADER_BYTES).use { reader ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = reader.read(buffer)
                if (read <= 0) break
                var i = 0
                while (i < read) {
                    val take = minOf(windowRemaining, read - i)
                    var j = i
                    val end = i + take
                    while (j + 1 < end) {
                        val lo = buffer[j].toInt() and 0xFF
                        val hi = buffer[j + 1].toInt()
                        val sample = (hi shl 8) or lo
                        val signed = if (sample >= 0x8000) sample - 0x10000 else sample
                        val abs = if (signed < 0) -signed else signed
                        if (abs > windowPeak) windowPeak = abs
                        j += 2
                    }
                    i += take
                    windowRemaining -= take
                    if (windowRemaining <= 0 && window < windowCount) {
                        peaks[window++] = (windowPeak / 32768f).coerceIn(0f, 1f)
                        windowPeak = 0
                        windowRemaining = bytesPerWindow.toInt()
                    }
                }
                onProgress((reader.consumed().toFloat() / totalPcmBytes.coerceAtLeast(1L)).coerceIn(0f, 1f))
            }
        }
        while (window < windowCount) {
            peaks[window++] = (windowPeak / 32768f).coerceIn(0f, 1f)
            windowPeak = 0
        }
        peaks
    }

    private class RandomAccessChunkReader(
        file: File,
        private val startOffset: Long,
    ) : AutoCloseable {
        private val raf = java.io.RandomAccessFile(file, "r")
        private var consumed = 0L

        init {
            raf.seek(startOffset)
        }

        fun read(buffer: ByteArray): Int = raf.read(buffer).also {
            if (it > 0) consumed += it
        }

        fun consumed(): Long = consumed

        override fun close() {
            raf.close()
        }
    }

    private fun readCache(cache: File): FloatArray? = try {
        val wav = File(cache.parentFile, cache.nameWithoutExtension + ".wav")
        DataInputStream(FileInputStream(cache)).use { input ->
            val magic = input.readInt()
            if (magic != CACHE_MAGIC) return null
            val windowMs = input.readLong()
            if (windowMs != WINDOW_MS) return null
            val sourceLength = input.readLong()
            val sourceModified = input.readLong()
            if (sourceLength != wav.length() || sourceModified != wav.lastModified()) return null
            val count = input.readInt()
            if (count <= 0 || count > 10_000_000) return null
            val values = FloatArray(count)
            for (i in 0 until count) values[i] = input.readFloat()
            values
        }
    } catch (_: Exception) {
        null
    }

    private fun writeCache(cache: File, source: File, peaks: FloatArray) {
        try {
            DataOutputStream(FileOutputStream(cache)).use { out ->
                out.writeInt(CACHE_MAGIC)
                out.writeLong(WINDOW_MS)
                out.writeLong(source.length())
                out.writeLong(source.lastModified())
                out.writeInt(peaks.size)
                for (v in peaks) out.writeFloat(v)
            }
        } catch (_: Exception) {
            // Cache is optional; ignore write failures.
        }
    }
}
