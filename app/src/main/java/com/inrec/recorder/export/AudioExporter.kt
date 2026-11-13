package com.inrec.recorder.export

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import com.inrec.recorder.audio.Segments
import com.inrec.recorder.audio.TimeRange
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile

/** Supported export formats. Content is always encoded by the platform codecs. */
enum class ExportFormat(
    val extension: String,
    val mimeType: String,
    val codecMime: String?,
) {
    WAV("wav", "audio/wav", null),
    M4A("m4a", "audio/mp4", MediaFormat.MIMETYPE_AUDIO_AAC),
    FLAC("flac", "audio/flac", MediaFormat.MIMETYPE_AUDIO_FLAC),
    OPUS("opus", "audio/ogg", MediaFormat.MIMETYPE_AUDIO_OPUS),
}

/** Thrown when a format cannot be produced on this device. */
class ExportException(message: String) : Exception(message)

/**
 * Exports the kept ranges of a 16-bit stereo PCM/WAV file into the requested
 * format. Work happens on the calling thread — invoke from Dispatchers.IO.
 */
object AudioExporter {

    private const val WAV_HEADER_BYTES = 44L

    fun isSupported(format: ExportFormat): Boolean {
        val mime = format.codecMime ?: return true
        return findEncoder(mime) != null
    }

    fun export(
        source: File,
        sampleRate: Int,
        channels: Int,
        kept: List<TimeRange>,
        format: ExportFormat,
        outFile: File,
        bitrate: Int = 128_000,
        onProgress: (Float) -> Unit = {},
    ) {
        when (format) {
            ExportFormat.WAV -> exportWav(source, sampleRate, channels, kept, outFile, onProgress)
            ExportFormat.M4A -> encodeWithMuxer(
                source, sampleRate, channels, kept, outFile,
                MediaFormat.MIMETYPE_AUDIO_AAC, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
                bitrate, onProgress,
            )
            ExportFormat.OPUS -> encodeWithMuxer(
                source, sampleRate, channels, kept, outFile,
                MediaFormat.MIMETYPE_AUDIO_OPUS, MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG,
                bitrate, onProgress,
            )
            ExportFormat.FLAC -> exportFlac(source, sampleRate, channels, kept, outFile, onProgress)
        }
    }

    // ------------------------------------------------------------------ WAV

    private fun exportWav(
        source: File,
        sampleRate: Int,
        channels: Int,
        kept: List<TimeRange>,
        outFile: File,
        onProgress: (Float) -> Unit,
    ) {
        val totalBytes = totalBytesOf(kept, sampleRate, channels)
        PcmChunkReader(source, sampleRate, channels, kept).use { reader ->
            FileOutputStream(outFile).use { out ->
                out.write(wavHeader(sampleRate, channels, totalBytes))
                copyChunks(reader, out, totalBytes, onProgress)
            }
        }
    }

    private fun wavHeader(sampleRate: Int, channels: Int, dataSize: Long): ByteArray {
        val blockAlign = channels * 2
        val byteRate = sampleRate * blockAlign
        val h = ByteArray(44)
        fun putStr(at: Int, s: String) { for (i in s.indices) h[at + i] = s[i].code.toByte() }
        fun putInt(at: Int, v: Long) {
            h[at] = (v and 0xFF).toByte(); h[at + 1] = ((v shr 8) and 0xFF).toByte()
            h[at + 2] = ((v shr 16) and 0xFF).toByte(); h[at + 3] = ((v shr 24) and 0xFF).toByte()
        }
        fun putShort(at: Int, v: Int) {
            h[at] = (v and 0xFF).toByte(); h[at + 1] = ((v shr 8) and 0xFF).toByte()
        }
        putStr(0, "RIFF"); putInt(4, 36 + dataSize); putStr(8, "WAVE")
        putStr(12, "fmt "); putInt(16, 16); putShort(20, 1)
        putShort(22, channels); putInt(24, sampleRate.toLong())
        putInt(28, byteRate.toLong()); putShort(32, blockAlign); putShort(34, 16)
        putStr(36, "data"); putInt(40, dataSize)
        return h
    }

    // ------------------------------------------------------- AAC-M4A / Opus

