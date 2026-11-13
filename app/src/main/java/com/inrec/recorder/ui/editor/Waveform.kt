package com.inrec.recorder.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import com.inrec.recorder.audio.TimeRange
import com.inrec.recorder.audio.WaveformPeaks
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToLong

private enum class SelectionDragMode { NEW, LEFT, RIGHT }

/**
 * Main waveform canvas.
 *
 * Gestures:
 * - tap: move the playhead (seek)
 * - long-press + drag: create a selection
 * - long-press near a selection edge and drag: resize the selection
 *
 * Removed (cut) regions are tinted; kept regions are drawn in the wave colour.
 */
@Composable
fun WaveformView(
    peaks: FloatArray,
    durationMs: Long,
    keptRanges: List<TimeRange>,
    selection: TimeRange?,
    playheadMs: Long,
    viewportStartMs: Long,
    viewportMs: Long,
    onSeek: (Long) -> Unit,
    onSelectionUpdate: (TimeRange) -> Unit,
    onSelectionCommit: (TimeRange) -> Unit,
    modifier: Modifier = Modifier,
) {
    val waveColor = MaterialTheme.colorScheme.primary
    val playedColor = MaterialTheme.colorScheme.tertiary
    val cutColor = MaterialTheme.colorScheme.error.copy(alpha = 0.30f)
    val selectionColor = MaterialTheme.colorScheme.secondary
    val selectionFill = selectionColor.copy(alpha = 0.22f)
    val playheadColor = MaterialTheme.colorScheme.onSurface
    val centerLineColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)

    // Gesture callbacks must observe the latest values (pointerInput closures
    // would otherwise capture stale state).
    val selectionState by rememberUpdatedState(selection)
    val onSeekState by rememberUpdatedState(onSeek)
    val onSelectionUpdateState by rememberUpdatedState(onSelectionUpdate)
    val onSelectionCommitState by rememberUpdatedState(onSelectionCommit)

    val windowMs = WaveformPeaks.WINDOW_MS

    Canvas(
        modifier = modifier
            .pointerInput(durationMs, viewportStartMs, viewportMs) {
                detectTapGestures { pos ->
                    val ms = xToMs(pos.x, size.width.toFloat(), viewportStartMs, viewportMs)
                    onSeekState(ms.coerceIn(0L, durationMs))
                }
            }
            .pointerInput(durationMs, viewportStartMs, viewportMs) {
                var mode = SelectionDragMode.NEW
                var anchorMs = 0L
                detectDragGesturesAfterLongPress(
                    onDragStart = { pos ->
                        val ms = xToMs(pos.x, size.width.toFloat(), viewportStartMs, viewportMs)
                        val sel = selectionState
                        val thresholdMs = (viewportMs / 24f).roundToLong()
                        when {
                            sel != null && abs(ms - sel.startMs) <= thresholdMs -> {
                                mode = SelectionDragMode.LEFT
                                anchorMs = sel.endMs
                                onSelectionUpdateState(TimeRange(ms.coerceAtLeast(0L), sel.endMs))
                            }

                            sel != null && abs(ms - sel.endMs) <= thresholdMs -> {
                                mode = SelectionDragMode.RIGHT
                                anchorMs = sel.startMs
                                onSelectionUpdateState(TimeRange(sel.startMs, ms.coerceAtMost(durationMs)))
                            }

                            else -> {
                                mode = SelectionDragMode.NEW
                                anchorMs = ms
                                onSelectionUpdateState(
                                    TimeRange(
                                        ms.coerceIn(0L, durationMs),
                                        ms.coerceIn(0L, durationMs),
                                    ),
                                )
                            }
                        }
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        val ms = xToMs(change.position.x, size.width.toFloat(), viewportStartMs, viewportMs)
                        val start = minOf(anchorMs, ms).coerceIn(0L, durationMs)
                        val end = maxOf(anchorMs, ms).coerceIn(0L, durationMs)
                        onSelectionUpdateState(TimeRange(start, end))
                    },
                    onDragEnd = {
                        val sel = selectionState
                        if (sel != null && sel.durationMs >= 30L) {
                            onSelectionCommitState(sel)
                        }
                    },
                    onDragCancel = {},
                )
            },
    ) {
        val w = size.width
        val h = size.height
        val half = h / 2f

        // Zero axis.
        drawLine(centerLineColor, Offset(0f, half), Offset(w, half), strokeWidth = 1f)

        // Cut (removed) regions.
        val cuts = complementWithin(keptRanges, durationMs)
        for (cut in cuts) {
            val x0 = msToX(cut.startMs, w, viewportStartMs, viewportMs)
            val x1 = msToX(cut.endMs, w, viewportStartMs, viewportMs)
            if (x1 <= 0f || x0 >= w) continue
            val left = x0.coerceAtLeast(0f)
            drawRect(
                cutColor,
                topLeft = Offset(left, 0f),
                size = Size((x1 - left).coerceAtLeast(1f).coerceAtMost(w - left), h),
            )
        }

        // Waveform: one rounded line per 2px column.
        var x = 0f
        while (x < w) {
            val t0 = xToMs(x, w, viewportStartMs, viewportMs)
            val t1 = xToMs(x + 2f, w, viewportStartMs, viewportMs)
            val idx0 = (t0 / windowMs).toInt().coerceIn(0, peaks.size - 1)
            val idx1 = ((t1 / windowMs).toInt()).coerceIn(idx0, peaks.size - 1)
            var peak = 0f
            var i = idx0
            while (i <= idx1) {
                if (peaks[i] > peak) peak = peaks[i]
                i++
            }
            if (peak <= 0f) peak = 0.008f
            val amplitude = peak * half
            val inSelection = selection != null && t0 >= selection.startMs && t0 <= selection.endMs
            val color = when {
                inSelection -> selectionColor
                t0 <= playheadMs -> playedColor
                isCut(cuts, t0) -> cutColor.copy(alpha = 0.85f)
                else -> waveColor
            }
            drawLine(
                color,
                Offset(x, half - amplitude),
                Offset(x, half + amplitude),
                strokeWidth = 2f,
                cap = StrokeCap.Round,
            )
            x += 2f
        }

        // Selection overlay + handles.
        selection?.let { sel ->
            val x0 = msToX(sel.startMs, w, viewportStartMs, viewportMs)
            val x1 = msToX(sel.endMs, w, viewportStartMs, viewportMs)
            if (x1 >= 0f && x0 <= w) {
                val left = x0.coerceIn(0f, w)
                drawRect(
                    selectionFill,
                    topLeft = Offset(left, 0f),
                    size = Size((x1 - x0).coerceAtLeast(2f).coerceAtMost(w - left), h),
                )
                drawLine(selectionColor, Offset(x0, 0f), Offset(x0, h), strokeWidth = 3f)
                drawLine(selectionColor, Offset(x1, 0f), Offset(x1, h), strokeWidth = 3f)
            }
        }

        // Playhead.
        val px = msToX(playheadMs, w, viewportStartMs, viewportMs)
        if (px >= 0f && px <= w) {
            drawLine(playheadColor, Offset(px, 0f), Offset(px, h), strokeWidth = 2f)
            drawCircle(playheadColor, radius = 6f, center = Offset(px, 8f))
        }
    }
}

