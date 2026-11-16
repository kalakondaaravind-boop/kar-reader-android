package com.kar.reader.feature.transcribe

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.kar.reader.R
import com.kar.reader.core.domain.TranscriptText
import com.kar.reader.data.db.TranscriptStatus
import com.kar.reader.di.AppScope
import com.kar.reader.widget.DeepLinks
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Keeps the app in the foreground while it records or transcribes, with a notification showing progress.
 * Uses the "microphone" service type while recording and "media processing" (Android 15+) or "data sync"
 * while transcribing. Stops itself when nothing is left to do.
 */
@AndroidEntryPoint
class TranscribeService : Service() {
    @Inject lateinit var manager: TranscriptionManager
    @Inject @AppScope lateinit var scope: CoroutineScope

    private var watcher: Job? = null
    private var currentType = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        val mode = intent?.getStringExtra(EXTRA_MODE)
        val recording = mode == MODE_RECORD || manager.recording.value != null
        goForeground(recording, if (recording) "Recording…" else "Preparing transcription…", null)
        if (watcher == null) watcher = scope.launch { watch() }
        return START_NOT_STICKY
    }

    private suspend fun watch() {
        // Give a freshly queued job a moment to start before deciding there is nothing to do.
        delay(1_500)
        combine(manager.recording, manager.processing) { r, p ->
            when {
                r != null -> Shown(true, "Recording · ${TranscriptText.clock(r.elapsedMs)}", null)
                p != null -> Shown(false, "${stageLabel(p.status)} · ${p.title}", (p.progress * 100).toInt())
                else -> null
            }
        }
            .distinctUntilChanged()
            .collect { shown ->
                if (shown != null) {
                    goForeground(shown.recording, shown.text, shown.percent?.let { it / 100f })
                } else {
                    delay(800)
                    if (!manager.isBusy) {
                        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }
    }

    private data class Shown(val recording: Boolean, val text: String, val percent: Int?)

    private fun goForeground(recording: Boolean, text: String, progress: Float?) {
        val type = when {
            recording -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            Build.VERSION.SDK_INT >= 35 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            else -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }
        val open = PendingIntent.getActivity(
            this, 0,
            DeepLinks.intent(this, destination = DeepLinks.DEST_TRANSCRIBE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_transcribe)
            .setContentTitle(if (recording) "KAR READER is recording" else "KAR READER is transcribing")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(open)
            .apply { if (progress != null) setProgress(100, (progress * 100).toInt(), false) }
            .build()
        try {
            if (type != currentType) {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
                currentType = type
            } else {
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            // Android refused the foreground start (for example the app was in the background). Work continues
            // while the process lives; the transcript is marked interrupted at next start if it is killed.
        }
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Recording and transcription", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows while KAR READER records or transcribes audio on this device."
            })
        }
    }

    override fun onDestroy() {
        watcher?.cancel()
        watcher = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_RECORD = "record"
        const val MODE_PROCESS = "process"
        private const val CHANNEL = "transcription"
        private const val NOTIFICATION_ID = 4107

        fun stageLabel(status: String): String = when (status) {
            TranscriptStatus.QUEUED -> "Waiting"
            TranscriptStatus.CONVERTING -> "Reading the audio"
            TranscriptStatus.SPEAKERS -> "Telling voices apart"
            TranscriptStatus.WORDS -> "Writing the words"
            else -> "Working"
        }
    }
}
