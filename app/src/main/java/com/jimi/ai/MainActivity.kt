package com.jimi.ai

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import android.text.InputType
import android.view.accessibility.AccessibilityManager
import android.widget.EditText
import android.widget.LinearLayout
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

        binding.btnEnableAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        binding.btnSettings.setOnClickListener { showApiKeyDialog() }

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

        binding.switchAlwaysListening.setChecked(SettingsStore.isAlwaysListeningEnabled(this))
        binding.switchAlwaysListening.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED) {
                    enableAlwaysListening()
                } else {
                    binding.switchAlwaysListening.isChecked = false
                    micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            } else {
                SettingsStore.setAlwaysListening(this, false)
                WakeWordService.stop(this)
                Toast.makeText(this, "Always-listening band kar diya", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun enableAlwaysListening() {
        SettingsStore.setAlwaysListening(this, true)
        WakeWordService.start(this)
        Toast.makeText(this, "Ab 'Jimi' bolke kabhi bhi jagao — screen off ho tab bhi 👂", Toast.LENGTH_LONG).show()
        requestBatteryOptimizationExemption()
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
            "Accessibility service: ON ✅" else "Accessibility service: OFF ❌ (button 1 dabao)"

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
            adapter.addMessage(ChatMessage("Jimi", "Pehle API keys set karo (button 2) — mujhe Gemini API key chahiye kaam karne ke liye."))
            return
        }

        lifecycleScope.launch {
            val reply = try {
                withContext(Dispatchers.IO) { CommandRouter(this@MainActivity).handle(command) }
            } catch (e: Exception) {
                "Error aa gaya: ${e.message}"
            }
            adapter.addMessage(ChatMessage("Jimi", reply))
            binding.chatRecyclerView.scrollToPosition(messages.size - 1)
            speechHelper.speak(reply)
        }
    }

    private fun showApiKeyDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
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
        layout.addView(claudeInput)
        layout.addView(youtubeInput)

        AlertDialog.Builder(this)
            .setTitle("Jimi Settings")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                SettingsStore.saveKeys(this, claudeInput.text.toString().trim(), youtubeInput.text.toString().trim())
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
