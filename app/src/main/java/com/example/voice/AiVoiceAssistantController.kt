package com.example.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.example.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID

enum class AiAssistantPhase {
    HIDDEN,
    GREETING,
    LISTENING,
    SENDING,
    PROCESSING,
    SPEAKING_DONE,
    IDLE,
}

data class AiAssistantUiState(
    val isVisible: Boolean = false,
    val phase: AiAssistantPhase = AiAssistantPhase.HIDDEN,
    val isMuted: Boolean = false,
    val isTtsSpeaking: Boolean = false,
    val rmsLevel: Float = 0f,
    val liveTranscript: String = "",
    val lastUserPrompt: String = "",
    val currentProcessText: String = "",
    val parentProcessLog: List<String> = emptyList(),
    val statusSubtext: String = "",
    val errorMessage: String? = null,
) {
    val isMicLocked: Boolean
        get() = phase == AiAssistantPhase.GREETING ||
            phase == AiAssistantPhase.SENDING ||
            phase == AiAssistantPhase.PROCESSING ||
            phase == AiAssistantPhase.SPEAKING_DONE
}

/**
 * Coordinates the Indonesian conversational AI voice mode with strict turn-taking:
 * - Speaks Indonesian (id-ID) via Android TextToSpeech.
 * - While Antigravity is processing, strictly locks/disables voice input and speaks ONLY
 *   top-level parent process updates while surfacing them on the AI face matrix screen.
 * - Once Antigravity finishes and the assistant finishes speaking, unlocks and resumes
 *   listening automatically for the user's next spoken chat command.
 */
