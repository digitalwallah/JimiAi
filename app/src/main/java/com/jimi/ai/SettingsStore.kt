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

    /** Proactive check-in: default OFF, user ko khud settings se on karna hoga. */
    fun setCheckInEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("checkin_enabled", enabled).apply()
    }

    fun isCheckInEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("checkin_enabled", false)

    /** Voice character: "arjun" (21, younger male), "veer" (26, deep male),
     * "ananya" (younger female, soft), "isha" (26, warm female). Default "veer". */
    fun setVoiceCharacter(context: Context, voice: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("voice_character", voice).apply()
    }

    fun getVoiceCharacter(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("voice_character", "veer") ?: "veer"

    // ---------- NAYA: VOICE SPEED / PITCH / LANGUAGE (character ke upar user-adjustable layer) ----------

    /** Character ke base speech rate par multiply hota hai. 1.0 = normal. User "speak faster"/
     * "speak slowly" bolke ise nudge kar sakta hai, ya exact value bhi bol sakta hai. */
    fun setSpeechRateMultiplier(context: Context, multiplier: Float) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat("speech_rate_multiplier", multiplier.coerceIn(0.5f, 2.0f)).apply()
    }

    fun getSpeechRateMultiplier(context: Context): Float =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat("speech_rate_multiplier", 1.0f)

    /** -1f = "override nahi hai, character ka default pitch use karo". */
    fun setPitchOverride(context: Context, pitch: Float) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat("pitch_override", pitch.coerceIn(0.5f, 2.0f)).apply()
    }

    fun getPitchOverride(context: Context): Float =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat("pitch_override", -1f)

    fun clearPitchOverride(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat("pitch_override", -1f).apply()
    }

    /** "auto" = default hi-IN. User explicitly "hi-IN"/"en-IN" pick kar sakta hai. */
    fun setLanguageOverride(context: Context, lang: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("language_override", lang).apply()
    }

    fun getLanguageOverride(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("language_override", "auto") ?: "auto"
}
