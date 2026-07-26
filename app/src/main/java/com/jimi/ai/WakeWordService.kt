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
        private const val WATCHDOG_REQUEST_CODE = 99

        // Exact-spelling list ke bajaye pattern-matching use karte hain — isse "Jimi" ki
        // HAR practical spelling automatically pakdi jaati hai, chahe Roman script mein ho
        // ("jimi", "jimmy", "jeemy", "jimmie"...) ya Devanagari mein ("जिमी", "जिम", "ज़िमी",
        // "जिम्मी"...). "Hi Jimi" / "हाय जिमी" jaisa koi prefix ho to bhi problem nahi,
        // kyunki hum pattern ko poore text ke andar kahin bhi dhoondhte hain.
        private val WAKE_WORD_ROMAN = Regex("j[iey]+m+[iy]?", RegexOption.IGNORE_CASE)
        private val WAKE_WORD_DEVANAGARI = Regex("ज़?ज[िी]?म+[्िीय]?")

        private fun findWakeWordMatch(text: String): String? {
            WAKE_WORD_ROMAN.find(text)?.let { return it.value }
            WAKE_WORD_DEVANAGARI.find(text)?.let { return it.value }
            return null
        }

        private val JARVIS_GREETINGS = listOf("Ji bolo", "Boliye", "Sunn raha hoon", "Ji, kahiye")
        private val MYRA_GREETINGS = listOf("Haanji bolo na", "Kaho kya scene hai", "Bolo bolo, sun rahi hoon", "Ji bataiye")

        fun start(context: Context) {
            val intent = Intent(context, WakeWordService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            cancelWatchdog(context)
            context.stopService(Intent(context, WakeWordService::class.java))
        }

        fun scheduleWatchdog(context: Context) {
            val intent = Intent(context, WakeWordWatchdogReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                context, WATCHDOG_REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.setInexactRepeating(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                android.os.SystemClock.elapsedRealtime() + AlarmManager.INTERVAL_FIFTEEN_MINUTES,
                AlarmManager.INTERVAL_FIFTEEN_MINUTES,
                pendingIntent
            )
        }

        fun cancelWatchdog(context: Context) {
            val intent = Intent(context, WakeWordWatchdogReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                context, WATCHDOG_REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.cancel(pendingIntent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        speechHelper = SpeechHelper(this)
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("Jimi sun raha hai... 👂"))
        setupRecognizer()
        startListeningCycle()
        scheduleWatchdog(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        recognizer?.destroy()
        wakeLock?.let { if (it.isHeld) it.release() }
        speechHelper.shutdown()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        val restartIntent = Intent(applicationContext, WakeWordService::class.java)
        val pendingIntent = PendingIntent.getService(
            applicationContext, 1, restartIntent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            android.os.SystemClock.elapsedRealtime() + 1000,
            pendingIntent
        )
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

    private fun setupRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            updateNotification("Is device pe speech recognition available nahi hai ❌")
            return
        }
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    val text = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()?.lowercase() ?: ""
                    onHeard(text)
                    restartSoon(quick = true)
                }
                override fun onError(error: Int) {
                    val isSilenceError = error == SpeechRecognizer.ERROR_NO_MATCH ||
                        error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                    if (!isSilenceError) {
                        updateNotification("Recognizer error ($error) — retry ho raha hai... 👂")
                    }
                    restartSoon(quick = isSilenceError)
                }
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
            handler.postDelayed({ setupRecognizer(); startListeningCycle() }, 800)
        }
    }

    private fun restartSoon(quick: Boolean) {
        handler.postDelayed({ startListeningCycle() }, if (quick) 400 else 1200)
    }

    private fun onHeard(text: String) {
        if (text.isBlank()) return

        if (!awaitingCommand) {
            val matched = findWakeWordMatch(text)
            if (matched != null) {
                wakeScreen()
                awaitingCommand = true
                updateNotification("Bolo, Jimi sun raha hai...")
                val greetings = if (SettingsStore.getPersona(this) == "myra") MYRA_GREETINGS else JARVIS_GREETINGS
                speechHelper.speak(greetings.random())
                // Poore ek saans mein bola gaya command ("Jimi, WhatsApp pe Rahul ko...")
                // wake-word ke baad ka hissa turant command ke roop mein bhej dete hain.
                val afterWakeWord = text.substringAfter(matched).trim(',', ' ', '.')
                if (afterWakeWord.length > 3) {
                    awaitingCommand = false
                    updateNotification("Jimi sun raha hai... 👂")
                    runCommand(afterWakeWord)
                }
            } else {
                updateNotification("Suna: \"$text\" (wake-word nahi mila) — sun raha hoon... 👂")
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
