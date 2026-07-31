package com.jimi.ai

import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings

/**
 * Periodically checks which app is currently in the foreground and pauses/resumes
 * Jimi's wake-word listening accordingly. This prevents the mic privacy indicator
 * from showing while the user is watching YouTube, on a call, using the camera, etc.
 * where always-listening is unnecessary and intrusive.
 *
 * Uses UsageStatsManager (needs "Usage Access" permission granted manually by the
 * user in Settings — no API key, no account, fully on-device and free).
 */
class ForegroundAppMonitor(
    private val context: Context,
    private val onShouldPause: () -> Unit,
    private val onShouldResume: () -> Unit
) {

    // Add/remove package names here as needed
    private val pauseListForApps = setOf(
        "com.google.android.youtube",
        "com.android.camera",
        "com.android.camera2",
        "org.codeaurora.snapcam",
        "com.google.android.dialer",
        "com.android.incallui",
        "com.whatsapp",              // during WhatsApp voice/video calls
        "com.google.android.apps.tachyon", // Google Meet
        "us.zoom.videomeetings",
        "com.netflix.mediaclient",
        "in.startv.hotstar",
        "com.google.android.videos"
    )

    private val handler = Handler(Looper.getMainLooper())
    private var isRunning = false
    private var wasPaused = false
    private val checkIntervalMs = 3000L // check every 3 seconds

    private val checkRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            checkForegroundApp()
            handler.postDelayed(this, checkIntervalMs)
        }
    }

    fun start() {
        if (!hasUsageAccessPermission()) {
            // Without this permission we can't detect foreground app;
            // caller should prompt user to grant it once.
            return
        }
        isRunning = true
        handler.post(checkRunnable)
    }

    fun stop() {
        isRunning = false
        handler.removeCallbacks(checkRunnable)
    }

    private fun checkForegroundApp() {
        val currentApp = getForegroundAppPackage()
        val shouldPause = currentApp != null && pauseListForApps.contains(currentApp)

        if (shouldPause && !wasPaused) {
            wasPaused = true
            onShouldPause()
        } else if (!shouldPause && wasPaused) {
            wasPaused = false
            onShouldResume()
        }
    }

    private fun getForegroundAppPackage(): String? {
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return null

        val endTime = System.currentTimeMillis()
        val beginTime = endTime - 10_000 // look back 10 seconds

        val stats = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            beginTime,
            endTime
        ) ?: return null

        return stats.maxByOrNull { it.lastTimeUsed }?.packageName
    }

    /** Checks if the user has granted "Usage Access" permission (required for this feature). */
    fun hasUsageAccessPermission(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
        val mode = appOps.unsafeCheckOpNoThrow(
            android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            context.packageName
        )
        return mode == android.app.AppOpsManager.MODE_ALLOWED
    }

    /** Call this to open the system settings screen where user can grant Usage Access. */
    fun requestUsageAccessPermission() {
        val intent = android.content.Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
