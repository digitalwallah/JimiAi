package com.jimi.ai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DocumentImportActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var btnPick: Button

    private val pickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> if (uri != null) processDocument(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_document_import)

        tvStatus = findViewById(R.id.tvImportStatus)
        progressBar = findViewById(R.id.progressImport)
        btnPick = findViewById(R.id.btnPickDocument)
        progressBar.visibility = android.view.View.GONE

        btnPick.setOnClickListener { pickerLauncher.launch("*/*") }
    }

    private fun processDocument(uri: Uri) {
        btnPick.isEnabled = false
        progressBar.visibility = android.view.View.VISIBLE
        tvStatus.text = "Content padh raha hoon..."

        lifecycleScope.launch {
            val extractedText = withContext(Dispatchers.IO) {
                try {
                    val mimeType = contentResolver.getType(uri) ?: ""
                    if (mimeType.contains("pdf") || uri.toString().endsWith(".pdf", ignoreCase = true)) {
                        extractTextFromPdf(uri)
                    } else {
                        extractTextFromImage(uri)
                    }
                } catch (e: Exception) { "" }
            }

            if (extractedText.isBlank()) {
                tvStatus.text = "Is file se koi text nahi nikal paaya. Doosri file try karo."
                progressBar.visibility = android.view.View.GONE
                btnPick.isEnabled = true
                return@launch
            }

            tvStatus.text = "Naya professional PDF ban raha hai..."

            val claude = ClaudeApiClient(SettingsStore.getClaudeKey(this@DocumentImportActivity))
            val docJson = withContext(Dispatchers.IO) {
                claude.generateStructuredDocument("Imported Document", extractedText)
            }
            val parsed = DocumentBlockParser.parse(docJson)

            if (parsed.blocks.isEmpty()) {
                tvStatus.text = "PDF banane mein dikkat aa gayi, dobara try karo."
                progressBar.visibility = android.view.View.GONE
                btnPick.isEnabled = true
                return@launch
            }

            val file = withContext(Dispatchers.IO) {
                AdvancedPdfGenerator.generate(this@DocumentImportActivity, parsed.title, parsed.blocks)
            }

            AdvancedPdfGenerator.shareFile(this@DocumentImportActivity, file)
            tvStatus.text = "Naya PDF ready hai — Share/Download screen khul gayi hai"
            progressBar.visibility = android.view.View.GONE
            btnPick.isEnabled = true
        }
    }

    private suspend fun extractTextFromImage(uri: Uri): String {
        val bitmap = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: return ""
        return ScreenOcrHelper.recognizeText(bitmap)
    }

    private suspend fun extractTextFromPdf(uri: Uri): String {
        val pfd: ParcelFileDescriptor = contentResolver.openFileDescriptor(uri, "r") ?: return ""
        val renderer = PdfRenderer(pfd)
        val sb = StringBuilder()
        val maxPages = minOf(renderer.pageCount, 25)

        for (i in 0 until maxPages) {
            val page = renderer.openPage(i)
            val scale = 2
            val bitmap = Bitmap.createBitmap(page.width * scale, page.height * scale, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            val pageText = ScreenOcrHelper.recognizeText(bitmap)
            if (pageText.isNotBlank()) sb.append(pageText).append("\n\n")
            page.close()
        }
        renderer.close()
        pfd.close()
        return sb.toString().trim()
    }
}
