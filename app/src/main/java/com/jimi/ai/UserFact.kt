package com.jimi.ai

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Ek clean, important fact jo Jimi ne user ke baare mein seekha hai (naam, preference, date, etc.)
 * Raw conversation nahi - sirf extracted, useful cheez. "key" unique hai, isliye same key ka naya
 * fact aane par purana automatically replace ho jata hai (duplicate rows nahi banenge). */
@Entity(tableName = "user_facts", indices = [Index(value = ["key"], unique = true)])
data class UserFact(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val key: String,        // jaise "name", "birthday", "favorite_food" - normalized short label
    val value: String,      // jaise "Rahul", "March 15", "biryani"
    val timestamp: Long = System.currentTimeMillis()
)
