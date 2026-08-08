package com.jarvis.ai.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import java.util.Locale

enum class VoiceState { IDLE, LISTENING, THINKING, SPEAKING, ERROR }

class VoiceManager(
    private val context: Context,
    private val onStateChanged: (VoiceState) -> Unit,
    private val onSpeechResult: (String) -> Unit,
    private val onError: (String) -> Unit
) : TextToSpeech.OnInitListener {
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    fun isRecognitionAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    fun startListening() {
        if (!isRecognitionAvailable()) {
            onError("Speech recognition service unavailable")
            onStateChanged(VoiceState.ERROR)
            return
        }
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(context).also { r ->
                r.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) { onStateChanged(VoiceState.LISTENING) }
                    override fun onBeginningOfSpeech() = Unit
                    override fun onRmsChanged(rmsdB: Float) = Unit
                    override fun onBufferReceived(buffer: ByteArray?) = Unit
                    override fun onEndOfSpeech() { onStateChanged(VoiceState.THINKING) }
                    override fun onError(error: Int) {
                        onStateChanged(VoiceState.ERROR)
                        onError("STT error: $error")
                    }
                    override fun onResults(results: Bundle?) {
                        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                        if (text != null) onSpeechResult(text) else onStateChanged(VoiceState.IDLE)
                    }
                    override fun onPartialResults(partialResults: Bundle?) = Unit
                    override fun onEvent(eventType: Int, params: Bundle?) = Unit
                })
            }
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        recognizer?.startListening(intent)
    }

    fun speak(text: String) {
        if (tts == null) tts = TextToSpeech(context, this)
        if (ttsReady) {
            onStateChanged(VoiceState.SPEAKING)
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "JARVIS")
        }
    }

    fun stop() {
        recognizer?.stopListening()
        tts?.stop()
        onStateChanged(VoiceState.IDLE)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
            ttsReady = true
        } else onError("TTS initialization failed")
    }

    fun destroy() {
        recognizer?.destroy()
        recognizer = null
        tts?.stop()
        tts?.shutdown()
        tts = null
        ttsReady = false
    }
}
