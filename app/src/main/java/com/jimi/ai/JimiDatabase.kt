package com.jimi.ai

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [ContactStyle::class, ConversationMemory::class, UserFact::class], version = 3, exportSchema = false)
abstract class JimiDatabase : RoomDatabase() {
    abstract fun contactStyleDao(): ContactStyleDao
    abstract fun conversationMemoryDao(): ConversationMemoryDao
    abstract fun userFactDao(): UserFactDao

    companion object {
        @Volatile private var INSTANCE: JimiDatabase? = null

        fun getInstance(context: Context): JimiDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    JimiDatabase::class.java,
                    "jimi_db"
                )
                    // Naya entity add hua hai (version 2 -> 3). Fallback destructive migration
                    // use kar rahe hain kyunki abhi development phase hai - purana test data
                    // preserve karne ki zaroorat nahi hai.
                    .fallbackToDestructiveMigration()
                    .build().also { INSTANCE = it }
            }
        }
    }
}
