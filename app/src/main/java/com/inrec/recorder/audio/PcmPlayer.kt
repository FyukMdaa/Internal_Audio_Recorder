package com.inrec.recorder.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.io.RandomAccessFile
import kotlin.concurrent.thread

/**
 * Streams the kept ranges of a 16-bit PCM/WAV file to an [AudioTrack],
 * honouring the current edit (cut regions are skipped).
 *
 * Positions exposed via [Listener] are on the *source* timeline.
 */
class PcmPlayer(
    private val wavFile: File,
    private val sampleRate: Int,
    private val channels: Int,
    private val listener: Listener,
) {
    interface Listener {
        fun onPlaybackChanged(playing: Boolean)
        fun onPositionChanged(sourceMs: Long)
        fun onPlaybackEnded()
    }

    private class SegmentCursor(
        val segmentIndex: Int,
        val startMs: Long,
        val startByte: Long,
        val endByte: Long,
        var pos: Long,
    )

    @Volatile
    private var kept: List<TimeRange> = emptyList()

    @Volatile
    private var running = false

    @Volatile
    private var paused = false

    /** Pending seek target on the *edited* timeline, or -1 when absent. */
    @Volatile
    private var pendingSeekEditedMs: Long = -1L

    private var track: AudioTrack? = null
    private var worker: Thread? = null
    private var readHandle: RandomAccessFile? = null
    private val lock = Any()

    /** Kept-stream byte offset where playback last restarted (after a seek/flush). */
    private var streamOffsetAtRestart = 0L

    /** Bytes written to the track since the last restart. */
    @Volatile
    private var writtenBytesSinceRestart = 0L

    val isPlaying: Boolean get() = running && !paused

    /** Starts playback of [ranges] from [startEditedMs] (edited-timeline position). */
    fun start(ranges: List<TimeRange>, startEditedMs: Long = 0L) {
        synchronized(lock) {
            stopLocked()
            if (ranges.isEmpty()) {
                listener.onPlaybackEnded()
                return
            }
            kept = ranges
            running = true
            paused = false
            pendingSeekEditedMs = startEditedMs
            val minBuf = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT,
            ).coerceAtLeast(1024)
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(
                            if (channels == 1) AudioFormat.CHANNEL_OUT_MONO
                            else AudioFormat.CHANNEL_OUT_STEREO
                        )
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(maxOf(minBuf * 4, 64 * 1024))
                .build()
            worker = thread(name = "pcm-player", isDaemon = true) { loop() }
            listener.onPlaybackChanged(true)
        }
    }

    fun pause() {
        synchronized(lock) {
            if (!running || paused) return
            paused = true
            try {
                track?.pause()
            } catch (_: IllegalStateException) {
            }
            listener.onPlaybackChanged(false)
        }
    }

    fun resume() {
        synchronized(lock) {
            if (!running || !paused) return
            paused = false
            try {
                track?.play()
            } catch (_: IllegalStateException) {
            }
            listener.onPlaybackChanged(true)
        }
    }

    fun toggle() {
        if (paused) resume() else pause()
    }

    /** Seeks to an *edited* timeline position; works while playing or paused. */
    fun seekToEdited(editedMs: Long) {
        synchronized(lock) {
            if (!running) return
            pendingSeekEditedMs = editedMs
        }
    }

    fun stop() {
        synchronized(lock) { stopLocked() }
    }

    fun release() {
        synchronized(lock) {
            stopLocked()
            try {
                readHandle?.close()
            } catch (_: Exception) {
            }
            readHandle = null
        }
    }

    private fun stopLocked() {
        running = false
        try {
            track?.stop()
        } catch (_: IllegalStateException) {
        }
        try {
            worker?.interrupt()
            worker?.join(1000)
        } catch (_: InterruptedException) {
        }
        worker = null
        try {
            track?.release()
        } catch (_: Exception) {
        }
        track = null
        pendingSeekEditedMs = -1L
    }

    private fun bytesPerMs(): Long = sampleRate * channels * 2L / 1000L

    private fun loop() {
        val chunk = ByteArray(32 * 1024)
        try {
            track?.play()
            while (running && !Thread.currentThread().isInterrupted) {
                val target = pendingSeekEditedMs
                if (target >= 0L) {
                    pendingSeekEditedMs = -1L
                    applySeekLocked(target)
                    if (!running) return
                }
                if (paused) {
                    Thread.sleep(30)
                    continue
                }
                val cursor = currentCursor() ?: run {
                    drainThenFinish()
                    return
                }
                val want = minOf(chunk.size.toLong(), cursor.endByte - cursor.pos).toInt()
                if (want <= 0) {
                    advanceCursor()
                    continue
                }
                val read = readAt(cursor.pos, chunk, want)
                if (read <= 0) {
                    advanceCursor()
                    continue
                }
                val written = track?.write(chunk, 0, read) ?: break
                if (written < 0) break
                cursor.pos += written
                writtenBytesSinceRestart += written
                reportPosition()
            }
        } catch (_: InterruptedException) {
            // Stopped from outside.
        } catch (_: Exception) {
            // Audio error: end quietly.
        } finally {
            synchronized(lock) {
                if (running) {
                    running = false
                    listener.onPlaybackChanged(false)
                }
            }
        }
    }

    /** Waits until everything queued in the track has been played, then ends. */
    private fun drainThenFinish() {
        val track = track ?: return finishPlayback()
        val frameBytes = channels * 2
        while (running && !Thread.currentThread().isInterrupted && !paused) {
            val head = try {
                track.playbackHeadPosition
            } catch (_: Exception) {
                break
            }
            if (head * frameBytes >= writtenBytesSinceRestart) break
            Thread.sleep(30)
        }
        finishPlayback()
    }

    private fun reportPosition() {
        val track = track ?: return
        val head = try {
            track.playbackHeadPosition
        } catch (_: Exception) {
            return
        }
        val playedBytes = streamOffsetAtRestart + head * (channels * 2).toLong()
        val sourceMs = keptBytesToSourceMs(playedBytes)
        listener.onPositionChanged(sourceMs)
    }

    /** Maps a position in the concatenated kept stream to a source-timeline position. */
    private fun keptBytesToSourceMs(playedBytes: Long): Long {
        var remaining = playedBytes
        for (r in kept) {
            val segBytes = Segments.byteOffsetFor(sampleRate, channels, r.endMs) -
                Segments.byteOffsetFor(sampleRate, channels, r.startMs)
            if (remaining < segBytes) {
                val offsetMs = remaining / bytesPerMs()
                return r.startMs + offsetMs
            }
            remaining -= segBytes
        }
        return kept.lastOrNull()?.endMs ?: 0L
    }

    private fun applySeekLocked(editedMs: Long) {
        val sourceMs = Segments.editedToSource(kept, editedMs)
        val byteTarget = sourceMs * bytesPerMs()
        var index = -1
        var cursor: SegmentCursor? = null
        var streamOffset = 0L
        for (i in kept.indices) {
            val r = kept[i]
            val startByte = Segments.byteOffsetFor(sampleRate, channels, r.startMs)
            val endByte = Segments.byteOffsetFor(sampleRate, channels, r.endMs)
            if (byteTarget in startByte..endByte) {
                index = i
                cursor = SegmentCursor(i, r.startMs, startByte, endByte, byteTarget.coerceIn(startByte, endByte))
                break
            }
            streamOffset += endByte - startByte
        }
        currentSegment = cursor
        currentSegmentIndex = if (index >= 0) index else kept.size
        streamOffsetAtRestart = streamOffset + (cursor?.let { it.pos - it.startByte } ?: 0L)
        writtenBytesSinceRestart = 0L
        // Flush queued audio and restart output so the seek is audible immediately.
        val t = track
        if (t != null) {
            try {
                t.pause()
                t.flush()
            } catch (_: IllegalStateException) {
            }
            try {
                t.play()
            } catch (_: IllegalStateException) {
            }
        }
        listener.onPositionChanged(sourceMs)
        if (paused) {
            try {
                t?.pause()
            } catch (_: IllegalStateException) {
            }
        }
    }

    private var currentSegmentIndex = 0
    private var currentSegment: SegmentCursor? = null

    private fun currentCursor(): SegmentCursor? {
        if (currentSegment == null) {
            while (currentSegmentIndex < kept.size) {
                val r = kept[currentSegmentIndex]
                val startByte = Segments.byteOffsetFor(sampleRate, channels, r.startMs)
                val endByte = Segments.byteOffsetFor(sampleRate, channels, r.endMs)
                if (endByte > startByte) {
                    currentSegment = SegmentCursor(
                        currentSegmentIndex, r.startMs, startByte, endByte, startByte,
                    )
                    break
                }
                currentSegmentIndex++
            }
        }
        return currentSegment
    }

    private fun advanceCursor() {
        currentSegment = null
        currentSegmentIndex++
    }

    private fun readAt(offset: Long, buffer: ByteArray, len: Int): Int {
        val handle = readHandle ?: RandomAccessFile(wavFile, "r").also { readHandle = it }
        handle.seek(WAV_HEADER_BYTES + offset)
        return handle.read(buffer, 0, len)
    }

    private fun finishPlayback() {
        listener.onPlaybackChanged(false)
        listener.onPlaybackEnded()
        synchronized(lock) { stopLocked() }
    }

    private companion object {
        const val WAV_HEADER_BYTES = 44L
    }
}
