package com.jimi.ai

import org.json.JSONArray
import org.json.JSONObject

object DocumentBlockParser {

    data class ParsedDocument(val title: String, val blocks: List<ContentBlock>)

    fun parse(json: JSONObject): ParsedDocument {
        val title = json.optString("title").ifBlank { "Jimi Document" }
        val blocksArray = json.optJSONArray("blocks")
        val blocks = mutableListOf<ContentBlock>()
        if (blocksArray != null) {
            for (i in 0 until blocksArray.length()) {
                val b = blocksArray.optJSONObject(i) ?: continue
                parseBlock(b)?.let { blocks.add(it) }
            }
        }
        return ParsedDocument(title, blocks)
    }

    private fun parseBlock(b: JSONObject): ContentBlock? {
        return when (b.optString("type")) {
            "heading" -> ContentBlock.Heading(b.optString("text"), b.optInt("level", 1))
            "paragraph" -> ContentBlock.Paragraph(b.optString("text"))
            "bullet_list" -> ContentBlock.BulletList(strList(b.optJSONArray("items")))
            "numbered_list" -> ContentBlock.NumberedList(strList(b.optJSONArray("items")))
            "table" -> {
                val headers = strList(b.optJSONArray("headers"))
                val rowsArr = b.optJSONArray("rows")
                val rows = mutableListOf<List<String>>()
                if (rowsArr != null) {
                    for (i in 0 until rowsArr.length()) {
                        rowsArr.optJSONArray(i)?.let { rows.add(strList(it)) }
                    }
                }
                ContentBlock.Table(headers, rows)
            }
            "bar_chart" -> ContentBlock.BarChart(
                b.optString("title"), strList(b.optJSONArray("labels")), floatList(b.optJSONArray("values"))
            )
            "line_chart" -> ContentBlock.LineChart(
                b.optString("title"), strList(b.optJSONArray("labels")), floatList(b.optJSONArray("values"))
            )
            "pie_chart" -> ContentBlock.PieChart(
                b.optString("title"), strList(b.optJSONArray("labels")), floatList(b.optJSONArray("values"))
            )
            "flowchart" -> ContentBlock.Flowchart(b.optString("title"), strList(b.optJSONArray("steps")))
            "divider" -> ContentBlock.Divider
            else -> null
        }
    }

    private fun strList(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optString(it, null) }
    }

    private fun floatList(arr: JSONArray?): List<Float> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).map { arr.optDouble(it, 0.0).toFloat() }
    }
}
