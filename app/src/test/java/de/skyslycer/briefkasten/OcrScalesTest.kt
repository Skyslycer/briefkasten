package de.skyslycer.briefkasten

import org.junit.Assert.*
import org.junit.Test

class OcrScalesTest {
    @Test fun `bank scan and cutout keep exact twofold enlargement`() {
        assertEquals(listOf(2), ocrScales(2320, 1148))
        assertEquals(listOf(2, 3), ocrScales(761, 403))
    }
    @Test fun `large images are never fractionally rescaled to fit memory limits`() {
        assertTrue(ocrScales(7000, 4000).isEmpty())
        for (scale in ocrScales(1000, 800)) {
            assertTrue(scale == 2 || scale == 3)
            assertTrue(1000L * 800 * scale * scale <= 16_000_000)
        }
    }
}
