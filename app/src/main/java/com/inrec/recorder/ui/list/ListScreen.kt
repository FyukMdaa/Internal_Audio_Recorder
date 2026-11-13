package com.inrec.recorder.ui.list

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.inrec.recorder.ui.RenameDialog
import com.inrec.recorder.R
import com.inrec.recorder.RecordActivity
import com.inrec.recorder.capture.CaptureService
import com.inrec.recorder.capture.RecordState
import com.inrec.recorder.capture.RecorderState
import com.inrec.recorder.data.AppSettings
import com.inrec.recorder.data.RecordingsRepository
import com.inrec.recorder.data.Recording
import com.inrec.recorder.ui.Format
import com.inrec.recorder.ui.shareRecording
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListScreen(
    repository: RecordingsRepository,
    settings: AppSettings,
    onOpenEditor: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current

    // Live recording state (tile-style status card).
    var recordState by remember { mutableStateOf(RecorderState.state) }
    var tick by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val listener: () -> Unit = {
            recordState = RecorderState.state
            tick++
        }
        RecorderState.addListener(listener)
        onDispose { RecorderState.removeListener(listener) }
    }
    LaunchedEffect(recordState) {
        while (recordState == RecordState.RECORDING || recordState == RecordState.PAUSED) {
            tick++
            delay(500)
        }
    }

    // List data + refresh triggers.
    var refreshKey by remember { mutableIntStateOf(0) }
    LaunchedEffect(recordState) {
        if (recordState == RecordState.IDLE) refreshKey++
    }
    val recordings = remember(refreshKey) { repository.list() }

    var pendingRename by remember { mutableStateOf<Recording?>(null) }
    var pendingDelete by remember { mutableStateOf<Recording?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.menu_settings))
                    }
                },
            )
        },
        floatingActionButton = {
            if (recordState == RecordState.IDLE) {
                ExtendedFloatingActionButton(
                    onClick = { context.startActivity(Intent(context, RecordActivity::class.java)) },
                    icon = { Icon(Icons.Filled.Mic, contentDescription = null) },
                    text = { Text(stringResource(R.string.action_start_recording)) },
                )
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (recordState != RecordState.IDLE) {
                RecordingCard(
                    state = recordState,
                    elapsedMs = RecorderState.elapsedMs(),
                    onTogglePause = {
                        if (recordState == RecordState.PAUSED) CaptureService.resume(context)
                        else CaptureService.pause(context)
                    },
                    onStop = { CaptureService.stop(context) },
                )
            }

            if (recordings.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = stringResource(R.string.list_empty_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.list_empty_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(recordings, key = { it.id }) { recording ->
                        RecordingItem(
                            recording = recording,
                            onOpen = { onOpenEditor(recording.id) },
                            onRename = { pendingRename = recording },
                            onDelete = { pendingDelete = recording },
                            onShare = { shareRecording(context, recording) },
                        )
                    }
                }
            }
        }
    }

    pendingRename?.let { recording ->
        RenameDialog(
            initial = recording.title,
            onDismiss = { pendingRename = null },
            onConfirm = { newTitle ->
                repository.renameTitle(recording, newTitle)
                pendingRename = null
                refreshKey++
            },
        )
    }

    pendingDelete?.let { recording ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.delete_dialog_title)) },
            text = { Text(stringResource(R.string.delete_dialog_body, recording.title)) },
            confirmButton = {
                TextButton(onClick = {
                    repository.delete(recording)
                    pendingDelete = null
                    refreshKey++
                }) {
                    Text(stringResource(R.string.delete_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun RecordingCard(
    state: RecordState,
    elapsedMs: Long,
    onTogglePause: () -> Unit,
    onStop: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (state == RecordState.PAUSED) Icons.Filled.Pause else Icons.Filled.Mic,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(
                        when (state) {
                            RecordState.PREPARING -> R.string.state_preparing
                            RecordState.PAUSED -> R.string.state_paused
                            else -> R.string.state_recording
                        },
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    text = Format.clock(elapsedMs),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            if (state == RecordState.RECORDING || state == RecordState.PAUSED) {
                IconButton(onClick = onTogglePause) {
                    Icon(
                        imageVector = if (state == RecordState.PAUSED) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                        contentDescription = stringResource(
                            if (state == RecordState.PAUSED) R.string.notif_resume else R.string.notif_pause,
                        ),
                    )
                }
                IconButton(onClick = onStop) {
                    Icon(
                        Icons.Filled.Stop,
                        contentDescription = stringResource(R.string.notif_stop),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun RecordingItem(
    recording: Recording,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = {
            Text(recording.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            Text(
                text = stringResource(
                    R.string.list_item_meta,
                    Format.dateTime(recording.createdAt),
                    Format.clock(recording.durationMs),
                    Format.size(recording.sizeBytes),
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingContent = {
            Icon(Icons.Filled.PlayArrow, contentDescription = null)
        },
        trailingContent = {
            Column {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.menu_more))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_open)) },
                        onClick = { menuOpen = false; onOpen() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_rename)) },
                        leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                        onClick = { menuOpen = false; onRename() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_share)) },
                        leadingIcon = { Icon(Icons.Filled.Share, contentDescription = null) },
                        onClick = { menuOpen = false; onShare() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_delete)) },
                        leadingIcon = {
                            Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        },
                        onClick = { menuOpen = false; onDelete() },
                    )
                }
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}