/** Full-duration overview strip; dragging it pans the main viewport. */
@Composable
fun WaveformMinimap(
    peaks: FloatArray,
    durationMs: Long,
    viewportStartMs: Long,
    viewportMs: Long,
    playheadMs: Long,
    onViewportMoved: (Long) -> Unit,
    onViewportCentered: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val waveColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
    val viewportFill = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.30f)
    val viewportBorder = MaterialTheme.colorScheme.tertiary
    val playheadColor = MaterialTheme.colorScheme.onSurface

    Canvas(
        modifier = modifier
            .pointerInput(durationMs) {
                detectHorizontalDragGestures { change, dragAmount ->
                    change.consume()
                    val msPerPx = durationMs / size.width.coerceAtLeast(1)
                    onViewportMoved((-dragAmount.x * msPerPx).roundToLong())
                }
            }
            .pointerInput(durationMs) {
                detectTapGestures { pos ->
                    val ms = (pos.x / size.width.coerceAtLeast(1) * durationMs).roundToLong()
                    onViewportCentered(ms.coerceIn(0L, durationMs))
                }
            },
    ) {
        val w = size.width
        val h = size.height
        val msPerPx = durationMs / w.coerceAtLeast(1f)
        val pxWindow = (WaveformPeaks.WINDOW_MS / msPerPx).coerceAtLeast(1f)

        var x = 0f
        while (x < w) {
            val idx0 = ((x * msPerPx) / WaveformPeaks.WINDOW_MS).toInt().coerceIn(0, peaks.size - 1)
            val idx1 = (((x + pxWindow) * msPerPx / WaveformPeaks.WINDOW_MS).toInt())
                .coerceIn(idx0, peaks.size - 1)
            var peak = 0f
            var i = idx0
            while (i <= idx1) {
                if (peaks[i] > peak) peak = peaks[i]
                i++
            }
            if (peak <= 0f) peak = 0.01f
            drawLine(
                waveColor,
                Offset(x, h / 2f - peak * h / 2f),
                Offset(x, h / 2f + peak * h / 2f),
                strokeWidth = 1.5f,
            )
            x += pxWindow
        }

        val vx0 = (viewportStartMs.toFloat() / durationMs * w).coerceIn(0f, w)
        val vx1 = (((viewportStartMs + viewportMs).coerceAtMost(durationMs)).toFloat() / durationMs * w)
            .coerceIn(vx0, w)
        val vw = (vx1 - vx0).coerceAtLeast(6f)
        drawRect(viewportFill, topLeft = Offset(vx0, 0f), size = Size(vw, h))
        drawRect(viewportBorder, topLeft = Offset(vx0, 0f), size = Size(vw, h), style = Stroke(width = 2f))

        val px = (playheadMs.toFloat() / durationMs * w)
        drawLine(playheadColor, Offset(px, 0f), Offset(px, h), strokeWidth = 2f)
    }
}

// ---- helpers --------------------------------------------------------------

private fun xToMs(x: Float, width: Float, viewportStartMs: Long, viewportMs: Long): Long =
    viewportStartMs + (x / width.coerceAtLeast(1f) * viewportMs).roundToLong()

private fun msToX(ms: Long, width: Float, viewportStartMs: Long, viewportMs: Long): Float =
    ((ms - viewportStartMs).toFloat() / viewportMs.coerceAtLeast(1L)) * width

private fun complementWithin(kept: List<TimeRange>, totalMs: Long): List<TimeRange> {
    val cuts = ArrayList<TimeRange>()
    var cursor = 0L
    for (r in kept) {
        if (r.startMs > cursor) cuts.add(TimeRange(cursor, r.startMs.coerceAtMost(totalMs)))
        cursor = max(cursor, r.endMs)
    }
    if (cursor < totalMs) cuts.add(TimeRange(cursor, totalMs))
    return cuts
}

private fun isCut(cuts: List<TimeRange>, ms: Long): Boolean =
    cuts.any { ms >= it.startMs && ms < it.endMs }
