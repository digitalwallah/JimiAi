package com.jimi.ai

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Stores how Jimi should "sound" when texting a particular contact.
 * Example styleNote values:
 *  - "Hinglish, casual, dost jaisa - jaise 'bhai kya scene hai', emojis thoda"
 *  - "Pure Hindi, respectful - papa/mummy ke liye"
 *  - "Professional English - office colleagues ke liye"
 */
@Entity(tableName = "contact_styles")
data class ContactStyle(
    @PrimaryKey val phoneNumber: String,
    val contactName: String,
    val styleNote: String
)
