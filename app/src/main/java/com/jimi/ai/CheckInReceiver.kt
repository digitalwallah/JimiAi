package com.jimi.ai

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

/** Alarm fire hone par yeh chalta hai - ek chhota check-in notification dikhata hai,
 * phir agle din ke liye agla check-in schedule kar deta hai. */
class CheckInReceiver : BroadcastReceiver() {

    companion object {
        const val CHANNEL_ID = "jimi_checkin_channel"
        const val NOTIF_ID = 77

        // Persona ke hisaab se message variety - random pick hoga har baar.
        private val JARVIS_MESSAGES = listOf(
            "Din kaisa raha? Koi kaam ho toh bata dena.",
            "Check-in: sab theek chal raha hai?",
            "Agar kuch madad chahiye ho, main yahan hoon."
        )
        private val MYRA_MESSAGES = listOf(
            "Hey, din kaisa gaya tumhara? 😊",
            "Bas yaad aa gaya tumhara, sab badhiya hai na?",
            "Kaisa raha aaj ka din? Bata na kuch khaas hua?"
        )
    }

    override fun onReceive(context: Context, intent: Intent?) {
        showNotification(context)
        // Agle din ke liye agla check-in schedule kar do.
        CheckInScheduler.scheduleNext(context)
    }

    private fun showNotification(context: Context) {
        createNotificationChannel(context)

        val messages = if (SettingsStore.getPersona(context) == "myra") MYRA_MESSAGES else JARVIS_MESSAGES
        val message = messages.random()

        val openAppIntent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("Jimi")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIF_ID, notification)
    }

    private fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Jimi Check-ins", NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }
}
