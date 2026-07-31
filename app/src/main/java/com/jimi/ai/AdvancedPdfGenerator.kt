package com.jimi.ai

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val PAGE_WIDTH = 595
private const val PAGE_HEIGHT = 842
private const val MARGIN = 45f
private const val CONTENT_WIDTH = (PAGE_WIDTH - MARGIN * 2)

private val COLOR_TEXT_DARK = Color.parseColor("#0F172A")
private val COLOR_TEXT_GRAY = Color.parseColor("#64748B")
private val COLOR_ACCENT = Color.parseColor("#0891B2")
private val COLOR_ACCENT_LIGHT = Color.parseColor("#E0F7FA")
private val COLOR_TABLE_HEADER_BG = Color.parseColor("#0891B2")
private val COLOR_TABLE_ROW_ALT = Color.parseColor("#F1F5F9")
private val COLOR_BORDER = Color.parseColor("#CBD5E1")

private val CHART_PALETTE = listOf(
    "#0891B2", "#F59E0B", "#EF4444", "#8B5CF6", "#10B981", "#EC4899", "#6366F1", "#F97316"
)

/** Ek page ki drawing-state manage karta hai — jab bhi space kam pade, khud naya page bana leta hai. */
private class PdfPainter(private val pdfDocument: PdfDocument, private val docTitle: String) {
    var pageNumber = 1
    var page: PdfDocument.Page =
        pdfDocument.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create())
    var canvas: Canvas = page.canvas
    var y: Float = 0f

    init { drawHeader() }

    private fun drawHeader() {
        val headerPaint = Paint().apply { textSize = 8.5f; color = COLOR_TEXT_GRAY }
        val shownTitle = if (docTitle.length > 45) docTitle.take(45) + "…" else docTitle
        canvas.drawText(shownTitle, MARGIN, 28f, headerPaint)
        val pageLabelPaint = Paint().apply { textSize = 8.5f; color = COLOR_TEXT_GRAY; textAlign = Paint.Align.RIGHT }
        canvas.drawText("Page $pageNumber", MARGIN + CONTENT_WIDTH, 28f, pageLabelPaint)
        canvas.drawLine(MARGIN, 34f, MARGIN + CONTENT_WIDTH, 34f, Paint().apply { color = COLOR_BORDER; strokeWidth = 0.8f })
        y = 55f
    }

    fun ensureSpace(needed: Float) {
        if (y + needed > PAGE_HEIGHT - 45f) {
            pdfDocument.finishPage(page)
            pageNumber++
            page = pdfDocument.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create())
            canvas = page.canvas
            drawHeader()
        }
    }

    fun finish() { pdfDocument.finishPage(page) }
}

/** Structured content blocks (headings, paragraphs, tables, bar/line/pie charts, flowcharts)
 * se ek professional-looking, multi-page PDF banata hai — poora native Canvas drawing se,
 * koi third-party chart library nahi chahiye. */
object AdvancedPdfGenerator {

    fun generate(context: Context, docTitle: String, blocks: List<ContentBlock>): File {
        val pdfDocument = PdfDocument()
        val painter = PdfPainter(pdfDocument, docTitle)

        drawCoverTitle(painter, docTitle)

        blocks.forEach { block ->
            when (block) {
                is ContentBlock.Heading -> drawHeading(painter, block)
                is ContentBlock.Paragraph -> drawParagraph(painter, block)
                is ContentBlock.BulletList -> drawBulletList(painter, block)
                is ContentBlock.NumberedList -> drawNumberedList(painter, block)
                is ContentBlock.Table -> drawTable(painter, block)
                is ContentBlock.BarChart -> drawBarChart(painter, block)
                is ContentBlock.LineChart -> drawLineChart(painter, block)
                is ContentBlock.PieChart -> drawPieChart(painter, block)
                is ContentBlock.Flowchart -> drawFlowchart(painter, block)
                is ContentBlock.Divider -> {
                    painter.ensureSpace(20f)
                    painter.canvas.drawLine(MARGIN, painter.y, MARGIN + CONTENT_WIDTH, painter.y, Paint().apply { color = COLOR_BORDER })
                    painter.y += 20f
                }
            }
        }

        painter.finish()

        val safeName = docTitle.take(25).replace(Regex("[^A-Za-z0-9]"), "_")
        val fileName = "Jimi_${safeName}_${System.currentTimeMillis()}.pdf"
        val file = File(context.getExternalFilesDir(null), fileName)
        pdfDocument.writeTo(FileOutputStream(file))
        pdfDocument.close()
        return file
    }

