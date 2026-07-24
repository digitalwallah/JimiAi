package com.jimi.ai

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Har 15 minute mein AlarmManager se fire hota hai. Agar Always-Listening on hai
 * lekin OEM (iQOO/FunTouch) ne WakeWordService ko background mein chupke se maar diya hai,
 * to ye use wapas zinda kar deta hai - isse Jimi sirf app khule rehte hi nahi,
 * screen-off/home-screen pe bhi reliably sunta rehta hai. */
class WakeWordWatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (SettingsStore.isAlwaysListeningEnabled(context)) {
            WakeWordService.start(context)
        }
    }
}
