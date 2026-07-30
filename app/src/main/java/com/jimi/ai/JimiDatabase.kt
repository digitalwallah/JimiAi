package com.jimi.ai

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [ContactStyle::class, ConversationMemory::class, UserFact::class, Note::class],
    version = 4,
    exportSchema = false
)
abstract class JimiDatabase : RoomDatabase() {
    abstract fun contactStyleDao(): ContactStyleDao
    abstract fun conversationMemoryDao(): ConversationMemoryDao
    abstract fun userFactDao(): UserFactDao
    abstract fun noteDao(): NoteDao

    companion object {
        @Volatile private var INSTANCE: JimiDatabase? = null

        fun getInstance(context: Context): JimiDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    JimiDatabase::class.java,
                    "jimi_db"
                )
                    .fallbackToDestructiveMigration()
                    .build().also { INSTANCE = it }
            }
        }
    }
}
