package com.inrec.recorder.data

import android.content.Context
import com.inrec.recorder.audio.RecordingMeta
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Recording(
    val file: File,
    val title: String,
    val createdAt: Long,
    val sampleRate: Int,
    val channels: Int,
    val durationMs: Long,
    val sizeBytes: Long,
) {
    val id: String get() = file.nameWithoutExtension
}

data class WavInfo(val sampleRate: Int, val channels: Int, val durationMs: Long)

/**
 * Filesystem-backed repository for recordings stored in the app-specific
 * external directory `recordings/` as WAV files with `.meta.json` sidecars.
 */
class RecordingsRepository(context: Context) {

    val dir: File =
        (context.getExternalFilesDir(null) ?: context.filesDir)
            .let { File(it, "recordings") }

    init {
        dir.mkdirs()
    }

    fun list(): List<Recording> {
        val wavs = dir.listFiles { f -> f.isFile && f.extension.equals("wav", ignoreCase = true) } ?: emptyArray()
        return wavs.mapNotNull { wav ->
            try {
                val info = readWavInfo(wav)
                Recording(
                    file = wav,
                    title = readTitle(wav) ?: wav.nameWithoutExtension,
                    createdAt = wav.lastModified(),
                    sampleRate = info.sampleRate,
                    channels = info.channels,
                    durationMs = info.durationMs,
                    sizeBytes = wav.length(),
                )
            } catch (_: Exception) {
                null
            }
        }.sortedByDescending { it.createdAt }
    }

    fun get(id: String): Recording? = list().firstOrNull { it.id == id }

    fun newRecordingFile(): File =
        File(dir, "REC_${stamp(System.currentTimeMillis())}.wav")

    /**
     * Updates the display title stored in the sidecar metadata.
     * The WAV file itself keeps its name (which doubles as the recording id),
     * so previously opened editors stay valid.
     */
    fun renameTitle(recording: Recording, newTitle: String) {
        val clean = newTitle.trim()
        if (clean.isEmpty()) return
        val info = readWavInfo(recording.file)
        RecordingMeta.write(
            recording.file,
            clean,
            recording.createdAt,
            info.sampleRate,
            info.channels,
            info.durationMs,
        )
    }

    fun delete(recording: Recording) {
        recording.file.delete()
        RecordingMeta.metaFileFor(recording.file).delete()
        com.inrec.recorder.audio.WaveformPeaks.cacheFileFor(recording.file).delete()
    }

    fun readTitle(wavFile: File): String? = try {
        val json = RecordingMeta.metaFileFor(wavFile).readText(Charsets.UTF_8)
        extractString(json, "title")
    } catch (_: Exception) {
        null
    }

    fun readWavInfo(wavFile: File): WavInfo = try {
        // Prefer the sidecar metadata if present and consistent.
        val json = RecordingMeta.metaFileFor(wavFile).readText(Charsets.UTF_8)
        val rate = extractLong(json, "sampleRate")?.toInt()
        val channels = extractLong(json, "channels")?.toInt()
        val duration = extractLong(json, "durationMs")
        val parsed = parseWavHeader(wavFile)
        WavInfo(
            sampleRate = rate ?: parsed.sampleRate,
            channels = channels ?: parsed.channels,
            durationMs = duration ?: parsed.durationMs,
        )
    } catch (_: Exception) {
        parseWavHeader(wavFile)
    }

    /** Reads sampleRate / channels / duration directly from the 44-byte WAV header. */
    private fun parseWavHeader(wavFile: File): WavInfo {
        RandomAccessFile(wavFile, "r").use { raf ->
            val header = ByteArray(44)
            raf.readFully(header)
            fun leInt(at: Int): Int =
                (header[at].toInt() and 0xFF) or
                    ((header[at + 1].toInt() and 0xFF) shl 8) or
                    ((header[at + 2].toInt() and 0xFF) shl 16) or
                    ((header[at + 3].toInt() and 0xFF) shl 24)

            fun leShort(at: Int): Int =
                (header[at].toInt() and 0xFF) or ((header[at + 1].toInt() and 0xFF) shl 8)

            val channels = leShort(22).coerceAtLeast(1)
            val rate = leInt(24).coerceAtLeast(1)
            val dataSize = leInt(40)
            val durationMs = if (dataSize > 0) {
                dataSize * 1000L / (rate.toLong() * channels * 2L)
            } else {
                val dataBytes = (raf.length() - 44L).coerceAtLeast(0L)
                dataBytes * 1000L / (rate.toLong() * channels * 2L)
            }
            return WavInfo(rate, channels, durationMs)
        }
    }

    private fun extractString(json: String, key: String): String? {
        val pattern = Regex("\"$key\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
        val match = pattern.find(json) ?: return null
        return match.groupValues[1]
            .replace("\\\\", "\\")
            .replace("\\\"", "\"")
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t")
    }

    private fun extractLong(json: String, key: String): Long? =
        Regex("\"$key\"\\s*:\\s*(-?\\d+)").find(json)?.groupValues?.get(1)?.toLong()

    private fun stamp(ms: Long): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(ms))
}
