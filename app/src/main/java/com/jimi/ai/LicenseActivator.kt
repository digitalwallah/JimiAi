package com.jimi.ai

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.TimeUnit

object LicenseActivator {

    private const val PREFS_NAME = "jimi_license_prefs"
    private const val KEY_IS_PREMIUM = "is_premium"
    private const val KEY_EXPIRY = "premium_expiry_timestamp"
    private const val KEY_PLAN = "active_plan"

    private const val ONE_MONTH_MILLIS = 30L * 24 * 60 * 60 * 1000
    private const val FOUR_MONTH_MILLIS = 120L * 24 * 60 * 60 * 1000

    fun activatePremium(context: Context, plan: String) {
        val prefs = getPrefs(context)
        val durationMillis = when (plan) {
            "1_month" -> ONE_MONTH_MILLIS
            "4_month" -> FOUR_MONTH_MILLIS
            else -> ONE_MONTH_MILLIS
        }
        val expiryTimestamp = System.currentTimeMillis() + durationMillis

        prefs.edit()
            .putBoolean(KEY_IS_PREMIUM, true)
            .putLong(KEY_EXPIRY, expiryTimestamp)
            .putString(KEY_PLAN, plan)
            .apply()

        // TODO: hook into your existing License Manager here, e.g:
        // LicenseManager.getInstance(context).setPremiumStatus(true, expiryTimestamp)
    }

    fun isPremiumActive(context: Context): Boolean {
        val prefs = getPrefs(context)
        val isPremium = prefs.getBoolean(KEY_IS_PREMIUM, false)
        val expiry = prefs.getLong(KEY_EXPIRY, 0L)
        if (isPremium && System.currentTimeMillis() > expiry) {
            // expired — auto deactivate
            prefs.edit().putBoolean(KEY_IS_PREMIUM, false).apply()
            return false
        }
        return isPremium
    }

    fun getExpiryTimestamp(context: Context): Long {
        return getPrefs(context).getLong(KEY_EXPIRY, 0L)
    }

    fun getDaysRemaining(context: Context): Long {
        val expiry = getExpiryTimestamp(context)
        val remaining = expiry - System.currentTimeMillis()
        return if (remaining > 0) TimeUnit.MILLISECONDS.toDays(remaining) else 0
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}
