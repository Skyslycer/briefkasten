package de.skyslycer.briefkasten

import org.json.JSONObject
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlin.math.*
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await

class GroupIndexer(private val app: BriefkastenApp) {
    private val mutex = Mutex()
    suspend fun recognizedText(letter: Letter, cutout: Boolean): EnvelopeReadings = withContext(Dispatchers.IO) {
        mutex.withLock { readText(letter, cutout) }
    }

    suspend fun index(letter: Letter): LetterGroup? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val full = readText(letter, cutout = false)
            val cropped = readText(letter, cutout = true)
            val dao = app.database.archive()
            matchingGroupReadings(listOfNotNull(full.original, full.enlarged, full.detail,
                cropped.original, cropped.enlarged, cropped.detail), dao.groups())
                .also { dao.assignGroup(letter.id, it?.id) }
        }
    }

    private suspend fun readText(letter: Letter, cutout: Boolean): EnvelopeReadings {
        val cache = "${letter.id}.ocr-v8-${if (cutout) "cutout" else "full"}"
        app.vault.read(cache)?.let { return EnvelopeReadings.fromJson(it.toString(Charsets.UTF_8)) }
        val bytes = if (cutout) app.cutouts.bytes(letter) else app.vault.read(letter.fileName) ?: error("Archived image missing")
        val original = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("Unreadable image")
        try {
            return recognizeEnvelopeReadings(original).also {
                app.vault.write(cache, it.toJson().toByteArray(Charsets.UTF_8))
            }
        } finally {
            original.recycle()
        }
    }
}

class GroupIndexWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as BriefkastenApp
        var failed = false
        for (letter in app.database.archive().all()) {
            try { app.groups.index(letter) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { failed = true }
        }
        return if (failed && runAttemptCount < 3) Result.retry() else if (failed) Result.failure() else Result.success()
    }
}

/** Display filtered 2x OCR; retain native/3x and large-image tile readings for matching. */
data class EnvelopeReadings(val original: String, val enlarged: String? = null, val detail: String? = null) {
    val displayText: String get() = enlarged?.takeIf { it.isNotBlank() } ?: original
    val combined: String get() = listOfNotNull(original, enlarged, detail).joinToString("\n\n")
    fun toJson(): String = JSONObject().put("original", original).put("enlarged", enlarged).put("detail", detail).toString()
    companion object {
        fun fromJson(value: String): EnvelopeReadings {
            val json = JSONObject(value)
            return EnvelopeReadings(json.getString("original"), if (json.has("enlarged")) json.getString("enlarged") else null,
                if (json.has("detail")) json.getString("detail") else null)
        }
    }
}

suspend fun recognizeEnvelopeText(crop: Bitmap): String = recognizeEnvelopeReadings(crop).combined

suspend fun recognizeEnvelopeReadings(crop: Bitmap): EnvelopeReadings {
    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    var enlarged: Bitmap? = null
    try {
        // Do not recycle a task's bitmap until ML Kit has finished reading it.
        return withContext(NonCancellable) {
            val native = recognizer.process(InputImage.fromBitmap(crop, 0)).await().pageText()
            var enhanced: String? = null
            val additional = mutableListOf<String>()
            for (scale in ocrScales(crop.width, crop.height)) {
                val scaled = Bitmap.createScaledBitmap(crop, crop.width * scale, crop.height * scale, true)
                enlarged = scaled
                val text = recognizer.process(InputImage.fromBitmap(scaled, 0)).await().pageText()
                if (scale == 2) enhanced = text else additional.add(text)
                scaled.recycle(); enlarged = null
            }
            // The native pass always receives every original pixel. For large scans,
            // also inspect overlapping native-resolution tiles to expose tiny print
            // independently of any recognizer-internal whole-image resizing.
            val detail = if (max(crop.width, crop.height) > 3200 || crop.width.toLong() * crop.height > 6_000_000) {
                val readings = mutableListOf<String>()
                for (tile in ocrTiles(crop.width, crop.height)) {
                    val bitmap = Bitmap.createBitmap(crop, tile.left, tile.top, tile.right - tile.left, tile.bottom - tile.top)
                    try {
                        recognizer.process(InputImage.fromBitmap(bitmap, 0)).await().pageText().takeIf { it.isNotBlank() }?.let(readings::add)
                    } finally { if (bitmap !== crop) bitmap.recycle() }
                }
                readings.joinToString("\n\n").takeIf { it.isNotBlank() }
            } else null
            detail?.let(additional::add)
            EnvelopeReadings(native, enhanced, additional.filter { it.isNotBlank() }.joinToString("\n\n").takeIf { it.isNotBlank() })
        }
    } finally { recognizer.close(); enlarged?.recycle() }
}

/** Preserve recognized words but reconstruct page rows from ML Kit geometry. */
private fun Text.pageText(): String {
    val lines = textBlocks.flatMap { it.lines }
    val positioned = lines.mapNotNull { line ->
        val box = line.boundingBox ?: return@mapNotNull null
        val corners = line.cornerPoints
        val angle = if (corners != null && corners.size >= 2) Math.toDegrees(atan2(
            (corners[1].y - corners[0].y).toDouble(), (corners[1].x - corners[0].x).toDouble())) else 0.0
        OcrLine(line.text, box.left.toDouble(), box.top.toDouble(), box.right.toDouble(), box.bottom.toDouble(), angle)
    }
    val unpositioned = lines.filter { it.boundingBox == null }.map { it.text }
    return (listOf(orderedOcrText(positioned)) + unpositioned).filter { it.isNotBlank() }.joinToString("\n")
}
