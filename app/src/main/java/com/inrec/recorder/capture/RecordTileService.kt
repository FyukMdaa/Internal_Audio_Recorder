package com.inrec.recorder.capture

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.inrec.recorder.RecordActivity
import com.inrec.recorder.R

/**
 * Quick Settings tile that starts / stops internal-audio recording,
 * mirroring the system screen-recorder tile behaviour:
 * tap while idle -> consent dialog; tap while recording -> stop.
 */
class RecordTileService : TileService() {

    private val stateListener: () -> Unit = { refreshTile() }

    override fun onStartListening() {
        super.onStartListening()
        RecorderState.addListener(stateListener)
    }

    override fun onStopListening() {
        RecorderState.removeListener(stateListener)
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        when (RecorderState.state) {
            RecordState.IDLE -> launchConsentFlow()
            RecordState.PREPARING, RecordState.RECORDING, RecordState.PAUSED ->
                CaptureService.stop(this)

            RecordState.STOPPING -> Unit
        }
    }

    private fun launchConsentFlow() {
        val intent = Intent(this, RecordActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (Build.VERSION.SDK_INT >= 34) {
            val pi = PendingIntent.getActivity(
                this,
                1000,
                intent,
                PendingIntent.FLAG_IMMUTABLE,
            )
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun refreshTile() {
        val tile = qsTile ?: return
        val active = when (RecorderState.state) {
            RecordState.RECORDING, RecordState.PAUSED -> true
            else -> false
        }
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.subtitle = getString(
            if (active) R.string.qs_tile_recording else R.string.qs_tile_idle,
        )
        tile.updateTile()
    }
}
