package com.jimi.ai

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ContactStyleDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(style: ContactStyle)

    @Query("SELECT * FROM contact_styles WHERE contactName LIKE '%' || :name || '%' LIMIT 1")
    suspend fun findByName(name: String): ContactStyle?

    @Query("SELECT * FROM contact_styles")
    suspend fun getAll(): List<ContactStyle>
}
