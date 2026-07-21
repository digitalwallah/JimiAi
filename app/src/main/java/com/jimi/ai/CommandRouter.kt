package com.jimi.ai

import android.content.Context
import org.json.JSONObject

/**
 * The glue: takes a raw Hinglish/Hindi/English command from the user,
 * asks Claude/Gemini to classify it, then dispatches to the right module.
 */
class CommandRouter(private val context: Context) {

    private val claude = ClaudeApiClient(SettingsStore.getClaudeKey(context))
    private val youtube = YouTubeHelper(SettingsStore.getYoutubeKey(context))

    /** Returns a human-readable status string to show in the chat UI. */
    suspend fun handle(command: String, recentHistory: String = ""): String {
        val db = JimiDatabase.getInstance(context)
        
        // 1. Saved memories fetch karo taaki AI ko context mile
        val savedMemoriesList = db.memoryDao().getAllMemories() // Agar aapka DAO/method name alag hai toh use adjust kar lein
        val savedMemoriesText = savedMemoriesList.joinToString("\n") { "${it.key}: ${it.value}" }

        // 2. routeCommand me userCommand, recentHistory aur savedMemories pass karo
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

            // 3. Pehle missing tha: Memory save karne wala action
            "save_memory" -> handleSaveMemory(decision)

            else -> claude.ask(
                "Tum Jimi ho, ek friendly Hinglish-bolne wala personal assistant. Chhota, natural reply do. " +
                "Zaroori: agar user kisi aisi cheez ke liye bole jo tum actually kar nahi sakte (jaise koi " +
                "device action jiske liye koi tool available nahi hai), toh kabhi mat bolo ki 'kar diya' ya " +
                "'kar raha hoon' — saaf bata do ki abhi yeh feature available nahi hai.",
                command
            )
        }
    }

    private suspend fun handleSaveMemory(decision: JSONObject): String {
        val key = decision.optString("key")
        val value = decision.optString("value")

        if (key.isBlank() || value.isBlank()) {
            return "Kya yaad rakhna hai, thoda clear batao?"
        }

        val db = JimiDatabase.getInstance(context)
        // MemoryEntity aapke project ke model class ke hisab se
        db.memoryDao().insertOrUpdate(key, value)

        return "Yaad rakh liya: $key = $value ✅"
    }

    private suspend fun handleWhatsApp(decision: JSONObject, originalCommand: String): String {
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

    private fun handleYouTube(decision: JSONObject): String {
        val channel = decision.optString("channel").ifBlank { null }
        val query = decision.optString("query")
        val result = youtube.findVideo(query, channel)
            ?: return "Video nahi mila, thoda aur specific bata sakte ho?"
        youtube.playVideo(context, result.videoId)
        return "Playing: ${result.title} ▶️"
    }

    private fun handleTapScreen(decision: JSONObject): String {
        val targetText = decision.optString("target_text")
        if (targetText.isBlank()) return "Kaunsa button dabana hai, thoda clear batao?"

        val service = JimiAccessibilityService.instance
            ?: return "Accessibility permission on nahi hai, pehle wo enable karo."

        val node = service.findNodeByText(targetText)
            ?: return "'$targetText' mujhe abhi screen pe nahi mil raha."

        val success = service.clickNode(node)
        return if (success) "'$targetText' dabaya ✅" else "'$targetText' mila lekin tap nahi ho paaya."
    }

    private fun handleCall(decision: JSONObject): String {
        val contactName = decision.optString("contact")
        if (contactName.isBlank()) return "Kise call karna hai, naam batao?"

        val phone = ContactResolver.findPhoneNumberByName(context, contactName)
            ?: return "'$contactName' contacts me nahi mila 😕"

        val hasCallPermission = androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.CALL_PHONE
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        return if (hasCallPermission) {
            val intent = android.content.Intent(
                android.content.Intent.ACTION_CALL,
                android.net.Uri.parse("tel:$phone")
            ).apply {
                flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            "$contactName ko call laga raha hoon 📞"
        } else {
            val intent = android.content.Intent(
                android.content.Intent.ACTION_DIAL,
                android.net.Uri.parse("tel:$phone")
            ).apply {
                flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            "$contactName ka number dialer me khol diya. Auto-call ke liye app ko Call permission do (Settings > Apps > Jimi > Permissions)."
        }
    }
}
