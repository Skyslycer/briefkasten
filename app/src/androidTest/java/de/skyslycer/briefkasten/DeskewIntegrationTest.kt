package de.skyslycer.briefkasten

import android.graphics.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class DeskewIntegrationTest {
    private fun angle(bitmap: Bitmap): Double {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        for (i in pixels.indices) pixels[i] = Color.red(pixels[i])
        return deskewAngle(pixels, bitmap.width, bitmap.height)
    }

    @Test fun correctedCopyIsStraightAndOriginalRemainsByteIdentical() {
        val vault = Vault(InstrumentationRegistry.getInstrumentation().targetContext)
        val id = "deskew-test-${System.nanoTime()}"
        val letter = Letter(id, "test", "2026-11-02", 1, "$id.png", "test", 1, false)
        val straight = Bitmap.createBitmap(800, 450, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(straight).apply { drawColor(Color.WHITE) }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 28f }
        for (line in 0..5) canvas.drawText("Deutsche Post Musterstrasse Berlin", 100f, 110f + line * 45f, paint)
        val tilted = rotateEnvelope(straight, 3.0)
        try {
            assertEquals(-3.0, angle(tilted), .4)
            val source = ByteArrayOutputStream().use { tilted.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() }
            vault.write(letter.fileName, source)
            val images = CutoutImages(vault)
            val corrected = images.bytes(letter)
            assertArrayEquals(source, vault.read(letter.fileName))
            assertArrayEquals(corrected, vault.read("$id.cutout-v1"))
            assertArrayEquals(corrected, images.bytes(letter))
            val bitmap = BitmapFactory.decodeByteArray(corrected, 0, corrected.size)
            try {
                assertTrue(bitmap.width < tilted.width && bitmap.height < tilted.height)
                assertEquals(0.0, angle(bitmap), .4)
                assertEquals(Color.WHITE, bitmap.getPixel(0, 0))
            } finally { bitmap.recycle() }
        } finally {
            straight.recycle(); tilted.recycle()
            vault.remove(letter.fileName); vault.remove("$id.cutout-v1")
        }
    }
}
