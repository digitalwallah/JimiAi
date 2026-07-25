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
 *
 * NOTE ON OEM BACKGROUND KILL: vivo/iQOO (FunTouch/OriginOS), MIUI, ColorOS etc. run their
 * own background-process killer on top of standard Android, which can silently kill this
 * service even with battery-optimization exemption granted. `scheduleWatchdog()` below fights
 * this by periodically checking (independent of onTaskRemoved) whether the service is still
 * alive and restarting it if not. For this to actually stick, the user also needs to allow
 * "Autostart"/"Background power consumption" for Jimi in the OEM's own settings
 * (MainActivity.requestAutostartPermission() tries to open that screen directly).
 *
 * NOTE ON THE MIC INDICATOR: Android shows a system-level privacy dot/icon whenever
 * SpeechRecognizer.startListening() is active — this is an OS security feature, not
 * something an app can suppress, and it appears on EVERY always-listening app (Assistant,
 * WhatsApp, etc.), not just Jimi. What we CAN control is how often we restart the listening
 * cycle: EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS below makes each cycle listen for
 * a longer stretch before giving up on silence, so the indicator flickers far less often than
 * the old rapid-restart approach, while also giving the recognizer more time to catch a full
 * sentence (which also improves accuracy).
 *
 * FIX (this version): removed EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS. It was forcing every
 * listening cycle to stay open at least 15 seconds regardless of silence, which fought the
 * 2.5s silence-timeout below, made each cycle much slower, and kept the mic open long enough
 * that the OEM's background-killer was more likely to kill the service. Removing it restores
 * the fast, responsive cycle behavior.
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
        // Common mishears bhi include kiye — "Jimi" jaisa naam recognizer kabhi-kabhi
        // thoda alag transcribe kar deta hai, isliye sirf exact "jimi" pe depend nahi karte.
        val WAKE_WORDS = listOf("jimi", "jimmy", "zimmy", "jimini", "jimy")
        private const val WATCHDOG_REQUEST_CODE = 99

        // Persona ke hisaab se greeting variety - har baar wake word sunte hi in me se random pick hoga.
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

        /** Har 15 min mein service ki zinda-hone-ki check karega. Ye Doze-friendly
         * inexact repeating alarm hai, isliye SCHEDULE_EXACT_ALARM ki zaroorat nahi padti. */
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

    /** FuntouchOS/MIUI jaise OEMs app swipe hote hi service ko turant maar dete hain.
     * Ek chhota alarm schedule karte hain jo kuch second baad service ko wapas zinda kar de. */
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

    /** Recognizer object ek hi baar banate hain (baar-baar destroy-create karna hi
     * screen-off reliability ka sabse bada dushman tha). */
    private fun setupRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    // Sirf top guess nahi, top 5 tak ke saare alternatives check karte hain —
                    // ek chhota-sa mishear bhi ab match miss nahi karega agar koi ek
                    // alternative sahi nikla.
                    val allGuesses = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.map { it.lowercase() } ?: emptyList()
                    onHeard(allGuesses)
                    restartSoon(quick = true)
                }
                override fun onError(error: Int) {
                    // "No speech" / timeout silence mein normal hai — inpe fast-restart karke
                    // mic indicator baar-baar flicker karna avoid karte hain, thoda slow retry karte hain.
                    val isSilenceError = error == SpeechRecognizer.ERROR_NO_MATCH ||
                        error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                    restartSoon(quick = !isSilenceError)
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

    /** Same recognizer instance pe dobara startListening call karta hai - naya object nahi banata. */
    private fun startListeningCycle() {
        val rec = recognizer ?: return
        try {
            rec.startListening(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    // "Jimi" (English proper noun) + Hinglish commands dono en-IN mode mein
                    // zyada reliably recognize hote hain — hi-IN mode "Jimi" ko aksar galat sun leta tha.
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                    // Top guess ke alawa 4 aur alternatives bhi maangte hain — accuracy ke liye,
                    // taaki ek chhota mishear bhi wake-word detection miss na kare.
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                    // Ek listening-window ko lamba karte hain (chup rehne pe bhi ~2.5s tak
                    // wait karega band karne se pehle) — isse (a) poora sentence pakadne ka
                    // zyada time milta hai (accuracy up) aur (b) cycle utni jaldi restart
                    // nahi hota, isliye mic-indicator utni baar-baar nahi chamakta.
                    // NOTE: EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS jaanbujh kar yahan NAHI hai —
                    // wo har cycle ko forcibly 15s tak khula rakhta tha chahe silence ho, jo isi
                    // silence-timeout se contradict karta tha aur mic ko zaroorat se zyada der
                    // khula rakhta tha (OEM background-killer trigger karne ka bada reason).
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2500)
                }
            )
        } catch (e: Exception) {
            // Kabhi-kabhi "already listening" jaisi state aa jaati hai - reset karke retry.
            handler.postDelayed({ setupRecognizer(); startListeningCycle() }, 800)
        }
    }

    /** quick=true (result mila, chahe wake-word na ho) toh 400ms mein turant restart.
     * quick=false (silence/timeout error) toh 2000ms wait karke restart — isse chup rehne
     * par mic indicator baar-baar nahi chamakta. */
    private fun restartSoon(quick: Boolean) {
        handler.postDelayed({ startListeningCycle() }, if (quick) 400 else 2000)
    }

    private fun onHeard(guesses: List<String>) {
        if (guesses.isEmpty() || guesses.all { it.isBlank() }) return

        // Jis bhi alternative mein wake-word mila, usi ko "sahi transcription" maan lete hain.
        val matchedGuess = guesses.firstOrNull { g -> WAKE_WORDS.any { g.contains(it) } }
        val text = matchedGuess ?: guesses.first()

        if (!awaitingCommand) {
            if (matchedGuess != null) {
                wakeScreen()
                awaitingCommand = true
                updateNotification("Bolo, Jimi sun raha hai...")
                val greetings = if (SettingsStore.getPersona(this) == "myra") MYRA_GREETINGS else JARVIS_GREETINGS
                speechHelper.speak(greetings.random())
                // If they said the whole thing in one breath ("Jimi, Rahul ko..."),
                // treat whatever comes after the wake word as the command right away.
                val matchedWord = WAKE_WORDS.first { text.contains(it) }
                val afterWakeWord = text.substringAfter(matchedWord).trim(',', ' ', '.')
                if (afterWakeWord.length > 3) {
                    awaitingCommand = false
                    updateNotification("Jimi sun raha hai... 👂")
                    runCommand(afterWakeWord)
                }
            } else {
                // Wake-word match nahi hua — debug ke liye notification mein dikha dete hain
                // ki asal mein kya suna gaya, taaki mis-recognition pattern pakda ja sake.
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
