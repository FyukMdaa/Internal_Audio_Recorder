package com.inrec.recorder.capture

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.inrec.recorder.MainActivity
import com.inrec.recorder.R
import com.inrec.recorder.audio.RecordingMeta
import com.inrec.recorder.audio.WavWriter
import java.io.File
import kotlin.concurrent.thread

/**
 * Foreground service that captures device-internal audio (the playback mix)
 * through [AudioPlaybackCaptureConfiguration] and streams it to a WAV file.
 *
 * Flow (mirrors the system screen-recorder):
 * 1. RecordActivity starts this service (FGS of type mediaProjection) — required
 *    by Android 14+ *before* showing the consent dialog.
 * 2. RecordActivity shows the MediaProjection consent dialog and forwards the
 *    result via [sendResult].
 * 3. The service obtains the [MediaProjection], opens an [AudioRecord] in
 *    stereo at 48 kHz and writes PCM16 samples to a WAV file.
 */
class CaptureService : Service() {

    companion object {
        private const val TAG = "CaptureService"

        const val ACTION_INIT = "com.inrec.recorder.action.INIT"
        const val ACTION_RESULT = "com.inrec.recorder.action.RESULT"
        const val ACTION_PAUSE = "com.inrec.recorder.action.PAUSE"
        const val ACTION_RESUME = "com.inrec.recorder.action.RESUME"
        const val ACTION_STOP = "com.inrec.recorder.action.STOP"

        private const val EXTRA_RESULT_CODE = "resultCode"
        private const val EXTRA_DATA = "data"

        private const val CHANNEL_ID = "recording_channel"
        private const val NOTIFICATION_ID = 42

        private const val SAMPLE_RATE = 48000
        private const val CHANNELS = 2

        fun startInit(context: Context) {
            context.startForegroundService(
                Intent(context, CaptureService::class.java).setAction(ACTION_INIT),
            )
        }

        fun sendResult(context: Context, resultCode: Int, data: Intent) {
            context.startService(
                Intent(context, CaptureService::class.java)
                    .setAction(ACTION_RESULT)
                    .putExtra(EXTRA_RESULT_CODE, resultCode)
                    .putExtra(EXTRA_DATA, data),
            )
        }

        fun pause(context: Context) {
            context.startService(Intent(context, CaptureService::class.java).setAction(ACTION_PAUSE))
        }

        fun resume(context: Context) {
            context.startService(Intent(context, CaptureService::class.java).setAction(ACTION_RESUME))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, CaptureService::class.java).setAction(ACTION_STOP))
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private var projection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var wav: WavWriter? = null
    private var recordFile: File? = null
    private var createdAt = 0L
    private var writerThread: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile
    private var paused = false

    @Volatile
    private var capturing = false

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            // The user stopped the projection from the system status-bar chip
            // (or another projection session took over).
            mainHandler.post { stopRecording() }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_INIT -> {
                RecorderState.update(RecordState.PREPARING)
                // Must enter the foreground immediately (Android 12+).
                startAsForeground()
            }

