package com.jimi.ai

import android.content.Context
import android.content.Intent
import android.net.Uri
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Uses the official YouTube Data API v3 (needs a free API key from Google Cloud Console)
 * to search for a channel and/or video, then opens the result via a normal Intent —
 * which launches the YouTube app if installed, or the browser otherwise.
 */
class YouTubeHelper(private val apiKey: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /** Finds a channel's ID by its display name. */
    private fun findChannelId(channelName: String): String? {
        val q = URLEncoder.encode(channelName, "UTF-8")
        val url = "https://www.googleapis.com/youtube/v3/search?part=snippet&type=channel&q=$q&maxResults=1&key=$apiKey"
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: return null
            val json = JSONObject(body)
            val items = json.optJSONArray("items") ?: return null
            if (items.length() == 0) return null
            return items.getJSONObject(0).getJSONObject("id").getString("channelId")
        }
    }

    /** Finds the top matching video, optionally scoped to a specific channel.
     * Duration ab by-default koi filter nahi lagata - relevance-order pe chhod dete hain taaki
     * YouTube ka apna algorithm sabse best-matching video de, chahe wo 2 minute ka ho ya 2 ghante ka.
     * User agar explicitly duration maange, tabhi filter lagta hai:
     * - "latest/newest" bola to date-order use karta hai (koi duration filter nahi).
     * - "long/crash course/full/poora/lambi" bola to videoDuration=long (20+ min).
     * - "short/chhota/choti" bola to videoDuration=short (<4 min).
     * - Kuch specific na bola to koi duration filter nahi (any duration), sirf relevance order. */
    fun findVideo(query: String, channelName: String? = null): VideoResult? {
        val channelId = channelName?.let { findChannelId(it) }
        val wantsLatest = Regex("latest|newest|naya|nayi|abhi ka", RegexOption.IGNORE_CASE).containsMatchIn(query)
        val wantsLong = Regex("long|lambi|lamba|badi|crash course|full course|poora|pura|complete", RegexOption.IGNORE_CASE).containsMatchIn(query)
        val wantsShort = Regex("\\bshort\\b|chhota|choti|shorts", RegexOption.IGNORE_CASE).containsMatchIn(query)
        val q = URLEncoder.encode(query.ifBlank { "latest video" }, "UTF-8")

        fun buildUrl(durationFilter: String?, order: String, maxResults: Int): String {
            var url = "https://www.googleapis.com/youtube/v3/search?part=snippet&type=video" +
                "&order=$order&maxResults=$maxResults&q=$q&key=$apiKey"
            if (channelId != null) url += "&channelId=$channelId"
            if (durationFilter != null) url += "&videoDuration=$durationFilter"
            return url
        }

        val durationFilter = when {
            wantsLong -> "long"
            wantsShort -> "short"
            else -> null // koi filter nahi - latest ho ya normal search, YouTube relevance decide karega
        }
        val order = if (wantsLatest) "date" else "relevance"
        val maxResults = if (wantsLatest) 1 else 5

        var result = fetchFirstVideo(buildUrl(durationFilter, order, maxResults))

        // Agar kuch na mila (jaise koi bahut niche topic ho), to bina channel-restriction ke bhi
        // ek dobara try - kuch na milne se behtar hai koi bhi relevant video mil jaana.
        if (result == null && channelId != null) {
            result = fetchFirstVideo(
                "https://www.googleapis.com/youtube/v3/search?part=snippet&type=video" +
                    "&order=relevance&maxResults=1&q=$q&key=$apiKey"
            )
        }

        return result
    }

    private fun fetchFirstVideo(url: String): VideoResult? {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: return null
            val json = JSONObject(body)
            val items = json.optJSONArray("items") ?: return null
            if (items.length() == 0) return null
            val item = items.getJSONObject(0)
            val videoId = item.getJSONObject("id").getString("videoId")
            val title = item.getJSONObject("snippet").getString("title")
            return VideoResult(videoId, title)
        }
    }

    /** Opens a given video ID in the YouTube app (or browser fallback). */
    fun playVideo(context: Context, videoId: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$videoId")).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }

    data class VideoResult(val videoId: String, val title: String)
}