    fun shareFile(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooser = Intent.createChooser(intent, "PDF share/download karo").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }
}

private fun drawCoverTitle(p: PdfPainter, title: String) {
    val titlePaint = Paint().apply { textSize = 22f; isFakeBoldText = true; color = COLOR_TEXT_DARK }
    val lines = wrapText(title, titlePaint, CONTENT_WIDTH)
    p.ensureSpace(lines.size * 28f + 40f)
    lines.forEach { line ->
        p.canvas.drawText(line, MARGIN, p.y + 22f, titlePaint)
        p.y += 28f
    }
    val subtitlePaint = Paint().apply { textSize = 10.5f; color = COLOR_TEXT_GRAY }
    val dateStr = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date())
    p.canvas.drawText("Generated by Jimi • $dateStr", MARGIN, p.y + 8f, subtitlePaint)
    p.y += 20f
    p.canvas.drawRect(MARGIN, p.y, MARGIN + CONTENT_WIDTH, p.y + 2.5f, Paint().apply { color = COLOR_ACCENT })
    p.y += 24f
}

private fun drawHeading(p: PdfPainter, block: ContentBlock.Heading) {
    val size = when (block.level) { 1 -> 17f; 2 -> 14.5f; else -> 13f }
    val paint = Paint().apply {
        textSize = size; isFakeBoldText = true
        color = if (block.level == 1) COLOR_ACCENT else COLOR_TEXT_DARK
    }
    val lines = wrapText(block.text, paint, CONTENT_WIDTH)
    val lineHeight = size + 6f
    p.ensureSpace(lines.size * lineHeight + 18f)
    p.y += 12f
    lines.forEach { line ->
        p.canvas.drawText(line, MARGIN, p.y, paint)
        p.y += lineHeight
    }
    if (block.level == 1) {
        p.canvas.drawRect(MARGIN, p.y - lineHeight + 4f, MARGIN + 40f, p.y - lineHeight + 7f, Paint().apply { color = COLOR_ACCENT })
    }
    p.y += 6f
}

private fun drawParagraph(p: PdfPainter, block: ContentBlock.Paragraph) {
    val paint = Paint().apply { textSize = 11.5f; color = COLOR_TEXT_DARK }
    val lines = wrapText(block.text, paint, CONTENT_WIDTH)
    lines.forEach { line ->
        p.ensureSpace(16f)
        p.canvas.drawText(line, MARGIN, p.y, paint)
        p.y += 16f
    }
    p.y += 8f
}

private fun drawBulletList(p: PdfPainter, block: ContentBlock.BulletList) {
    val paint = Paint().apply { textSize = 11.5f; color = COLOR_TEXT_DARK }
    val indent = 14f
    block.items.forEach { item ->
        val lines = wrapText(item, paint, CONTENT_WIDTH - indent)
        lines.forEachIndexed { idx, line ->
            p.ensureSpace(16f)
            if (idx == 0) p.canvas.drawText("•", MARGIN, p.y, paint)
            p.canvas.drawText(line, MARGIN + indent, p.y, paint)
            p.y += 16f
        }
    }
    p.y += 6f
}

private fun drawNumberedList(p: PdfPainter, block: ContentBlock.NumberedList) {
    val paint = Paint().apply { textSize = 11.5f; color = COLOR_TEXT_DARK }
    val indent = 18f
    block.items.forEachIndexed { i, item ->
        val lines = wrapText(item, paint, CONTENT_WIDTH - indent)
        lines.forEachIndexed { idx, line ->
            p.ensureSpace(16f)
            if (idx == 0) p.canvas.drawText("${i + 1}.", MARGIN, p.y, paint)
            p.canvas.drawText(line, MARGIN + indent, p.y, paint)
            p.y += 16f
        }
    }
    p.y += 6f
}

