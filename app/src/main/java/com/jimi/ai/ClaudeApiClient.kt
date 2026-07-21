package com.jimi.ai

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Talks to Google's Gemini API (aistudio.google.com) — free tier, no credit card needed.
 * This is Jimi's "brain":
 * - Understands free-form Hinglish/Hindi/English commands
 * - Decides which action to take (open app, send WhatsApp msg, play YouTube video, etc.)
 * - Generates natural chat replies matching a contact's usual language style
 *
 * Class name kept as ClaudeApiClient so the rest of the app (CommandRouter etc.)
 * doesn't need to change — only the underlying API swapped.
 */
class ClaudeApiClient(private val apiKey: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val model = "gemini-3.1-flash-lite"
    private fun endpoint() =
        "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"

    /**
     * Sends a system + user prompt, returns raw text reply.
     * Kept synchronous - call this from a background coroutine/thread.
     */
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
                throw RuntimeException("Gemini API error ${response.code}: $responseBody")
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

    /**
     * Asks Gemini to classify the user's command into a structured action.
     * Returns JSON like:
     * {"action":"whatsapp_send","contact":"Rahul","message":"..."}
     * {"action":"youtube_play","channel":"MrBeast","query":"latest video"}
     * {"action":"open_app","app_name":"Instagram"}
     * {"action":"chat_reply"}  -> just a normal conversation, no device action
     */
    fun routeCommand(userCommand: String, recentHistory: String = ""): JSONObject {
        val system = """
            Tum Jimi ho, ek Android automation assistant ka "brain". User Hindi/English/Hinglish mix me
            command dega. Tumhara kaam hai command ko classify karke SIRF ek JSON object return karna,
            koi extra text nahi, koi markdown fence nahi.

            Zaroori: Neeche "Recent conversation" diya gaya hai (agar hai). Agar current command
            adhoora/ambiguous lage (jaise "bhej do", "haan kar do", "usko bolo" - jisme contact ya
            message clear na ho), toh Recent conversation dekh kar missing details (contact naam,
            message content) wahan se nikaalo. Follow-up commands ko standalone treat mat karo.

            Possible actions:
            1. whatsapp_send -> {"action":"whatsapp_send","contact":"<naam jaisa user ne bola, ya recent conversation se>","message":"<AGAR user ne kuch specific bolne ko bola hai (jaise 'bolo main aa raha hu' ya 'likho ki kal milte hain'), toh us content ko YAHAN HOOBAHOO/almost-exact nikaalo, apni taraf se naya mat likho. Agar user ne sirf general instruction di hai jaise 'Rahul ko bolo main busy hu' toh 'main busy hu' extract karo. Agar yeh ek follow-up hai (jaise 'bhej do sidha'), toh recent conversation me jo message pehle discuss hua tha wahi yahan daalo. Sirf tab khali chhodo jab user ne bilkul bhi bataya na ho ki kya kehna hai>"}
            2. youtube_play -> {"action":"youtube_play","channel":"<agar user ne channel ka naam liya hai wahi yahan daalo, exact spelling jaisi boli waisi>","query":"<video kis baare me chahiye - agar 'latest video' bola hai toh 'latest video' likho, agar koi topic bola hai toh wahi topic likho>"}
            3. open_app -> {"action":"open_app","app_name":"<app ka standard English/Latin-script naam. ZAROORI: agar user ne Hindi/Devanagari script me ya kisi doosri language me app ka naam bola hai (jaise 'लिंक्डइन', 'यूट्यूब', 'व्हाट्सएप'), usse uske common English app-name me convert karke likho (jaise 'LinkedIn', 'YouTube', 'WhatsApp'), kyunki phone ke installed apps ka naam hamesha English/Latin script me hota hai>"}
            4. make_call -> {"action":"make_call","contact":"<naam jaisa user ne bola>"}
            5. tap_screen -> {"action":"tap_screen","target_text":"<screen pe jo button/text dikh raha hai jise dabana hai, jaisa user ne bola waisa hi>"}  (jab user kisi current-open app me kisi button/cheez ko dabane ko bole, jaise 'back button dabao', 'Send pe click karo', 'notification band karo')
            6. chat_reply -> {"action":"chat_reply"}  (jab user sirf baat kar raha ho, koi device action nahi chahiye, aur recent conversation me bhi koi clear pending action na ho)

            ${if (recentHistory.isNotBlank()) "Recent conversation:\n$recentHistory\n" else ""}
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
