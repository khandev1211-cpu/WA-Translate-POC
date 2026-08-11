package com.watranslate.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

/**
 * PROOF-OF-CONCEPT SERVICE.
 *
 * Goal: find out whether AudioPlaybackCaptureConfiguration can actually pick up
 * WhatsApp VoIP call audio on this device, or whether Android blocks it
 * (which is the expected/likely outcome for call audio specifically).
 *
 * IMPORTANT REALITY CHECK (read this before wiring in STT/translation):
 * - AudioPlaybackCaptureConfiguration.Builder(mediaProjection) can only capture
 *   audio of usage type USAGE_MEDIA, USAGE_GAME, or USAGE_UNKNOWN by default.
 * - Android explicitly EXCLUDES USAGE_VOICE_COMMUNICATION (i.e. VoIP call audio,
 *   which is what WhatsApp calls use) from capture, for privacy/security reasons.
 * - This means: on stock Android, this capture path will likely record SILENCE
 *   or only capture notification/media sounds during a WhatsApp call — not the
 *   other person's voice.
 * - Some OEM skins (heavily modified ones) behave differently, which is why we
 *   are testing on your actual device rather than assuming.
 *
 * This service records whatever it captures into a WAV file for 10 seconds so
 * we can inspect it afterward.
 */
class CaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private val job = Job()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    companion object {
        const val CHANNEL_ID = "capture_channel"
        const val NOTIF_ID = 1001
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val SAMPLE_RATE = 16000 // 16kHz mono is what Google STT expects
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: -1
        val resultData: Intent? = intent?.getParcelableExtra(EXTRA_RESULT_DATA)

        if (resultCode == -1 || resultData == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpm.getMediaProjection(resultCode, resultData)

        startCapture()

        return START_NOT_STICKY
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Audio Capture",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("WA Translate POC")
            .setContentText("Capturing test audio…")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
    }

    private fun startCapture() {
        val projection = mediaProjection ?: run {
            stopSelf()
            return
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // AudioPlaybackCaptureConfiguration requires API 29+
            stopSelf()
            return
        }

        val playbackConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            // NOTE: USAGE_VOICE_COMMUNICATION is intentionally NOT addable here —
            // the Android API does not allow apps to capture call audio this way.
            // We are including UNKNOWN in case WhatsApp tags its stream generically,
            // but do not expect this to reliably capture the other caller's voice.
            .build()

        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()

        val minBufSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        try {
            audioRecord = AudioRecord.Builder()
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(minBufSize * 2)
                .setAudioPlaybackCaptureConfig(playbackConfig)
                .build()
        } catch (e: Exception) {
            broadcastStatus("ERROR building AudioRecord: ${e.message}")
            stopSelf()
            return
        }

        val record = audioRecord ?: return

        scope.launch {
            try {
                val outFile = File(getExternalFilesDir(null), "capture_test.pcm")
                val fos = FileOutputStream(outFile)
                val buffer = ByteArray(minBufSize)

                record.startRecording()
                broadcastStatus("Recording 10 seconds of system audio…")

                val startTime = System.currentTimeMillis()
                var totalBytes = 0L
                var maxAmplitude = 0

                while (System.currentTimeMillis() - startTime < 10_000) {
                    val read = record.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        fos.write(buffer, 0, read)
                        totalBytes += read

                        // quick-and-dirty check: is there actual signal or just silence?
                        var i = 0
                        while (i < read - 1) {
                            val sample = (buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)
                            val amp = kotlin.math.abs(sample.toShort().toInt())
                            if (amp > maxAmplitude) maxAmplitude = amp
                            i += 2
                        }
                    }
                }

                record.stop()
                fos.close()

                val verdict = if (maxAmplitude < 50) {
                    "Mostly SILENCE captured (max amplitude=$maxAmplitude). " +
                    "This strongly suggests Android blocked capture of the call audio, " +
                    "as expected for USAGE_VOICE_COMMUNICATION streams."
                } else {
                    "Signal detected! max amplitude=$maxAmplitude, bytes=$totalBytes. " +
                    "Something was captured — needs manual listen-back to confirm " +
                    "it's actually the call voice and not something else (e.g. media)."
                }

                broadcastStatus("DONE. $verdict File: ${outFile.absolutePath}")

            } catch (e: Exception) {
                broadcastStatus("ERROR during capture: ${e.message}")
            } finally {
                stopSelf()
            }
        }
    }

    private fun broadcastStatus(msg: String) {
        val intent = Intent("com.watranslate.app.STATUS_UPDATE")
        intent.putExtra("message", msg)
        sendBroadcast(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        audioRecord?.release()
        mediaProjection?.stop()
        job.cancel()
    }
}
