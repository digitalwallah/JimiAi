package com.jimi.ai

import android.content.Context
import kotlinx.coroutines.delay

object QuickCommandParser {
    private val brightnessRegex = Regex("""(brightness|chamak)\D{0,12}?(\d{1,3})""", RegexOption.IGNORE_CASE)
    private val volumeRegex = Regex("""(volume|awaaz|aawaz)\D{0,12}?(\d{1,3})""", RegexOption.IGNORE_CASE)

    fun tryBrightness(command: String): Int? =
        brightnessRegex.find(command)?.groupValues?.get(2)?.toIntOrNull()?.coerceIn(0, 100)

    fun tryVolume(command: String): Int? =
        volumeRegex.find(command)?.groupValues?.get(2)?.toIntOrNull()?.coerceIn(0, 100)
}

private fun normalizeDirection(raw: String): String {
    val d = raw.trim().lowercase()
    return when {
        d.isBlank() -> d
        d in listOf("up", "increase", "increased", "raise", "raised", "badhao", "badhado", "badha do", "badha", "zyada", "zyada karo", "tez", "high", "louder", "loud", "more") -> "up"
        d in listOf("down", "decrease", "decreased", "lower", "lowered", "kam", "kam karo", "km", "halka", "halka karo", "dheem", "quiet", "quieter", "low", "less") -> "down"
        d in listOf("set", "exact", "specific", "particular") -> "set"
        d in listOf("mute", "silent", "band", "band karo", "off") -> "mute"
        d in listOf("unmute", "on", "chalu", "chalu karo") -> "unmute"
        d in listOf("max", "full", "maximum", "poora", "pura", "full karo") -> "max"
        else -> d
    }
}

private fun normalizeLockState(raw: String): String {
    val s = raw.trim().lowercase()
    return when {
        s in listOf("on", "lock", "locked", "lock karo", "band", "band karo") -> "on"
        s in listOf("off", "unlock", "unlocked", "unlock karo", "chalu", "auto", "auto rotate", "auto-rotate") -> "off"
        else -> s
    }
}

private fun normalizeMediaCommand(raw: String): String {
    val c = raw.trim().lowercase()
    return when {
        c in listOf("play", "resume", "chalao", "chalu karo", "start") -> "play"
        c in listOf("pause", "ruko", "rok do", "rok") -> "pause"
        c in listOf("play_pause", "toggle", "play/pause") -> "play_pause"
        c in listOf("next", "skip", "aage") -> "next"
        c in listOf("previous", "prev", "peeche", "back") -> "previous"
        c in listOf("stop", "band karo", "band") -> "stop"
        else -> c
    }
}

class CommandRouter(private val context: Context) {

    private val claude = ClaudeApiClient(SettingsStore.getClaudeKey(context))
    private val youtube = YouTubeHelper(SettingsStore.getYoutubeKey(context))

