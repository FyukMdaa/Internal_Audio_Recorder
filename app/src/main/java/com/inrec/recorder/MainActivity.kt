package com.inrec.recorder

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.inrec.recorder.data.AppSettings
import com.inrec.recorder.data.RecordingsRepository
import com.inrec.recorder.ui.editor.EditorScreen
import com.inrec.recorder.ui.list.ListScreen
import com.inrec.recorder.ui.settings.SettingsScreen
import com.inrec.recorder.ui.theme.InRecTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val repository = RecordingsRepository(applicationContext)
        val settings = AppSettings(applicationContext)
        setContent {
            InRecTheme {
                AppRoot(repository, settings)
            }
        }
    }
}

private enum class Screen { LIST, EDITOR, SETTINGS }

@Composable
private fun AppRoot(
    repository: RecordingsRepository,
    settings: AppSettings,
) {
    var screen by rememberSaveable { mutableStateOf(Screen.LIST.name) }
    var editorId by rememberSaveable { mutableStateOf<String?>(null) }

    val onBack: () -> Unit = {
        when (screen) {
            Screen.EDITOR.name -> screen = Screen.LIST.name
            Screen.SETTINGS.name -> screen = Screen.LIST.name
        }
    }
    BackHandler(enabled = screen != Screen.LIST.name, onBack = onBack)

    when (screen) {
        Screen.EDITOR.name -> {
            val id = editorId
            if (id == null) {
                ListScreen(
                    repository = repository,
                    settings = settings,
                    onOpenEditor = { newId -> editorId = newId; screen = Screen.EDITOR.name },
                    onOpenSettings = { screen = Screen.SETTINGS.name },
                )
            } else {
                EditorScreen(
                    recordingId = id,
                    repository = repository,
                    settings = settings,
                    onBack = onBack,
                )
            }
        }

        Screen.SETTINGS.name -> SettingsScreen(settings = settings, onBack = onBack)

        else -> ListScreen(
            repository = repository,
            settings = settings,
            onOpenEditor = { newId -> editorId = newId; screen = Screen.EDITOR.name },
            onOpenSettings = { screen = Screen.SETTINGS.name },
        )
    }
}
