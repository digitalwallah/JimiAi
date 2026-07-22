package com.jimi.ai

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface ConversationMemoryDao {

    @Insert
    suspend fun insert(memory: ConversationMemory)

    /** Sabse recent summaries laata hai - purani conversations ka context banane ke liye. */
    @Query("SELECT * FROM conversation_memory ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 5): List<ConversationMemory>

    /** Bahut purani entries clean karne ke liye (database bada na ho jaaye). */
    @Query("DELETE FROM conversation_memory WHERE id NOT IN (SELECT id FROM conversation_memory ORDER BY timestamp DESC LIMIT 50)")
    suspend fun trimOldEntries()
}
