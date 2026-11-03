package de.skyslycer.briefkasten

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

class ArchiveExportTest {
    @Test fun `export filenames identify selected months and years instead of export date`() {
        val today = java.time.LocalDate.of(2027, 1, 15)
        val zone = java.time.ZoneOffset.UTC
        assertEquals("01-2027", exportMonthRange(ExportPeriod.THIS_MONTH, emptyList(), today, zone))
        assertEquals("12-2026", exportMonthRange(ExportPeriod.LAST_MONTH, emptyList(), today, zone))
        assertEquals("12-2026_to_01-2027", exportMonthRange(ExportPeriod.TWO_MONTHS, emptyList(), today, zone))
        assertEquals("02-2026_to_01-2027", exportMonthRange(ExportPeriod.TWELVE_MONTHS, emptyList(), today, zone))
        val letters = listOf(Letter("a", "account", "2025-10-01", 0, "a.jpg", "hash", 1, false),
            Letter("b", "account", "2026-12-31", 0, "b.jpg", "hash", 1, false))
        assertEquals("10-2025_to_12-2026", exportMonthRange(ExportPeriod.ALL, letters, today, zone))
        assertEquals("10-2025", exportMonthRange(ExportPeriod.ALL, letters.take(1), today, zone))
        assertEquals("10-2026", exportMonthRange(ExportPeriod.LAST_MONTH, emptyList(), java.time.LocalDate.of(2026, 11, 2), zone))
    }
    @Test fun `month export uses announcement dates and handles year boundaries`() {
        val savedInJanuary = java.time.Instant.parse("2027-01-15T12:00:00Z").toEpochMilli()
        fun letter(id: String, date: String) = Letter(id, "account", date, savedInJanuary, "$id.jpg", id, 1, false)
        val letters = listOf(letter("dec", "31.12.2026"), letter("jan", "2027-01-01"),
            letter("feb", "01/02/2027"), letter("unknown", "Unknown date"))
        val today = java.time.LocalDate.of(2027, 1, 15)
        val zone = java.time.ZoneOffset.UTC
        assertEquals(listOf("jan", "unknown"), filterExport(letters, ExportPeriod.THIS_MONTH, today, zone).map { it.id })
        assertEquals(listOf("dec"), filterExport(letters, ExportPeriod.LAST_MONTH, today, zone).map { it.id })
        assertEquals(letters, filterExport(letters, ExportPeriod.ALL, today, zone))
        assertTrue(filterExport(listOf(letters[2]), ExportPeriod.THIS_MONTH, today, zone).isEmpty())
    }

    @Test fun `rolling calendar month ranges include current month and exclude future months`() {
        val today = java.time.LocalDate.of(2027, 1, 15)
        val current = java.time.YearMonth.from(today)
        val letters = (-1L..13L).map { offset ->
            Letter("$offset", "account", current.minusMonths(offset).atDay(1).toString(), 0, "$offset.jpg", "$offset", 1, false)
        }
        for (period in ExportPeriod.entries.filter { it.months >= 2 }) {
            assertEquals((0L until period.months).map { "$it" },
                filterExport(letters, period, today, java.time.ZoneOffset.UTC).map { it.id })
        }
        assertEquals(14, ExportPeriod.entries.size)
        assertEquals(ExportPeriod.THIS_MONTH, ExportPeriod.entries.first())
        assertEquals(ExportPeriod.ALL, ExportPeriod.entries.last())
    }

    @Test fun `export contains only uniquely named original images and no JSON`() {
        val first = Letter("one", "account", "02/11/2026", 1700000000000, "one.jpg", "hash", 1, false)
        val second = first.copy(id = "two", fileName = "two.png")
        val original = mapOf("one" to byteArrayOf(1, 2, 3), "two" to byteArrayOf(4, 5))
        val output = ByteArrayOutputStream()
        writeImageZip(output, listOf(first, second)) { letter, _ -> original.getValue(letter.id) }
        val contents = linkedMapOf<String, ByteArray>()
        ZipInputStream(output.toByteArray().inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                contents[entry.name] = zip.readBytes()
            }
        }
        assertEquals(listOf("02_11_2026_letter_00001_original.jpg", "02_11_2026_letter_00002_original.png"), contents.keys.toList())
        assertArrayEquals(original["one"], contents.values.first())
        assertArrayEquals(original["two"], contents.values.last())
        assertTrue(contents.keys.none { it.endsWith(".json") || it.contains('/') })
    }
    @Test fun `ZIP image selection exports exactly the requested variants`() {
        val letter = Letter("one", "account", "2026-10-24", 0, "one.jpg", "hash", 1, false)
        for (mode in PdfImages.entries) {
            val output = ByteArrayOutputStream()
            writeImageZip(output, listOf(letter), mode) { _, cutout -> if (cutout) byteArrayOf(7, 8) else byteArrayOf(1, 2, 3) }
            val names = mutableListOf<String>()
            ZipInputStream(output.toByteArray().inputStream()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    names.add(entry.name)
                    assertArrayEquals(if (entry.name.endsWith("_cutout.png")) byteArrayOf(7, 8) else byteArrayOf(1, 2, 3), zip.readBytes())
                }
            }
            assertEquals(mode.cutouts.map { "2026-10-24_letter_00001_${if (it) "cutout.png" else "original.jpg"}" }, names)
        }
    }

}
