package com.inrec.recorder

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.inrec.recorder.capture.CaptureService
import com.inrec.recorder.capture.RecordState
import com.inrec.recorder.capture.RecorderState

/**
 * Invisible activity that owns the MediaProjection consent dialog.
 *
 * Order matters on Android 14+: the mediaProjection foreground service must be
 * running *before* createScreenCaptureIntent() is invoked.
 */
class RecordActivity : ComponentActivity() {

    private val consentLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == RESULT_OK && data != null) {
                CaptureService.sendResult(this, result.resultCode, data)
            } else {
                // Consent denied / dismissed: tear the preparing service down.
                CaptureService.stop(this)
            }
            finish()
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
            // Proceed regardless: an FGS works without the notification permission
            // (the notification is simply not shown on Android 13+).
            showConsentDialog()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Transparent, empty content; the system consent dialog does the talking.
        if (RecorderState.state != RecordState.IDLE) {
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        if (RecorderState.state != RecordState.IDLE) {
            finish()
            return
        }
        startServiceThenConsent()
    }

    private fun startServiceThenConsent() {
        if (isFinishing || isDestroyed) return
        CaptureService.startInit(this)
        val needsNotificationPermission = Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        if (needsNotificationPermission) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            showConsentDialog()
        }
    }

    private fun showConsentDialog() {
        if (isFinishing || isDestroyed) return
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
        consentLauncher.launch(manager.createScreenCaptureIntent())
    }
}
