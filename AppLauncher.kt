package com.jimi.ai

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

object AppLauncher {

    /** Returns display-name -> package-name map of all launchable installed apps. */
    fun listInstalledApps(context: Context): Map<String, String> {
        val pm = context.packageManager
        val apps: List<ApplicationInfo> = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val result = mutableMapOf<String, String>()
        for (app in apps) {
            if (pm.getLaunchIntentForPackage(app.packageName) != null) {
                val label = pm.getApplicationLabel(app).toString()
                result[label] = app.packageName
            }
        }
        return result
    }

    /** Finds an installed app whose name best matches [query] and launches it. Returns true if launched. */
    fun openAppByName(context: Context, query: String): Boolean {
        val apps = listInstalledApps(context)
        val match = apps.entries.firstOrNull { it.key.contains(query, ignoreCase = true) }
            ?: return false
        val intent = context.packageManager.getLaunchIntentForPackage(match.value) ?: return false
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return true
    }
}
