package com.example.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.example.data.VoiceLanguage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class VoiceStatus {
    IDLE,
    LISTENING,
    PROCESSING,
    ERROR,
}

data class VoiceUiState(
    val isVisible: Boolean = false,
    val status: VoiceStatus = VoiceStatus.IDLE,
    val partialTranscript: String = "",
    val finalTranscript: String = "",
    val rmsLevel: Float = 0f,
    val errorMessage: String? = null,
)

class VoiceCommandManager(
    private val context: Context,
    private val onTranscriptReady: (String) -> Unit,
) {
    private val _state = MutableStateFlow(VoiceUiState())
    val state: StateFlow<VoiceUiState> = _state.asStateFlow()

    private var speechRecognizer: SpeechRecognizer? = null

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    fun startListening(language: VoiceLanguage) {
        if (!isAvailable()) {
            _state.update {
                it.copy(
                    isVisible = true,
                    status = VoiceStatus.ERROR,
                    errorMessage = "Layanan pengenalan suara tidak tersedia di perangkat ini.",
                )
            }
            return
        }

        ensureRecognizer()
        _state.update {
            VoiceUiState(
                isVisible = true,
                status = VoiceStatus.LISTENING,
                partialTranscript = "",
                finalTranscript = "",
                rmsLevel = 0f,
                errorMessage = null,
            )
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language.localeTag)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language.localeTag)
            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, false)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

        runCatching {
            speechRecognizer?.cancel()
            speechRecognizer?.startListening(intent)
        }.onFailure { err ->
            _state.update {
                it.copy(
                    status = VoiceStatus.ERROR,
                    errorMessage = err.localizedMessage ?: "Gagal memulai mikrofon.",
                )
            }
        }
    }

    fun stopListening() {
        _state.update { it.copy(status = VoiceStatus.PROCESSING) }
        runCatching { speechRecognizer?.stopListening() }
    }

    fun cancelAndDismiss() {
        runCatching { speechRecognizer?.cancel() }
        _state.value = VoiceUiState()
    }

    fun destroy() {
        runCatching {
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        }
        speechRecognizer = null
    }

    private fun ensureRecognizer() {
        if (speechRecognizer != null) return
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context.applicationContext).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    _state.update { it.copy(status = VoiceStatus.LISTENING, errorMessage = null) }
                }

                override fun onBeginningOfSpeech() {
                    _state.update { it.copy(status = VoiceStatus.LISTENING) }
                }

                override fun onRmsChanged(rmsdB: Float) {
                    val normalized = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                    _state.update { it.copy(rmsLevel = normalized) }
                }

                override fun onBufferReceived(buffer: ByteArray?) = Unit

                override fun onEndOfSpeech() {
                    _state.update { it.copy(status = VoiceStatus.PROCESSING, rmsLevel = 0f) }
                }

                override fun onError(error: Int) {
                    val message = when (error) {
                        SpeechRecognizer.ERROR_AUDIO -> "Gagal merekam audio mikrofon."
                        SpeechRecognizer.ERROR_CLIENT -> "Permintaan suara dibatalkan."
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Izin mikrofon belum diberikan."
                        SpeechRecognizer.ERROR_NETWORK,
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Periksa koneksi internet untuk pengenalan suara."
                        SpeechRecognizer.ERROR_NO_MATCH -> "Suara tidak dikenali. Ketuk mic untuk mencoba lagi."
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Mikrofon sedang sibuk. Coba sesaat lagi."
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Tidak ada suara terdeteksi."
                        else -> "Terjadi kesalahan pengenalan suara."
                    }
                    _state.update {
                        it.copy(
                            status = VoiceStatus.ERROR,
                            rmsLevel = 0f,
                            errorMessage = message,
                        )
                    }
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val transcript = matches?.firstOrNull()?.trim().orEmpty()
                    if (transcript.isNotEmpty()) {
                        _state.value = VoiceUiState()
                        onTranscriptReady(transcript)
                    } else {
                        _state.update {
                            it.copy(
                                status = VoiceStatus.ERROR,
                                errorMessage = "Suara tidak dikenali. Coba ulangi.",
                            )
                        }
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val partial = partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        ?.trim()
                        .orEmpty()
                    if (partial.isNotEmpty()) {
                        _state.update { it.copy(partialTranscript = partial) }
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
        }
    }
}