private fun drawTable(p: PdfPainter, block: ContentBlock.Table) {
    if (block.headers.isEmpty()) return
    val cols = block.headers.size
    val colWidth = CONTENT_WIDTH / cols
    val headerPaint = Paint().apply { textSize = 10.5f; isFakeBoldText = true; color = Color.WHITE }
    val cellPaint = Paint().apply { textSize = 10f; color = COLOR_TEXT_DARK }
    val padding = 6f
    val lineHeight = 13f

    val headerLines = block.headers.map { wrapText(it, headerPaint, colWidth - 12f) }
    val headerRowHeight = (headerLines.maxOf { it.size }) * lineHeight + padding * 2
    p.ensureSpace(headerRowHeight)
    p.canvas.drawRect(MARGIN, p.y, MARGIN + CONTENT_WIDTH, p.y + headerRowHeight, Paint().apply { color = COLOR_TABLE_HEADER_BG })
    headerLines.forEachIndexed { i, lines ->
        var ty = p.y + padding + lineHeight - 3f
        lines.forEach { line -> p.canvas.drawText(line, MARGIN + i * colWidth + 6f, ty, headerPaint); ty += lineHeight }
    }
    p.y += headerRowHeight

    block.rows.forEachIndexed { rIdx, row ->
        val cellLines = row.map { wrapText(it, cellPaint, colWidth - 12f) }
        val rowHeight = (cellLines.maxOfOrNull { it.size } ?: 1) * lineHeight + padding * 2
        p.ensureSpace(rowHeight)
        if (rIdx % 2 == 1) {
            p.canvas.drawRect(MARGIN, p.y, MARGIN + CONTENT_WIDTH, p.y + rowHeight, Paint().apply { color = COLOR_TABLE_ROW_ALT })
        }
        cellLines.forEachIndexed { i, lines ->
            var ty = p.y + padding + lineHeight - 3f
            lines.forEach { line -> p.canvas.drawText(line, MARGIN + i * colWidth + 6f, ty, cellPaint); ty += lineHeight }
        }
        val borderPaint = Paint().apply { color = COLOR_BORDER; strokeWidth = 0.7f }
        for (c in 0..cols) {
            val x = MARGIN + c * colWidth
            p.canvas.drawLine(x, p.y, x, p.y + rowHeight, borderPaint)
        }
        p.canvas.drawLine(MARGIN, p.y + rowHeight, MARGIN + CONTENT_WIDTH, p.y + rowHeight, borderPaint)
        p.y += rowHeight
    }
    p.y += 10f
}

private fun drawBarChart(p: PdfPainter, block: ContentBlock.BarChart) {
    if (block.values.isEmpty()) return
    val chartHeight = 160f
    val titleHeight = if (block.title.isNotBlank()) 20f else 0f
    p.ensureSpace(chartHeight + titleHeight + 30f)
    if (block.title.isNotBlank()) {
        p.canvas.drawText(block.title, MARGIN, p.y + 12f, Paint().apply { textSize = 12.5f; isFakeBoldText = true; color = COLOR_TEXT_DARK })
        p.y += titleHeight
    }
    val chartBottom = p.y + chartHeight
    p.canvas.drawLine(MARGIN, chartBottom, MARGIN + CONTENT_WIDTH, chartBottom, Paint().apply { color = COLOR_BORDER; strokeWidth = 1f })
    val maxVal = block.values.maxOrNull()?.takeIf { it > 0 } ?: 1f
    val n = block.values.size
    val gap = 10f
    val barWidth = (CONTENT_WIDTH - gap * (n + 1)) / n
    val barPaint = Paint().apply { color = COLOR_ACCENT }
    val labelPaint = Paint().apply { textSize = 8.5f; color = COLOR_TEXT_GRAY; textAlign = Paint.Align.CENTER }
    val valuePaint = Paint().apply { textSize = 8.5f; isFakeBoldText = true; color = COLOR_TEXT_DARK; textAlign = Paint.Align.CENTER }

    block.values.forEachIndexed { i, v ->
        val barHeight = (v / maxVal) * (chartHeight - 20f)
        val left = MARGIN + gap + i * (barWidth + gap)
        val top = chartBottom - barHeight
        p.canvas.drawRect(left, top, left + barWidth, chartBottom, barPaint)
        p.canvas.drawText(formatNum(v), left + barWidth / 2f, top - 4f, valuePaint)
        p.canvas.drawText(block.labels.getOrElse(i) { "" }, left + barWidth / 2f, chartBottom + 12f, labelPaint)
    }
    p.y = chartBottom + 24f
}

