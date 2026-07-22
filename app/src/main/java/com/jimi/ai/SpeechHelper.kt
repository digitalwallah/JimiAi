package com.jimi.ai

import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.util.Locale

/**
 * Handles:
 *  - Speech-to-text via Android's built-in speech recognizer (mic button press)
 *  - Text-to-speech so Jimi can speak its replies back
 *
 * Voice quality: automatically picks the highest-quality voice available on-device
 * for Hindi/English-India, instead of a fixed default. Uses SSML markup (natural
 * pauses + pitch/rate variation) to sound less flat/robotic - this works fully
 * on-device through Android's built-in TTS engine, no cloud API/billing involved.
 *
 * NOTE: SSML gives better pacing and pitch variation, not true emotional acting -
 * that level of realism needs a cloud neural TTS service, which this deliberately avoids.
 */
class SpeechHelper(context: Context) {

    private var tts: TextToSpeech? = null
    private var ttsReady = false

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true
                tts?.language = Locale("hi", "IN")
                tts?.setSpeechRate(0.92f)   // thoda natural pace, pehle se kam slow
                selectBestVoice()
            }
        }
    }

    /** Phone mein available saari voices mein se sabse high-quality wali (Hindi/English-India)
     * automatically choose karta hai, fixed "male name pattern" ke bharose rehne ke bajaye. */
    private fun selectBestVoice() {
        val engine = tts ?: return
        val voices = engine.voices ?: return

        val relevantVoices = voices.filter {
            it.locale.language == "hi" || it.locale.country == "IN"
        }

        // Quality tiers: VERY_HIGH > HIGH > NORMAL > LOW. Sabse behtar wali chuno.
        val bestVoice = relevantVoices.maxByOrNull { it.quality }

        if (bestVoice != null) {
            engine.voice = bestVoice
        } else {
            // Koi Hindi/IN voice na mile toh purana fallback - pitch thoda kam karke natural banate hain.
            engine.setPitch(0.9f)
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
            val cleaned = cleanForSpeech(text)
            val ssml = wrapWithSsml(cleaned)
            tts?.speak(ssml, TextToSpeech.QUEUE_FLUSH, null, "jimi_reply")
        }
    }

    /** TTS engine symbols ko literally bol deta hai (jaise "*" ko "star"). Yeh function un
     * markdown/special characters ko hata deta hai jo bolne mein natural nahi lagte. */
    private fun cleanForSpeech(text: String): String {
        return text
            .replace(Regex("[*#_~`]"), "")           // markdown symbols: bold, headers, etc.
            .replace(Regex("[/\\\\]"), " ")           // slashes ko space se replace (word break na tute)
            .replace(Regex("[<>{}\\[\\]|]"), "")      // brackets/pipes jo TTS ajeeb bolta hai (SSML wrap se pehle hi hata do)
            .replace(Regex("\\s+"), " ")              // extra spaces clean up
            .trim()
    }

    /** Text ko SSML markup mein wrap karta hai - commas/full-stops ke baad chhoti pause daalta hai,
     * taaki bolne ka flow zyada natural lage, ek hi saans mein poora paragraph bolne jaisa nahi. */
    private fun wrapWithSsml(text: String): String {
        val withPauses = text
            .replace(",", ",<break time=\"180ms\"/>")
            .replace(".", ".<break time=\"280ms\"/>")
            .replace("!", "!<break time=\"250ms\"/>")
            .replace("?", "?<break time=\"280ms\"/>")
        return "<speak>$withPauses</speak>"
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
    }
}