            ACTION_RESULT -> {
                val code = intent.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE)
                val data = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_DATA)
                }
                if (data != null && code != Int.MIN_VALUE) {
                    startCapture(code, data)
                } else {
                    stopRecording()
                }
            }

            ACTION_PAUSE -> {
                paused = true
                RecorderState.markPaused()
                postNotification()
            }

            ACTION_RESUME -> {
                paused = false
                RecorderState.markResumed()
                postNotification()
            }

            ACTION_STOP -> stopRecording()

            else -> {
                // Unknown restart intent: nothing sensible to do.
                if (RecorderState.state == RecordState.PREPARING) {
                    stopRecording()
                } else {
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        startForeground(
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
        )
    }

    private fun startCapture(resultCode: Int, data: Intent) {
        if (capturing) return
        try {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            // Nullable in recent API stubs: reject a failed consent handshake here.
            val projection = manager.getMediaProjection(resultCode, data)
                ?: throw IllegalStateException(getString(R.string.error_capture_start))
            this.projection = projection
            projection.registerCallback(projectionCallback, mainHandler)

            val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()

            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()

            val minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            val bufferSize = maxOf(minBuffer * 4, 128 * 1024)

            val recorder = AudioRecord.Builder()
                .setAudioFormat(format)
                .setAudioPlaybackCaptureConfig(captureConfig)
                .setBufferSizeInBytes(bufferSize)
                .build()

            audioRecord = recorder
            recorder.startRecording()
            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw IllegalStateException(getString(R.string.error_capture_start))
            }

            val dir = File(getExternalFilesDir(null), "recordings").apply { mkdirs() }
            createdAt = System.currentTimeMillis()
            val file = File(dir, "REC_${timeStamp(createdAt)}.wav")
            recordFile = file
            wav = WavWriter(file, SAMPLE_RATE, CHANNELS)
            RecordingMeta.write(file, file.nameWithoutExtension, createdAt, SAMPLE_RATE, CHANNELS, 0L)

            capturing = true
            paused = false
            RecorderState.markRecordingStarted()
            postNotification()

            acquireWakeLock()
            writerThread = thread(name = "capture-writer") { captureLoop(recorder) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start capture", e)
            mainHandler.post {
                android.widget.Toast
                    .makeText(this, R.string.error_capture_start, android.widget.Toast.LENGTH_LONG)
                    .show()
                stopRecording()
            }
        }
    }

    private fun captureLoop(recorder: AudioRecord) {
        val buffer = ByteArray(16 * 1024)
        var lastNotify = 0L
        try {
            while (capturing && !Thread.currentThread().isInterrupted) {
                if (paused) {
                    Thread.sleep(50)
                    continue
                }
                val read = recorder.read(buffer, 0, buffer.size)
                if (read > 0) {
                    wav?.write(buffer, 0, read)
                } else if (read < 0) {
                    break
                }
                val now = System.currentTimeMillis()
                if (now - lastNotify >= 1000L) {
                    lastNotify = now
                    postNotification()
                }
            }
        } catch (_: InterruptedException) {
            // Normal stop path.
        } catch (e: Exception) {
            Log.e(TAG, "Capture loop error", e)
        }
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        @SuppressLint("WakelockTimeout")
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "inrec:capture").also {
            it.acquire(6 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {
        }
        wakeLock = null
    }

    private fun stopRecording() {
        if (RecorderState.state == RecordState.IDLE) return
        RecorderState.update(RecordState.STOPPING)
        capturing = false
        try {
            writerThread?.interrupt()
            writerThread?.join(2000)
        } catch (_: InterruptedException) {
        }
        writerThread = null

        try {
            audioRecord?.stop()
        } catch (_: IllegalStateException) {
        }
        try {
            audioRecord?.release()
        } catch (_: Exception) {
        }
        audioRecord = null

        try {
            projection?.unregisterCallback(projectionCallback)
            projection?.stop()
        } catch (_: Exception) {
        }
        projection = null

        wav?.closeQuietly()
        wav = null

        // Finalize the sidecar metadata with the real duration.
        recordFile?.let { file ->
            val channels = CHANNELS
            val dataBytes = (file.length() - 44L).coerceAtLeast(0L)
            val durationMs = dataBytes * 1000L / (SAMPLE_RATE * channels * 2L)
            val title = file.nameWithoutExtension
            RecordingMeta.write(file, title, createdAt, SAMPLE_RATE, channels, durationMs)
        }
        recordFile = null

        releaseWakeLock()
        paused = false
        RecorderState.update(RecordState.IDLE)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopRecording()
        super.onDestroy()
    }

    private fun timeStamp(ms: Long): String {
        return java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
            .format(java.util.Date(ms))
    }

    // ---- Notification -------------------------------------------------------

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        channel.setShowBadge(false)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val isPaused = RecorderState.state == RecordState.PAUSED
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_record)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(elapsedText())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)

        val toggleLabel = if (isPaused) R.string.notif_resume else R.string.notif_pause
        val toggleAction = if (isPaused) ACTION_RESUME else ACTION_PAUSE
        builder.addAction(
            0,
            getString(toggleLabel),
            PendingIntent.getService(
                this, 1,
                Intent(this, CaptureService::class.java).setAction(toggleAction),
                PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        builder.addAction(
            0,
            getString(R.string.notif_stop),
            PendingIntent.getService(
                this, 2,
                Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        return builder.build()
    }

    private fun elapsedText(): String {
        val totalSec = RecorderState.elapsedMs() / 1000
        val m = totalSec / 60
        val s = totalSec % 60
        return getString(R.string.notif_elapsed_format, m, s)
    }

    private fun postNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }
}
