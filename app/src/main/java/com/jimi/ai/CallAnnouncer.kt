package com.jimi.ai

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.telephony.TelephonyManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import java.util.Locale

/**
 * Incoming call aane par caller ka naam announce karta hai (agar contact mein saved hai),
 * phir user ke voice response ("haan"/"receive karo" vs "cut karo"/"mat uthao") sun kar
 * Accessibility Service se Answer/Decline button tap karta hai.
 *
 * IMPORTANT LIMITATION: Answer/Decline/Speaker/Mute button ka exact text/label phone ke
 * dialer app par depend karta hai (stock Android vs iQOO/Vivo FuntouchOS dialer alag hote
 * hain). Yeh common labels try karta hai - agar match na ho, dialer ke actual button text
 * batana taaki isko tune kiya ja sake.
 */
class CallAnnouncer : BroadcastReceiver() {

    private var lastState = TelephonyManager.CALL_STATE_IDLE
    private val handler = Handler(Looper.getMainLooper())

    override fun onReceive(context: Context, intent: Intent) {
        val stateStr = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        val incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)

        when (stateStr) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                if (lastState != TelephonyManager.CALL_STATE_RINGING) {
                    lastState = TelephonyManager.CALL_STATE_RINGING
                    if (!incomingNumber.isNullOrBlank()) {
                        announceAndListen(context, incomingNumber)
                    }
                }
            }
            TelephonyManager.EXTRA_STATE_OFFHOOK, TelephonyManager.EXTRA_STATE_IDLE -> {
                lastState = if (stateStr == TelephonyManager.EXTRA_STATE_OFFHOOK)
                    TelephonyManager.CALL_STATE_OFFHOOK else TelephonyManager.CALL_STATE_IDLE
            }
        }
    }

    private fun announceAndListen(context: Context, phoneNumber: String) {
        val callerName = ContactResolver.findNameByNumber(context, phoneNumber)
        val announcement = if (callerName != null) {
            "$callerName ka call aa raha hai. Receive karne ke liye 'haan' bolo, cut karne ke liye 'cut karo' bolo."
        } else {
            "Ek unknown number se call aa raha hai. Receive karne ke liye 'haan' bolo, cut karne ke liye 'cut karo' bolo."
        }

        var tts: TextToSpeech? = null
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale("hi", "IN")
                tts?.speak(announcement, TextToSpeech.QUEUE_FLUSH, null, "call_announce")
                // TTS bolne ke baad thoda wait karke sunna shuru karo, taaki khud ki hi awaaz na sun le.
                handler.postDelayed({ listenForResponse(context, tts) }, 3500)
            }
        }
    }

    private fun listenForResponse(context: Context, tts: TextToSpeech?) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            tts?.shutdown()
            return
        }
        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.lowercase() ?: ""
                handleResponse(text)
                recognizer.destroy()
                tts?.shutdown()
            }
            override fun onError(error: Int) {
                recognizer.destroy()
                tts?.shutdown()
            }
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        recognizer.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
            }
        )
    }

    private fun handleResponse(text: String) {
        val service = JimiAccessibilityService.instance ?: return

        val wantsAnswer = Regex("haan|receive|uthao|answer|utha lo").containsMatchIn(text)
        val wantsDecline = Regex("cut|katao|mat uthao|decline|reject|na uthao").containsMatchIn(text)

        val buttonLabels = if (wantsAnswer) {
            listOf("Answer", "Accept", "Receive")
        } else if (wantsDecline) {
            listOf("Decline", "Reject", "Cut", "End")
        } else {
            return // clear command nahi mila, kuch mat karo
        }

        for (label in buttonLabels) {
            val node = service.findNodeByText(label)
            if (node != null) {
                service.clickNode(node)
                break
            }
        }
    }
}