private fun drawLineChart(p: PdfPainter, block: ContentBlock.LineChart) {
    if (block.values.isEmpty()) return
    val chartHeight = 160f
    val titleHeight = if (block.title.isNotBlank()) 20f else 0f
    p.ensureSpace(chartHeight + titleHeight + 30f)
    if (block.title.isNotBlank()) {
        p.canvas.drawText(block.title, MARGIN, p.y + 12f, Paint().apply { textSize = 12.5f; isFakeBoldText = true; color = COLOR_TEXT_DARK })
        p.y += titleHeight
    }
    val chartBottom = p.y + chartHeight
    p.canvas.drawLine(MARGIN, chartBottom, MARGIN + CONTENT_WIDTH, chartBottom, Paint().apply { color = COLOR_BORDER; strokeWidth = 1f })
    val maxVal = block.values.maxOrNull()?.takeIf { it > 0 } ?: 1f
    val minVal = minOf(0f, block.values.minOrNull() ?: 0f)
    val n = block.values.size
    val stepX = if (n > 1) CONTENT_WIDTH / (n - 1) else 0f
    val linePaint = Paint().apply { color = COLOR_ACCENT; strokeWidth = 2.5f; style = Paint.Style.STROKE }
    val dotPaint = Paint().apply { color = COLOR_ACCENT }
    val labelPaint = Paint().apply { textSize = 8.5f; color = COLOR_TEXT_GRAY; textAlign = Paint.Align.CENTER }

    val points = block.values.mapIndexed { i, v ->
        val x = MARGIN + i * stepX
        val ratio = (v - minVal) / (maxVal - minVal).coerceAtLeast(0.001f)
        val y = chartBottom - ratio * (chartHeight - 20f)
        Pair(x, y)
    }
    for (i in 0 until points.size - 1) {
        p.canvas.drawLine(points[i].first, points[i].second, points[i + 1].first, points[i + 1].second, linePaint)
    }
    points.forEachIndexed { i, (x, y) ->
        p.canvas.drawCircle(x, y, 3.5f, dotPaint)
        p.canvas.drawText(block.labels.getOrElse(i) { "" }, x, chartBottom + 12f, labelPaint)
    }
    p.y = chartBottom + 24f
}

private fun drawPieChart(p: PdfPainter, block: ContentBlock.PieChart) {
    if (block.values.isEmpty()) return
    val diameter = 140f
    val titleHeight = if (block.title.isNotBlank()) 20f else 0f
    val legendHeight = block.values.size * 14f
    p.ensureSpace(maxOf(diameter, legendHeight) + titleHeight + 24f)
    if (block.title.isNotBlank()) {
        p.canvas.drawText(block.title, MARGIN, p.y + 12f, Paint().apply { textSize = 12.5f; isFakeBoldText = true; color = COLOR_TEXT_DARK })
        p.y += titleHeight
    }
    val total = block.values.sum().takeIf { it > 0 } ?: 1f
    val rect = RectF(MARGIN, p.y, MARGIN + diameter, p.y + diameter)
    var startAngle = -90f
    val legendX = MARGIN + diameter + 24f
    var legendY = p.y + 10f
    block.values.forEachIndexed { i, v ->
        val sweep = (v / total) * 360f
        val slicePaint = Paint().apply { color = Color.parseColor(CHART_PALETTE[i % CHART_PALETTE.size]) }
        p.canvas.drawArc(rect, startAngle, sweep, true, slicePaint)
        startAngle += sweep

        p.canvas.drawRect(legendX, legendY - 8f, legendX + 8f, legendY, slicePaint)
        val pct = String.format("%.0f", v / total * 100)
        p.canvas.drawText("${block.labels.getOrElse(i) { "" }} ($pct%)", legendX + 12f, legendY, Paint().apply { textSize = 9.5f; color = COLOR_TEXT_DARK })
        legendY += 14f
    }
    p.y += maxOf(diameter, legendHeight) + 20f
}

