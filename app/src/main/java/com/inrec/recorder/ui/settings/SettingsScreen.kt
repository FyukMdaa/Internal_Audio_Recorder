package com.inrec.recorder.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.inrec.recorder.R
import com.inrec.recorder.data.AppSettings

/**
 * Silence-detection parameters used by the editor's cut suggestions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(settings: AppSettings, onBack: () -> Unit) {
    var threshold by remember { mutableFloatStateOf(settings.thresholdDb.toFloat()) }
    var minSilence by remember { mutableFloatStateOf(settings.minSilenceMs.toFloat()) }
    var padding by remember { mutableFloatStateOf(settings.paddingMs.toFloat()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                actions = {
                    TextButton(onClick = {
                        settings.resetToDefaults()
                        threshold = settings.thresholdDb.toFloat()
                        minSilence = settings.minSilenceMs.toFloat()
                        padding = settings.paddingMs.toFloat()
                    }) {
                        Text(stringResource(R.string.settings_reset))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )

            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.settings_threshold_title, threshold.toInt()),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.settings_threshold_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(
                value = threshold,
                onValueChange = { threshold = it },
                onValueChangeFinished = { settings.thresholdDb = threshold.toInt() },
                valueRange = -70f..-10f,
                steps = 11,
            )

            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.settings_min_silence_title, minSilence / 1000f),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.settings_min_silence_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(
                value = minSilence,
                onValueChange = { minSilence = it },
                onValueChangeFinished = { settings.minSilenceMs = minSilence.toInt() },
                valueRange = 300f..3000f,
            )

            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.settings_padding_title, padding / 1000f),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.settings_padding_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(
                value = padding,
                onValueChange = { padding = it },
                onValueChangeFinished = { settings.paddingMs = padding.toInt() },
                valueRange = 0f..500f,
            )

            Spacer(Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.settings_capture_note_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.settings_capture_note_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 24.dp),
            )
        }
    }
}