    private fun encodeWithMuxer(
        source: File,
        sampleRate: Int,
        channels: Int,
        kept: List<TimeRange>,
        outFile: File,
        codecMime: String,
        muxerFormat: Int,
        bitrate: Int,
        onProgress: (Float) -> Unit,
    ) {
        val encoderName = findEncoder(codecMime)
            ?: throw ExportException("No $codecMime encoder on this device")

        val format = MediaFormat.createAudioFormat(codecMime, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
            if (codecMime == MediaFormat.MIMETYPE_AUDIO_AAC) {
                setInteger(
                    MediaFormat.KEY_AAC_PROFILE,
                    android.media.MediaCodecInfo.CodecProfileLevel.AACObjectLC,
                )
            }
        }

        val codec = MediaCodec.createByCodecName(encoderName)
        val muxer = MediaMuxer(outFile.absolutePath, muxerFormat)
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            val bufferInfo = MediaCodec.BufferInfo()
            var trackIndex = -1
            var muxerStarted = false
            var samplesWritten = 0L

            PcmChunkReader(source, sampleRate, channels, kept).use { reader ->
                val totalBytes = totalBytesOf(kept, sampleRate, channels)
                val inBuf = ByteArray(32 * 1024)
                var inputDone = false
                var outputDone = false

                while (!outputDone) {
                    if (!inputDone) {
                        val inIndex = codec.dequeueInputBuffer(10_000L)
                        if (inIndex >= 0) {
                            val inputBuffer = codec.getInputBuffer(inIndex)!!
                            inputBuffer.clear()
                            val read = reader.read(inBuf, minOf(inBuf.size, inputBuffer.remaining()))
                            if (read > 0) {
                                inputBuffer.put(inBuf, 0, read)
                                val pts = samplesWritten * 1_000_000L / sampleRate
                                samplesWritten += read / (channels * 2)
                                codec.queueInputBuffer(inIndex, 0, read, pts, 0)
                            } else {
                                val pts = samplesWritten * 1_000_000L / sampleRate
                                codec.queueInputBuffer(
                                    inIndex, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                                )
                                inputDone = true
                            }
                            onProgress((reader.consumed().toFloat() / totalBytes.coerceAtLeast(1)).coerceIn(0f, 1f))
                        }
                    }

                    val outIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000L)
                    when {
                        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val outFormat = codec.outputFormat
                            trackIndex = muxer.addTrack(outFormat)
                            muxer.start()
                            muxerStarted = true
                        }

                        outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit

                        outIndex >= 0 -> {
                            val encoded = codec.getOutputBuffer(outIndex)!!
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                bufferInfo.size = 0 // csd consumed by muxer via track format
                            }
                            if (bufferInfo.size > 0 && trackIndex >= 0 && muxerStarted) {
                                encoded.position(bufferInfo.offset)
                                encoded.limit(bufferInfo.offset + bufferInfo.size)
                                muxer.writeSampleData(trackIndex, encoded, bufferInfo)
                            }
                            codec.releaseOutputBuffer(outIndex, false)
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                outputDone = true
                            }
                        }
                    }
                }
            }
            if (muxerStarted) {
                muxer.stop()
            }
        } finally {
            try {
                codec.stop()
            } catch (_: Exception) {
            }
            try {
                codec.release()
            } catch (_: Exception) {
            }
            try {
                muxer.release()
            } catch (_: Exception) {
            }
        }
    }

    // ----------------------------------------------------------------- FLAC

    /**
     * FLAC has no MediaMuxer container support, so the raw FLAC frames from the
     * encoder are wrapped by hand: "fLaC" magic + STREAMINFO metadata block
     * (provided by the encoder as csd-0) + concatenated audio frames.
     */
    private fun exportFlac(
        source: File,
        sampleRate: Int,
        channels: Int,
        kept: List<TimeRange>,
        outFile: File,
        onProgress: (Float) -> Unit,
    ) {
        val encoderName = findEncoder(MediaFormat.MIMETYPE_AUDIO_FLAC)
            ?: throw ExportException("No FLAC encoder on this device")

        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_FLAC, sampleRate, channels,
        )
        val codec = MediaCodec.createByCodecName(encoderName)
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            val bufferInfo = MediaCodec.BufferInfo()
            var streamInfo: ByteArray? = null

            PcmChunkReader(source, sampleRate, channels, kept).use { reader ->
                val totalBytes = totalBytesOf(kept, sampleRate, channels)
                val inBuf = ByteArray(32 * 1024)
                FileOutputStream(outFile).use { out ->
                    out.write(byteArrayOf('f'.code.toByte(), 'L'.code.toByte(), 'a'.code.toByte(), 'C'.code.toByte()))

                    var inputDone = false
                    var outputDone = false
                    var wroteStreamInfo = false

                    while (!outputDone) {
                        if (!inputDone) {
                            val inIndex = codec.dequeueInputBuffer(10_000L)
                            if (inIndex >= 0) {
                                val inputBuffer = codec.getInputBuffer(inIndex)!!
                                inputBuffer.clear()
                                val read = reader.read(inBuf, minOf(inBuf.size, inputBuffer.remaining()))
                                if (read > 0) {
                                    inputBuffer.put(inBuf, 0, read)
                                    codec.queueInputBuffer(inIndex, 0, read, 0, 0)
                                } else {
                                    codec.queueInputBuffer(
                                        inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                                    )
                                    inputDone = true
                                }
                                onProgress((reader.consumed().toFloat() / totalBytes.coerceAtLeast(1)).coerceIn(0f, 1f))
                            }
                        }

                        val outIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000L)
                        when {
                            outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                streamInfo = codec.outputFormat.getByteBuffer("csd-0")?.let { bb ->
                                    val arr = ByteArray(bb.remaining())
                                    bb.get(arr)
                                    arr
                                }
                            }

                            outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit

                            outIndex >= 0 -> {
                                val encoded = codec.getOutputBuffer(outIndex)!!
                                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                    // csd-0 == STREAMINFO (34 bytes)
                                    val arr = ByteArray(bufferInfo.size)
                                    encoded.position(bufferInfo.offset)
                                    encoded.get(arr)
                                    streamInfo = arr
                                } else if (bufferInfo.size > 0) {
                                    if (!wroteStreamInfo) {
                                        val info = streamInfo
                                        if (info == null) {
                                            throw ExportException("FLAC encoder produced no STREAMINFO")
                                        }
                                        writeFlacStreamInfoBlock(out, info)
                                        wroteStreamInfo = true
                                    }
                                    encoded.position(bufferInfo.offset)
                                    encoded.limit(bufferInfo.offset + bufferInfo.size)
                                    out.write(encoded)
                                }
                                codec.releaseOutputBuffer(outIndex, false)
                                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                    outputDone = true
                                }
                            }
                        }
                    }
                }
            }
        } finally {
            try {
                codec.stop()
            } catch (_: Exception) {
            }
            try {
                codec.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun writeFlacStreamInfoBlock(out: OutputStream, streamInfo: ByteArray) {
        // Metadata block header: last-block flag + block type 0 (STREAMINFO) + 24-bit length.
        out.write(0x80)                    // 1xxx xxxx : last metadata block
        out.write(0x00)                    // block type = STREAMINFO
        out.write((streamInfo.size shr 16) and 0xFF)
        out.write((streamInfo.size shr 8) and 0xFF)
        out.write(streamInfo.size and 0xFF)
        out.write(streamInfo)
    }

    // ------------------------------------------------------------- plumbing

    private fun findEncoder(mime: String): String? {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        for (info in list.codecInfos) {
            if (!info.isEncoder) continue
            if (info.supportedTypes.contains(mime)) return info.name
        }
        return null
    }

    private fun totalBytesOf(kept: List<TimeRange>, sampleRate: Int, channels: Int): Long =
        kept.sumOf { range ->
            Segments.byteOffsetFor(sampleRate, channels, range.endMs) -
                Segments.byteOffsetFor(sampleRate, channels, range.startMs)
        }

    /**
     * Sequential reader over the kept ranges of the WAV's PCM payload.
     * [consumed] reports bytes served for progress reporting.
     */
    private class PcmChunkReader(
        source: File,
        sampleRate: Int,
        channels: Int,
        kept: List<TimeRange>,
    ) : AutoCloseable {

        private val raf = RandomAccessFile(source, "r")
        private val segments: List<Pair<Long, Long>> = kept.map { range ->
            Segments.byteOffsetFor(sampleRate, channels, range.startMs) to
                Segments.byteOffsetFor(sampleRate, channels, range.endMs)
        }
        private var segmentIndex = 0
        private var position = 0L
        private var consumed = 0L

        fun consumed(): Long = consumed

        /** Reads up to [maxLen] bytes into [buffer], returns the number read (0 at EOF). */
        fun read(buffer: ByteArray, maxLen: Int): Int {
            while (segmentIndex < segments.size) {
                val (start, end) = segments[segmentIndex]
                if (position == 0L) position = start
                if (position < end) {
                    val want = minOf(maxLen, (end - position).toInt())
                    raf.seek(WAV_HEADER_BYTES + position)
                    val read = raf.read(buffer, 0, want)
                    if (read > 0) {
                        position += read
                        consumed += read
                        return read
                    }
                }
                segmentIndex++
                position = 0L
            }
            return 0
        }

        override fun close() {
            raf.close()
        }
    }

    private fun copyChunks(
        reader: PcmChunkReader,
        out: OutputStream,
        totalBytes: Long,
        onProgress: (Float) -> Unit,
    ) {
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = reader.read(buf, buf.size)
            if (n <= 0) break
            out.write(buf, 0, n)
            onProgress((reader.consumed().toFloat() / totalBytes.coerceAtLeast(1)).coerceIn(0f, 1f))
        }
    }
}
