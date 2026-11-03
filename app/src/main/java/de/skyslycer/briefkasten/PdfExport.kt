package de.skyslycer.briefkasten

import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.time.ZoneId
import java.time.LocalDate
import kotlin.coroutines.coroutineContext
import kotlin.math.min

enum class PdfImages(val label: String, val cutouts: List<Boolean>) {
    ORIGINALS("Originals", listOf(false)), CUTOUTS("Cutouts", listOf(true)), BOTH("Both", listOf(false, true))
}
data class SharedPdfs(val files: List<File>, val envelopes: Int)

class PdfExport(private val app: BriefkastenApp) {
    suspend fun prepareShare(period: ExportPeriod, images: PdfImages, separate: Boolean): SharedPdfs = withContext(Dispatchers.IO) {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val letters = filterExport(app.database.archive().all(), period, today, zone)
            .sortedWith(compareBy<Letter> { announcementDate(it, zone) }.thenBy { it.id })
        if (letters.isEmpty()) throw EmptyExport()
        val folder = newExportFolder(app.cacheDir)
        val range = exportMonthRange(period, letters, today, zone)
        val files = mutableListOf<File>()
        try {
            val batches = if (separate) letters.map { listOf(it) } else listOf(letters)
            for ((index, batch) in batches.withIndex()) {
                coroutineContext.ensureActive()
                val name = if (separate) "${announcementDate(batch.single(), zone)}_Briefkasten_${range}_letter_${(index + 1).toString().padStart(5, '0')}.pdf"
                    else "Briefkasten_$range.pdf"
                val file = File(folder, name)
                val pdf = EnvelopePdf(app)
                try {
                    for ((letterIndex, letter) in batch.withIndex()) {
                        val views = mutableListOf<PdfView>()
                        try {
                            for (cutout in images.cutouts) {
                                coroutineContext.ensureActive()
                                val bytes = if (cutout) app.cutouts.bytes(letter) else app.vault.read(letter.fileName) ?: error("Archived image missing")
                                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("Unreadable image")
                                views.add(PdfView(bitmap, "", cutout))
                                val readings = app.groups.recognizedText(letter, cutout)
                                views[views.lastIndex] = PdfView(bitmap, readings.displayText, cutout)
                            }
                            pdf.addViews(letter, views, letterIndex + 1, batch.size)
                        } finally { views.forEach { it.bitmap.recycle() } }
                    }
                    file.outputStream().buffered().use { pdf.writeTo(it) }
                } finally { pdf.close() }
                files.add(file)
            }
            SharedPdfs(files, letters.size)
        } catch (e: Exception) {
            folder.deleteRecursively()
            throw e
        }
    }
}

/** Full-resolution image plus a visible, extractable transcription. OCR never becomes a bitmap. */
data class PdfView(val bitmap: Bitmap, val text: String, val cutout: Boolean)

class EnvelopePdf(context: android.content.Context? = null) : AutoCloseable {
    private val logo = context?.let { PdfLogo(it) }
    private val document = PdfDocument()
    private var pages = 0
    private val ink = Color.rgb(32, 38, 31)
    private val green = Color.rgb(56, 101, 75)
    private val body = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; textSize = 11f; typeface = Typeface.create("sans-serif", Typeface.NORMAL) }

    fun add(letter: Letter, bitmap: Bitmap, text: String, cutout: Boolean, index: Int, count: Int) {
        addViews(letter, listOf(PdfView(bitmap, text, cutout)), index, count)
    }

    fun addViews(letter: Letter, views: List<PdfView>, index: Int, count: Int) {
        require(views.isNotEmpty())
        val date = announcementDate(letter, ZoneId.systemDefault()).toString()
        val source = views.joinToString(" + ") { if (it.cutout) "Cutout" else "Full image" }
        val columnWidth = if (views.size > 1) 249 else 523
        val layouts = views.map { view ->
            val text = view.text.ifBlank { "No text detected." }
            StaticLayout.Builder.obtain(text, 0, text.length, body, columnWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).setLineSpacing(3f, 1f).build()
        }
        val lines = IntArray(views.size)
        var first = true
        while (layouts.indices.any { lines[it] < layouts[it].lineCount }) {
            val page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, ++pages).create())
            try {
                val canvas = page.canvas
                canvas.drawColor(Color.WHITE)
                val heading = Paint(body).apply { textSize = 18f; typeface = Typeface.create("sans-serif", Typeface.BOLD) }
                canvas.drawText("Received: $date", 36f, 52f, heading)
                val label = Paint(body).apply { textSize = 9f; color = green }
                logo?.draw(canvas)
                if (parsedAnnouncementDate(letter.date) == null)
                    canvas.drawText("First saved date (announcement unavailable)", 36f, 72f, label)
                var textTop = 115f
                if (first) {
                    var top = 100f
                    for (view in views) {
                        val badge = Paint(label).apply { textSize = 10f; typeface = Typeface.create("sans-serif", Typeface.BOLD) }
                        canvas.drawRoundRect(RectF(36f, top - 14f, 559f, top + 6f), 4f, 4f,
                            Paint().apply { color = Color.rgb(234, 240, 231) })
                        canvas.drawText(if (view.cutout) "CUTOUT" else "FULL IMAGE", 44f, top, badge)
                        top += 18f
                        val bitmap = view.bitmap
                        val scale = min(523f / bitmap.width, (if (views.size > 1) 160f else 300f) / bitmap.height)
                        val width = bitmap.width * scale; val height = bitmap.height * scale
                        val left = (595f - width) / 2
                        canvas.drawBitmap(bitmap, null, RectF(left, top, left + width, top + height),
                            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
                        top += height + 28f
                    }
                    textTop = top + 12f
                }
                layouts.forEachIndexed { column, layout ->
                    val left = 36f + column * 274f
                    canvas.drawText("${if (views[column].cutout) "CUTOUT" else "FULL IMAGE"}", left, textTop - 12f, label)
                    val line = lines[column]
                    if (line < layout.lineCount) {
                        val start = layout.getLineTop(line)
                        var end = line + 1
                        while (end < layout.lineCount && layout.getLineBottom(end) - start <= 786f - textTop) end++
                        canvas.save()
                        canvas.clipRect(left, textTop, left + columnWidth, textTop + layout.getLineTop(end) - start)
                        canvas.translate(left, textTop - start)
                        layout.draw(canvas)
                        canvas.restore()
                        lines[column] = end
                    }
                }
                canvas.drawLine(36f, 802f, 559f, 802f, Paint().apply { color = Color.LTGRAY; strokeWidth = .5f })
                canvas.drawText("Envelope $index of $count  |  $source  |  Page $pages", 36f, 818f, label)
                val wordmark = TextPaint(label).apply { textSize = 8f; letterSpacing = .25f }
                canvas.drawText("BRIEFKASTEN", 559f - wordmark.measureText("BRIEFKASTEN"), 818f, wordmark)
                first = false
            } finally { document.finishPage(page) }
        }
    }

    fun writeTo(stream: java.io.OutputStream) = document.writeTo(stream)
    override fun close() = document.close()
}