private fun drawFlowchart(p: PdfPainter, block: ContentBlock.Flowchart) {
    if (block.steps.isEmpty()) return
    if (block.title.isNotBlank()) {
        p.ensureSpace(30f)
        p.canvas.drawText(block.title, MARGIN, p.y + 12f, Paint().apply { textSize = 12.5f; isFakeBoldText = true; color = COLOR_TEXT_DARK })
        p.y += 20f
    }
    val boxWidth = CONTENT_WIDTH * 0.8f
    val boxX = MARGIN + (CONTENT_WIDTH - boxWidth) / 2f
    val textPaint = Paint().apply { textSize = 10.5f; color = COLOR_TEXT_DARK; textAlign = Paint.Align.CENTER }
    val boxFillPaint = Paint().apply { color = COLOR_ACCENT_LIGHT }
    val boxBorderPaint = Paint().apply { color = COLOR_ACCENT; style = Paint.Style.STROKE; strokeWidth = 1.5f }
    val arrowPaint = Paint().apply { color = COLOR_TEXT_GRAY; strokeWidth = 1.5f }

    block.steps.forEachIndexed { index, step ->
        val lines = wrapText(step, textPaint, boxWidth - 20f)
        val boxHeight = lines.size * 14f + 16f
        p.ensureSpace(boxHeight + 26f)
        val rect = RectF(boxX, p.y, boxX + boxWidth, p.y + boxHeight)
        p.canvas.drawRoundRect(rect, 8f, 8f, boxFillPaint)
        p.canvas.drawRoundRect(rect, 8f, 8f, boxBorderPaint)
        var ty = p.y + 16f + 10f - (lines.size - 1) * 7f
        lines.forEach { line -> p.canvas.drawText(line, boxX + boxWidth / 2f, ty, textPaint); ty += 14f }
        p.y += boxHeight
        if (index < block.steps.size - 1) {
            val arrowX = boxX + boxWidth / 2f
            p.canvas.drawLine(arrowX, p.y + 2f, arrowX, p.y + 18f, arrowPaint)
            val path = Path().apply {
                moveTo(arrowX - 4f, p.y + 14f); lineTo(arrowX, p.y + 20f); lineTo(arrowX + 4f, p.y + 14f); close()
            }
            p.canvas.drawPath(path, Paint().apply { color = COLOR_TEXT_GRAY })
            p.y += 22f
        } else {
            p.y += 10f
        }
    }
    p.y += 6f
}

private fun formatNum(v: Float): String =
    if (v == v.toLong().toFloat()) v.toLong().toString() else String.format("%.1f", v)

private fun wrapText(text: String, paint: Paint, maxWidth: Float): List<String> {
    val words = text.split(" ")
    val lines = mutableListOf<String>()
    var currentLine = StringBuilder()
    for (word in words) {
        val testLine = if (currentLine.isEmpty()) word else "$currentLine $word"
        if (paint.measureText(testLine) > maxWidth) {
            if (currentLine.isNotEmpty()) lines.add(currentLine.toString())
            currentLine = StringBuilder(word)
        } else {
            currentLine = StringBuilder(testLine)
        }
    }
    if (currentLine.isNotEmpty()) lines.add(currentLine.toString())
    return lines
}
