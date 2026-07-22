package com.jimi.ai

import android.content.Context
import android.provider.ContactsContract
import kotlin.math.max

object ContactResolver {

    /** Phone book me naam se dhoondh kar number return karta hai. Pehle exact/partial match try
     * karta hai (fast path). Agar wo fail ho (jaise STT ne naam thoda galat suna ho, spelling
     * off ho), to saare contacts fetch karke FUZZY MATCHING karta hai - jo bhi contact naam
     * sabse zyada milta-julta ho (chhoti spelling mistakes, extra/missing letters tolerate karke),
     * usko return karta hai. Isse "Yasin"/"Yasine"/"Yasean" jaisi STT galtiyan bhi sahi contact
     * dhoondh legi. */
    fun findPhoneNumberByName(context: Context, name: String): String? {
        exactMatch(context, name)?.let { return it }
        return fuzzyMatch(context, name)
    }

    private fun exactMatch(context: Context, name: String): String? {
        val resolver = context.contentResolver
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        val args = arrayOf("%$name%")

        resolver.query(uri, projection, selection, args, null)?.use { cursor ->
            val numberIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            if (cursor.moveToFirst()) {
                return cursor.getString(numberIdx)?.replace(" ", "")?.replace("-", "")
            }
        }
        return null
    }

    private fun fuzzyMatch(context: Context, name: String): String? {
        val resolver = context.contentResolver
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )

        var bestMatchNumber: String? = null
        var bestSimilarity = 0.0

        resolver.query(uri, projection, null, null, null)?.use { cursor ->
            val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (cursor.moveToNext()) {
                val contactName = cursor.getString(nameIdx) ?: continue
                val similarity = similarityRatio(name.lowercase(), contactName.lowercase())
                if (similarity > bestSimilarity) {
                    bestSimilarity = similarity
                    bestMatchNumber = cursor.getString(numberIdx)?.replace(" ", "")?.replace("-", "")
                }
            }
        }

        // 0.6 threshold - kaafi lenient hai spelling mistakes ke liye, lekin bilkul alag naam
        // (jaise "Rahul" bola aur "Priya" match ho jaye) ko avoid karta hai.
        return if (bestSimilarity >= 0.6) bestMatchNumber else null
    }

    /** 0.0 (bilkul alag) se 1.0 (hoobahoo same) tak similarity score - Levenshtein edit-distance
     * par based, taaki chhoti spelling mistakes (missing/extra/swapped letters) tolerate ho sakein. */
    private fun similarityRatio(a: String, b: String): Double {
        val distance = levenshteinDistance(a, b)
        val maxLen = max(a.length, b.length)
        if (maxLen == 0) return 1.0
        return 1.0 - (distance.toDouble() / maxLen.toDouble())
    }

    private fun levenshteinDistance(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                dp[i][j] = if (a[i - 1] == b[j - 1]) {
                    dp[i - 1][j - 1]
                } else {
                    1 + minOf(dp[i - 1][j], dp[i][j - 1], dp[i - 1][j - 1])
                }
            }
        }
        return dp[a.length][b.length]
    }
}
