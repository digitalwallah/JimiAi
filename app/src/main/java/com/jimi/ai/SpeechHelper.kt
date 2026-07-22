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
 * Voice character: 4 personas built purely from on-device voice selection + pitch/rate tuning
 * (no cloud API/billing). "Arjun" (21, younger male), "Veer" (26, deep male), "Ananya" (younger
 * female, soft), "Isha" (26, warm female). Uses SSML markup for natural pauses.
 *
 * NOTE: this gives distinguishable characters via pitch/pace, not true voice acting - that
 * level of realism needs a cloud neural TTS service, which this deliberately avoids.
 */
class SpeechHelper(private val context: Context) {

    private var tts: TextToSpeech? = null
    private var ttsReady = false

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true
                tts?.language = Locale("hi", "IN")
                applyVoiceCharacter()
            }
        }
    }

    /** Current persona ke hisaab se best-matching voice + pitch/rate apply karta hai. Settings
     * badalne ke baad bhi is function ko dubara call kiya ja sakta hai (naya character turant lagu ho). */
    fun applyVoiceCharacter() {
        val engine = tts ?: return
        val character = SettingsStore.getVoiceCharacter(context)

        val voices = engine.voices ?: emptySet()
        val relevantVoices = voices.filter { it.locale.language == "hi" || it.locale.country == "IN" }
        val isFemaleCharacter = character == "ananya" || character == "isha"

        // Pehle koshish karte hain ki gender-matching naam wali voice mil jaye engine mein,
        // warna sirf pitch/rate se hi character differentiate karte hain.
        val genderMatch = relevantVoices.filter {
            val nameHasFemale = it.name.contains("female", ignoreCase = true)
            val nameHasMale = it.name.contains("male", ignoreCase = true) && !nameHasFemale
            if (isFemaleCharacter) nameHasFemale else nameHasMale
        }
        val bestVoice = (genderMatch.ifEmpty { relevantVoices }).maxByOrNull { it.quality }

        if (bestVoice != null) engine.voice = bestVoice

        when (character) {
            "arjun" -> { engine.setPitch(1.15f); engine.setSpeechRate(1.05f) }   // 21, younger, thoda fast/high
            "veer" -> { engine.setPitch(0.80f); engine.setSpeechRate(0.90f) }   // 26, deep, slow/confident
            "ananya" -> { engine.setPitch(1.20f); engine.setSpeechRate(0.95f) } // younger female, soft
            "isha" -> { engine.setPitch(1.05f); engine.setSpeechRate(0.92f) }   // 26, warm female
            else -> { engine.setPitch(1.0f); engine.setSpeechRate(0.92f) }
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
            .replace(Regex("[*#_~`]"), "")
            .replace(Regex("[/\\\\]"), " ")
            .replace(Regex("[<>{}\\[\\]|]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /** Text ko SSML markup mein wrap karta hai - commas/full-stops ke baad chhoti pause daalta hai. */
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
