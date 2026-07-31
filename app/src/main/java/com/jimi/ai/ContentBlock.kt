package com.jimi.ai

sealed class ContentBlock {
    data class Heading(val text: String, val level: Int = 1) : ContentBlock()
    data class Paragraph(val text: String) : ContentBlock()
    data class BulletList(val items: List<String>) : ContentBlock()
    data class NumberedList(val items: List<String>) : ContentBlock()
    data class Table(val headers: List<String>, val rows: List<List<String>>) : ContentBlock()
    data class BarChart(val title: String, val labels: List<String>, val values: List<Float>) : ContentBlock()
    data class LineChart(val title: String, val labels: List<String>, val values: List<Float>) : ContentBlock()
    data class PieChart(val title: String, val labels: List<String>, val values: List<Float>) : ContentBlock()
    data class Flowchart(val title: String, val steps: List<String>) : ContentBlock()
    object Divider : ContentBlock()
}
