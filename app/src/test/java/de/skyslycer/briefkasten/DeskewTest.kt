package de.skyslycer.briefkasten

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class DeskewTest {
    private val w = 700
    private val h = 400
    private fun scan(angle: Double): IntArray {
        val pixels = IntArray(w * h) { 250 }
        val slope = tan(Math.toRadians(angle))
        for (line in 0..5) for (letter in 0..29) {
            for (dy in 0..9) for (dx in 0..4) {
                val x = 100 + letter * 13 + dx
                val y = (100 + line * 27 + dy + (x - w / 2) * slope).roundToInt()
                pixels[y * w + x] = 20
            }
        }
        return pixels
    }
    @Test fun `corrects slight clockwise and counterclockwise text slopes`() {
        for (angle in listOf(-4.0, -2.0, 1.5, 3.0, 5.0)) {
            assertEquals("Input $angle", -angle, deskewAngle(scan(angle), w, h), .35)
        }
    }
    @Test fun `straight and blank pages remain unchanged`() {
        assertEquals(0.0, deskewAngle(scan(0.0), w, h), 0.0)
        assertEquals(0.0, deskewAngle(IntArray(w * h) { 255 }, w, h), 0.0)
    }
    @Test fun `window outline without enough text is not used as a baseline`() {
        val pixels = IntArray(w * h) { 255 }
        for (x in 20 until w - 20) {
            pixels[20 * w + x] = 0
            pixels[(h - 20) * w + x] = 0
        }
        assertEquals(0.0, deskewAngle(pixels, w, h), 0.0)
    }
    @Test fun `correction follows text rather than horizontal window seams`() {
        val pixels = scan(2.5)
        for (x in 20 until w - 20) {
            pixels[20 * w + x] = 0
            pixels[(h - 20) * w + x] = 0
        }
        assertEquals(-2.5, deskewAngle(pixels, w, h), .35)
    }
}
