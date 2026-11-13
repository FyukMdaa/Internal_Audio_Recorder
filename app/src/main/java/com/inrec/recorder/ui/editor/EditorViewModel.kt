package com.inrec.recorder.ui.editor

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.inrec.recorder.R
import com.inrec.recorder.audio.Segments
import com.inrec.recorder.audio.SilenceDetector
import com.inrec.recorder.audio.TimeRange
import com.inrec.recorder.audio.WaveformPeaks
import com.inrec.recorder.data.AppSettings
import com.inrec.recorder.data.RecordingsRepository
import com.inrec.recorder.data.Recording
import com.inrec.recorder.export.AudioExporter
import com.inrec.recorder.export.ExportException
import com.inrec.recorder.export.ExportFormat
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * State holder for the waveform editor.
 *
 * The source WAV is never modified: edits are kept as a list of [TimeRange]
 * "kept ranges" on top of the original timeline, with undo/redo support.
 */
class EditorViewModel(
    application: Application,
    private val repository: RecordingsRepository,
    private val settings: AppSettings,
    private val recordingId: String,
) : AndroidViewModel(application) {

    data class UiState(
        val recording: Recording? = null,
        val durationMs: Long = 0L,
        val peaks: FloatArray? = null,
        val peaksLoading: Boolean = true,
        val peaksProgress: Float = 0f,
        val keptRanges: List<TimeRange> = emptyList(),
        val selection: TimeRange? = null,
        val playheadMs: Long = 0L,
        val playing: Boolean = false,
        val suggestions: List<TimeRange> = emptyList(),
        val checkedSuggestions: Set<Int> = emptySet(),
        val showSuggestionsPanel: Boolean = false,
        val scanning: Boolean = false,
        val scanProgress: Float = 0f,
        val exporting: Boolean = false,
        val exportProgress: Float = 0f,
        val formatSupport: Map<ExportFormat, Boolean> = emptyMap(),
        val canUndo: Boolean = false,
        val canRedo: Boolean = false,
        val snackbar: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val undoStack = ArrayDeque<List<TimeRange>>()
    private val redoStack = ArrayDeque<List<TimeRange>>()

    private var player: PcmPlayerHolder? = null
    private var scanJob: Job? = null

    init {
        viewModelScope.launch {
            val recording = repository.get(recordingId)
            if (recording == null) {
                _state.update {
                    it.copy(peaksLoading = false, snackbar = getString(R.string.error_recording_missing))
                }
                return@launch
            }
            _state.update {
                it.copy(
                    recording = recording,
                    durationMs = recording.durationMs,
                    keptRanges = listOf(TimeRange(0L, recording.durationMs)),
                    formatSupport = ExportFormat.entries.associateWith { f -> AudioExporter.isSupported(f) },
                )
            }
            val peaks = WaveformPeaks.loadOrCompute(recording.file, recording.channels) { p ->
                _state.update { s -> s.copy(peaksProgress = p) }
            }
            _state.update { it.copy(peaks = peaks, peaksLoading = false) }
        }
    }

    private fun getString(res: Int): String = getApplication<Application>().getString(res)

    // ------------------------------------------------------------ edit ops

    private fun pushUndo() {
        undoStack.addLast(_state.value.keptRanges)
        if (undoStack.size > 50) undoStack.removeFirst()
        redoStack.clear()
        _state.update { it.copy(canUndo = true, canRedo = false) }
    }

    fun undo() {
        val prev = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(_state.value.keptRanges)
        _state.update {
            it.copy(
                keptRanges = prev,
                selection = null,
                suggestions = filterSuggestions(it.suggestions, prev),
                canUndo = undoStack.isNotEmpty(),
                canRedo = true,
            )
        }
        clampPlayhead()
    }

    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(_state.value.keptRanges)
        _state.update {
            it.copy(
                keptRanges = next,
                selection = null,
                suggestions = filterSuggestions(it.suggestions, next),
                canUndo = true,
                canRedo = redoStack.isNotEmpty(),
            )
        }
        clampPlayhead()
    }

    fun setSelection(selection: TimeRange?) {
        _state.update { it.copy(selection = selection) }
    }

    /** Removes [cut] from the kept ranges. */
    fun cutRange(cut: TimeRange) {
        val current = _state.value.keptRanges
        val newKept = subtract(current, listOf(cut))
        if (newKept == current) return
        pushUndo()
        _state.update {
            it.copy(
                keptRanges = newKept,
                selection = null,
                suggestions = filterSuggestions(it.suggestions, newKept),
            )
        }
        clampPlayhead()
    }

    /** Keeps only the part of the kept ranges inside [keep]. */
    fun keepOnlyRange(keep: TimeRange) {
        val current = _state.value.keptRanges
        val newKept = current.mapNotNull { r ->
            val s = maxOf(r.startMs, keep.startMs)
            val e = minOf(r.endMs, keep.endMs)
            if (e > s) TimeRange(s, e) else null
        }
        if (newKept == current) return
        pushUndo()
        _state.update {
            it.copy(
                keptRanges = newKept,
                selection = null,
                suggestions = filterSuggestions(it.suggestions, newKept),
            )
        }
        clampPlayhead()
    }

    /** Applies every suggestion whose index is in [indices]. */
    fun applySuggestions(indices: Set<Int>) {
        val state = _state.value
        val cuts = indices.mapNotNull { state.suggestions.getOrNull(it) }
        if (cuts.isEmpty()) return
        pushUndo()
        val newKept = subtract(state.keptRanges, cuts)
        _state.update {
            it.copy(
                keptRanges = newKept,
                suggestions = emptyList(),
                checkedSuggestions = emptySet(),
                selection = null,
            )
        }
        clampPlayhead()
    }

    /** Discards every cut (with an undo entry). */
    fun restoreAll() {
        val state = _state.value
        val full = listOf(TimeRange(0L, state.durationMs))
        if (state.keptRanges == full) return
        pushUndo()
        _state.update {
            it.copy(
                keptRanges = full,
                selection = null,
                suggestions = emptyList(),
                checkedSuggestions = emptySet(),
            )
        }
        clampPlayhead()
    }

    fun clearCheckedSuggestions() {
        _state.update { it.copy(checkedSuggestions = emptySet()) }
    }

    fun checkSuggestion(index: Int, checked: Boolean) {
        _state.update {
            it.copy(
                checkedSuggestions = if (checked) it.checkedSuggestions + index
                else it.checkedSuggestions - index,
            )
        }
    }

    fun checkAllSuggestions(checked: Boolean) {
        _state.update {
            it.copy(
                checkedSuggestions = if (checked) it.suggestions.indices.toSet() else emptySet(),
            )
        }
    }

    fun toggleSuggestionsPanel(expanded: Boolean) {
        _state.update { it.copy(showSuggestionsPanel = expanded) }
    }

    // ---------------------------------------------------- silence detection

    fun detectSilence() {
        val recording = _state.value.recording ?: return
        if (_state.value.scanning) return
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            _state.update { it.copy(scanning = true, scanProgress = 0f) }
            try {
                val suggestions = SilenceDetector.detect(
                    wavFile = recording.file,
                    channels = recording.channels,
                    params = settings.toSilenceParams(),
                ) { p ->
                    _state.update { s -> s.copy(scanProgress = p) }
                }
                _state.update {
                    it.copy(
                        scanning = false,
                        suggestions = suggestions,
                        checkedSuggestions = suggestions.indices.toSet(),
                        showSuggestionsPanel = true,
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(scanning = false, snackbar = getString(R.string.error_silence_scan))
                }
            }
        }
    }

    private fun filterSuggestions(
        suggestions: List<TimeRange>,
        kept: List<TimeRange>,
    ): List<TimeRange> = suggestions.filter { s ->
        kept.any { it.startMs < s.endMs && s.startMs < it.endMs }
    }

    // ------------------------------------------------------------- playback

    private inner class PcmPlayerHolder {
        val player = com.inrec.recorder.audio.PcmPlayer(
            wavFile = _state.value.recording!!.file,
            sampleRate = _state.value.recording!!.sampleRate,
            channels = _state.value.recording!!.channels,
            listener = object : com.inrec.recorder.audio.PcmPlayer.Listener {
                override fun onPlaybackChanged(playing: Boolean) {
                    _state.update { it.copy(playing = playing) }
                }

                override fun onPositionChanged(sourceMs: Long) {
                    _state.update { it.copy(playheadMs = sourceMs) }
                }

                override fun onPlaybackEnded() {
                    _state.update { s ->
                        s.copy(
                            playing = false,
                            playheadMs = Segments.totalDuration(s.keptRanges).let { edited ->
                                Segments.editedToSource(s.keptRanges, edited)
                            },
                        )
                    }
                }
            },
        )
    }

    fun playPause() {
        val state = _state.value
        val recording = state.recording ?: return
        if (state.playing) {
            player?.player?.toggle()
            return
        }
        val holder = player ?: PcmPlayerHolder().also { player = it }
        val startEdited = Segments.sourceToEdited(state.keptRanges, state.playheadMs)
        holder.player.start(state.keptRanges, startEdited)
    }

    fun seekTo(sourceMs: Long) {
        val state = _state.value
        _state.update { it.copy(playheadMs = sourceMs.coerceIn(0L, state.durationMs)) }
        if (state.playing) {
            val edited = Segments.sourceToEdited(state.keptRanges, sourceMs)
            player?.player?.seekToEdited(edited)
        }
    }

    fun stopPlayback() {
        player?.player?.stop()
        _state.update { it.copy(playing = false) }
    }

    private fun clampPlayhead() {
        val state = _state.value
        if (state.playing) return
        val source = state.playheadMs
        val inside = state.keptRanges.any { source in it.startMs..it.endMs }
        if (!inside) {
            val nearest = state.keptRanges.minByOrNull { r ->
                when {
                    source < r.startMs -> r.startMs - source
                    source > r.endMs -> source - r.endMs
                    else -> 0L
                }
            }?.let { r ->
                when {
                    source < r.startMs -> r.startMs
                    else -> r.endMs
                }
            } ?: 0L
            _state.update { it.copy(playheadMs = nearest) }
        }
    }

    // ---------------------------------------------------------------- edits

    /**
     * Subtract [cuts] from [kept]. Both lists must be sorted, non-overlapping
     * kept ranges; cuts may be arbitrary.
     */
    private fun subtract(kept: List<TimeRange>, cuts: List<TimeRange>): List<TimeRange> {
        var current = kept
        for (cut in cuts) {
            current = subtractOne(current, cut)
        }
        return current
    }

    private fun subtractOne(kept: List<TimeRange>, cut: TimeRange): List<TimeRange> {
        val out = ArrayList<TimeRange>(kept.size + 2)
        for (r in kept) {
            if (!r.intersects(cut)) {
                out.add(r)
                continue
            }
            if (cut.startMs > r.startMs) out.add(TimeRange(r.startMs, minOf(cut.startMs, r.endMs)))
            if (cut.endMs < r.endMs) out.add(TimeRange(maxOf(cut.endMs, r.startMs), r.endMs))
        }
        return out.filter { it.durationMs > 0 }
    }

    // --------------------------------------------------------------- export

    fun suggestedFileName(format: ExportFormat): String {
        val title = _state.value.recording?.title ?: recordingId
        val clean = title.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        return "$clean.${format.extension}"
    }

    fun export(format: ExportFormat, uri: Uri) {
        val state = _state.value
        val recording = state.recording ?: return
        if (state.exporting) return
        viewModelScope.launch {
            _state.update { it.copy(exporting = true, exportProgress = 0f) }
            val app = getApplication<Application>()
            val temp = File(app.cacheDir, "export_${System.currentTimeMillis()}.${format.extension}")
            try {
                withContext(Dispatchers.IO) {
                    AudioExporter.export(
                        source = recording.file,
                        sampleRate = recording.sampleRate,
                        channels = recording.channels,
                        kept = state.keptRanges,
                        format = format,
                        outFile = temp,
                    ) { p ->
                        _state.update { s -> s.copy(exportProgress = p) }
                    }
                    app.contentResolver.openOutputStream(uri)?.use { out ->
                        temp.inputStream().use { it.copyTo(out) }
                    } ?: throw ExportException("Cannot open destination")
                }
                _state.update { it.copy(exporting = false, snackbar = getString(R.string.export_done)) }
            } catch (e: ExportException) {
                _state.update {
                    it.copy(exporting = false, snackbar = getString(R.string.export_error) + " " + e.message)
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(exporting = false, snackbar = getString(R.string.export_error))
                }
            } finally {
                temp.delete()
            }
        }
    }

    fun consumeSnackbar() {
        _state.update { it.copy(snackbar = null) }
    }

    /** Re-reads the recording (e.g. after an in-editor rename). */
    fun refreshRecording() {
        val rec = repository.get(recordingId) ?: return
        _state.update { it.copy(recording = rec, durationMs = rec.durationMs) }
    }

    override fun onCleared() {
        scanJob?.cancel()
        player?.player?.release()
        player = null
        super.onCleared()
    }
}
