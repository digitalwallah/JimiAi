package com.jimi.ai

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Har conversation ka ek chhota summary store karta hai, taaki Jimi purani baaton
 * ka context yaad rakh sake (MYRA-style "yaad rakhne wali" companion feel). */
@Entity(tableName = "conversation_memory")
data class ConversationMemory(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val summary: String,
    val timestamp: Long = System.currentTimeMillis()
)
