package com.jimi.ai

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * Continuously listens using Android's free built-in SpeechRecognizer
 * and checks every transcript chunk for the wake word "jimi".
 * No API key, no third-party account, no cloud service signup needed —
 * uses whatever recognizer is already installed on the device (usually
 * Google's, which comes free with FuntouchOS/Android).
 */
class WakeWordListener(
    private val context: Context,
    private val onWakeWordDetected: () -> Unit
) {
    private var recognizer: SpeechRecognizer? = null
    private var isListening = false
    private val wakeWords = listOf("jimi", "जिमी", "jimmy", "jini", "gimi", "suno")

    fun start() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.e("WakeWordListener", "Speech recognition not available on this device")
            return
        }
        isListening = true
        startListeningCycle()
    }

    fun stop() {
        isListening = false
        recognizer?.destroy()
        recognizer = null
    }

    private fun startListeningCycle() {
        if (!isListening) return

        recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500)
        }

        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                checkForWakeWord(results)
                restartIfStillListening()
            }

            override fun onPartialResults(partialResults: Bundle?) {
                checkForWakeWord(partialResults)
            }

            override fun onError(error: Int) {
                // Common errors: no speech / timeout — just restart the cycle
                restartIfStillListening()
            }

            override fun onEndOfSpeech() {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onReadyForSpeech(params: Bundle?) {}
        })

        recognizer?.startListening(intent)
    }

    private fun checkForWakeWord(bundle: Bundle?) {
        val matches = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return
        for (text in matches) {
            val lower = text.lowercase()
            if (wakeWords.any { lower.contains(it) }) {
                onWakeWordDetected()
                return
            }
        }
    }

    private fun restartIfStillListening() {
        recognizer?.destroy()
        recognizer = null
        if (isListening) {
            // Small restart loop — Android's recognizer auto-stops after silence,
            // so we relaunch it immediately to simulate "always listening"
            startListeningCycle()
        }
    }
}
