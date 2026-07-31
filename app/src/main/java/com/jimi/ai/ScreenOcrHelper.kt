package com.jimi.ai

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine

/** Screenshot bitmap se text nikalta hai, poori tarah on-device (ML Kit) - koi cloud/API
 * key/billing nahi. Latin (English/numbers) aur Devanagari (Hindi script) dono recognizers
 * chalata hai taaki mixed-language screens (Hinglish articles, PDFs) bhi sahi se padhein ja sakein. */
object ScreenOcrHelper {

    suspend fun recognizeText(bitmap: Bitmap): String {
        val image = InputImage.fromBitmap(bitmap, 0)
        val latinText = runCatching { recognizeWithLatin(image) }.getOrDefault("")
        val devanagariText = runCatching { recognizeWithDevanagari(image) }.getOrDefault("")

        return listOf(latinText, devanagariText)
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString("\n")
            .trim()
    }

    private suspend fun recognizeWithLatin(image: InputImage): String =
        suspendCancellableCoroutine { cont ->
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            recognizer.process(image)
                .addOnSuccessListener { result -> cont.resume(result.text) {} }
                .addOnFailureListener { cont.resume("") {} }
        }

    private suspend fun recognizeWithDevanagari(image: InputImage): String =
        suspendCancellableCoroutine { cont ->
            val recognizer = TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
            recognizer.process(image)
                .addOnSuccessListener { result -> cont.resume(result.text) {} }
                .addOnFailureListener { cont.resume("") {} }
        }
}
