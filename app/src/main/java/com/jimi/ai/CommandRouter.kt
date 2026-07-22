package com.jimi.ai

import android.content.Context

/**
 * The glue: takes a raw Hinglish/Hindi/English command from the user,
 * asks Claude to classify it, then dispatches to the right module.
 */
class CommandRouter(private val context: Context) {

    private val claude = ClaudeApiClient(SettingsStore.getClaudeKey(context))
    private val youtube = YouTubeHelper(SettingsStore.getYoutubeKey(context))

    /** Returns a human-readable status string to show in the chat UI. */
    suspend fun handle(command: String, recentHistory: String = ""): String {
        val db = JimiDatabase.getInstance(context)
        val savedFacts = db.userFactDao().getAll()
        val savedMemoriesText = if (savedFacts.isNotEmpty()) {
            savedFacts.joinToString(" | ") { "${it.key}: ${it.value}" }
        } else ""

        val decision = claude.routeCommand(command, recentHistory, savedMemoriesText)
        return when (decision.optString("action")) {

            "whatsapp_send" -> handleWhatsApp(decision, command)

            "youtube_play" -> handleYouTube(decision)

            "make_call" -> handleCall(decision)

            "tap_screen" -> handleTapScreen(decision)

            "open_app" -> {
                val appName = decision.optString("app_name")
                val opened = AppLauncher.openAppByName(context, appName)
                if (opened) "$appName khol diya ✅" else "'$appName' naam ka app nahi mila 😕"
            }

            "save_memory" -> handleSaveMemory(decision, command, savedMemoriesText)

            else -> handleGeneralChat(command, savedMemoriesText)
        }
    }

    /** Jab Claude decide kare ki koi important fact yaad rakhna hai. Agar yahi fact same value
     * ke saath pehle se saved hai, iska matlab ye asal mein ek QUESTION tha (jaise "kahan rehta hai"),
     * naya fact nahi - is case mein hum "yaad rakh liya" spam nahi karte, balki jaankari se seedha
     * natural jawab dete hain (general chat route se). */
    private suspend fun handleSaveMemory(decision: org.json.JSONObject, originalCommand: String, savedMemoriesText: String): String {
        val key = decision.optString("key")
        val value = decision.optString("value")
        if (key.isBlank() || value.isBlank()) return "Kya yaad rakhna hai, thoda clear batao?"

        val db = JimiDatabase.getInstance(context)
        val existing = db.userFactDao().findByKey(key)

        if (existing != null && existing.value.equals(value, ignoreCase = true)) {
            // Fact already saved hai same value ke saath - ye ek query tha, naya info nahi.
            // "Yaad rakh liya" dobara bolne ke bajaye, saved fact use karke natural jawab do.
            return handleGeneralChat(originalCommand, savedMemoriesText)
        }

        db.userFactDao().insert(UserFact(key = key, value = value))
        return "Yaad rakh liya: $key - $value ✅"
    }

    /** General chat: purani conversations ka summary yaad karke reply deta hai,
     * aur naye reply ka summary future ke liye save kar deta hai. */
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
            "6) 'Always Listening' feature (jo app me Switch 3 se ON/OFF hota hai) - yeh ON hone par " +
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

        // Is exchange ka chhota summary save karo, future context ke liye.
        try {
            db.conversationMemoryDao().insert(
                ConversationMemory(summary = "User: $command | Jimi: ${reply.take(100)}")
            )
            db.conversationMemoryDao().trimOldEntries()
        } catch (e: Exception) { /* memory save fail ho jaaye toh bhi reply block nahi hona chahiye */ }

        return reply
    }

  /** Persona ke hisaab se tone instruction. Jarvis = formal/concise, MYRA = warm/casual companion feel.
     * Dono mein mood-aware acknowledgment bhi add hai - agar user ke message mein emotional cue ho
     * (tired, bura din, khush, stressed, etc.), toh command execute karne se pehle usko thoda
     * acknowledge kare, phir kaam kare. */
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
            // User ne pehle hi bata diya ki kya likhna hai - iske words ke saath chedkhaani nahi,
            // seedha wahi bhejo (bas thoda clean-up, naya content generate mat karo).
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

    private fun handleTapScreen(decision: org.json.JSONObject): String {
        val targetText = decision.optString("target_text")
        if (targetText.isBlank()) return "Kaunsa button dabana hai, thoda clear batao?"

        val service = JimiAccessibilityService.instance
            ?: return "Accessibility permission on nahi hai, pehle wo enable karo."

        val node = service.findNodeByText(targetText)
            ?: return "'$targetText' mujhe abhi screen pe nahi mil raha."

        val success = service.clickNode(node)
        return if (success) "'$targetText' dabaya ✅" else "'$targetText' mila lekin tap nahi ho paaya."
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
            // Permission nahi hai toh fallback - dialer khol do, user khud tap kare.
            val intent = android.content.Intent(android.content.Intent.ACTION_DIAL,
                android.net.Uri.parse("tel:$phone")).apply {
                flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            "$contactName ka number dialer me khol diya. Auto-call ke liye app ko Call permission do (Settings > Apps > Jimi > Permissions)."
        }
    }
}
