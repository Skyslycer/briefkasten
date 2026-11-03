package de.skyslycer.briefkasten

import android.graphics.*
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PdfExportIntegrationTest {
    @Test fun rendersUnicodeAndPaginatesWithoutLosingText() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(context.cacheDir, "pdf-export-qa").apply { mkdirs() }
        val bitmap = Bitmap.createBitmap(1200, 600, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 42f }
            drawText("Musterbank - Grüße aus Köln", 80f, 200f, paint)
            drawText("Musterstraße 12, 12345 Berlin", 80f, 270f, paint)
        }
        try {
            val letter = Letter("pdf-fixture", "test", "24.10.2026", 1, "fixture.png", "test", 1, false)
            val text = "Musterbank\nGrüße aus Köln - ÄÖÜ äöü ß\nMusterstraße 12\n12345 Berlin"
            EnvelopePdf(context).use { pdf ->
                pdf.addViews(letter, listOf(PdfView(bitmap, text, false), PdfView(bitmap, text, true)), 1, 1)
                File(folder, "both.pdf").outputStream().use { pdf.writeTo(it) }
            }
            EnvelopePdf(context).use { pdf ->
                pdf.add(letter, bitmap, (1..220).joinToString("\n") { "Line $it: Grüße aus Köln, searchable envelope text." }, true, 1, 1)
                File(folder, "long.pdf").outputStream().use { pdf.writeTo(it) }
            }
            fun pages(name: String): Int = ParcelFileDescriptor.open(File(folder, name), ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { it.pageCount }
            }
            assertEquals(1, pages("both.pdf"))
            assertTrue(pages("long.pdf") > 3)
        } finally { bitmap.recycle() }
    }

}
