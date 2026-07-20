package com.jimi.ai

import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Handles:
 *  - Speech-to-text via Android's built-in speech recognizer (mic button press)
 *  - Text-to-speech so Jimi can speak its replies back
 *
 * Recognizer language is set to Hindi (hi-IN) with English as a free-form fallback,
 * since Android's on-device/Google recognizer handles Hinglish reasonably well when
 * the primary locale is hi-IN.
 */
class SpeechHelper(context: Context) {

    private var tts: TextToSpeech? = null
    private var ttsReady = false

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true
                tts?.language = Locale("hi", "IN")
            }
        }
    }

    /** Builds the intent to launch for voice input; call via startActivityForResult / Activity Result API. */
    fun buildRecognizerIntent(): Intent {
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Bolo, Jimi sun raha hai...")
        }
    }

    fun speak(text: String) {
        if (ttsReady) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jimi_reply")
        }
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
    }
}
