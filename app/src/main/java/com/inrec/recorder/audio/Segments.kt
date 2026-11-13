package com.inrec.recorder.audio

/**
 * Pure helpers for the "kept ranges" editing model.
 *
 * The editor stores a list of kept [TimeRange]s (sorted, non-overlapping).
 * Cutting a selection removes that region from the kept list; playback and
 * export read only the kept ranges.
 */
object Segments {

    /** Kept ranges = [0, totalMs) minus all cuts. */
    fun complementOf(cuts: List<TimeRange>, totalMs: Long): List<TimeRange> {
        if (totalMs <= 0L) return emptyList()
        val normalized = normalize(cuts)
        val kept = ArrayList<TimeRange>()
        var cursor = 0L
        for (cut in normalized) {
            val s = cut.startMs.coerceIn(0L, totalMs)
            val e = cut.endMs.coerceIn(0L, totalMs)
            if (e <= s) continue
            if (s > cursor) kept.add(TimeRange(cursor, s))
            cursor = maxOf(cursor, e)
        }
        if (cursor < totalMs) kept.add(TimeRange(cursor, totalMs))
        return kept
    }

    /** Sorts and merges overlapping / touching ranges. */
    fun normalize(ranges: List<TimeRange>): List<TimeRange> {
        if (ranges.isEmpty()) return emptyList()
        val sorted = ranges.filter { it.endMs > it.startMs }.sortedBy { it.startMs }
        val out = ArrayList<TimeRange>()
        var current: TimeRange? = null
        for (r in sorted) {
            val c = current
            if (c == null) {
                current = r
            } else if (r.startMs <= c.endMs) {
                current = TimeRange(c.startMs, maxOf(c.endMs, r.endMs))
            } else {
                out.add(c)
                current = r
            }
        }
        current?.let { out.add(it) }
        return out
    }

    fun totalDuration(kept: List<TimeRange>): Long =
        kept.sumOf { it.durationMs }

    /** Maps a position on the *source* timeline to a position on the *edited* timeline. */
    fun sourceToEdited(kept: List<TimeRange>, sourceMs: Long): Long {
        var edited = 0L
        for (r in kept) {
            if (sourceMs >= r.endMs) {
                edited += r.durationMs
            } else if (sourceMs > r.startMs) {
                edited += sourceMs - r.startMs
                break
            } else {
                break
            }
        }
        return edited
    }

    /** Maps a position on the *edited* timeline back to the *source* timeline. */
    fun editedToSource(kept: List<TimeRange>, editedMs: Long): Long {
        var remaining = editedMs
        for (r in kept) {
            if (remaining < r.durationMs) return r.startMs + remaining
            remaining -= r.durationMs
        }
        return kept.lastOrNull()?.endMs ?: 0L
    }

    /** Byte offset in the 16-bit stereo PCM stream for a given millisecond. */
    fun byteOffsetFor(sampleRate: Int, channels: Int, ms: Long): Long =
        ms * sampleRate * channels * 2L / 1000L
}
