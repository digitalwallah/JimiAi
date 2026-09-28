package com.jimi.ai

import androidx.room.*

@Dao
interface UserFieldDao {
    @Query("SELECT * FROM user_fields WHERE isEnabled = 1")
    suspend fun getAllEnabled(): List<UserField>

    @Query("SELECT * FROM user_fields")
    suspend fun getAll(): List<UserField>

    @Query("SELECT * FROM user_fields WHERE fieldType = :fieldType LIMIT 1")
    suspend fun findByType(fieldType: String): UserField?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(field: UserField): Long

    @Update
    suspend fun update(field: UserField)

    @Delete
    suspend fun delete(field: UserField)
}
