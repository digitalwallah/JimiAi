package com.jimi.ai

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
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
    private lateinit var audioManager: AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.IO)

    private var awaitingCommand = false

    // --- Foreground-app-aware pausing (prevents mic indicator during YouTube/calls/etc) ---
    private lateinit var foregroundAppMonitor: ForegroundAppMonitor
    private var isPausedForForegroundApp = false

    companion object {
        const val CHANNEL_ID = "jimi_wakeword_channel"
        const val NOTIF_ID = 42
        private const val WATCHDOG_REQUEST_CODE = 99

        private val WAKE_WORD_FRAGMENTS = listOf(
            "jim", "zim", "gimi", "gimmy", "suno",
            "जिम", "जीम", "ज़िम", "ज़ीम", "सुनो"
        )

        private fun findWakeWordMatch(text: String): String? {
            return WAKE_WORD_FRAGMENTS.firstOrNull { text.contains(it, ignoreCase = true) }
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
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("Jimi sun raha hai... 👂"))
        setupRecognizer()
        startListeningCycle()
        scheduleWatchdog(this)

        // Start foreground-app monitor to pause listening during YouTube/calls/camera etc.
        foregroundAppMonitor = ForegroundAppMonitor(
            context = this,
            onShouldPause = { pauseForForegroundApp() },
            onShouldResume = { resumeFromForegroundApp() }
        )
        foregroundAppMonitor.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        foregroundAppMonitor.stop()
        abandonListeningFocus()
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

    private fun requestListeningFocus() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(attrs)
                    .setWillPauseWhenDucked(false)
                    .build()
                focusRequest = request
                audioManager.requestAudioFocus(request)
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                )
            }
        } catch (e: Exception) { }
    }

    private fun abandonListeningFocus() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
                focusRequest = null
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(null)
            }
        } catch (e: Exception) { }
    }

    private fun setupRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            updateNotification("Is device pe speech recognition available nahi hai ❌")
            return
        }

        val googleRecognizerComponent = findGoogleRecognizerComponent()
        recognizer = if (googleRecognizerComponent != null) {
            SpeechRecognizer.createSpeechRecognizer(this, googleRecognizerComponent)
        } else {
            SpeechRecognizer.createSpeechRecognizer(this)
        }

        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                abandonListeningFocus()
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.lowercase() ?: ""
                onHeard(text)
                restartSoon(quick = true)
            }
            override fun onError(error: Int) {
                abandonListeningFocus()
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

    private fun findGoogleRecognizerComponent(): android.content.ComponentName? {
        return try {
            val pm = packageManager
            val services = pm.queryIntentServices(
                Intent(android.speech.RecognitionService.SERVICE_INTERFACE), 0
            )
            val googleService = services.firstOrNull {
                it.serviceInfo.packageName == "com.google.android.googlequicksearchbox"
            }
            googleService?.let {
                android.content.ComponentName(it.serviceInfo.packageName, it.serviceInfo.name)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun startListeningCycle() {
        if (isPausedForForegroundApp) return
        val rec = recognizer ?: return
        try {
            requestListeningFocus()
            rec.startListening(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                }
            )
        } catch (e: Exception) {
            abandonListeningFocus()
            handler.postDelayed({ setupRecognizer(); startListeningCycle() }, 800)
        }
    }

    private fun restartSoon(quick: Boolean) {
        if (isPausedForForegroundApp) return
        val mediaPlaying = try { audioManager.isMusicActive } catch (e: Exception) { false }
        val delayMs = when {
            mediaPlaying -> 3000L
            quick -> 400L
            else -> 1200L
        }
        handler.postDelayed({ startListeningCycle() }, delayMs)
    }

    /** Called when user opens YouTube/camera/a call — stop actively listening so the mic indicator disappears. */
    private fun pauseForForegroundApp() {
        if (isPausedForForegroundApp) return
        isPausedForForegroundApp = true
        try {
            recognizer?.stopListening()
            recognizer?.cancel()
        } catch (e: Exception) { }
        abandonListeningFocus()
        updateNotification("Jimi thodi der ke liye pause hai... 🔇")
    }

    /** Called when user leaves the pause-list app — resume normal always-listening. */
    private fun resumeFromForegroundApp() {
        if (!isPausedForForegroundApp) return
        isPausedForForegroundApp = false
        updateNotification("Jimi sun raha hai... 👂")
        startListeningCycle()
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
            val recentHistory = try {
                val db = JimiDatabase.getInstance(this@WakeWordService)
                val past = db.conversationMemoryDao().getRecent(3)
                past.reversed().joinToString("\n") { it.summary }
            } catch (e: Exception) { "" }

            val reply = try {
                CommandRouter(this@WakeWordService).handle(command, recentHistory)
            } catch (e: Exception) {
                "Error: ${e.message}"
            }
            LastReplyStore.lastReply = reply
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