    suspend fun handle(command: String, recentHistory: String = ""): String {
        QuickCommandParser.tryBrightness(command)?.let { pct ->
            val success = SystemControlHelper.adjustBrightness(context, percent = pct)
            return if (success) "Brightness $pct% kar diya ☀️"
            else "Brightness control ke liye permission chahiye — Settings me Jimi ko allow karo."
        }
        QuickCommandParser.tryVolume(command)?.let { pct ->
            val success = SystemControlHelper.setVolumePercent(context, pct)
            return if (success) "Volume $pct% kar diya 🔊" else "Volume control nahi ho paaya."
        }

        val db = JimiDatabase.getInstance(context)
        val savedFacts = db.userFactDao().getAll()
        val savedMemoriesText = if (savedFacts.isNotEmpty()) {
            savedFacts.joinToString(" | ") { "${it.key}: ${it.value}" }
        } else ""

        val decision = claude.routeCommand(command, recentHistory, savedMemoriesText)
        return when (decision.optString("action")) {

            "whatsapp_send" -> handleWhatsApp(decision, command)

            "youtube_play" -> {
                if (LicenseActivator.isPremiumActive(context)) {
                    handleYouTube(decision)
                } else {
                    val intent = android.content.Intent(context, PaymentQRActivity::class.java).apply {
                        flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                    "YouTube play ek premium feature hai — payment QR khol diya hai, complete karke phir try karna 🔒"
                }
            }

            "make_call" -> handleCall(decision)

            "tap_screen" -> handleTapScreen(decision)

            "toggle_flashlight" -> {
                val state = decision.optString("state").lowercase()
                val turnOn = state != "off"
                val success = SystemControlHelper.setFlashlight(context, turnOn)
                if (success) "Flashlight ${if (turnOn) "on" else "off"} kar diya ✅"
                else "Flashlight control nahi ho paaya — camera permission check karo."
            }

            "open_app" -> {
                val appName = decision.optString("app_name")
                val opened = AppLauncher.openAppByName(context, appName)
                if (opened) "$appName khol diya ✅" else "'$appName' naam ka app nahi mila 😕"
            }

            "save_memory" -> handleSaveMemory(decision, command, savedMemoriesText)

            "volume_control" -> handleVolumeControl(decision)

            "brightness_control" -> handleBrightnessControl(decision)

            "rotation_lock" -> handleRotationLock(decision)

            "media_control" -> handleMediaControl(decision)

            "set_alarm" -> handleSetAlarm(decision)

            "set_timer" -> handleSetTimer(decision)

            else -> handleGeneralChat(command, savedMemoriesText)
        }
    }

    private suspend fun handleSaveMemory(decision: org.json.JSONObject, originalCommand: String, savedMemoriesText: String): String {
        val key = decision.optString("key")
        val value = decision.optString("value")
        if (key.isBlank() || value.isBlank()) return "Kya yaad rakhna hai, thoda clear batao?"

        val db = JimiDatabase.getInstance(context)
        val existing = db.userFactDao().findByKey(key)

        if (existing != null && existing.value.equals(value, ignoreCase = true)) {
            return handleGeneralChat(originalCommand, savedMemoriesText)
        }

        db.userFactDao().insert(UserFact(key = key, value = value))
        return "Yaad rakh liya: $key - $value ✅"
    }

    private suspend fun handleGeneralChat(command: String, savedMemoriesText: String): String {
        val db = JimiDatabase.getInstance(context)
        val pastMemories = db.conversationMemoryDao().getRecent(5)
        val memoryContext = if (pastMemories.isNotEmpty()) {
            "Pichli kuch baaton ka yaad: " + pastMemories.reversed().joinToString(" | ") { it.summary }
        } else ""
        val factsContext = if (savedMemoriesText.isNotBlank()) {
            "User ke baare mein yaad rakhi hui important baatein: $savedMemoriesText"
        } else ""

        val reply = claude.ask(
            personaPrompt() + " " + factsContext + " " + memoryContext + " " +
            "Tum Jimi ho, ek Android app jo user ke phone pe already install hai. Tumhare paas yeh " +
            "features PEHLE SE BANE HUE HAIN (yeh sab already kaam karte hain, koi limitation nahi hai): " +
            "1) WhatsApp pe kisi contact ko unke style me message bhej sakte ho. " +
            "2) YouTube pe kisi channel/topic ka video dhoondh kar play kar sakte ho. " +
            "3) Phone ki kisi bhi installed app ko naam bol kar khol sakte ho. " +
            "4) Contact ko call laga sakte ho. " +
            "5) Screen pe dikh rahe kisi bhi button ko tap kar sakte ho. " +
            "6) Flashlight on/off kar sakte ho. " +
            "7) Volume badha/kam kar sakte ho ya exact percent pe set kar sakte ho. " +
            "8) Screen brightness badha/kam kar sakte ho ya exact percent pe set kar sakte ho. " +
            "9) Screen rotation lock on/off kar sakte ho. " +
            "10) Music/video ko play/pause/next/previous kar sakte ho, chahe koi bhi player chal raha ho. " +
            "11) Alarm set kar sakte ho bole gaye time pe. " +
            "12) Timer set kar sakte ho boli gayi duration ke liye. " +
            "13) 'Always Listening' feature (jo app me Switch 3 se ON/OFF hota hai) - yeh ON hone par " +
            "tum bina button dabaye, 'Hey Jimi' bolke bhi activate ho sakte ho, aur yeh SCREEN OFF hone " +
            "par bhi kaam karta hai (background me chalta rehta hai). Agar user poochhe ki 'screen off me " +
            "kaam karoge' ya 'bina button dabaye sunoge', toh HAAN bolo aur bata do ki Switch 3 'Always " +
            "Listening' ON karna hai (agar pehle se ON nahi hai). Kabhi mat bolo ki yeh feature nahi hai " +
            "ya tumhe 'active rehna padta hai' - yeh sab already automatic hai jab switch ON ho. " +
            "Agar user ke baare mein upar koi yaad rakhi hui jaankari di gayi hai aur user usi ke baare " +
            "mein sawaal poochhe, to seedha us jaankari se natural jawab do - 'yaad rakh liya' mat bolo, " +
            "kyunki wo pehle se hi yaad hai, sirf uska jawab do. " +
            "Chhota, natural Hinglish reply do. Agar user kisi aisi cheez ke liye bole jo upar list me " +
            "nahi hai (jaise koi bilkul naya device action), tabhi saaf bata do ki abhi available nahi hai.",
            command
        )

        try {
            db.conversationMemoryDao().insert(
                ConversationMemory(summary = "User: $command | Jimi: ${reply.take(100)}")
            )
            db.conversationMemoryDao().trimOldEntries()
        } catch (e: Exception) { }

        return reply
    }

    private fun personaPrompt(): String {
        val moodNote = "Agar user ke message mein koi emotional cue ho (jaise 'tired hoon', 'bura din tha', " +
            "'khush hoon', 'stress ho raha hai'), toh seedha kaam pe mat kudo - pehle ek chhoti si " +
            "acknowledgment line do (over-the-top nahi, natural), phir agar koi command bhi ho usse execute karo."
        val languageNote = "IMPORTANT: User jis language mein message likhe/bole (pure English, pure Hindi, " +
            "ya Hinglish), tum bilkul usi language/style mein reply do. Agar user pure English mein likhe, " +
            "tum bhi pure English mein jawab do - Hindi words mat mix karo. Agar Hinglish likhe, tum bhi " +
            "Hinglish mein raho. Kabhi khud se apni marzi se language switch mat karo."
        return when (SettingsStore.getPersona(context)) {
            "myra" -> "Tum ek warm, caring, thodi playful dost jaisi personality ho — natural tone use karo, " +
                "reply thoda affectionate aur friendly rakho, lekin over-the-top mat karo. $moodNote $languageNote"
            else -> "Tum ek formal, professional, to-the-point assistant ho — reply concise aur seedha rakho. $moodNote $languageNote"
        }
    }

    private suspend fun handleWhatsApp(decision: org.json.JSONObject, originalCommand: String): String {
        val contactName = decision.optString("contact")
        if (contactName.isBlank()) return "Kis contact ko message karna hai, naam batao?"

        val phone = ContactResolver.findPhoneNumberByName(context, contactName)
            ?: return "'$contactName' contacts me nahi mila 😕"

        val db = JimiDatabase.getInstance(context)
        val savedStyle = db.contactStyleDao().findByName(contactName)
        val styleNote = savedStyle?.styleNote ?: "Hinglish, casual, jaisa dost log WhatsApp pe likhte hain"

        val intentText = decision.optString("message").ifBlank { originalCommand }
        val draftedMessage = if (decision.optString("message").isNotBlank()) {
            intentText.trim()
        } else {
            claude.generateStyledReply(contactName, styleNote, intentText)
        }

        return if (SettingsStore.isAutoSendEnabled(context)) {
            val sent = WhatsAppAutomator.sendMessage(context, phone, draftedMessage)
            if (sent) "$contactName ko bhej diya:\n\"$draftedMessage\" ✅"
            else "$contactName ka WhatsApp khola, lekin Send button auto-tap nahi ho paaya — khud tap kar do."
        } else {
            WhatsAppAutomator.openChatWithPrefilledText(context, phone, draftedMessage)
            "$contactName ke liye draft ready hai aur WhatsApp me khul gaya hai:\n\"$draftedMessage\"\nCheck karke khud Send dabao (Auto-send Settings me on kar sakte ho)."
        }
    }

    private fun handleYouTube(decision: org.json.JSONObject): String {
        val channel = decision.optString("channel").ifBlank { null }
        val query = decision.optString("query")
        val result = youtube.findVideo(query, channel)
            ?: return "Video nahi mila, thoda aur specific bata sakte ho?"
        youtube.playVideo(context, result.videoId)
        return "Playing: ${result.title} ▶️"
    }

    private suspend fun handleTapScreen(decision: org.json.JSONObject): String {
        val targetText = decision.optString("target_text")
        if (targetText.isBlank()) return "Kaunsa button dabana hai, thoda clear batao?"

        val service = JimiAccessibilityService.instance
            ?: return "Accessibility permission on nahi hai, pehle wo enable karo."

        val maxAttempts = 5
        repeat(maxAttempts) { attempt ->
            val node = service.findNodeByText(targetText)
            if (node != null) {
                val success = service.clickNode(node)
                return if (success) "'$targetText' dabaya ✅" else "'$targetText' mila lekin tap nahi ho paaya."
            }
            if (attempt < maxAttempts - 1) delay(500)
        }
        return "'$targetText' mujhe abhi screen pe nahi mil raha."
    }

    private fun handleCall(decision: org.json.JSONObject): String {
        val contactName = decision.optString("contact")
        if (contactName.isBlank()) return "Kise call karna hai, naam batao?"

        val phone = ContactResolver.findPhoneNumberByName(context, contactName)
            ?: return "'$contactName' contacts me nahi mila 😕"

        val hasCallPermission = androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.CALL_PHONE
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        return if (hasCallPermission) {
            val intent = android.content.Intent(android.content.Intent.ACTION_CALL,
                android.net.Uri.parse("tel:$phone")).apply {
                flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            "$contactName ko call laga raha hoon 📞"
        } else {
            val intent = android.content.Intent(android.content.Intent.ACTION_DIAL,
                android.net.Uri.parse("tel:$phone")).apply {
                flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            "$contactName ka number dialer me khol diya. Auto-call ke liye app ko Call permission do (Settings > Apps > Jimi > Permissions)."
        }
    }

    private fun handleVolumeControl(decision: org.json.JSONObject): String {
        val direction = normalizeDirection(decision.optString("direction"))
        val percent = decision.optInt("percent", -1)

        val success = if (direction == "set" && percent in 0..100) {
            SystemControlHelper.setVolumePercent(context, percent)
        } else {
            SystemControlHelper.adjustVolume(context, direction)
        }

        return if (success) {
            when (direction) {
                "set" -> "Volume $percent% kar diya 🔊"
                "up" -> "Volume badha diya 🔊"
                "down" -> "Volume kam kar diya 🔉"
                "mute" -> "Mute kar diya 🔇"
                "unmute" -> "Unmute kar diya 🔊"
                "max" -> "Volume full kar diya 🔊"
                else -> "Volume adjust kar diya"
            }
        } else "Volume control nahi ho paaya."
    }

    private fun handleBrightnessControl(decision: org.json.JSONObject): String {
        if (!SystemControlHelper.canWriteSettings(context)) {
            SystemControlHelper.requestWriteSettingsPermission(context)
            return "Brightness control ke liye ek special permission chahiye — jo screen khuli hai usme Jimi ko allow kar do, phir dobara try karna."
        }

        val direction = normalizeDirection(decision.optString("direction"))
        val percent = decision.optInt("percent", -1)

        val success = if (direction == "set" && percent in 0..100) {
            SystemControlHelper.adjustBrightness(context, percent = percent)
        } else {
            SystemControlHelper.adjustBrightness(context, direction = direction)
        }

        return if (success) {
            when (direction) {
                "set" -> "Brightness $percent% kar diya ☀️"
                "up" -> "Brightness badha diya ☀️"
                "down" -> "Brightness kam kar diya 🌙"
                else -> "Brightness adjust kar diya"
            }
        } else "Brightness control nahi ho paaya."
    }

    private fun handleRotationLock(decision: org.json.JSONObject): String {
        if (!SystemControlHelper.canWriteSettings(context)) {
            SystemControlHelper.requestWriteSettingsPermission(context)
            return "Rotation control ke liye ek special permission chahiye — jo screen khuli hai usme Jimi ko allow kar do, phir dobara try karna."
        }

        val state = normalizeLockState(decision.optString("state"))
        val locked = state == "on"
        val success = SystemControlHelper.setRotationLock(context, locked)

        return if (success) {
            if (locked) "Screen rotation lock kar diya 🔒" else "Auto-rotate on kar diya 🔄"
        } else "Rotation control nahi ho paaya."
    }

    private fun handleMediaControl(decision: org.json.JSONObject): String {
        val command = normalizeMediaCommand(decision.optString("command"))
        val success = SystemControlHelper.controlMedia(context, command)

        return if (success) {
            when (command) {
                "play" -> "Play kar diya ▶️"
                "pause" -> "Pause kar diya ⏸️"
                "play_pause", "toggle" -> "Play/Pause kar diya"
                "next" -> "Next track ⏭️"
                "previous" -> "Previous track ⏮️"
                "stop" -> "Stop kar diya ⏹️"
                else -> "Media control kar diya"
            }
        } else "Media control nahi ho paaya — koi player active nahi hai shayad."
    }

    private suspend fun handleSetAlarm(decision: org.json.JSONObject): String {
        val hour = decision.optInt("hour", -1)
        val minute = decision.optInt("minute", 0)
        if (hour !in 0..23) return "Kitne baje alarm lagana hai, thoda clear batao?"

        val label = decision.optString("label").ifBlank { "Jimi Alarm" }
        val success = SystemControlHelper.setAlarm(context, hour, minute, label)
        val timeStr = String.format("%02d:%02d", hour, minute)

        if (!success) {
            val errorDetail = SystemControlHelper.lastAlarmError
            return if (errorDetail != null) "Alarm set nahi ho paaya — error: $errorDetail"
            else "Alarm set nahi ho paaya."
        }

        val service = JimiAccessibilityService.instance
        if (service != null) {
            val confirmLabels = listOf("Save", "Done", "OK", "Ok", "सहेजें", "ठीक है", "Set", "Confirm", "Add", "Tick")
            var tapped = false
            val maxAttempts = 5
            repeat(maxAttempts) { attempt ->
                if (!tapped) {
                    for (label2 in confirmLabels) {
                        val node = service.findNodeByText(label2)
                        if (node != null) {
                            service.clickNode(node)
                            tapped = true
                            break
                        }
                    }
                }
                if (!tapped && attempt < maxAttempts - 1) delay(500)
            }
        }

        return "$timeStr ka alarm laga diya ⏰"
    }

    private fun handleSetTimer(decision: org.json.JSONObject): String {
        val seconds = decision.optInt("seconds", -1)
        if (seconds <= 0) return "Kitni der ka timer lagana hai, thoda clear batao?"

        val label = decision.optString("label").ifBlank { "Jimi Timer" }
        val success = SystemControlHelper.setTimer(context, seconds, label)

        val readable = when {
            seconds >= 3600 -> "${seconds / 3600} ghante"
            seconds >= 60 -> "${seconds / 60} minute"
            else -> "$seconds second"
        }
        return if (success) "$readable ka timer laga diya ⏱️" else "Timer set nahi ho paaya."
    }
}
