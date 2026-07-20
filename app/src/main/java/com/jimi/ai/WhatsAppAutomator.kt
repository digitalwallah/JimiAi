package com.jimi.ai

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.delay

/**
 * IMPORTANT — read before using:
 * WhatsApp doesn't give a personal-account API to send arbitrary automated messages.
 * The only officially-safe deep link is the "wa.me" scheme, which OPENS a chat with the
 * message pre-filled in the text box — it does NOT send automatically. To actually send,
 * this class uses the Accessibility Service to tap WhatsApp's own Send button, exactly
 * like a human finger would.
 *
 * This is much safer than trying to fully script WhatsApp's UI from scratch, but it still
 * means Jimi is automating a third-party app outside its official API. Use responsibly:
 * - Don't blast messages to many contacts in a short time (WhatsApp's spam detection can
 *   flag/ban the number).
 * - Prefer keeping "review before send" ON (see MainActivity) rather than fully autonomous
 *   sending, especially at first.
 */
object WhatsAppAutomator {

    /** Step 1: open WhatsApp chat with the message pre-filled. */
    fun openChatWithPrefilledText(context: Context, phoneNumber: String, message: String) {
        val cleanNumber = phoneNumber.filter { it.isDigit() || it == '+' }.removePrefix("+")
        val encoded = Uri.encode(message)
        val uri = Uri.parse("https://wa.me/$cleanNumber?text=$encoded")
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }

    /**
     * Step 2: after WhatsApp opens (call this a second or two later, from a coroutine),
     * find and tap the Send button via the Accessibility Service.
     * Returns true if a send button was found and tapped.
     */
    suspend fun tapSendButton(): Boolean {
        val service = JimiAccessibilityService.instance ?: return false
        // Give WhatsApp's UI a moment to finish rendering after the intent launch.
        delay(1800)

        // WhatsApp's send button typically has content-description "Send"
        // (localized on Hindi phones it may say "भेजें" — we try both).
        val candidates = listOf("Send", "भेजें")
        for (label in candidates) {
            val node = service.findNodeByText(label)
            if (node != null) {
                return service.clickNode(node)
            }
        }
        return false
    }

    /** Full flow: open chat, wait, tap send. Call from a coroutine (e.g. lifecycleScope). */
    suspend fun sendMessage(context: Context, phoneNumber: String, message: String): Boolean {
        openChatWithPrefilledText(context, phoneNumber, message)
        return tapSendButton()
    }
}
