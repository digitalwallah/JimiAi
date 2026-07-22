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
                tts?.setSpeechRate(0.85f)   // thoda slow, samajhne me aasaan
                selectMaleVoice()
            }
        }
    }

    /** Android voices don't have a strict "gender" field, so we match by name pattern
     * (most TTS engines name their voices like "hi-in-x-hib-network#male_1"). Falls back
     * to the default voice if no clearly-male voice is found for Hindi/English-India. */
    private fun selectMaleVoice() {
        val engine = tts ?: return
        val voices = engine.voices ?: return
        val maleVoice = voices.firstOrNull {
            (it.locale.language == "hi" || it.locale.country == "IN") &&
                it.name.contains("male", ignoreCase = true) &&
                !it.name.contains("female", ignoreCase = true)
        }
        if (maleVoice != null) {
            engine.voice = maleVoice
        } else {
            // Kuch devices pe pitch thoda kam karne se awaaz zyada "ladke jaisi" lagti hai.
            engine.setPitch(0.85f)
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
            tts?.speak(cleanForSpeech(text), TextToSpeech.QUEUE_FLUSH, null, "jimi_reply")
        }
    }

    /** TTS engine symbols ko literally bol deta hai (jaise "*" ko "star"). Yeh function un
     * markdown/special characters ko hata deta hai jo bolne mein natural nahi lagte, taaki
     * Jimi sirf asli words bole, symbols nahi. */
    private fun cleanForSpeech(text: String): String {
        return text
            .replace(Regex("[*#_~`]"), "")           // markdown symbols: bold, headers, etc.
            .replace(Regex("[/\\\\]"), " ")           // slashes ko space se replace (word break na tute)
            .replace(Regex("[<>{}\\[\\]|]"), "")      // brackets/pipes jo TTS ajeeb bolta hai
            .replace(Regex("\\s+"), " ")              // extra spaces clean up
            .trim()
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
    }
}
