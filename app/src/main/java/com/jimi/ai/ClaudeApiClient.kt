package com.jimi.ai

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Talks to the real Anthropic API. This is Jimi's "brain":
 * - Understands free-form Hinglish/Hindi/English commands
 * - Decides which action to take (open app, send WhatsApp msg, play YouTube video, etc.)
 * - Generates natural chat replies matching a contact's usual language style
 */
class ClaudeApiClient(private val apiKey: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val endpoint = "https://api.anthropic.com/v1/messages"
    private val model = "claude-sonnet-5"

    /**
     * Sends a system + user prompt, returns raw text reply.
     * Kept synchronous - call this from a background coroutine/thread.
     */
    fun ask(systemPrompt: String, userMessage: String): String {
        val body = JSONObject().apply {
            put("model", model)
            put("max_tokens", 1024)
            put("system", systemPrompt)
            put("messages", JSONArray().put(
                JSONObject().apply {
                    put("role", "user")
                    put("content", userMessage)
                }
            ))
        }

        val request = Request.Builder()
            .url(endpoint)
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("content-type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string() ?: "{}"
            if (!response.isSuccessful) {
                throw RuntimeException("Claude API error ${response.code}: $responseBody")
            }
            val json = JSONObject(responseBody)
            val content = json.getJSONArray("content")
            val sb = StringBuilder()
            for (i in 0 until content.length()) {
                val block = content.getJSONObject(i)
                if (block.getString("type") == "text") {
                    sb.append(block.getString("text"))
                }
            }
            return sb.toString()
        }
    }

    /**
     * Asks Claude to classify the user's command into a structured action.
     * Returns JSON like:
     * {"action":"whatsapp_send","contact":"Rahul","message":"..."}
     * {"action":"youtube_play","channel":"MrBeast","query":"latest video"}
     * {"action":"open_app","app_name":"Instagram"}
     * {"action":"chat_reply"}  -> just a normal conversation, no device action
     */
    fun routeCommand(userCommand: String): JSONObject {
        val system = """
            Tum Jimi ho, ek Android automation assistant ka "brain". User Hindi/English/Hinglish mix me
            command dega. Tumhara kaam hai command ko classify karke SIRF ek JSON object return karna,
            koi extra text nahi, koi markdown fence nahi.

            Possible actions:
            1. whatsapp_send -> {"action":"whatsapp_send","contact":"<naam jaisa user ne bola>","message":"<jo bhejna hai uska matlab, tum apne words me nahi likhna, message field khali chhodo agar user ne exact content nahi diya>"}
            2. youtube_play -> {"action":"youtube_play","channel":"<channel ka naam agar bola>","query":"<search query>"}
            3. open_app -> {"action":"open_app","app_name":"<app ka naam>"}
            4. chat_reply -> {"action":"chat_reply"}  (jab user sirf baat kar raha ho, koi device action nahi chahiye)

            Sirf raw JSON return karo.
        """.trimIndent()

        val raw = ask(system, userCommand).trim()
        val cleaned = raw.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        return JSONObject(cleaned)
    }

    /**
     * Generates a reply message in the given contact's usual style (Hinglish/Hindi/English),
     * so the WhatsApp message actually sounds like how the user normally texts that person.
     */
    fun generateStyledReply(contactName: String, styleNote: String, intent: String): String {
        val system = """
            Tum $contactName ko WhatsApp message likh rahe ho, user ki taraf se.
            Is contact ke saath baat karne ka style: "$styleNote"
            Message chhota, natural aur casual rakho - jaise log actually WhatsApp pe likhte hain
            (zaroorat ho toh Hinglish mix karo). Sirf message text return karo, kuch aur nahi.
        """.trimIndent()
        return ask(system, intent).trim()
    }
}
