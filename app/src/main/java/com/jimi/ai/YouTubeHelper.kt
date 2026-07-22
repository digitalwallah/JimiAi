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
     * By default excludes YouTube Shorts (videoDuration=medium, i.e. 4-20 min) and sorts
     * by relevance (not just newest upload) so the actual best-matching video comes back,
     * not just whatever the channel posted most recently (which is often a Short). If the
     * user's query explicitly asks for the "latest"/"newest" video, we switch to date-order
     * and drop the duration filter (since a fresh upload might genuinely be short). */
    fun findVideo(query: String, channelName: String? = null): VideoResult? {
        val channelId = channelName?.let { findChannelId(it) }
        val wantsLatest = Regex("latest|newest|naya|nayi|abhi ka", RegexOption.IGNORE_CASE).containsMatchIn(query)
        val q = URLEncoder.encode(query.ifBlank { "latest video" }, "UTF-8")

        fun buildUrl(useDurationFilter: Boolean, order: String, maxResults: Int): String {
            var url = "https://www.googleapis.com/youtube/v3/search?part=snippet&type=video" +
                "&order=$order&maxResults=$maxResults&q=$q&key=$apiKey"
            if (channelId != null) url += "&channelId=$channelId"
            if (useDurationFilter) url += "&videoDuration=medium"
            return url
        }

        // Pehli koshish: agar "latest" nahi maanga, to relevance-order + shorts-excluded results try karo.
        val primaryUrl = if (wantsLatest) buildUrl(useDurationFilter = false, order = "date", maxResults = 1)
                          else buildUrl(useDurationFilter = true, order = "relevance", maxResults = 5)

        var result = fetchFirstVideo(primaryUrl)

        // Agar duration-filtered search mein kuch na mila (chhota/niche topic ho sakta hai jahan
        // sirf shorts hi available hon), to bina filter ke dobara try karo - kuch na milne se
        // behtar hai koi bhi relevant video mil jaana.
        if (result == null && !wantsLatest) {
            result = fetchFirstVideo(buildUrl(useDurationFilter = false, order = "relevance", maxResults = 1))
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