class AiVoiceAssistantController(
    context: Context,
    private val onSendChatMessage: (transcript: String, onResult: (Boolean) -> Unit) -> Unit,
    private val onObserverEnabledChanged: (Boolean) -> Unit,
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(AiAssistantUiState())
    val state: StateFlow<AiAssistantUiState> = _state.asStateFlow()

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingUtteranceAfterInit: Pair<String, String>? = null

    private var speechRecognizer: SpeechRecognizer? = null
    private var isRecognizerListening = false
    private var isAgentRunning = false
    private var observedRunningThisTurn = false
    private var turnWatchdogJob: Job? = null
    private var settleCompleteJob: Job? = null
    private val spokenStepsThisTurn = LinkedHashSet<String>()

    init {
        initTextToSpeech()
    }

    private fun initTextToSpeech() {
        tts = TextToSpeech(appContext) { status ->
            mainHandler.post {
                if (status == TextToSpeech.SUCCESS) {
                    val engine = tts ?: return@post
                    val indonesian = Locale.forLanguageTag("id-ID")
                    val langResult = engine.setLanguage(indonesian)
                    if (langResult == TextToSpeech.LANG_MISSING_DATA ||
                        langResult == TextToSpeech.LANG_NOT_SUPPORTED
                    ) {
                        engine.setLanguage(Locale.getDefault())
                    }
                    engine.setSpeechRate(1.02f)
                    engine.setPitch(1.0f)
                    engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            mainHandler.post {
                                _state.update { it.copy(isTtsSpeaking = true) }
                            }
                        }

                        override fun onDone(utteranceId: String?) {
                            mainHandler.post {
                                handleUtteranceFinished(utteranceId)
                            }
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            mainHandler.post {
                                handleUtteranceFinished(utteranceId)
                            }
                        }

                        override fun onError(utteranceId: String?, errorCode: Int) {
                            mainHandler.post {
                                handleUtteranceFinished(utteranceId)
                            }
                        }
                    })
                    ttsReady = true
                    val pending = pendingUtteranceAfterInit
                    pendingUtteranceAfterInit = null
                    if (pending != null && _state.value.isVisible) {
                        speakInternal(pending.first, pending.second)
                    }
                } else {
                    ttsReady = false
                }
            }
        }
    }

    fun open() {
        mainHandler.post {
            if (_state.value.isVisible) return@post
            cancelTimers()
            stopAndCancelRecognizer()
            spokenStepsThisTurn.clear()
            isAgentRunning = false
            observedRunningThisTurn = false

            onObserverEnabledChanged(true)

            val greeting = appContext.getString(R.string.ai_greeting_speech)
            _state.value = AiAssistantUiState(
                isVisible = true,
                phase = AiAssistantPhase.GREETING,
                isMuted = _state.value.isMuted,
                statusSubtext = greeting,
            )

            speakIndonesian(greeting, UTT_GREETING)
        }
    }

    fun close() {
        mainHandler.post {
            cancelTimers()
            stopAndCancelRecognizer()
            tts?.stop()
            onObserverEnabledChanged(false)
            isAgentRunning = false
            observedRunningThisTurn = false
            spokenStepsThisTurn.clear()
            _state.update {
                AiAssistantUiState(
                    isVisible = false,
                    phase = AiAssistantPhase.HIDDEN,
                    isMuted = it.isMuted,
                )
            }
        }
    }

    fun toggleMute() {
        mainHandler.post {
            val newMuted = !_state.value.isMuted
            _state.update { it.copy(isMuted = newMuted) }
            if (newMuted) {
                tts?.stop()
                _state.update { it.copy(isTtsSpeaking = false) }
                // Never deadlock waiting for speech when muted
                val phase = _state.value.phase
                if (phase == AiAssistantPhase.GREETING || phase == AiAssistantPhase.SPEAKING_DONE) {
                    beginListeningIfUnlocked()
                }
            }
        }
    }

    fun onMicButtonPressed() {
        mainHandler.post {
            val current = _state.value
            if (!current.isVisible || current.isMicLocked) {
                // Strictly refuse voice input while Antigravity is running or AI is speaking
                return@post
            }
            if (current.phase == AiAssistantPhase.LISTENING && isRecognizerListening) {
                runCatching { speechRecognizer?.stopListening() }
            } else {
                beginListeningIfUnlocked()
            }
        }
    }

    /**
     * Called from AndroidBridge whenever Antigravity's top-level parent process updates or finishes.
     */
    fun onAgentProcessUpdate(
        isRunning: Boolean,
        parentStep: String,
        finalSummary: String,
    ) {
        mainHandler.post {
            val current = _state.value
            if (!current.isVisible) return@post

            val cleanStep = sanitizeParentStep(parentStep)
            val cleanSummary = sanitizeParentStep(finalSummary, maxLen = 190)

            if (isRunning) {
                isAgentRunning = true
                observedRunningThisTurn = true
                turnWatchdogJob?.cancel()
                settleCompleteJob?.cancel()

                // Strictly lock voice input whenever Antigravity is actively running
                stopAndCancelRecognizer()

                val isNewParentStep = cleanStep.isNotBlank() &&
                    spokenStepsThisTurn.add(cleanStep.lowercase(Locale.ROOT))

                val updatedLog = if (isNewParentStep) {
                    (current.parentProcessLog + cleanStep).takeLast(6)
                } else {
                    current.parentProcessLog
                }

                val displayStep = when {
                    cleanStep.isNotBlank() -> cleanStep
                    current.currentProcessText.isNotBlank() -> current.currentProcessText
                    else -> appContext.getString(R.string.ai_waiting_process)
                }

                _state.update {
                    it.copy(
                        phase = AiAssistantPhase.PROCESSING,
                        rmsLevel = 0f,
                        liveTranscript = "",
                        currentProcessText = displayStep,
                        parentProcessLog = updatedLog,
                        statusSubtext = displayStep,
                        errorMessage = null,
                    )
                }

                if (isNewParentStep) {
                    speakIndonesian(cleanStep, "${UTT_STEP_PREFIX}${UUID.randomUUID()}")
                }
            } else {
                isAgentRunning = false
                if (current.phase == AiAssistantPhase.PROCESSING || current.phase == AiAssistantPhase.SENDING) {
                    if (observedRunningThisTurn) {
                        // Brief settle delay (800ms) so momentary spinner transitions don't prematurely unlock mic
                        settleCompleteJob?.cancel()
                        settleCompleteJob = scope.launch {
                            delay(800L)
                            if (!isAgentRunning && _state.value.isVisible &&
                                _state.value.phase == AiAssistantPhase.PROCESSING
                            ) {
                                handleAgentCompleted(cleanSummary)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun handleAgentCompleted(summary: String) {
        cancelTimers()
        stopAndCancelRecognizer()
        observedRunningThisTurn = false

        val spokenDone = if (summary.isNotBlank()) {
            "Proses selesai. $summary"
        } else {
            appContext.getString(R.string.ai_done_speech)
        }

        _state.update {
            it.copy(
                phase = AiAssistantPhase.SPEAKING_DONE,
                currentProcessText = if (summary.isNotBlank()) summary else appContext.getString(R.string.ai_done_speech),
                statusSubtext = spokenDone,
                rmsLevel = 0f,
                errorMessage = null,
            )
        }

        speakIndonesian(spokenDone, UTT_DONE)
    }

    private fun speakIndonesian(text: String, utteranceId: String) {
        val clean = text.trim()
        if (clean.isEmpty()) {
            handleUtteranceFinished(utteranceId)
            return
        }
        if (_state.value.isMuted) {
            // Skip audible speech when muted, still honor turn-taking transition
            handleUtteranceFinished(utteranceId)
            return
        }
        if (!ttsReady) {
            pendingUtteranceAfterInit = clean to utteranceId
            // Fallback in case TTS engine takes too long to initialize
            scope.launch {
                delay(2200L)
                if (!ttsReady && pendingUtteranceAfterInit?.second == utteranceId) {
                    pendingUtteranceAfterInit = null
                    handleUtteranceFinished(utteranceId)
                }
            }
            return
        }
        speakInternal(clean, utteranceId)
    }

    private fun speakInternal(text: String, utteranceId: String) {
        val engine = tts
        if (engine == null) {
            handleUtteranceFinished(utteranceId)
            return
        }
        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
        }
        val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        if (result == TextToSpeech.ERROR) {
            handleUtteranceFinished(utteranceId)
        } else {
            _state.update { it.copy(isTtsSpeaking = true) }
        }
    }

    private fun handleUtteranceFinished(utteranceId: String?) {
        _state.update { it.copy(isTtsSpeaking = false) }
        val current = _state.value
        if (!current.isVisible) return

        when {
            utteranceId == UTT_GREETING && current.phase == AiAssistantPhase.GREETING -> {
                beginListeningIfUnlocked()
            }
            utteranceId == UTT_DONE && current.phase == AiAssistantPhase.SPEAKING_DONE -> {
                beginListeningIfUnlocked()
            }
            // Note: Finishing a parent step utterance (UTT_STEP_PREFIX) while PROCESSING intentionally
            // keeps the microphone locked until Antigravity finishes completely.
        }
    }

    private fun beginListeningIfUnlocked() {
        val current = _state.value
        if (!current.isVisible || isAgentRunning) return

        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            _state.update {
                it.copy(
                    phase = AiAssistantPhase.IDLE,
                    errorMessage = appContext.getString(R.string.voice_unavailable),
                )
            }
            return
        }

        stopAndCancelRecognizer()
        tts?.stop()

        _state.update {
            it.copy(
                phase = AiAssistantPhase.LISTENING,
                isTtsSpeaking = false,
                rmsLevel = 0f,
                liveTranscript = "",
                errorMessage = null,
                statusSubtext = appContext.getString(R.string.ai_mic_ready_hint),
            )
        }

        val recognizer = SpeechRecognizer.createSpeechRecognizer(appContext).also {
            speechRecognizer = it
        }
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                isRecognizerListening = true
            }

            override fun onBeginningOfSpeech() = Unit

            override fun onRmsChanged(rmsdB: Float) {
                val normalized = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                _state.update { it.copy(rmsLevel = normalized) }
            }

            override fun onBufferReceived(buffer: ByteArray?) = Unit

            override fun onEndOfSpeech() {
                isRecognizerListening = false
                _state.update { it.copy(rmsLevel = 0f) }
            }

            override fun onError(error: Int) {
                isRecognizerListening = false
                if (_state.value.phase != AiAssistantPhase.LISTENING) return
                val errorText = when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> "Gagal membaca audio mikrofon."
                    SpeechRecognizer.ERROR_NETWORK,
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                    -> "Koneksi pengenalan suara bermasalah."
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                        appContext.getString(R.string.voice_permission_denied)
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                    -> null // Quietly return to IDLE so user can tap mic when ready
                    else -> "Gagal mengenali suara. Ketuk mikrofon untuk coba lagi."
                }
                _state.update {
                    it.copy(
                        phase = AiAssistantPhase.IDLE,
                        rmsLevel = 0f,
                        errorMessage = errorText,
                    )
                }
            }

            override fun onResults(results: Bundle?) {
                isRecognizerListening = false
                val spoken = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.trim()
                    .orEmpty()

                if (spoken.isEmpty()) {
                    _state.update {
                        it.copy(
                            phase = AiAssistantPhase.IDLE,
                            rmsLevel = 0f,
                            liveTranscript = "",
                        )
                    }
                } else {
                    submitSpokenPrompt(spoken)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                if (_state.value.phase != AiAssistantPhase.LISTENING) return
                val partial = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.trim()
                    .orEmpty()
                if (partial.isNotEmpty()) {
                    _state.update { it.copy(liveTranscript = partial) }
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "id-ID")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "id-ID")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

        runCatching {
            recognizer.startListening(intent)
        }.onFailure {
            isRecognizerListening = false
            _state.update {
                it.copy(
                    phase = AiAssistantPhase.IDLE,
                    errorMessage = appContext.getString(R.string.voice_unavailable),
                )
            }
        }
    }

    private fun submitSpokenPrompt(spoken: String) {
        stopAndCancelRecognizer()
        spokenStepsThisTurn.clear()
        observedRunningThisTurn = false
        isAgentRunning = false

        _state.update {
            it.copy(
                phase = AiAssistantPhase.SENDING,
                rmsLevel = 0f,
                liveTranscript = spoken,
                lastUserPrompt = spoken,
                currentProcessText = appContext.getString(R.string.ai_waiting_process),
                parentProcessLog = emptyList(),
                errorMessage = null,
            )
        }

        onSendChatMessage(spoken) { sent ->
            mainHandler.post {
                if (!_state.value.isVisible) return@post
                if (!sent) {
                    val noInput = appContext.getString(R.string.ai_no_chat_found_speech)
                    _state.update {
                        it.copy(
                            phase = AiAssistantPhase.IDLE,
                            currentProcessText = "",
                            errorMessage = noInput,
                            statusSubtext = noInput,
                        )
                    }
                    speakIndonesian(noInput, UTT_ERROR)
                    return@post
                }

                // Transition immediately into PROCESSING with microphone strictly locked
                _state.update {
                    it.copy(
                        phase = AiAssistantPhase.PROCESSING,
                        currentProcessText = appContext.getString(R.string.ai_waiting_process),
                        statusSubtext = appContext.getString(R.string.ai_mic_locked_hint),
                    )
                }

                // Watchdog: if Antigravity finishes super quickly or doesn't expose a busy widget
                // within 6.5s, unlock gracefully instead of locking forever.
                turnWatchdogJob?.cancel()
                turnWatchdogJob = scope.launch {
                    delay(6_500L)
                    if (_state.value.isVisible &&
                        _state.value.phase == AiAssistantPhase.PROCESSING &&
                        !observedRunningThisTurn &&
                        !isAgentRunning
                    ) {
                        handleAgentCompleted("")
                    }
                }
            }
        }
    }

    private fun stopAndCancelRecognizer() {
        isRecognizerListening = false
        runCatching { speechRecognizer?.cancel() }
        runCatching { speechRecognizer?.destroy() }
        speechRecognizer = null
    }

    private fun cancelTimers() {
        turnWatchdogJob?.cancel()
        turnWatchdogJob = null
        settleCompleteJob?.cancel()
        settleCompleteJob = null
    }

    private fun sanitizeParentStep(raw: String, maxLen: Int = 140): String {
        val collapsed = raw
            .replace(Regex("[#*`_~>•▪]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (collapsed.length <= maxLen) return collapsed
        return collapsed.take(maxLen - 1).trimEnd() + "…"
    }

    fun destroy() {
        cancelTimers()
        scope.cancel()
        mainHandler.post {
            stopAndCancelRecognizer()
            tts?.stop()
            tts?.shutdown()
            tts = null
            ttsReady = false
        }
    }

    private companion object {
        const val UTT_GREETING = "utt_ai_greeting"
        const val UTT_DONE = "utt_ai_done"
        const val UTT_ERROR = "utt_ai_error"
        const val UTT_STEP_PREFIX = "utt_ai_step_"
    }
}
