package com.inrec.recorder.ui.editor

import android.app.Application
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.inrec.recorder.R
import com.inrec.recorder.audio.Segments
import com.inrec.recorder.audio.TimeRange
import com.inrec.recorder.data.AppSettings
import com.inrec.recorder.data.RecordingsRepository
import com.inrec.recorder.export.ExportFormat
import com.inrec.recorder.ui.Format
import com.inrec.recorder.ui.RenameDialog
import com.inrec.recorder.ui.shareRecording

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    recordingId: String,
    repository: RecordingsRepository,
    settings: AppSettings,
    onBack: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as Application
    val context = LocalContext.current
    val vm: EditorViewModel = viewModel(
        key = "editor_$recordingId",
        factory = viewModelFactory {
            initializer { EditorViewModel(app, repository, settings, recordingId) }
        },
    )
    val state by vm.state.collectAsStateWithLifecycle()

    val snackbarHost = remember { SnackbarHostState() }
    LaunchedEffect(state.snackbar) {
        state.snackbar?.let {
            snackbarHost.showSnackbar(it)
            vm.consumeSnackbar()
        }
    }

    // Viewport / zoom state.
    var zoomIndex by rememberSaveable { mutableIntStateOf(0) }
    val zoomLevels = remember { intArrayOf(1, 4, 16, 64) }
    var viewportStart by remember { mutableStateOf(0L) }

    val durationMs = state.durationMs
    val viewportMs = (durationMs / zoomLevels[zoomIndex]).coerceAtLeast(200L)
    LaunchedEffect(durationMs, viewportMs) {
        viewportStart = viewportStart.coerceIn(0L, (durationMs - viewportMs).coerceAtLeast(0L))
    }
    // Follow the playhead while playing.
    LaunchedEffect(state.playheadMs, state.playing) {
        if (state.playing) {
            if (state.playheadMs < viewportStart || state.playheadMs > viewportStart + viewportMs) {
                viewportStart = (state.playheadMs - viewportMs / 4)
                    .coerceIn(0L, (durationMs - viewportMs).coerceAtLeast(0L))
            }
        }
    }

    // Rename / delete / share.
    var showRename by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    // Export dialog.
    var showExportDialog by remember { mutableStateOf(false) }
    var exportFormat by remember { mutableStateOf(ExportFormat.WAV) }
    var fileName by remember { mutableStateOf("") }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("audio/*"),
    ) { uri ->
        if (uri != null) vm.export(exportFormat, uri)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.recording?.title ?: recordingId,
                        maxLines = 1,
                        modifier = Modifier.widthIn(max = 240.dp),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { vm.undo() }, enabled = state.canUndo) {
                        Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = stringResource(R.string.action_undo))
                    }
                    IconButton(onClick = { vm.redo() }, enabled = state.canRedo) {
                        Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = stringResource(R.string.action_redo))
                    }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.menu_more))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_rename)) },
                            onClick = { menuOpen = false; showRename = true },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_share)) },
                            leadingIcon = { Icon(Icons.Filled.Share, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                state.recording?.let { shareRecording(context, it) }
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_delete)) },
                            leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                            onClick = { menuOpen = false; confirmDelete = true },
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHost) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            // --- waveform ---------------------------------------------------
            if (state.peaksLoading) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(stringResource(R.string.editor_generating_waveform))
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = { state.peaksProgress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else if (state.peaks != null) {
                val peaks = state.peaks!!
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = Format.clockPrecise(viewportStart),
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Text(
                        text = Format.clockPrecise(state.playheadMs),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Text(
                        text = Format.clockPrecise((viewportStart + viewportMs).coerceAtMost(durationMs)),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    tonalElevation = 2.dp,
                ) {
                    WaveformView(
                        peaks = peaks,
                        durationMs = durationMs,
                        keptRanges = state.keptRanges,
                        selection = state.selection,
                        playheadMs = state.playheadMs,
                        viewportStartMs = viewportStart,
                        viewportMs = viewportMs,
                        onSeek = { vm.seekTo(it) },
                        onSelectionUpdate = { vm.setSelection(it) },
                        onSelectionCommit = { vm.setSelection(it) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                    )
                }

                WaveformMinimap(
                    peaks = peaks,
                    durationMs = durationMs,
                    viewportStartMs = viewportStart,
                    viewportMs = viewportMs,
                    playheadMs = state.playheadMs,
                    onViewportMoved = { delta ->
                        viewportStart = (viewportStart + delta)
                            .coerceIn(0L, (durationMs - viewportMs).coerceAtLeast(0L))
                    },
                    onViewportCentered = { ms ->
                        viewportStart = (ms - viewportMs / 2)
                            .coerceIn(0L, (durationMs - viewportMs).coerceAtLeast(0L))
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .height(44.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )

                // Zoom + selection info.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    zoomLevels.forEachIndexed { i, level ->
                        AssistChip(
                            onClick = { zoomIndex = i },
                            label = { Text("×$level") },
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    state.selection?.let { sel ->
                        Text(
                            text = stringResource(
                                R.string.editor_selection_info,
                                Format.clockPrecise(sel.startMs),
                                Format.clockPrecise(sel.endMs),
                                (sel.durationMs / 1000f),
                            ),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }

                // Cut actions.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            state.selection?.let { vm.cutRange(it) }
                        },
                        enabled = state.selection != null,
                    ) {
                        Icon(Icons.Filled.ContentCut, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.editor_cut_selection))
                    }
                    OutlinedButton(
                        onClick = {
                            state.selection?.let { vm.keepOnlyRange(it) }
                        },
                        enabled = state.selection != null,
                    ) {
                        Text(stringResource(R.string.editor_keep_selection))
                    }
                    TextButton(
                        onClick = { vm.restoreAll() },
                        enabled = state.keptRanges.size != 1 ||
                            state.keptRanges.first().startMs != 0L ||
                            state.keptRanges.first().endMs != durationMs,
                    ) {
                        Text(stringResource(R.string.editor_restore_all))
                    }
                }

                // --- silence suggestions -------------------------------------
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.GraphicEq, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.editor_suggestions_title),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                text = stringResource(
                                    R.string.editor_suggestions_params,
                                    settings.thresholdDb,
                                    settings.minSilenceMs / 1000f,
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(
                            onClick = { vm.toggleSuggestionsPanel(!state.showSuggestionsPanel) },
                        ) {
                            Icon(
                                if (state.showSuggestionsPanel) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                contentDescription = stringResource(R.string.editor_toggle_suggestions),
                            )
                        }
                    }

                    if (state.showSuggestionsPanel) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Button(
                                onClick = { vm.detectSilence() },
                                enabled = !state.scanning,
                            ) {
                                Text(
                                    if (state.suggestions.isEmpty()) stringResource(R.string.editor_detect_silence)
                                    else stringResource(R.string.editor_redetect_silence),
                                )
                            }
                        }
                        if (state.scanning) {
                            LinearProgressIndicator(
                                progress = { state.scanProgress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        } else if (state.suggestions.isEmpty()) {
                            Text(
                                text = stringResource(R.string.editor_no_suggestions),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        } else {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(160.dp)
                                    .padding(horizontal = 8.dp),
                            ) {
                                itemsIndexed(state.suggestions) { index, range ->
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Checkbox(
                                            checked = index in state.checkedSuggestions,
                                            onCheckedChange = { vm.checkSuggestion(index, it) },
                                        )
                                        Text(
                                            text = stringResource(
                                                R.string.editor_suggestion_row,
                                                Format.clockPrecise(range.startMs),
                                                Format.clockPrecise(range.endMs),
                                                (range.durationMs / 1000f),
                                            ),
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                }
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                TextButton(onClick = { vm.checkAllSuggestions(true) }) {
                                    Text(stringResource(R.string.editor_select_all))
                                }
                                TextButton(onClick = { vm.checkAllSuggestions(false) }) {
                                    Text(stringResource(R.string.editor_select_none))
                                }
                                Spacer(Modifier.weight(1f))
                                Button(
                                    onClick = { vm.applySuggestions(state.checkedSuggestions) },
                                    enabled = state.checkedSuggestions.isNotEmpty(),
                                ) {
                                    Text(
                                        stringResource(
                                            R.string.editor_apply_cuts,
                                            state.checkedSuggestions.size,
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }

                // --- playback + export --------------------------------------
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilledIconButton(onClick = { vm.playPause() }) {
                        Icon(
                            if (state.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = stringResource(
                                if (state.playing) R.string.notif_pause else R.string.notif_resume,
                            ),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(
                                R.string.editor_time_edited,
                                Format.clockPrecise(state.playheadMs),
                                Format.clockPrecise(durationMs),
                                Format.clock(Segments.totalDuration(state.keptRanges)),
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Button(
                        onClick = {
                            exportFormat = ExportFormat.WAV
                            fileName = vm.suggestedFileName(ExportFormat.WAV)
                            showExportDialog = true
                        },
                        enabled = !state.exporting,
                    ) {
                        Text(stringResource(R.string.editor_export))
                    }
                }
                if (state.exporting) {
                    LinearProgressIndicator(
                        progress = { state.exportProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                    )
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (showRename) {
        state.recording?.let { recording ->
            RenameDialog(
                initial = recording.title,
                onDismiss = { showRename = false },
                onConfirm = { newTitle ->
                    repository.renameTitle(recording, newTitle)
                    vm.refreshRecording()
                    showRename = false
                },
            )
        }
    }

    if (confirmDelete) {
        state.recording?.let { recording ->
            AlertDialog(
                onDismissRequest = { confirmDelete = false },
                title = { Text(stringResource(R.string.delete_dialog_title)) },
                text = { Text(stringResource(R.string.delete_dialog_body, recording.title)) },
                confirmButton = {
                    TextButton(onClick = {
                        repository.delete(recording)
                        confirmDelete = false
                        onBack()
                    }) {
                        Text(stringResource(R.string.delete_confirm))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { confirmDelete = false }) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )
        }
    }

    if (showExportDialog) {
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            title = { Text(stringResource(R.string.export_dialog_title)) },
            text = {
                Column {
                    ExportFormat.entries.forEach { format ->
                        val supported = state.formatSupport[format] ?: false
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.RadioButton(
                                selected = exportFormat == format,
                                onClick = {
                                    exportFormat = format
                                    fileName = vm.suggestedFileName(format)
                                },
                                enabled = supported,
                            )
                            Column {
                                Text(
                                    text = stringResource(
                                        R.string.export_format_label,
                                        format.name,
                                        format.extension,
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                if (!supported) {
                                    Text(
                                        text = stringResource(R.string.export_format_unsupported),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = fileName,
                        onValueChange = { fileName = it },
                        singleLine = true,
                        label = { Text(stringResource(R.string.export_filename_label)) },
                    )
                    Text(
                        text = stringResource(R.string.export_extension_note),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showExportDialog = false
                        exportLauncher.launch(fileName.ifBlank { vm.suggestedFileName(exportFormat) })
                    },
                    enabled = !state.exporting && fileName.isNotBlank(),
                ) {
                    Text(stringResource(R.string.save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showExportDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}
