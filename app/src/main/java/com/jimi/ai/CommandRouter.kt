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
    suspend fun handle(command: String): String {
        val decision = claude.routeCommand(command)
        return when (decision.optString("action")) {

            "whatsapp_send" -> handleWhatsApp(decision, command)

            "youtube_play" -> handleYouTube(decision)

            "open_app" -> {
                val appName = decision.optString("app_name")
                val opened = AppLauncher.openAppByName(context, appName)
                if (opened) "$appName khol diya ✅" else "'$appName' naam ka app nahi mila 😕"
            }

            else -> claude.ask(
                "Tum Jimi ho, ek friendly Hinglish-bolne wala personal assistant. Chhota, natural reply do.",
                command
            )
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
        val draftedMessage = claude.generateStyledReply(contactName, styleNote, intentText)

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
}
