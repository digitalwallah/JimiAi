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

    private val model = "gemini-3.1-flash-lite"

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
            8. toggle_flashlight -> {"action":"toggle_flashlight","state":"<'on' ya 'off', jo user bole>"}
            9. volume_control -> {"action":"volume_control","direction":"<'up','down','mute','unmute','max', ya 'set' agar exact percent bola ho>","percent":<0-100 ka number, sirf agar direction 'set' hai warna -1>}
            10. brightness_control -> {"action":"brightness_control","direction":"<'up','down', ya 'set' agar exact percent bola ho>","percent":<0-100 ka number, sirf agar direction 'set' hai warna -1>}
            11. rotation_lock -> {"action":"rotation_lock","state":"<'on' (lock/fix karna) ya 'off' (auto-rotate)>"}
            12. media_control -> {"action":"media_control","command":"<'play','pause','play_pause','next','previous','stop'>"}
            13. set_alarm -> {"action":"set_alarm","hour":<0-23, 24-hour format>,"minute":<0-59>,"label":"<agar koi naam/reason bola ho, warna khaali>"}
            14. set_timer -> {"action":"set_timer","seconds":<total seconds mein duration>,"label":"<agar koi naam bola ho, warna khaali>"}
            15. explain_screen -> {"action":"explain_screen","instruction":"<user ne exactly kya poocha/bola hai - jaise 'iska matlab batao', 'ise English mein translate karo', 'ye calculate karo', 'ye samjhao'>"}
            16. save_note -> {"action":"save_note","content":"<jo bhi user ne note karne ko bola, uska exact content>"}
            17. notes_summary -> {"action":"notes_summary"}
            18. generate_notes_pdf -> {"action":"generate_notes_pdf"}
            19. typing_help -> {"action":"typing_help","mode":"<'suggest' agar user sirf salah maang raha hai jaise 'yaha kya likhu', 'kya reply karu'; 'type' agar user ne exact message dictate kiya hai jaise 'ye likh do: ...', 'type kardo ki ...'>","instruction":"<user ka poora request/context - jo bhi bola hai>"}
            20. generate_topic_pdf -> {"action":"generate_topic_pdf","topic":"<jis topic/subject pe user ne PDF/notes banane ko bola, jaise 'Photosynthesis', 'French Revolution'>"}
            21. import_document -> {"action":"import_document"}

            Important rules:
            - Contact naam aur app naam (whatsapp_send, make_call, open_app ke andar) HAMESHA Roman/English
              letters mein hi return karo, chahe user kisi bhi script/bhasha mein bole (Hindi, Devanagari,
              Hinglish). Phone ki contact list aur app names English script mein saved hote hain, isliye
              transliterate karke do. Example: user "होम को कॉल करो" bole toh contact field mein "Home"
              likhna hai, "होम" nahi likhna.
            - youtube_play ke query field mein user ke exact words/keywords use karo jaise unhone bole.
              Apna guess ya loosely-related topic mat banao. Agar user ne specific title, naam, ya keyword
              diya hai, wahi verbatim (ho sake toh us bhasha mein bhi jisme original video ka title likha
              hota hai) query mein daalo.
            - set_alarm ke liye time hamesha 24-hour format mein convert karo: "shaam/sham 5 baje" = 17,
              "raat 9 baje" ya "raat ke 9" = 21, "subah 7 baje" = 7, "dopahar 2 baje" = 14. Agar user sirf
              "9 baje" bole bina AM/PM/subah-shaam ke, aur context na ho, toh sabse natural guess lo
              (jaise akela "9 baje" alarm ke liye usually subah hota hai).
            - set_timer ke liye duration ko hamesha total seconds mein convert karo: "5 minute" = 300,
              "10 minute" = 600, "1 ghanta"/"1 hour" = 3600, "30 second" = 30.
            - volume_control/brightness_control mein agar user exact number bole ("volume 50 kar do",
              "brightness 80% kar do"), toh direction="set" aur percent us number ko do. Agar sirf
              "badhao"/"kam karo"/"tez karo"/"dheema karo" bole bina number ke, toh direction="up"/"down"
              aur percent=-1.
            - explain_screen tabhi use karo jab user current screen pe dikh rahi kisi cheez ke baare
              mein pooche — jaise "iska matlab kya hai", "ye kya likha hai", "translate karo", "ye
              calculate karo", "ye samjhao". Agar sawaal general knowledge ka hai (screen se related
              nahi), toh chat_reply use karo.
            - save_note tabhi use karo jab user explicitly bole "note karo", "ye likh lo", "yaad rakhne
              ke liye note bana do" — content field mein user ka poora point verbatim daalo.
              notes_summary tab use karo jab user apne saare saved notes ka summary/overview maange
              (jaise "mere notes ka summary do", "kya kya note kiya hai batao").
              generate_notes_pdf tab use karo jab user apne SAVED NOTES ka PDF banane ko bole
              (jaise "notes ka PDF banao", "PDF bhejo", "notes download karo").
            - typing_help tabhi use karo jab user kisi doosre app (WhatsApp, Instagram, etc.) mein
              screen pe dikh rahe kisi text-field mein kya likhna hai iske baare mein pooche ya bole,
              jaise "yaha kya likhu", "isko reply me kya bolu", "ye type kar do [message]". explain_screen
              se alag hai - explain_screen kisi cheez ko samjhane/translate karne ke liye hai, typing_help
              naya text likhne/suggest karne ke liye hai.
            - generate_topic_pdf tab use karo jab user kisi SUBJECT/TOPIC PE naya study material/notes/PDF
              banane ko bole — jaise "Photosynthesis pe notes banao", "History ka PDF chahiye", "explain
              karke PDF do". Ye generate_notes_pdf se alag hai — wo saved notes ka hai, ye ek bilkul naya
              topic explain karke document banane ke liye hai.
            - import_document tab use karo jab user apni PURANI PDF ya IMAGE ko naya/professional/better
              banane ko bole — jaise "meri purani PDF ko naya banado", "is image ka PDF banao", "document
              import karo", "purani file se achha PDF banao".

            - SAVE_MEMORY vs CHAT_REPLY (bahut zaroori, isme galti mat karna):
              save_memory SIRF tab use karo jab user KHUD apni marzi se ek NAYA FACT/STATEMENT bata raha
              ho jo future mein yaad rakhna zaroori hai — jaise koi rishta, pasand, birthday, ya koi bhi
              naya info jo user ne diya hai. Ye ek STATEMENT hota hai, sawaal nahi.
              chat_reply hamesha use karo jab user koi bhi SAWAAL poochh raha ho — chahe wo sawaal Jimi
              ke baare mein ho (jaise "tumhare features kya hain", "tum kya kar sakte ho"), user khud ke
              baare mein ho, ya kisi bhi topic pe ho. Sawaal ka jawab HAMESHA chat_reply se do, save_memory
              se KABHI nahi — chahe us sawaal ka jawab pata ho ya na ho.
              Pehchaanne ka tareeka: agar sentence "kya", "kaun", "kaha", "kaise", "konsi", "kitna" jaise
              question words se bana hai, ya "?" jaisa sawaal lagta hai, ya matlab poochha ja raha hai -
              ye HAMESHA chat_reply hai.
              Examples:
              - "tumhare ander konsi features hain?" -> chat_reply (sawaal hai)
              - "kaha ho tum?" -> chat_reply (sawaal hai)
              - "Faiz Alam mera dost hai" -> save_memory (naya fact diya hai)
              - "yaad rakhna mera birthday 5 June hai" -> save_memory (explicitly yaad rakhne bola)
              - "jaurez bhaiya mere dost hain" -> save_memory (naya fact diya hai)
              - "flashlight on karo" -> toggle_flashlight (device action hai, sawaal nahi)
              - "volume badhao" -> volume_control
              - "subah 6 baje alarm laga do" -> set_alarm
              - "5 minute ka timer lagao" -> set_timer
              - "gaana pause karo" -> media_control
              - "screen ghumne mat do" -> rotation_lock (state="on")
              - "iska matlab kya hai" -> explain_screen
              - "ye English mein translate kardo" -> explain_screen
              - "note kar lo kal doctor jaana hai" -> save_note
              - "mere notes ka summary do" -> notes_summary
              - "notes ka pdf bana do" -> generate_notes_pdf
              - "yaha kya likhu" -> typing_help (mode="suggest")
              - "ise reply me bol do main busy hoon" -> typing_help (mode="type", instruction="main busy hoon")
              - "photosynthesis pe notes bana do" -> generate_topic_pdf (topic="Photosynthesis")
              - "meri purani pdf ko naya banado" -> import_document
              - "is image se professional pdf banao" -> import_document

            $memorySection$historySection
            Sirf raw JSON return karo.
        """.trimIndent()

        val raw = ask(system, userCommand).trim()
        return try {
            val cleaned = extractJson(raw)
            JSONObject(cleaned)
        } catch (e: Exception) {
            JSONObject().apply { put("action", "chat_reply") }
        }
    }

    /** Ek professional, structured document (headings, paragraphs, tables, charts, flowcharts)
     * JSON format mein generate karwata hai — is JSON ko DocumentBlockParser parse karke
     * AdvancedPdfGenerator ko deta hai jo actual PDF banata hai. */
    fun generateStructuredDocument(topic: String, sourceContent: String = ""): JSONObject {
        val sourceSection = if (sourceContent.isNotBlank()) {
            "Neeche user ka original content diya gaya hai jise reorganize/explain karna hai:\n$sourceContent\n"
        } else ""

        val system = """
            Tum ek professional document-writer ho. Tumhara kaam hai ek study-quality, well-structured
            document JSON format mein banana - jaisa ek top-class educational PDF (Gemini/ChatGPT jaisa
            professional) dikhta hai. SIRF JSON return karo, koi extra text, koi markdown fence nahi.

            JSON format:
            {
              "title": "<document ka clear title>",
              "blocks": [
                {"type":"heading","text":"...","level":1},
                {"type":"paragraph","text":"..."},
                {"type":"bullet_list","items":["...","..."]},
                {"type":"numbered_list","items":["...","..."]},
                {"type":"table","headers":["Col1","Col2"],"rows":[["a","b"],["c","d"]]},
                {"type":"bar_chart","title":"...","labels":["A","B"],"values":[10,20]},
                {"type":"line_chart","title":"...","labels":["Jan","Feb"],"values":[5,15]},
                {"type":"pie_chart","title":"...","labels":["X","Y"],"values":[60,40]},
                {"type":"flowchart","title":"...","steps":["Step 1","Step 2","Step 3"]},
                {"type":"divider"}
              ]
            }

            Rules:
            - level=1 heading sirf main sections ke liye, level=2 sub-sections ke liye.
            - Jahan bhi numeric/comparison data ho, ek bar_chart ya pie_chart zaroor add karo.
            - Jahan bhi ek process/sequence/steps hon (jaise "kaise hota hai", "steps"), ek flowchart
              zaroor add karo.
            - Jahan comparison/structured data ho (jaise pros/cons, categories), table use karo.
            - Content clear, well-organized, aur student/professional-grade hona chahiye - jaisa best
              educational material dikhta hai. Achhi tarah headings se organize karo.
            - Agar user ne kisi specific language (Hindi/English/Hinglish) mein likha/bola hai, wahi
              language content mein use karo. Agar source content diya gaya hai, usi ke language mein raho.
            - Kam se kam 1 chart/diagram/table zaroor include karo jahan bhi genuinely relevant ho -
              lekin random/forced chart mat daalo agar data uske liye fit nahi karta.

            $sourceSection
            Sirf raw JSON return karo.
        """.trimIndent()

        val raw = ask(system, "Topic/Instruction: $topic").trim()
        return try {
            JSONObject(extractJson(raw))
        } catch (e: Exception) {
            JSONObject().apply {
                put("title", topic.ifBlank { "Jimi Document" })
                put("blocks", JSONArray().put(
                    JSONObject().apply {
                        put("type", "paragraph")
                        put("text", raw.ifBlank { "Content generate nahi ho paaya." })
                    }
                ))
            }
        }
    }

    private fun extractJson(raw: String): String {
        var cleaned = raw.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start != -1 && end != -1 && end > start) {
            cleaned = cleaned.substring(start, end + 1)
        }
        return cleaned
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
