package com.watranslate.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive

/**
 * Listens via the phone MICROPHONE (not system/internal audio capture),
 * so this works reliably regardless of Android version or OEM restrictions.
 *
 * Intended usage: put the WhatsApp call on LOUDSPEAKER, then this service
 * picks up the other person's voice from the room, same way Google Translate's
 * "conversation" mode works.
 *
 * Pipeline per ~4 second chunk:
 *   mic PCM -> WAV+base64 -> Google STT (id-ID) -> text
 *   text -> Google Translate -> Urdu (ur) + English (en)
 *   -> update floating overlay
 */
class TranslateService : Service() {

    private var audioRecord: AudioRecord? = null
    private val job = Job()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var overlay: OverlayWindow? = null
    private var networkClient: NetworkClient? = null

    @Volatile
    private var isRunning = false

    companion object {
        const val CHANNEL_ID = "translate_channel"
        const val NOTIF_ID = 2001
        const val SAMPLE_RATE = 16000
        const val CHUNK_DURATION_MS = 4000L // 4 seconds per recognition chunk

        const val ACTION_STOP = "com.watranslate.app.ACTION_STOP"
    }

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val apiKey = intent?.getStringExtra("api_key")
        if (apiKey.isNullOrBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }

        networkClient = NetworkClient(apiKey)
        startForeground(NOTIF_ID, buildNotification())

        overlay = OverlayWindow(this)
        mainHandler.post { overlay?.show() }

        startListeningLoop()

        return START_STICKY
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Live Translation",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }

        val stopIntent = Intent(this, TranslateService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("WA Translate running")
            .setContentText("Listening via microphone — put call on loudspeaker")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .addAction(0, "Stop", stopPending)
            .setOngoing(true)
            .build()
    }

    private fun startListeningLoop() {
        val minBufSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBufSize * 2
            )
        } catch (e: SecurityException) {
            stopSelf()
            return
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            stopSelf()
            return
        }

        audioRecord = record
        isRunning = true
        record.startRecording()

        scope.launch {
            val bytesPerChunk = (SAMPLE_RATE * 2 * CHUNK_DURATION_MS / 1000).toInt()

            while (isActive && isRunning) {
                val buffer = ByteArray(bytesPerChunk)
                var offset = 0

                // Fill one chunk's worth of audio
                while (offset < bytesPerChunk && isActive && isRunning) {
                    val read = record.read(buffer, offset, bytesPerChunk - offset)
                    if (read > 0) offset += read else break
                }

                if (offset < 1000) continue // essentially empty, skip

                processChunk(buffer.copyOf(offset))
            }
        }
    }

    private fun processChunk(pcmChunk: ByteArray) {
        val client = networkClient ?: return

        val base64Wav = NetworkClient.pcmToBase64Wav(pcmChunk, SAMPLE_RATE)
        val indonesianText = client.speechToText(base64Wav, "id-ID") ?: return
        if (indonesianText.isBlank()) return

        val urdu = client.translateText(indonesianText, "ur") ?: "…"
        val english = client.translateText(indonesianText, "en") ?: "…"

        mainHandler.post {
            overlay?.updateText(indonesianText, urdu, english)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        job.cancel()
        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }
        audioRecord?.release()
        mainHandler.post { overlay?.hide() }
    }
}
