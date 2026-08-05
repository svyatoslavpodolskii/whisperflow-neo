package com.pimax.whisperflow

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "🌐 WhisperApi"

data class TranscriptionResult(
    val text: String,
    val language: String? = null,
    val durationMs: Long = 0L,
    val success: Boolean = true,
    val error: String? = null
) {
    companion object {
        fun failure(error: String) = TranscriptionResult(
            text = "",
            success = false,
            error = error
        )
    }
}

class WhisperApi(private val baseUrl: String) {

    companion object {
        /**
         * Filter hallucinations from transcription text.
         * Returns null if the text is a hallucination, otherwise returns the original text.
         */
        fun filterHallucinations(text: String): String? {
            val cleaned = text.trim()
                .lowercase()
                .removeSuffix(".")
                .removeSuffix("!")
                .removeSuffix(",")
                .trim()

            if (cleaned.isEmpty()) return null
            if (cleaned.length < 3) return null

            val phantoms = setOf(
                "thank you", "thanks", "thanks for watching", "thank you for watching",
                "thank you for listening", "thanks for listening", "thank you so much",
                "thank you very much", "gracias", "gracias por ver", "merci", "merci beaucoup",
                "danke", "danke schön", "dankeschön", "arigato", "arigatou gozaimasu",
                "obrigado", "obrigada", "spasibo", "shukran", "dhanyavaad", "xie xie",
                "xiexie", "kamsa hamnida", "gamsahamnida", "bye", "goodbye", "see you",
                "see you next time", "subscribe", "please subscribe", "like and subscribe",
                "please like and subscribe", "subtitles by", "subtitles", "translated by",
                "copyright", "all rights reserved", "music", "applause", "laughter",
                "silence", "you", "the end", "hmm", "um", "uh", "ah", "oh", "so"
            )

            if (cleaned in phantoms) return null

            val prefixes = listOf(
                "thank you", "thanks for", "gracias por", "merci pour", "danke für",
                "subtitles by", "translated by", "transcribed by", "copyright"
            )
            if (prefixes.any { cleaned.startsWith(it) }) return null

            // Repeated single characters or dots
            if (cleaned.all { it == cleaned[0] || it == ' ' || it == '.' }) return null

            // Very short with only common filler words
            val words = cleaned.split("\\s+".toRegex())
            val fillerWords = setOf("the", "a", "an", "is", "it", "i", "you", "we", "um", "uh", "oh", "ah", "hmm", "so", "and")
            if (words.size <= 2 && words.all { it in fillerWords }) return null

            return text // Return original (not cleaned) if not a hallucination
        }
    }

    private val gradioBase get() = "${baseUrl.trimEnd('/')}/gradio_api"

    suspend fun transcribe(
        audioFile: File,
        language: String = "Auto",
        task: String = "transcribe"
    ): TranscriptionResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()

