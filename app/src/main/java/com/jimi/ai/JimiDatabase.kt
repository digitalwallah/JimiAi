package com.jimi.ai

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [ContactStyle::class], version = 1)
abstract class JimiDatabase : RoomDatabase() {
    abstract fun contactStyleDao(): ContactStyleDao

    companion object {
        @Volatile private var INSTANCE: JimiDatabase? = null

        fun getInstance(context: Context): JimiDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    JimiDatabase::class.java,
                    "jimi_db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
