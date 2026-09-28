package com.jimi.ai

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Sensitive credentials (password, card number, CVV, bank account, government IDs waghera) ki
 * asli value YAHAN store hoti hai — Android Keystore-backed encryption se, kabhi bhi plain Room
 * database mein nahi. */
object SecureCredentialStore {

    private fun prefs(context: Context) = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "jimi_secure_credentials",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun save(context: Context, fieldType: String, value: String) {
        prefs(context).edit().putString(fieldType, value).apply()
    }

    fun get(context: Context, fieldType: String): String? =
        prefs(context).getString(fieldType, null)

    fun delete(context: Context, fieldType: String) {
        prefs(context).edit().remove(fieldType).apply()
    }
}
