package com.jimi.ai

/** "Repeat that" jaisa command turant handle karne ke liye — WakeWordService har reply speak
 * karne se pehle isko update kar deta hai. Ye sirf in-memory hai (process restart pe khaali ho
 * jaata hai), tab OfflineIntentRouter fallback ke roop mein Room ki last ConversationMemory
 * entry use kar leta hai. */
object LastReplyStore {
    var lastReply: String? = null
}
