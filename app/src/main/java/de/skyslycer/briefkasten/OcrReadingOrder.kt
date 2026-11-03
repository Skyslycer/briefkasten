package de.skyslycer.briefkasten

import kotlin.math.*

data class OcrLine(val text: String, val left: Double, val top: Double, val right: Double,
    val bottom: Double, val angle: Double = 0.0)

/** Page order rather than recognizer block order; a sender line may span multiple blocks. */
fun orderedOcrText(lines: List<OcrLine>): String {
    val usable = lines.filter { it.text.isNotBlank() }
    if (usable.isEmpty()) return ""
    val angles = usable.filter { it.right - it.left > (it.bottom - it.top) * 3 && abs(it.angle) <= 10 }
        .map { it.angle }.sorted()
    val slope = tan(Math.toRadians(angles.getOrNull(angles.size / 2) ?: 0.0))
    fun center(line: OcrLine) = (line.top + line.bottom) / 2 - slope * (line.left + line.right) / 2
    data class Row(val anchor: OcrLine, val lines: MutableList<OcrLine>)
    val rows = mutableListOf<Row>()
    for (line in usable.sortedWith(compareBy<OcrLine> { center(it) }.thenBy { it.left })) {
        val row = rows.lastOrNull()
        val tolerance = if (row == null) 0.0 else .45 * min(line.bottom - line.top, row.anchor.bottom - row.anchor.top)
        if (row != null && abs(center(line) - center(row.anchor)) <= tolerance) row.lines.add(line)
        else rows.add(Row(line, mutableListOf(line)))
    }
    return rows.joinToString("\n") { row -> row.lines.sortedBy { it.left }.joinToString(" ") { it.text.trim() } }
}
