package com.pimax.whisperflow.server

import android.content.Context
import android.util.Log
import com.pimax.whisperflow.local.TranscriptionEngine
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/**
 * Local Whisper server that allows other apps to use the transcription engine.
 *
 * Implements a "Distributed STT" architecture on the device.
 */
class WhisperServer(
    private val context: Context,
    private val transcriptionEngine: TranscriptionEngine
) {
    companion object {
        private const val TAG = "WhisperServer"
    }

    private var server: NettyApplicationEngine? = null
    private val _isRunning = MutableStateFlow(false)
    val isRunning = _isRunning.asStateFlow()

    private val serverScope = CoroutineScope(Dispatchers.IO + Job())

    @Serializable
    data class StatusResponse(
        val status: String,
        val mode: String,
        val model: String?,
        val isReady: Boolean
    )

    @Serializable
    data class TranscriptionResponse(
        val text: String,
        val language: String?,
        val durationMs: Long,
        val model: String
    )

    @Serializable
    data class ErrorResponse(val error: String)

    /**
     * Start the Ktor server on the specified port.
     */
    fun start(port: Int = 8060) {
        if (_isRunning.value) return

        server = embeddedServer(Netty, port = port, host = "0.0.0.0") {
            install(ContentNegotiation) {
                json()
            }

            routing {
                get("/status") {
                    val info = transcriptionEngine.getLocalModelInfo()
                    call.respond(StatusResponse(
                        status = "online",
                        mode = transcriptionEngine.mode.name,
                        model = info?.signatures?.joinToString(",") ?: "Unknown",
                        isReady = transcriptionEngine.isReady()
                    ))
                }

                post("/transcribe") {
                    try {
                        val multipart = call.receiveMultipart()
                        var wavBytes: ByteArray? = null
                        var language: String? = null
                        var task: String? = "transcribe"

                        multipart.forEachPart { part ->
                            when (part) {
                                is PartData.FileItem -> {
                                    wavBytes = part.streamProvider().readBytes()
                                }
                                is PartData.FormItem -> {
                                    when (part.name) {
                                        "language" -> language = part.value
                                        "task" -> task = part.value
                                    }
                                }
                                else -> {}
                            }
                            part.dispose()
                        }

                        if (wavBytes != null) {
                            Log.d(TAG, "Received transcription request (${wavBytes!!.size} bytes, lang=$language)")
                            
                            // Perform transcription
                            val result = transcriptionEngine.transcribeWav(wavBytes!!, language)
                            
                            call.respond(TranscriptionResponse(
                                text = result.text,
                                language = result.language,
                                durationMs = result.processingTimeMs,
                                model = result.modelName
                            ))
                        } else {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse("No audio data provided"))
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Server transcription failed: ${e.message}", e)
                        call.respond(HttpStatusCode.InternalServerError, ErrorResponse(e.message ?: "Unknown error"))
                    }
                }
            }
        }.start(wait = false)

        _isRunning.value = true
        Log.i(TAG, "WhisperServer started on port $port")
    }

    /**
     * Stop the server.
     */
    fun stop() {
        server?.stop(1000, 2000)
        server = null
        _isRunning.value = false
        Log.i(TAG, "WhisperServer stopped")
    }
}
