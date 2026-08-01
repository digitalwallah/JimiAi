package com.jimi.ai

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import android.text.InputType
import android.view.accessibility.AccessibilityManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.jimi.ai.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: ChatAdapter
    private val messages = mutableListOf<ChatMessage>()
    private lateinit var speechHelper: SpeechHelper

    // Launches Android's built-in speech-to-text screen, gets the recognized text back.
    private val speechRecognizerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val spokenText = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
            if (!spokenText.isNullOrBlank()) {
                binding.commandInput.setText(spokenText)
                sendCommand(spokenText)
                binding.commandInput.setText("")
            }
        }
    }

    // Asks for mic permission the first time the user taps the mic button.
    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) launchVoiceInput()
        else Toast.makeText(this, "Mic permission ke bina voice command kaam nahi karega", Toast.LENGTH_SHORT).show()
    }

    // PURANE Android (11 se neeche) pe screen-reading (OCR) feature ke liye ek-baar wali
    // MediaProjection permission. Grant hote hi ScreenCaptureService ko permanently start
    // kar dete hain taaki future mein kabhi dobara na poochhna pade.
    private val screenCapturePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            ScreenCaptureService.start(this, result.resultCode, result.data!!)
            Toast.makeText(this, "Screen-reading enable ho gaya", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Permission nahi mili — screen-reading (images/PDF) is device pe kaam nahi karega", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        speechHelper = SpeechHelper(this)

        // Call-permission bhi ek baar maang lete hain taaki "call karo" command pehli baar me hi kaam kare.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CALL_PHONE), 101)
        }

        adapter = ChatAdapter(messages)
        binding.chatRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.chatRecyclerView.adapter = adapter

        binding.btnMenu.setOnClickListener { showSettingsMenu() }

        binding.btnSend.setOnClickListener {
            val text = binding.commandInput.text.toString().trim()
            if (text.isNotEmpty()) {
                sendCommand(text)
                binding.commandInput.setText("")
            }
        }

        // Mic button: type karne ka mann nahi ho toh bolke command diya ja sakta hai.
        binding.btnMic.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
                launchVoiceInput()
            } else {
                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    /** Sab settings ek hi jagah - Accessibility, API keys, persona, voice character,
     * Always Listening, Auto-send, Check-ins, Premium status. Jaisa Gemini/ChatGPT mein
     * ek "⚙️" menu ke peeche sab options hote hain. */
    private fun showSettingsMenu() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }

        // ---------- PREMIUM STATUS / UPGRADE ----------
        val isPremium = LicenseActivator.isPremiumActive(this)
        val btnPremium = Button(this).apply {
            text = if (isPremium) {
                "⭐ Premium Active — ${LicenseActivator.getDaysRemaining(this@MainActivity)} din baaki"
            } else {
                "🔒 Upgrade to Premium (YouTube unlock karo)"
            }
        }
        btnPremium.setOnClickListener {
            startActivity(Intent(this, PaymentQRActivity::class.java))
        }

        val btnAccessibility = Button(this).apply {
            text = if (isAccessibilityServiceEnabled()) "Accessibility: ON ✅" else "Accessibility Permission On Karo"
        }
        btnAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        val btnUsageAccess = Button(this).apply {
            val monitor = ForegroundAppMonitor(this@MainActivity, {}, {})
            text = if (monitor.hasUsageAccessPermission()) "Usage Access: ON ✅ (mic disturbance rukega)"
            else "⚠️ Usage Access ON Karo (YouTube/call ke time mic disturb na kare)"
        }
        btnUsageAccess.setOnClickListener {
            val monitor = ForegroundAppMonitor(this@MainActivity, {}, {})
            monitor.requestUsageAccessPermission()
        }

        val claudeInput = EditText(this).apply {
            hint = "Gemini API key (aistudio.google.com - FREE)"
            inputType = InputType.TYPE_CLASS_TEXT
            setText(SettingsStore.getClaudeKey(this@MainActivity))
        }
        val youtubeInput = EditText(this).apply {
            hint = "YouTube Data API key (Google Cloud Console)"
            inputType = InputType.TYPE_CLASS_TEXT
            setText(SettingsStore.getYoutubeKey(this@MainActivity))
        }

        val personaLabel = TextView(this).apply {
            text = "Jimi ka style:"
            setPadding(0, 32, 0, 8)
        }
        val personaOptions = listOf("Jarvis (formal)", "MYRA (warm/casual)")
        val personaSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, personaOptions)
            setSelection(if (SettingsStore.getPersona(this@MainActivity) == "myra") 1 else 0)
        }

        val voiceLabel = TextView(this).apply {
            text = "Jimi ki awaaz:"
            setPadding(0, 32, 0, 8)
        }
        val voiceKeys = listOf("arjun", "veer", "ananya", "isha")
        val voiceOptions = listOf(
            "Arjun (21, younger male)",
            "Veer (26, deep male)",
            "Ananya (younger female, soft)",
            "Isha (26, warm female)"
        )
        val voiceSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, voiceOptions)
            setSelection(voiceKeys.indexOf(SettingsStore.getVoiceCharacter(this@MainActivity)).coerceAtLeast(0))
        }

        val alwaysListeningLabel = TextView(this).apply {
            text = "Always Listening ('Jimi' bolke jagao, screen off ho tab bhi):"
            setPadding(0, 32, 0, 4)
        }
        val switchAlwaysListening = Switch(this).apply {
            isChecked = SettingsStore.isAlwaysListeningEnabled(this@MainActivity)
        }

        val autoSendLabel = TextView(this).apply {
            text = "WhatsApp Auto-send (ON = review ke bina seedha bhej dega; OFF = pehle draft dikhayega):"
            setPadding(0, 24, 0, 4)
        }
        val switchAutoSend = Switch(this).apply {
            isChecked = SettingsStore.isAutoSendEnabled(this@MainActivity)
        }

        val checkInLabel = TextView(this).apply {
            text = "Proactive Check-ins (Jimi din mein ek baar khud check-in karega):"
            setPadding(0, 24, 0, 4)
        }
        val switchCheckIn = Switch(this).apply {
            isChecked = SettingsStore.isCheckInEnabled(this@MainActivity)
        }

        layout.addView(btnPremium)
        layout.addView(btnAccessibility)
        layout.addView(btnUsageAccess)
        layout.addView(claudeInput)
        layout.addView(youtubeInput)
        layout.addView(personaLabel)
        layout.addView(personaSpinner)
        layout.addView(voiceLabel)
        layout.addView(voiceSpinner)
        layout.addView(alwaysListeningLabel)
        layout.addView(switchAlwaysListening)
        layout.addView(autoSendLabel)
        layout.addView(switchAutoSend)
        layout.addView(checkInLabel)
        layout.addView(switchCheckIn)

        // Screen-reading (OCR) permission button - sirf purane Android (11 se neeche) pe dikhta hai,
        // kyunki Android 11+ pe ye silently, bina kisi permission ke hi kaam karta hai.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R && !ScreenCaptureService.isReady()) {
            val screenReadLabel = TextView(this).apply {
                text = "Screen-reading (images/PDF ka text padhne ke liye) - ek baar allow karo:"
                setPadding(0, 24, 0, 4)
            }
            val btnEnableScreenRead = Button(this).apply {
                text = "Screen-reading enable karo"
            }
            btnEnableScreenRead.setOnClickListener {
                val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                screenCapturePermissionLauncher.launch(manager.createScreenCaptureIntent())
            }
            layout.addView(screenReadLabel)
            layout.addView(btnEnableScreenRead)
        }

        val scrollView = android.widget.ScrollView(this).apply { addView(layout) }

        AlertDialog.Builder(this)
            .setTitle("Jimi Settings")
            .setView(scrollView)
            .setPositiveButton("Save") { _, _ ->
                SettingsStore.saveKeys(this, claudeInput.text.toString().trim(), youtubeInput.text.toString().trim())
                val chosenPersona = if (personaSpinner.selectedItemPosition == 1) "myra" else "jarvis"
                SettingsStore.setPersona(this, chosenPersona)

                val chosenVoice = voiceKeys[voiceSpinner.selectedItemPosition]
                SettingsStore.setVoiceCharacter(this, chosenVoice)
                speechHelper.applyVoiceCharacter()

                // Always Listening toggle
                if (switchAlwaysListening.isChecked != SettingsStore.isAlwaysListeningEnabled(this)) {
                    if (switchAlwaysListening.isChecked) {
                        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                            == PackageManager.PERMISSION_GRANTED) {
                            enableAlwaysListening()
                        } else {
                            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    } else {
                        SettingsStore.setAlwaysListening(this, false)
                        WakeWordService.stop(this)
                        Toast.makeText(this, "Always-listening band kar diya", Toast.LENGTH_SHORT).show()
                    }
                }

                // Auto-send toggle
                if (switchAutoSend.isChecked != SettingsStore.isAutoSendEnabled(this)) {
                    SettingsStore.setAutoSend(this, switchAutoSend.isChecked)
                    Toast.makeText(
                        this,
                        if (switchAutoSend.isChecked) "Auto-send ON — Jimi ab review ke bina seedha message bhej dega"
                        else "Auto-send OFF — Jimi ab pehle draft dikhayega",
                        Toast.LENGTH_LONG
                    ).show()
                }

                // Check-in toggle
                if (switchCheckIn.isChecked != SettingsStore.isCheckInEnabled(this)) {
                    if (switchCheckIn.isChecked) {
                        SettingsStore.setCheckInEnabled(this, true)
                        CheckInScheduler.scheduleNext(this)
                        Toast.makeText(this, "Jimi din mein ek baar check-in karega 😊", Toast.LENGTH_SHORT).show()
                    } else {
                        SettingsStore.setCheckInEnabled(this, false)
                        CheckInScheduler.cancel(this)
                        Toast.makeText(this, "Proactive check-ins band kar diye", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun enableAlwaysListening() {
        SettingsStore.setAlwaysListening(this, true)
        WakeWordService.start(this)
        Toast.makeText(this, "Ab 'Jimi' bolke kabhi bhi jagao — screen off ho tab bhi 👂", Toast.LENGTH_LONG).show()
        requestBatteryOptimizationExemption()
        requestAutostartPermission()
    }

    /** Without this, Android may kill the background listener after a while to save battery. */
    private fun requestBatteryOptimizationExemption() {
        val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = android.net.Uri.parse("package:$packageName")
                })
            } catch (e: Exception) { /* some OEMs block this intent; ignore */ }
        }
    }

    /** FunTouch OS (vivo/iQOO) aur kai dusre OEMs standard Android battery-optimization
     * ke upar bhi apna khud ka background-kill layer rakhte hain jise koi standard
     * Android API control nahi kar sakta — isko sirf OEM ki khud ki "Autostart" /
     * "Background power consumption" settings screen se manually allow karwana padta hai.
     * Yahan un screens ko seedha kholne ki koshish karte hain; agar koi bhi match na kare
     * to app info screen khol dete hain taaki user khud dhoondh sake. */
    private fun requestAutostartPermission() {
        val knownAutostartIntents = listOf(
            Intent().setComponent(ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")),
            Intent().setComponent(ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.PurviewTabActivity")),
            Intent().setComponent(ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity")),
            Intent().setComponent(ComponentName("com.iqoo.secure", "com.iqoo.secure.MainActivity")),
            Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
            Intent().setComponent(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")),
            Intent().setComponent(ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity")),
            Intent().setComponent(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"))
        )

        var opened = false
        for (intent in knownAutostartIntents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                opened = true
                break
            } catch (e: Exception) {
                // Ye OEM ka screen is device pe nahi hai, agla try karo
            }
        }

        if (opened) {
            Toast.makeText(
                this,
                "Yahan 'Jimi' ko Autostart/background allow kar do — warna screen off hote hi iQOO ise band kar dega",
                Toast.LENGTH_LONG
            ).show()
        } else {
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = android.net.Uri.parse("package:$packageName")
                })
                Toast.makeText(
                    this,
                    "Battery/Autostart settings mein jaake Jimi ke liye background restriction hata do",
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) { /* give up quietly */ }
        }
    }

    private fun launchVoiceInput() {
        try {
            speechRecognizerLauncher.launch(speechHelper.buildRecognizerIntent())
        } catch (e: Exception) {
            Toast.makeText(this, "Voice input available nahi hai is device pe", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        speechHelper.shutdown()
    }

    override fun onResume() {
        super.onResume()
        binding.statusText.text = if (isAccessibilityServiceEnabled())
            "Accessibility service: ON ✅" else "Accessibility service: OFF ❌ (⚙️ menu se on karo)"

        if (SettingsStore.isAlwaysListeningEnabled(this)) {
            WakeWordService.start(this) // idempotent - agar already chal raha hai toh no-op jaisa hi hai
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val am = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(
            android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        )
        return enabledServices.any { it.resolveInfo.serviceInfo.packageName == packageName }
    }

    private fun sendCommand(command: String) {
        adapter.addMessage(ChatMessage("You", command))
        binding.chatRecyclerView.scrollToPosition(messages.size - 1)

        if (SettingsStore.getClaudeKey(this).isBlank()) {
            adapter.addMessage(ChatMessage("Jimi", "Pehle API keys set karo (⚙️ menu se) — mujhe Gemini API key chahiye kaam karne ke liye."))
            return
        }

        lifecycleScope.launch {
            val reply = try {
                withContext(Dispatchers.IO) {
                    val recentHistory = messages.dropLast(1).takeLast(6).joinToString("\n") { "${it.sender}: ${it.text}" }
                    CommandRouter(this@MainActivity).handle(command, recentHistory)
                }
            } catch (e: Exception) {
                "Error aa gaya: ${e.message}"
            }
            adapter.addMessage(ChatMessage("Jimi", reply))
            binding.chatRecyclerView.scrollToPosition(messages.size - 1)
            speechHelper.speak(reply)
        }
    }
}
