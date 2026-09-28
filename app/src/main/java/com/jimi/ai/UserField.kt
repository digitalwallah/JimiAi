package com.jimi.ai

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Ek saved field jo AI form filling ke liye use hota hai — non-sensitive data yahin store hota
 * hai. Sensitive fields (password, card number, bank details, government IDs) ki VALUE yahan
 * kabhi store nahi hoti — sirf "field exist karta hai" wala record hota hai, asli value
 * SecureCredentialStore (encrypted) mein rehti hai. [isSensitive] hi decide karta hai kaunsa
 * store use hoga. */
@Entity(tableName = "user_fields")
data class UserField(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fieldType: String,
    val label: String,
    val plainValue: String = "",
    val isSensitive: Boolean = false,
    val isEnabled: Boolean = true,
    val requiresConfirm: Boolean = true,
    val updatedAt: Long = System.currentTimeMillis()
)
