package com.jimi.ai

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [ContactStyle::class, ConversationMemory::class], version = 2, exportSchema = false)
abstract class JimiDatabase : RoomDatabase() {
    abstract fun contactStyleDao(): ContactStyleDao
    abstract fun conversationMemoryDao(): ConversationMemoryDao

    companion object {
        @Volatile private var INSTANCE: JimiDatabase? = null

        fun getInstance(context: Context): JimiDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    JimiDatabase::class.java,
                    "jimi_db"
                )
                    // Naya entity add hua hai (version 1 -> 2). Fallback destructive migration
                    // use kar rahe hain kyunki abhi tak koi published users nahi hain jinka
                    // purana data preserve karna zaroori ho - development phase mein safe hai.
                    .fallbackToDestructiveMigration()
                    .build().also { INSTANCE = it }
            }
        }
    }
}
