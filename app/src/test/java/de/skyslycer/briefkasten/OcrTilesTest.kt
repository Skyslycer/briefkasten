package de.skyslycer.briefkasten

import org.junit.Assert.*
import org.junit.Test

class OcrTilesTest {
    @Test fun `native resolution tiles cover every source pixel including edges`() {
        val width = 3501; val height = 2103
        val coverage = ByteArray(width * height)
        val tiles = ocrTiles(width, height)
        for (tile in tiles) {
            assertTrue(tile.right - tile.left <= 1600 && tile.bottom - tile.top <= 1600)
            for (y in tile.top until tile.bottom) for (x in tile.left until tile.right) coverage[y * width + x]++
        }
        assertTrue(coverage.all { it > 0 })
        assertTrue(coverage.any { it > 1 })
    }
    @Test fun `small inputs are kept whole`() {
        assertEquals(listOf(ContentBounds(0, 0, 700, 400)), ocrTiles(700, 400))
    }
}
