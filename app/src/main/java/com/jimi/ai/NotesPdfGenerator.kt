package com.jimi.ai

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Saare saved notes ko ek professional-looking PDF mein convert karta hai —
 * title, generation-date, aur har note ke saath uska apna timestamp. */
object NotesPdfGenerator {

    private const val PAGE_WIDTH = 595
    private const val PAGE_HEIGHT = 842
    private const val MARGIN = 40f

    fun generate(context: Context, notes: List<Note>): File {
        val pdfDocument = PdfDocument()
        var pageNumber = 1
        var page = pdfDocument.startPage(
            PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create()
        )
        var canvas = page.canvas

        val titlePaint = Paint().apply { textSize = 22f; isFakeBoldText = true; color = Color.BLACK }
        val subtitlePaint = Paint().apply { textSize = 11f; color = Color.GRAY }
        val bodyPaint = Paint().apply { textSize = 13f; color = Color.BLACK }
        val dateTagPaint = Paint().apply { textSize = 9f; color = Color.GRAY }

        var y = 50f
        canvas.drawText("Jimi Notes", MARGIN, y, titlePaint)
        y += 22f
        val genDate = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date())
        canvas.drawText("Generated on: $genDate", MARGIN, y, subtitlePaint)
        y += 15f
        canvas.drawText("Total notes: ${notes.size}", MARGIN, y, subtitlePaint)
        y += 30f

        val maxWidth = PAGE_WIDTH - (MARGIN * 2)

        notes.forEachIndexed { index, note ->
            val noteDate = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()).format(Date(note.timestamp))
            val heading = "${index + 1}. "
            val lines = wrapText(heading + note.content, bodyPaint, maxWidth)

            lines.forEachIndexed { lineIdx, line ->
                if (y > PAGE_HEIGHT - 60f) {
                    pdfDocument.finishPage(page)
                    pageNumber++
                    page = pdfDocument.startPage(
                        PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create()
                    )
                    canvas = page.canvas
                    y = 50f
                }
                canvas.drawText(line, MARGIN, y, bodyPaint)
                y += 18f
            }
            canvas.drawText(noteDate, MARGIN + 14f, y, dateTagPaint)
            y += 22f
        }

        pdfDocument.finishPage(page)

        val fileName = "Jimi_Notes_${System.currentTimeMillis()}.pdf"
        val file = File(context.getExternalFilesDir(null), fileName)
        pdfDocument.writeTo(FileOutputStream(file))
        pdfDocument.close()
        return file
    }

    /** File already device pe save ho chuki hoti hai (Android/data/com.jimi.ai/files/ mein) —
     * ye function seedha share-sheet khol deta hai (WhatsApp, Drive, Files app, kahin bhi). */
    fun shareFile(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooser = Intent.createChooser(intent, "Notes PDF share/download karo").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }

    private fun wrapText(text: String, paint: Paint, maxWidth: Float): List<String> {
        val words = text.split(" ")
        val lines = mutableListOf<String>()
        var currentLine = StringBuilder()
        for (word in words) {
            val testLine = if (currentLine.isEmpty()) word else "$currentLine $word"
            if (paint.measureText(testLine) > maxWidth) {
                lines.add(currentLine.toString())
                currentLine = StringBuilder(word)
            } else {
                currentLine = StringBuilder(testLine)
            }
        }
        if (currentLine.isNotEmpty()) lines.add(currentLine.toString())
        return lines
    }
}
