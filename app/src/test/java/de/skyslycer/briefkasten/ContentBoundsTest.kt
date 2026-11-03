package de.skyslycer.briefkasten

import org.junit.Assert.*
import org.junit.Test

class ContentBoundsTest {
    private val w = 600
    private val h = 300
    private fun fill(image: IntArray, x: Int, y: Int, width: Int, height: Int, color: Int = 30) {
        for (row in y until y + height) for (col in x until x + width) image[row * w + col] = color
    }
    private fun text(image: IntArray, x: Int, y: Int) {
        for (line in 0..3) for (letter in 0..15) fill(image, x + letter * 6, y + line * 12, 3, 6)
    }
    @Test fun `finds address block and ignores scanner edges and isolated dust`() {
        val image = IntArray(w * h) { 245 }
        fill(image, 0, 0, w, 14, 0)
        fill(image, 0, h - 2, w, 2, 0)
        text(image, 110, 150)
        fill(image, 520, 80, 2, 2)
        val bounds = detectContentBounds(image, w, h)!!
        assertTrue(bounds.left < 110 && bounds.left > 80)
        assertTrue(bounds.top < 150 && bounds.top > 120)
        assertTrue(bounds.right >= 203 && bounds.right < 240)
        assertTrue(bounds.bottom >= 192 && bounds.bottom < 220)
    }
    @Test fun `preserves separate sender and recipient blocks`() {
        val image = IntArray(w * h) { 245 }
        text(image, 40, 40); text(image, 220, 160)
        val bounds = detectContentBounds(image, w, h)!!
        assertTrue(bounds.left < 40 && bounds.top < 40)
        assertTrue(bounds.right > 313 && bounds.bottom > 202)
    }
    @Test fun `wide printed block near top is not mistaken for border texture`() {
        val image = IntArray(w * h) { 255 }
        for (block in 0..4) text(image, 40 + block * 100, 45)
        val bounds = detectContentBounds(image, w, h)!!
        assertTrue("Unexpected bounds: $bounds", bounds.left < 40 && bounds.top < 45)
        assertTrue("Unexpected bounds: $bounds", bounds.right > 530 && bounds.bottom > 85)
    }
    @Test fun `blank and low contrast shadows fall back to original`() {
        assertNull(detectContentBounds(IntArray(w * h) { 255 }, w, h))
        val shadow = IntArray(w * h) { 230 + (it % w) * 20 / w }
        assertNull(detectContentBounds(shadow, w, h))
    }
    @Test fun `retains faint printed text on shaded paper`() {
        val image = IntArray(w * h) { 210 }
        for (line in 0..3) for (letter in 0..15) fill(image, 200 + letter * 6, 100 + line * 12, 3, 6, 145)
        val bounds = detectContentBounds(image, w, h)!!
        assertTrue(bounds.left < 200 && bounds.right > 293)
    }
    @Test fun `border only does not produce a cutout`() {
        val image = IntArray(w * h) { 255 }
        fill(image, 0, 0, w, 15, 0)
        fill(image, 0, h - 1, w, 1, 0)
        assertNull(detectContentBounds(image, w, h))
    }
    @Test fun `broken window outline does not add blank space around printed content`() {
        val image = IntArray(w * h) { 255 }
        text(image, 160, 90)
        text(image, 160, 155)
        // Disconnected corners of a window seam survive scanner-band masking.
        fill(image, 24, 28, 120, 1)
        fill(image, 24, 28, 1, 70)
        fill(image, 455, 28, 120, 1)
        fill(image, 574, 28, 1, 70)
        fill(image, 24, 205, 1, 70)
        fill(image, 24, 274, 120, 1)
        fill(image, 574, 205, 1, 70)
        fill(image, 455, 274, 120, 1)
        val bounds = detectContentBounds(image, w, h)!!
        assertTrue("Unexpected bounds: $bounds", bounds.left > 130 && bounds.top > 65)
        assertTrue("Unexpected bounds: $bounds", bounds.right < 290 && bounds.bottom < 225)
    }
    @Test fun `textured black band and side border do not engulf address crop`() {
        val random = java.util.Random(42)
        val image = IntArray(w * h) { 245 }
        for (y in 0 until 110) for (x in 0 until w) {
            image[y * w + x] = if (y in 50..105) random.nextInt(110) else if (random.nextInt(8) == 0) 110 else 245
        }
        for (y in 105 until h) for (x in w - 16 until w) image[y * w + x] = random.nextInt(100)
        text(image, 110, 200)
        val bounds = detectContentBounds(image, w, h)!!
        assertTrue("Unexpected bounds: $bounds", bounds.top > 170 && bounds.left > 80 && bounds.right < 240)
    }

}
