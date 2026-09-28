package com.jimi.ai

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.autofill.AutofillManager

object AutofillHelper {
    fun isJimiAutofillEnabled(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        val afm = context.getSystemService(AutofillManager::class.java) ?: return false
        return afm.hasEnabledAutofillServices()
    }

    /** User ko Android ke system Autofill-settings screen pe le jaata hai — ye ek system-level
     * choice hai, app khud force nahi kar sakta. */
    fun requestEnableAutofill(activity: Activity, requestCode: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val intent = Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE).apply {
            data = Uri.parse("package:${activity.packageName}")
        }
        activity.startActivityForResult(intent, requestCode)
    }
}
