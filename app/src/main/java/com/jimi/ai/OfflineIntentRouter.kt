package com.jimi.ai

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.Settings

/** Deterministic, fully offline command handling ("Offline Simple Voice & Controls" spec).
 * Ye Gemini/internet ke bina turant execute hote hain, aur CommandRouter.handle() mein Gemini
 * call se PEHLE try kiye jaate hain — isse jab internet na ho (ya sirf speed ke liye bhi) in
 * basic commands ka turant jawab milta hai. */
object OfflineIntentRouter {

    fun isInternetAvailable(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return false
            val capabilities = cm.getNetworkCapabilities(network) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) {
            false
        }
    }

    /** Agar command yahin handle ho gaya toh reply string return karta hai, warna null —
     * null milne par CommandRouter aage Gemini classification try karta hai. */
    suspend fun tryHandle(context: Context, command: String): String? {
        val c = command.trim().lowercase()

        voiceSettingsMatch(context, c)?.let { return it }

        if (containsAny(c, listOf("repeat", "phir se bolo", "dobara bolo", "wahi bolo", "phir bolo", "phirse bolo")) &&
            !containsAny(c, listOf("kya poocha", "kya pucha", "what did i ask"))
        ) {
            return repeatLastResponse(context)
        }
        if (containsAny(c, listOf("what did i ask", "kya poocha tha", "kya pucha tha", "pichle command", "recent command", "pichli baatein"))) {
            return recentCommandsSummary(context)
        }

        if (containsAny(c, listOf("flashlight on", "torch on", "flashlight chalu", "torch chalu"))) {
            val ok = SystemControlHelper.setFlashlight(context, true)
            return if (ok) "Flashlight on kar diya ✅" else "Flashlight control nahi ho paaya."
        }
        if (containsAny(c, listOf("flashlight off", "torch off", "flashlight band", "torch band"))) {
            val ok = SystemControlHelper.setFlashlight(context, false)
            return if (ok) "Flashlight off kar diya ✅" else "Flashlight control nahi ho paaya."
        }

        if (containsAny(c, listOf("volume badhao", "volume badha do", "awaaz badhao", "volume up", "volume tez")) &&
            !c.any { it.isDigit() }
        ) {
            val ok = SystemControlHelper.adjustVolume(context, "up")
            return if (ok) "Volume badha diya 🔊" else "Volume control nahi ho paaya."
        }
        if (containsAny(c, listOf("volume kam", "awaaz kam", "volume down", "volume dheema")) &&
            !c.any { it.isDigit() }
        ) {
            val ok = SystemControlHelper.adjustVolume(context, "down")
            return if (ok) "Volume kam kar diya 🔉" else "Volume control nahi ho paaya."
        }
        if (containsAny(c, listOf("mute karo", "mute kar do", "chup karo"))) {
            val ok = SystemControlHelper.adjustVolume(context, "mute")
            return if (ok) "Mute kar diya 🔇" else "Volume control nahi ho paaya."
        }

        if (containsAny(c, listOf("gaana pause", "music pause", "video pause", "pause karo", "pause kardo"))) {
            val ok = SystemControlHelper.controlMedia(context, "pause")
            return if (ok) "Pause kar diya ⏸️" else "Media control nahi ho paaya — koi player active nahi hai shayad."
        }
        if (containsAny(c, listOf("gaana chalao", "music play", "video play", "play karo", "resume karo"))) {
            val ok = SystemControlHelper.controlMedia(context, "play")
            return if (ok) "Play kar diya ▶️" else "Media control nahi ho paaya — koi player active nahi hai shayad."
        }

        if (containsAny(c, listOf("wifi settings", "wifi kholo", "wireless settings", "wi-fi settings", "wi fi settings"))) {
            return try {
                val intent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                "Wi-Fi settings khol diya 📶"
            } catch (e: Exception) {
                "Wi-Fi settings nahi khul paaya."
            }
        }

        tryCalculator(c)?.let { return it }

        return null
    }

    private fun containsAny(text: String, needles: List<String>): Boolean = needles.any { text.contains(it) }

    // ---------- Voice speed / pitch / language ----------

    private fun voiceSettingsMatch(context: Context, c: String): String? {
        val exactSpeed = Regex("""speed\D{0,10}?(\d(\.\d+)?)""").find(c)
            ?: Regex("""(\d(\.\d+)?)\D{0,10}?speed""").find(c)
        if (exactSpeed != null) {
            val value = exactSpeed.groupValues[1].toFloatOrNull()
            if (value != null) {
                SettingsStore.setSpeechRateMultiplier(context, value)
                return "Voice speed ${value}x kar diya 🎙️"
            }
        }

        if (containsAny(c, listOf("speak faster", "tez bolo", "jaldi bolo", "speed badhao", "fast bolo"))) {
            val current = SettingsStore.getSpeechRateMultiplier(context)
            SettingsStore.setSpeechRateMultiplier(context, (current + 0.15f).coerceAtMost(2.0f))
            return "Thoda tez bol raha hoon ab 🎙️"
        }
        if (containsAny(c, listOf("speak slowly", "dheere bolo", "slow bolo", "speed kam karo", "aaram se bolo"))) {
            val current = SettingsStore.getSpeechRateMultiplier(context)
            SettingsStore.setSpeechRateMultiplier(context, (current - 0.15f).coerceAtLeast(0.5f))
            return "Thoda dheere bol raha hoon ab 🎙️"
        }
        if (containsAny(c, listOf("normal speed", "speed reset", "default speed"))) {
            SettingsStore.setSpeechRateMultiplier(context, 1.0f)
            return "Voice speed normal kar diya 🎙️"
        }

        if (containsAny(c, listOf("change your pitch", "pitch badlo", "pitch change", "awaaz badlo"))) {
            val current = SettingsStore.getPitchOverride(context)
            val base = if (current > 0f) current else 1.0f
            val newPitch = if (base >= 1.3f) 0.8f else base + 0.2f
            SettingsStore.setPitchOverride(context, newPitch)
            return "Pitch change kar diya 🎙️"
        }

        if (containsAny(c, listOf("speak in hindi", "hindi me bolo", "hindi mein bolo", "use hindi"))) {
            SettingsStore.setLanguageOverride(context, "hi-IN")
            return "Theek hai, ab Hindi mein baat karunga"
        }
        if (containsAny(c, listOf("speak in english", "english me bolo", "english mein bolo", "use english"))) {
            SettingsStore.setLanguageOverride(context, "en-IN")
            return "Okay, I'll speak in English now"
        }

        return null
    }

    // ---------- Repeat / recent commands (Room ke existing ConversationMemory se, koi naya table nahi) ----------

    private suspend fun repeatLastResponse(context: Context): String {
        LastReplyStore.lastReply?.let { return it }
        return try {
            val db = JimiDatabase.getInstance(context)
            val last = db.conversationMemoryDao().getRecent(1).firstOrNull()
                ?: return "Abhi tak koi baat nahi hui hai jo repeat kar sakoon."
            val reply = last.summary.substringAfter("Jimi: ", missingDelimiterValue = last.summary)
            reply.ifBlank { "Kuch yaad nahi mila jo repeat kar sakoon." }
        } catch (e: Exception) {
            "Kuch yaad nahi mila jo repeat kar sakoon."
        }
    }

    private suspend fun recentCommandsSummary(context: Context): String {
        return try {
            val db = JimiDatabase.getInstance(context)
            val recent = db.conversationMemoryDao().getRecent(5)
            if (recent.isEmpty()) return "Abhi tak koi command history nahi hai."
            recent.reversed().joinToString("\n") { entry ->
                val userPart = entry.summary.substringAfter("User: ").substringBefore(" | Jimi:")
                "• $userPart"
            }
        } catch (e: Exception) {
            "Recent commands nahi nikal paaya."
        }
    }

    // ---------- Simple calculator ----------

    private fun tryCalculator(c: String): String? {
        val pattern = Regex("""(-?\d+(\.\d+)?)\s*(\+|-|\*|x|×|/|plus|minus|jod|ghata|guna|multiply|times|divide|bhaag)\s*(-?\d+(\.\d+)?)""")
        val match = pattern.find(c) ?: return null

        val a = match.groupValues[1].toDoubleOrNull() ?: return null
        val opRaw = match.groupValues[3]
        val b = match.groupValues[4].toDoubleOrNull() ?: return null

        val (result, symbol) = when (opRaw) {
            "+", "plus", "jod" -> (a + b) to "+"
            "-", "minus", "ghata" -> (a - b) to "-"
            "*", "x", "×", "guna", "multiply", "times" -> (a * b) to "×"
            "/", "divide", "bhaag" -> {
                if (b == 0.0) return "Zero se divide nahi ho sakta."
                (a / b) to "÷"
            }
            else -> return null
        }

        val formatted = if (result == result.toLong().toDouble()) result.toLong().toString() else result.toString()
        return "${formatDouble(a)} $symbol ${formatDouble(b)} = $formatted"
    }

    private fun formatDouble(d: Double): String =
        if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()
}
