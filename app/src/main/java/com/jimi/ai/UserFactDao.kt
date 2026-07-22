package com.jimi.ai

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface UserFactDao {

    /** Agar same "key" pehle se hai (jaise "name"), to naya value usko replace kar dega -
     * duplicate facts nahi banenge, purana update ho jayega. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(fact: UserFact)

    @Query("SELECT * FROM user_facts WHERE key = :key LIMIT 1")
    suspend fun findByKey(key: String): UserFact?

    /** Reply banate waqt sab facts fetch karne ke liye - list chhoti hi rahegi (raw history nahi). */
    @Query("SELECT * FROM user_facts ORDER BY timestamp DESC")
    suspend fun getAll(): List<UserFact>
}