        try {
            Log.d(TAG, "Transcribing ${audioFile.name} (${audioFile.length()} bytes) → $baseUrl")

            val uploadedPath = uploadFile(audioFile)
                ?: return@withContext TranscriptionResult.failure("Failed to upload audio file")

            Log.d(TAG, "Uploaded → $uploadedPath")

            val fileData = JSONObject().apply {
                put("path", uploadedPath)
                put("meta", JSONObject().put("_type", "gradio.FileData"))
                put("orig_name", audioFile.name)
            }

            val langValue = mapLanguageCode(language)

            val eventId = callTranscribe(fileData, task, langValue)
                ?: return@withContext TranscriptionResult.failure("Failed to initiate transcription")

            Log.d(TAG, "Event ID → $eventId")

            val text = pollResult(eventId)
            val elapsed = System.currentTimeMillis() - startTime

            if (text != null) {
                Log.d(TAG, "✅ Transcribed in ${elapsed}ms: ${text.take(100)}...")
                TranscriptionResult(
                    text = text.trim(),
                    language = langValue.takeIf { it != "Auto" },
                    durationMs = elapsed
                )
            } else {
                TranscriptionResult.failure("Server returned empty response")
            }
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - startTime
            Log.e(TAG, "❌ Transcription failed after ${elapsed}ms", e)
            TranscriptionResult.failure(e.message ?: "Unknown error")
        }
    }

    suspend fun transcribeChunk(
        audioFile: File,
        language: String = "Auto",
        task: String = "transcribe",
        peakAmplitude: Float = 0f
    ): String? = withContext(Dispatchers.IO) {
        try {
            val uploadedPath = uploadFile(audioFile) ?: return@withContext null
            val fileData = JSONObject().apply {
                put("path", uploadedPath)
                put("meta", JSONObject().put("_type", "gradio.FileData"))
                put("orig_name", audioFile.name)
            }
            val langValue = mapLanguageCode(language)
            val eventId = callTranscribe(fileData, task, langValue) ?: return@withContext null
            val text = pollResult(eventId)
            // Only filter hallucinations on quiet chunks (no real speech detected).
            // If the user actually spoke (peak >= 0.15), let everything through —
            // even "Thank you" — because they actually said it.
            if (text != null && peakAmplitude < 0.15f && isHallucination(text)) {
                Log.d(TAG, "Filtered hallucination from quiet chunk (peak=${"%.3f".format(peakAmplitude)}): \"${text.trim()}\"")
                return@withContext null
            }
            if (text != null) {
                Log.d(TAG, "Chunk result (peak=${"%.3f".format(peakAmplitude)}): \"${text.trim()}\"")
            }
            text
        } catch (e: Exception) {
            Log.e(TAG, "Chunk transcription failed: ${e.message}")
            null
        }
    }

    suspend fun transcribeViaGateway(
        audioFile: File,
        gatewayUrl: String,
        model: String = "whisper-large-v3-turbo",
        language: String? = null
    ): TranscriptionResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val url = URL("${gatewayUrl.trimEnd('/')}/api/v1/audio/transcriptions")
        val connection = url.openConnection() as HttpURLConnection
        val boundary = "WhisperFlow-${System.currentTimeMillis()}"

        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            connection.connectTimeout = 15_000
            connection.readTimeout = 60_000

            DataOutputStream(connection.outputStream).use { out ->
                // File part
                out.writeBytes("--$boundary\r\n")
                out.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"${audioFile.name}\"\r\n")
                out.writeBytes("Content-Type: audio/wav\r\n")
                out.writeBytes("\r\n")
                FileInputStream(audioFile).use { fis ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (fis.read(buffer).also { bytesRead = it } != -1) {
                        out.write(buffer, 0, bytesRead)
                    }
                }
                out.writeBytes("\r\n")

                // Model part
                out.writeBytes("--$boundary\r\n")
                out.writeBytes("Content-Disposition: form-data; name=\"model\"\r\n\r\n")
                out.writeBytes(model)
                out.writeBytes("\r\n")

                // Language part
                if (language != null && language != "Auto") {
                    out.writeBytes("--$boundary\r\n")
                    out.writeBytes("Content-Disposition: form-data; name=\"language\"\r\n\r\n")
                    out.writeBytes(language)
                    out.writeBytes("\r\n")
                }

                out.writeBytes("--$boundary--\r\n")
                out.flush()
            }

            val responseCode = connection.responseCode
            Log.d(TAG, "Gateway response code: $responseCode")
            
            if (responseCode != 200) {
                val errorBody = try {
                    connection.errorStream?.bufferedReader()?.readText() ?: "no body"
                } catch (e: Exception) { "unreadable" }
                Log.w(TAG, "Gateway STT returned $responseCode: $errorBody")
                return@withContext TranscriptionResult.failure("Gateway error: $responseCode - $errorBody")
            }

            val response = connection.inputStream.bufferedReader().readText()
            Log.d(TAG, "Gateway response body: $response")
            
            val json = JSONObject(response)
            val text = json.optString("text", "")
            val elapsed = System.currentTimeMillis() - startTime

            TranscriptionResult(
                text = text.trim(),
                language = language,
                durationMs = elapsed
            )
        } catch (e: Exception) {
            Log.e(TAG, "Gateway transcription failed: ${e.message}", e)
            TranscriptionResult.failure(e.message ?: "Unknown error")
        } finally {
            connection.disconnect()
        }
    }

    private fun isHallucination(text: String): Boolean {
        val cleaned = text.trim()
            .lowercase()
            .removeSuffix(".")
            .removeSuffix("!")
            .removeSuffix(",")
            .trim()

        if (cleaned.isEmpty()) return true

        if (cleaned.length < 3) return true

        val phantoms = setOf(
            "thank you",
            "thanks",
            "thanks for watching",
            "thank you for watching",
            "thank you for listening",
            "thanks for listening",
            "thank you so much",
            "thank you very much",
            "gracias",
            "gracias por ver",
            "merci",
            "merci beaucoup",
            "danke",
            "danke schön",
            "dankeschön",
            "arigato",
            "arigatou gozaimasu",
            "obrigado",
            "obrigada",
            "spasibo",
            "shukran",
            "dhanyavaad",
            "xie xie",
            "xiexie",
            "kamsa hamnida",
            "gamsahamnida",
            "bye",
            "goodbye",
            "see you",
            "see you next time",
            "subscribe",
            "please subscribe",
            "like and subscribe",
            "please like and subscribe",
            "subtitles by",
            "subtitles",
            "translated by",
            "copyright",
            "all rights reserved",
            "music",
            "applause",
            "laughter",
            "silence",
            "you",
            "the end",
            "hmm",
            "um",
            "uh",
            "ah",
            "oh",
            "so",
        )

        if (cleaned in phantoms) return true

        val prefixes = listOf(
            "thank you",
            "thanks for",
            "gracias por",
            "merci pour",
            "danke für",
            "subtitles by",
            "translated by",
            "transcribed by",
            "copyright",
        )
        if (prefixes.any { cleaned.startsWith(it) }) return true

        // Repeated single characters or dots
        if (cleaned.all { it == cleaned[0] || it == ' ' || it == '.' }) return true

        // Very short with only common filler words
        val words = cleaned.split("\\s+".toRegex())
        val fillerWords = setOf("the", "a", "an", "is", "it", "i", "you", "we", "um", "uh", "oh", "ah", "hmm", "so", "and")
        if (words.size <= 2 && words.all { it in fillerWords }) return true

        return false
    }

    private fun uploadFile(audioFile: File): String? {
        val boundary = "WhisperFlow-${System.currentTimeMillis()}"
        val url = URL("$gradioBase/upload")
        val connection = url.openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            connection.connectTimeout = 15_000
            connection.readTimeout = 60_000

            DataOutputStream(connection.outputStream).use { out ->
                out.writeBytes("--$boundary\r\n")
                out.writeBytes("Content-Disposition: form-data; name=\"files\"; filename=\"${audioFile.name}\"\r\n")
                out.writeBytes("Content-Type: audio/wav\r\n")
                out.writeBytes("\r\n")

                FileInputStream(audioFile).use { fis ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (fis.read(buffer).also { bytesRead = it } != -1) {
                        out.write(buffer, 0, bytesRead)
                    }
                }

                out.writeBytes("\r\n")
                out.writeBytes("--$boundary--\r\n")
                out.flush()
            }

            val responseCode = connection.responseCode
            if (responseCode != 200) {
                val errorBody = try {
                    connection.errorStream?.bufferedReader()?.readText() ?: "no body"
                } catch (_: Exception) { "unreadable" }
                Log.w(TAG, "Upload returned $responseCode: $errorBody")
                return null
            }

            val response = connection.inputStream.bufferedReader().readText()
            Log.d(TAG, "Upload response: $response")

            val jsonArray = JSONArray(response)
            if (jsonArray.length() > 0) {
                return jsonArray.getString(0)
            }
            return null
        } catch (e: Exception) {
            Log.w(TAG, "Upload failed: ${e.message}")
            return null
        } finally {
            connection.disconnect()
        }
    }

    private fun callTranscribe(
        fileData: JSONObject,
        task: String,
        language: String
    ): String? {
        val url = URL("$gradioBase/call/transcribe")
        val connection = url.openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000

            val payload = JSONObject().apply {
                put("data", JSONArray().apply {
                    put(fileData)
                    put(task)
                    put(language)
                })
            }

            Log.d(TAG, "Call payload: $payload")

            connection.outputStream.use { os ->
                os.write(payload.toString().toByteArray())
                os.flush()
            }

            val responseCode = connection.responseCode
            if (responseCode != 200) {
                val errorBody = try {
                    connection.errorStream?.bufferedReader()?.readText() ?: "no body"
                } catch (_: Exception) { "unreadable" }
                Log.w(TAG, "Call returned $responseCode: $errorBody")
                return null
            }

            val response = connection.inputStream.bufferedReader().readText()
            Log.d(TAG, "Call response: $response")

            val json = JSONObject(response)
            val eventId = json.optString("event_id", "")
            return eventId.ifEmpty { null }
        } catch (e: Exception) {
            Log.w(TAG, "Call failed: ${e.message}")
            return null
        } finally {
            connection.disconnect()
        }
    }

    private fun pollResult(eventId: String): String? {
        val url = URL("$gradioBase/call/transcribe/$eventId")
        val connection = url.openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 15_000
            connection.readTimeout = 120_000
            connection.setRequestProperty("Accept", "text/event-stream")

            val responseCode = connection.responseCode
            if (responseCode != 200) {
                Log.w(TAG, "Poll returned $responseCode")
                return null
            }

            val reader = BufferedReader(InputStreamReader(connection.inputStream))
            var eventType: String? = null
            var dataLine: String? = null

            reader.forEachLine { line ->
                when {
                    line.startsWith("event: ") -> {
                        eventType = line.removePrefix("event: ").trim()
                    }
                    line.startsWith("data: ") -> {
                        dataLine = line.removePrefix("data: ").trim()
                        if (eventType == "complete" || eventType == null) {
                            return@forEachLine
                        }
                    }
                }
            }
            reader.close()

            if (dataLine != null) {
                return extractTextFromData(dataLine!!)
            }

            return null
        } catch (e: Exception) {
            Log.w(TAG, "Poll failed: ${e.message}")
            return null
        } finally {
            connection.disconnect()
        }
    }

    private fun extractTextFromData(data: String): String? {
        try {
            val jsonArray = JSONArray(data)
            if (jsonArray.length() > 0) {
                val first = jsonArray.get(0)
                if (first is String) return first
                if (first is JSONObject) {
                    return first.optString("value", first.optString("text", ""))
                        .takeIf { it.isNotEmpty() }
                }
            }
        } catch (_: Exception) {
            try {
                val json = JSONObject(data)
                json.optString("text", "").takeIf { it.isNotEmpty() }?.let { return it }
                val arr = json.optJSONArray("data")
                if (arr != null && arr.length() > 0) return arr.getString(0)
            } catch (_: Exception) {
                val trimmed = data.trim()
                if (trimmed.isNotEmpty() && !trimmed.startsWith("{") && !trimmed.startsWith("[")) {
                    return trimmed
                }
            }
        }
        return null
    }

    private fun mapLanguageCode(code: String): String {
        return when (code.lowercase()) {
            "auto" -> "Auto"
            "en" -> "English"
            "zh" -> "Chinese"
            "es" -> "Spanish"
            "fr" -> "French"
            "de" -> "German"
            "it" -> "Italian"
            "pt" -> "Portuguese"
            "ru" -> "Russian"
            "ja" -> "Japanese"
            "ko" -> "Korean"
            "ar" -> "Arabic"
            "hi" -> "Hindi"
            else -> {
                if (code.first().isUpperCase()) code else "Auto"
            }
        }
    }

    suspend fun cleanRamblerText(
        gatewayUrl: String,
        text: String
    ): String? = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext text
        val url = URL("${gatewayUrl.trimEnd('/')}/api/v1/rambler")
        var connection: HttpURLConnection? = null
        try {
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.connectTimeout = 8_000
            connection.readTimeout = 12_000

            val jsonInput = JSONObject().apply {
                put("text", text)
            }

            DataOutputStream(connection.outputStream).use { out ->
                val inputBytes = jsonInput.toString().toByteArray(Charsets.UTF_8)
                out.write(inputBytes, 0, inputBytes.size)
                out.flush()
            }

            val responseCode = connection.responseCode
            if (responseCode == 200) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(response)
                json.optString("text", text)
            } else {
                Log.w(TAG, "Rambler API returned response code $responseCode")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Rambler API request failed: ${e.message}")
            null
        } finally {
            connection?.disconnect()
        }
    }

    suspend fun checkHealth(): Boolean = withContext(Dispatchers.IO) {
        // Try /health first (Gateway/Standard APIs)
        if (tryUrl("${baseUrl.trimEnd('/')}/health")) return@withContext true
        
        // Fallback to root / (Gradio/Other servers)
        return@withContext tryUrl("${baseUrl.trimEnd('/')}/")
    }

    private suspend fun tryUrl(urlString: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = URL(urlString)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            connection.instanceFollowRedirects = true

            val code = connection.responseCode
            connection.disconnect()

            val healthy = code in 200..399
            Log.d(TAG, "Health check ($urlString): $code (${if (healthy) "✅" else "❌"})")
            healthy
        } catch (e: Exception) {
            Log.w(TAG, "Health check failed ($urlString): ${e.message}")
            false
        }
    }

    suspend fun fetchApiInfo(): JSONObject? = withContext(Dispatchers.IO) {
        try {
            val url = URL("$gradioBase/info")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000

            if (connection.responseCode == 200) {
                val response = connection.inputStream.bufferedReader().readText()
                connection.disconnect()
                JSONObject(response)
            } else {
                connection.disconnect()
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "API info fetch failed: ${e.message}")
            null
        }
    }
}
