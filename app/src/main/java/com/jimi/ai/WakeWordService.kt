package com.jimi.ai

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Runs as a foreground service so Android allows it to keep the mic "warm" even with
 * the screen off. It continuously restarts Android's SpeechRecognizer in short bursts,
 * listening for the word "Jimi". When heard:
 *   1. Wakes the screen (PowerManager wake lock)
 *   2. Listens for the actual command that follows
 *   3. Runs it through the same CommandRouter used by the mic button / text box
 *   4. Speaks the result back
 *
 * NOTE ON BATTERY: continuously restarting SpeechRecognizer is noticeably more battery-hungry
 * than a dedicated low-power wake-word engine (e.g. Picovoice Porcupine), because it briefly
 * uses cloud/on-device STT every cycle rather than a tiny always-on keyword model. It works
 * fine for personal use; if battery drain bothers you, swapping in Porcupine later is a
 * drop-in replacement for the listening loop.
 *
 * NOTE ON LOCK SCREEN: if your phone has a PIN/pattern/fingerprint lock, Jimi CANNOT and
 * WILL NOT bypass it (that would be a serious security hole). It wakes the screen so you can
 * unlock normally, then continues.
 */
class WakeWordService : Service() {

    private var recognizer: SpeechRecognizer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var speechHelper: SpeechHelper
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.IO)

    private var awaitingCommand = false

    companion object {
        const val CHANNEL_ID = "jimi_wakeword_channel"
        const val NOTIF_ID = 42
        const val WAKE_WORD = "jimi"

        fun start(context: Context) {
            val intent = Intent(context, WakeWordService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WakeWordService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        speechHelper = SpeechHelper(this)
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("Jimi sun raha hai... 👂"))
        setupRecognizer()
        startListeningCycle()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        recognizer?.destroy()
        wakeLock?.let { if (it.isHeld) it.release() }
        speechHelper.shutdown()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Jimi Always-Listening", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Jimi")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIF_ID, buildNotification(text))
    }

    /** Recognizer object ek hi baar banate hain (baar-baar destroy-create karna hi
     * screen-off reliability ka sabse bada dushman tha). */
    private fun setupRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    val text = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()?.lowercase() ?: ""
                    onHeard(text)
                    restartSoon()
                }
                override fun onError(error: Int) { restartSoon() }
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
    }

    /** Same recognizer instance pe dobara startListening call karta hai - naya object nahi banata. */
    private fun startListeningCycle() {
        val rec = recognizer ?: return
        try {
            rec.startListening(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                }
            )
        } catch (e: Exception) {
            // Kabhi-kabhi "already listening" jaisi state aa jaati hai - reset karke retry.
            handler.postDelayed({ setupRecognizer(); startListeningCycle() }, 800)
        }
    }

    private fun restartSoon() {
        // Tiny delay avoids hammering the recognizer in a tight loop.
        handler.postDelayed({ startListeningCycle() }, 400)
    }

    private fun onHeard(text: String) {
        if (text.isBlank()) return

        if (!awaitingCommand) {
            if (text.contains(WAKE_WORD)) {
                wakeScreen()
                awaitingCommand = true
                updateNotification("Bolo, Jimi sun raha hai...")
                speechHelper.speak("Ji bolo")
                // If they said the whole thing in one breath ("Jimi, Rahul ko..."),
                // treat whatever comes after the wake word as the command right away.
                val afterWakeWord = text.substringAfter(WAKE_WORD).trim(',', ' ', '.')
                if (afterWakeWord.length > 3) {
                    awaitingCommand = false
                    updateNotification("Jimi sun raha hai... 👂")
                    runCommand(afterWakeWord)
                }
            }
        } else {
            awaitingCommand = false
            updateNotification("Jimi sun raha hai... 👂")
            runCommand(text)
        }
    }

    private fun runCommand(command: String) {
        scope.launch {
            val reply = try {
                CommandRouter(this@WakeWordService).handle(command)
            } catch (e: Exception) {
                "Error: ${e.message}"
            }
            speechHelper.speak(reply)
            updateNotification(reply.take(60))
        }
    }

    /** Turns the screen on briefly so the user sees what's happening / can unlock if needed. */
    private fun wakeScreen() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock?.let { if (it.isHeld) it.release() }
        @Suppress("DEPRECATION")
        wakeLock = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "Jimi:WakeScreen"
        )
        wakeLock?.acquire(10_000)
    }
}
