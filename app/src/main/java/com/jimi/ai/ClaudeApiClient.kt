package com.jimi.ai

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ClaudeApiClient(private val apiKey: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val model = "gemini-1.5-flash"

    private fun endpoint(): String {
        return "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
    }

    fun ask(systemPrompt: String, userMessage: String): String {
        val body = JSONObject().apply {
            put("system_instruction", JSONObject().apply {
                put("parts", JSONArray().put(JSONObject().put("text", systemPrompt)))
            })
            put("contents", JSONArray().put(
                JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().put(JSONObject().put("text", userMessage)))
                }
            ))
        }

        val request = Request.Builder()
            .url(endpoint())
            .addHeader("content-type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string() ?: "{}"
            if (!response.isSuccessful) {
                throw RuntimeException("Gemini API error ${response.code}:$responseBody")
            }
            val json = JSONObject(responseBody)
            val candidates = json.optJSONArray("candidates") ?: return ""
            if (candidates.length() == 0) return ""
            val content = candidates.getJSONObject(0).getJSONObject("content")
            val parts = content.getJSONArray("parts")
            val sb = StringBuilder()
            for (i in 0 until parts.length()) {
                sb.append(parts.getJSONObject(i).optString("text"))
            }
            return sb.toString()
        }
    }

    fun routeCommand(userCommand: String, recentHistory: String = "", savedMemories: String = ""): JSONObject {
        val memorySection = if (savedMemories.isNotBlank()) "User ki pehle se yaad rakhi hui baatein (Memories):\n$savedMemories\n" else ""
        val historySection = if (recentHistory.isNotBlank()) "Recent conversation:\n$recentHistory\n" else ""

        val system = """
            Tum Jimi ho, ek Android automation assistant ka "brain". User Hindi/English/Hinglish mix me
            command dega. Tumhara kaam hai command ko classify karke SIRF ek JSON object return karna,
            koi extra text nahi, koi markdown fence nahi.

            Zaroori: Neeche "Recent conversation" diya gaya hai (agar hai). Agar current command
            adhoora/ambiguous lage (jaise "bhej do", "haan kar do", "usko bolo" - jisme contact ya
            message clear na ho), toh Recent conversation dekh kar missing details (contact naam,
            message content) wahan se nikaalo. Follow-up commands ko standalone treat mat karo.

            Possible actions:
            1. whatsapp_send -> {"action":"whatsapp_send","contact":"<naam jaisa user ne bola, ya recent conversation se>","message":"<AGAR user ne kuch specific bolne ko bola hai, toh content ko YAHAN HOOBAHOO nikaalo>"}
            2. youtube_play -> {"action":"youtube_play","channel":"<channel ka naam>","query":"<video topic>"}
            3. open_app -> {"action":"open_app","app_name":"<app ka standard English naam, jaise 'WhatsApp', 'YouTube'>"}
            4. make_call -> {"action":"make_call","contact":"<naam jaisa user ne bola>"}
            5. tap_screen -> {"action":"tap_screen","target_text":"<screen button text>"}
            6. chat_reply -> {"action":"chat_reply"}
            7. save_memory -> {"action":"save_memory","key":"<kis baare me yaad rakhna hai>","value":"<kya info yaad rakhni hai>"}

            $memorySection$historySection
            Sirf raw JSON return karo.
        """.trimIndent()

        val raw = ask(system, userCommand).trim()
        val cleaned = raw.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        return JSONObject(cleaned)
    }

    fun generateStyledReply(contactName: String, styleNote: String, intent: String): String {
        val system = """
            Tum $contactName ko WhatsApp message likh rahe ho, user ki taraf se.
            Is contact ke saath baat karne ka style: "$styleNote"
            Message chhota, natural aur casual rakho. Sirf message text return karo, kuch aur nahi.
        """.trimIndent()
        return ask(system, intent).trim()
    }
}
