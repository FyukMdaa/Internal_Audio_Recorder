package com.inrec.recorder.capture

import android.os.SystemClock
import java.util.concurrent.CopyOnWriteArrayList

/** Recording lifecycle shared between the service, the QS tile and the UI. */
enum class RecordState { IDLE, PREPARING, RECORDING, PAUSED, STOPPING }

/**
 * Process-wide recorder state holder.
 *
 * [CaptureService] writes, [RecordTileService] and the UI read. Listeners are
 * notified on the main thread (the service posts updates through the main handler).
 */
object RecorderState {

    @Volatile
    var state: RecordState = RecordState.IDLE
        private set

    @Volatile
    private var startedAtElapsed: Long = 0L

    @Volatile
    private var pausedAtElapsed: Long = 0L

    @Volatile
    private var accumulatedPauseMs: Long = 0L

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
        listener()
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    fun update(newState: RecordState) {
        state = newState
        listeners.forEach { it() }
    }

    fun markRecordingStarted() {
        startedAtElapsed = SystemClock.elapsedRealtime()
        pausedAtElapsed = 0L
        accumulatedPauseMs = 0L
        update(RecordState.RECORDING)
    }

    fun markPaused() {
        if (state != RecordState.RECORDING) return
        pausedAtElapsed = SystemClock.elapsedRealtime()
        update(RecordState.PAUSED)
    }

    fun markResumed() {
        if (state != RecordState.PAUSED) return
        if (pausedAtElapsed > 0L) {
            accumulatedPauseMs += SystemClock.elapsedRealtime() - pausedAtElapsed
        }
        pausedAtElapsed = 0L
        update(RecordState.RECORDING)
    }

    /** Elapsed recording time (pauses excluded), in milliseconds. */
    fun elapsedMs(): Long {
        val now = SystemClock.elapsedRealtime()
        val livePause = if (pausedAtElapsed > 0L) now - pausedAtElapsed else 0L
        return (now - startedAtElapsed - accumulatedPauseMs - livePause).coerceAtLeast(0L)
    }
}
