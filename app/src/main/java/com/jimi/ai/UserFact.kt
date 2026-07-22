package com.jimi.ai

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Ek clean, important fact jo Jimi ne user ke baare mein seekha hai (naam, preference, date, etc.)
 * Raw conversation nahi - sirf extracted, useful cheez. Duplicate check "key" ke through hota hai. */
@Entity(tableName = "user_facts")
data class UserFact(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val key: String,        // jaise "name", "birthday", "favorite_food" - normalized short label
    val value: String,      // jaise "Rahul", "March 15", "biryani"
    val timestamp: Long = System.currentTimeMillis()
)
