package com.watranslate.app

import android.util.Base64
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Thin REST wrapper around Google Cloud Speech-to-Text and Translation APIs.
 *
 * Uses a simple API key (not a service-account JSON) because that's the
 * easiest to use directly from an Android app without shipping a private key
 * on-device. Get one from Google Cloud Console -> APIs & Services -> Credentials,
 * then restrict it to "Cloud Speech-to-Text API" and "Cloud Translation API".
 *
 * IMPORTANT: shipping any API key inside an APK is inherently extractable by
 * anyone who has the APK. For personal/private use this is an acceptable
 * trade-off, but don't publish this app publicly with your key embedded.
 */
class NetworkClient(private val apiKey: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    /**
     * Sends a chunk of 16kHz mono 16-bit PCM audio (already base64-encoded WAV)
     * to Google Speech-to-Text and returns the recognized Indonesian text.
     * Returns null if nothing was recognized (e.g. silence).
     */
    fun speechToText(base64Wav: String, languageCode: String = "id-ID"): String? {
        val url = "https://speech.googleapis.com/v1/speech:recognize?key=$apiKey"

        val body = JSONObject().apply {
            put("config", JSONObject().apply {
                put("encoding", "LINEAR16")
                put("sampleRateHertz", 16000)
                put("languageCode", languageCode)
                put("enableAutomaticPunctuation", true)
            })
            put("audio", JSONObject().apply {
                put("content", base64Wav)
            })
        }

        val request = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody(jsonMedia))
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e("NetworkClient", "STT failed: ${response.code} ${response.body?.string()}")
                    return null
                }
                val respJson = JSONObject(response.body?.string() ?: return null)
                val results = respJson.optJSONArray("results") ?: return null
                if (results.length() == 0) return null

                val transcriptBuilder = StringBuilder()
                for (i in 0 until results.length()) {
                    val alt = results.getJSONObject(i)
                        .getJSONArray("alternatives")
                        .getJSONObject(0)
                    transcriptBuilder.append(alt.getString("transcript")).append(" ")
                }
                transcriptBuilder.toString().trim().ifEmpty { null }
            }
        } catch (e: IOException) {
            Log.e("NetworkClient", "STT network error", e)
            null
        }
    }

    /**
     * Translates text from Indonesian to the given target language code
     * (e.g. "ur" for Urdu, "en" for English).
     */
    fun translateText(text: String, targetLang: String, sourceLang: String = "id"): String? {
        val url = "https://translation.googleapis.com/language/translate/v2?key=$apiKey"

        val body = JSONObject().apply {
            put("q", text)
            put("source", sourceLang)
            put("target", targetLang)
            put("format", "text")
        }

        val request = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody(jsonMedia))
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e("NetworkClient", "Translate failed: ${response.code} ${response.body?.string()}")
                    return null
                }
                val respJson = JSONObject(response.body?.string() ?: return null)
                val translations: JSONArray = respJson
                    .getJSONObject("data")
                    .getJSONArray("translations")
                if (translations.length() == 0) return null
                translations.getJSONObject(0).getString("translatedText")
            }
        } catch (e: IOException) {
            Log.e("NetworkClient", "Translate network error", e)
            null
        }
    }

    companion object {
        /** Helper to base64-encode raw PCM bytes wrapped in a minimal WAV header. */
        fun pcmToBase64Wav(pcmData: ByteArray, sampleRate: Int = 16000): String {
            val wavBytes = wrapPcmAsWav(pcmData, sampleRate)
            return Base64.encodeToString(wavBytes, Base64.NO_WRAP)
        }

        private fun wrapPcmAsWav(pcmData: ByteArray, sampleRate: Int): ByteArray {
            val totalDataLen = pcmData.size + 36
            val byteRate = sampleRate * 2 // 16-bit mono

            val header = ByteArray(44)
            "RIFF".toByteArray().copyInto(header, 0)
            writeIntLE(header, 4, totalDataLen)
            "WAVE".toByteArray().copyInto(header, 8)
            "fmt ".toByteArray().copyInto(header, 12)
            writeIntLE(header, 16, 16) // Subchunk1Size for PCM
            writeShortLE(header, 20, 1) // AudioFormat PCM
            writeShortLE(header, 22, 1) // NumChannels mono
            writeIntLE(header, 24, sampleRate)
            writeIntLE(header, 28, byteRate)
            writeShortLE(header, 32, 2) // BlockAlign
            writeShortLE(header, 34, 16) // BitsPerSample
            "data".toByteArray().copyInto(header, 36)
            writeIntLE(header, 40, pcmData.size)

            return header + pcmData
        }

        private fun writeIntLE(bytes: ByteArray, offset: Int, value: Int) {
            bytes[offset] = (value and 0xff).toByte()
            bytes[offset + 1] = ((value shr 8) and 0xff).toByte()
            bytes[offset + 2] = ((value shr 16) and 0xff).toByte()
            bytes[offset + 3] = ((value shr 24) and 0xff).toByte()
        }

        private fun writeShortLE(bytes: ByteArray, offset: Int, value: Int) {
            bytes[offset] = (value and 0xff).toByte()
            bytes[offset + 1] = ((value shr 8) and 0xff).toByte()
        }
    }
}
