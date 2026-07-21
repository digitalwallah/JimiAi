package com.jimi.ai

import android.content.Context

object SettingsStore {
    private const val PREFS = "jimi_prefs"

    fun saveKeys(context: Context, claudeKey: String, youtubeKey: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("claude_key", claudeKey)
            .putString("youtube_key", youtubeKey)
            .apply()
    }

    fun getClaudeKey(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("claude_key", "") ?: ""

    fun getYoutubeKey(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("youtube_key", "") ?: ""

    fun setAutoSend(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("auto_send", enabled).apply()
    }

    /** Default OFF on purpose: Jimi shows the drafted WhatsApp reply for review before sending. */
    fun isAutoSendEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("auto_send", false)

    fun setAlwaysListening(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("always_listening", enabled).apply()
    }

    fun isAlwaysListeningEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("always_listening", false)

    /** Persona controls Jimi's reply tone. "jarvis" = formal/concise, "myra" = warm/casual/Hinglish.
     * Default is "jarvis" to match existing behavior — nothing changes until user picks MYRA mode. */
    fun setPersona(context: Context, persona: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("persona", persona).apply()
    }

    fun getPersona(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("persona", "jarvis") ?: "jarvis"
}
