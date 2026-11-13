package com.inrec.recorder.audio

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * Streams 16-bit PCM data into a RIFF/WAVE file.
 *
 * The 44-byte header is written up-front with placeholder sizes and patched
 * periodically (every [PATCH_INTERVAL_BYTES]) so that the file stays playable
 * even if the process is killed mid-recording.
 */
class WavWriter(
    private val file: File,
    private val sampleRate: Int,
    private val channels: Int,
    private val bitsPerSample: Short = 16,
) {
    private val out = BufferedOutputStream(FileOutputStream(file), 256 * 1024)
    private var dataBytes = 0L
    private var lastPatchBytes = 0L
    private var closed = false

    init {
        out.write(buildHeader(dataBytes))
    }

    val currentDataBytes: Long get() = dataBytes

    fun write(pcm: ByteArray, offset: Int = 0, length: Int = pcm.size) {
        check(!closed) { "WavWriter already closed" }
        out.write(pcm, offset, length)
        dataBytes += length
        if (dataBytes - lastPatchBytes >= PATCH_INTERVAL_BYTES) {
            patchHeader()
        }
    }

    /** Flushes buffered data and writes the final, correct header. */
    fun finish() {
        if (closed) return
        out.flush()
        patchHeader()
        closed = true
        out.close()
    }

    /** Best-effort finalization for error paths; swallows IO problems. */
    fun closeQuietly() {
        try {
            finish()
        } catch (_: Exception) {
            try {
                out.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun patchHeader() {
        out.flush()
        lastPatchBytes = dataBytes
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(4)
            raf.write_le_int((36 + dataBytes).toInt())
            raf.seek(40)
            raf.write_le_int(dataBytes.toInt())
        }
    }

    private fun RandomAccessFile.write_le_int(v: Int) {
        write(v and 0xFF)
        write((v shr 8) and 0xFF)
        write((v shr 16) and 0xFF)
        write((v shr 24) and 0xFF)
    }

    private fun shortLe(v: Int, into: ByteArray, at: Int) {
        into[at] = (v and 0xFF).toByte()
        into[at + 1] = ((v shr 8) and 0xFF).toByte()
    }

    private fun intLe(v: Long, into: ByteArray, at: Int) {
        into[at] = (v and 0xFF).toByte()
        into[at + 1] = ((v shr 8) and 0xFF).toByte()
        into[at + 2] = ((v shr 16) and 0xFF).toByte()
        into[at + 3] = ((v shr 24) and 0xFF).toByte()
    }

    private fun buildHeader(dataSize: Long): ByteArray {
        val blockAlign = channels * (bitsPerSample / 8)
        val byteRate = sampleRate * blockAlign
        val header = ByteArray(44)
        // "RIFF"
        header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
        intLe(36 + dataSize, header, 4)
        // "WAVE"
        header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
        // "fmt "
        header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
        intLe(16, header, 16)
        shortLe(1, header, 20) // PCM
        shortLe(channels, header, 22)
        intLe(sampleRate.toLong(), header, 24)
        intLe(byteRate.toLong(), header, 28)
        shortLe(blockAlign, header, 32)
        shortLe(bitsPerSample.toInt(), header, 34)
        // "data"
        header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
        intLe(dataSize, header, 40)
        return header
    }

    private companion object {
        /** Patch the header at least once every ~5 seconds of audio. */
        const val PATCH_INTERVAL_BYTES = 48000L * 2 * 2 * 5
    }
}

/** Writes the .meta.json sidecar that keeps title / format info next to the WAV. */
object RecordingMeta {
    private const val FILE_SUFFIX = ".meta.json"

    fun metaFileFor(wavFile: File): File =
        File(wavFile.parentFile, wavFile.nameWithoutExtension + FILE_SUFFIX)

    fun write(
        wavFile: File,
        title: String,
        createdAt: Long,
        sampleRate: Int,
        channels: Int,
        durationMs: Long,
    ) {
        val json = buildString {
            append("{\n")
            append("  \"title\": ").append(quote(title)).append(",\n")
            append("  \"createdAt\": ").append(createdAt).append(",\n")
            append("  \"sampleRate\": ").append(sampleRate).append(",\n")
            append("  \"channels\": ").append(channels).append(",\n")
            append("  \"durationMs\": ").append(durationMs).append("\n")
            append("}\n")
        }
        File(wavFile.parentFile, wavFile.nameWithoutExtension + FILE_SUFFIX)
            .writeText(json, Charsets.UTF_8)
    }

    private fun quote(s: String): String = "\"" + s
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t") + "\""
}
