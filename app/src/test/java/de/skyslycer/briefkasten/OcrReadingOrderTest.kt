package de.skyslycer.briefkasten

import org.junit.Assert.*
import org.junit.Test

class OcrReadingOrderTest {
    @Test fun `reads page rows rather than sender and recipient block order`() {
        val lines = listOf(
            OcrLine("Sender", 20.0, 200.0, 90.0, 212.0),
            OcrLine("Herr", 20.0, 320.0, 60.0, 334.0),
            OcrLine("Recipient", 20.0, 340.0, 120.0, 354.0),
            OcrLine("With best wishes", 200.0, 20.0, 390.0, 40.0),
            OcrLine("expert", 10.0, 18.0, 90.0, 42.0),
            OcrLine("12345 City", 300.0, 201.0, 395.0, 213.0),
            OcrLine("Street 1", 95.0, 200.0, 290.0, 212.0),
            OcrLine("Postage", 150.0, 225.0, 290.0, 239.0))
        assertEquals("expert With best wishes\nSender Street 1 12345 City\nPostage\nHerr\nRecipient", orderedOcrText(lines))
    }
    @Test fun `small rotation does not split fragments on the same baseline`() {
        assertEquals("Left Right\nNext line", orderedOcrText(listOf(
            OcrLine("Right", 400.0, 114.0, 500.0, 126.0, 2.0),
            OcrLine("Next line", 0.0, 130.0, 120.0, 142.0, 2.0),
            OcrLine("Left", 0.0, 100.0, 100.0, 112.0, 2.0))))
    }
    @Test fun `blank OCR remains empty`() { assertEquals("", orderedOcrText(emptyList())) }
}
