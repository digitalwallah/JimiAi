package com.jimi.ai

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.util.Calendar

/** Din mein ek baar, random evening time (5 PM - 8 PM) par ek check-in notification schedule karta hai. */
object CheckInScheduler {

    fun scheduleNext(context: Context) {
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, (17..20).random())
            set(Calendar.MINUTE, (0..59).random())
            set(Calendar.SECOND, 0)
        }
        // Agar aaj ka time nikal chuka hai, to kal ke liye schedule karo.
        if (calendar.timeInMillis <= System.currentTimeMillis()) {
            calendar.add(Calendar.DAY_OF_YEAR, 1)
        }

        val intent = Intent(context, CheckInReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context, 99, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            calendar.timeInMillis,
            pendingIntent
        )
    }

    fun cancel(context: Context) {
        val intent = Intent(context, CheckInReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context, 99, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(pendingIntent)
    }
}
