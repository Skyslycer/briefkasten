package de.skyslycer.briefkasten

import org.junit.Assert.*
import org.junit.Test

class LetterGroupsTest {
    private fun group(name: String, id: String = name) = LetterGroup(id, name, 0)
    @Test fun `full and cutout readings both match and most specific name wins`() {
        val groups = listOf(group("Bank"), group("Deutsche Post"))
        assertEquals("Deutsche Post", matchingGroupReadings(listOf("Deutsche Post", "Bank"), groups)?.name)
        assertEquals("Deutsche Post", matchingGroupReadings(listOf("Bank", "Deutsche Post"), groups)?.name)
        assertNull(matchingGroupReadings(listOf("Deutsche", "Post"), listOf(group("Deutsche Post"))))
    }
    @Test fun `matches literal substring ignoring case and OCR line breaks`() {
        assertEquals("Deutsche Post", matchingGroup("Absender DEUTSCHE\n POST AG", listOf(group("Deutsche Post")))?.name)
        assertEquals("Bank", matchingGroup("Musterbank AG", listOf(group("Bank")))?.name)
    }
    @Test fun `Volksbank matches Volksbanken but not OCR misspellings`() {
        val groups = listOf(group("Volksbank"))
        assertEquals("Volksbank", matchingGroup("Vereinigte Volksbanken eG", groups)?.name)
        assertNull(matchingGroup("Vereinigte Voiksbanken eG", groups))
    }
    @Test fun `longest matching name wins and ties are stable`() {
        val groups = listOf(group("Post"), group("Deutsche Post"))
        assertEquals("Deutsche Post", matchingGroup("Deutsche Post", groups)?.name)
        assertEquals("a", matchingGroup("alpha bravo", listOf(group("bravo", "b"), group("alpha", "a")))?.id)
    }
    @Test fun `unmatched and blank names are never assigned`() {
        assertNull(matchingGroup("Electric company", listOf(group("Bank"), group("  "))))
        assertNull(matchingGroup("", listOf(group("Bank"))))
    }
    @Test fun `percent underscore and regex characters are literal`() {
        assertNull(matchingGroup("Anything", listOf(group("%"), group("_"), group(".*"))))
        assertEquals("50%", matchingGroup("Save 50% today", listOf(group("50%")))?.name)
    }
}
