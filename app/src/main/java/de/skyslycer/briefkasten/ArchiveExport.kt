package de.skyslycer.briefkasten

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ArchiveExport(private val app: BriefkastenApp) {
    suspend fun prepareShare(period: ExportPeriod, images: PdfImages = PdfImages.ORIGINALS): SharedArchive = withContext(Dispatchers.IO) {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val letters = filterExport(app.database.archive().all(), period, today, zone)
        if (letters.isEmpty()) throw EmptyExport()
        val folder = newExportFolder(app.cacheDir)
        val file = File(folder, "Briefkasten_${exportMonthRange(period, letters, today, zone)}.zip")
        try {
            writeImageZip(file.outputStream().buffered(), letters, images) { letter, cutout ->
                if (cutout) app.cutouts.bytes(letter) else app.vault.read(letter.fileName)
                    ?: throw IOException("An archived image is missing. Export cancelled.")
            }
        } catch (e: Exception) {
            file.delete(); folder.delete()
            throw e
        }
        SharedArchive(file, letters.size)
    }
}

fun newExportFolder(cacheDir: File): File {
    val directory = File(cacheDir, "shared_exports").apply { mkdirs() }
    directory.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 86_400_000L }?.forEach { it.deleteRecursively() }
    return File(directory, UUID.randomUUID().toString()).apply { check(mkdirs()) { "Could not prepare export." } }
}

enum class ExportPeriod(val label: String, val months: Long = 1) {
    THIS_MONTH("This month"), LAST_MONTH("Last month"),
    TWO_MONTHS("Past 2 months", 2), THREE_MONTHS("Past 3 months", 3),
    FOUR_MONTHS("Past 4 months", 4), FIVE_MONTHS("Past 5 months", 5),
    SIX_MONTHS("Past 6 months", 6), SEVEN_MONTHS("Past 7 months", 7),
    EIGHT_MONTHS("Past 8 months", 8), NINE_MONTHS("Past 9 months", 9),
    TEN_MONTHS("Past 10 months", 10), ELEVEN_MONTHS("Past 11 months", 11),
    TWELVE_MONTHS("Past 12 months", 12), ALL("All time")
}
data class SharedArchive(val file: File, val count: Int)
class EmptyExport : IOException("No images are saved for that period yet.")

/** Selected calendar months; all-time exports use the actual oldest and newest envelope months. */
fun exportMonthRange(period: ExportPeriod, letters: List<Letter>, today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): String {
    val current = YearMonth.from(today)
    val (start, end) = if (period == ExportPeriod.ALL) {
        require(letters.isNotEmpty())
        val months = letters.map { YearMonth.from(announcementDate(it, zone)) }
        months.min() to months.max()
    } else {
        val end = if (period == ExportPeriod.LAST_MONTH) current.minusMonths(1) else current
        end.minusMonths(period.months - 1) to end
    }
    val format = DateTimeFormatter.ofPattern("MM-uuuu", java.util.Locale.ROOT)
    return if (start == end) start.format(format) else "${start.format(format)}_to_${end.format(format)}"
}

fun filterExport(letters: List<Letter>, period: ExportPeriod, today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): List<Letter> {
    if (period == ExportPeriod.ALL) return letters
    val current = YearMonth.from(today)
    val end = if (period == ExportPeriod.LAST_MONTH) current.minusMonths(1) else current
    val start = end.minusMonths(period.months - 1)
    return letters.filter { YearMonth.from(announcementDate(it, zone)) in start..end }
}

fun announcementDate(letter: Letter, zone: ZoneId): LocalDate {
    return parsedAnnouncementDate(letter.date) ?: Instant.ofEpochMilli(letter.firstSeen).atZone(zone).toLocalDate()
}

fun parsedAnnouncementDate(date: String): LocalDate? {
    val raw = date.trim()
    for (format in listOf(DateTimeFormatter.ISO_LOCAL_DATE, DateTimeFormatter.ofPattern("d.M.uuuu"), DateTimeFormatter.ofPattern("d/M/uuuu"))) {
        runCatching { LocalDate.parse(raw.substringBefore('T'), format) }.getOrNull()?.let { return it }
    }
    return null
}

fun writeImageZip(stream: java.io.OutputStream, letters: List<Letter>, images: PdfImages = PdfImages.ORIGINALS,
    read: (Letter, Boolean) -> ByteArray) {
    ZipOutputStream(stream).use { zip ->
        letters.forEachIndexed { index, letter ->
            images.cutouts.forEach { cutout ->
                val bytes = read(letter, cutout)
                val extension = if (cutout) "png" else letter.fileName.substringAfterLast('.')
                require(extension in listOf("jpg", "png", "webp", "gif"))
                val variant = if (cutout) "cutout" else "original"
                val name = "${safeDate(letter.date)}_letter_${(index + 1).toString().padStart(5, '0')}_${variant}.$extension"
                zip.putNextEntry(ZipEntry(name).apply { time = letter.firstSeen })
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }
}
