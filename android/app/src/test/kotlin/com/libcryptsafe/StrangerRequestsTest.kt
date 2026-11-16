package com.libcryptsafe

import com.libcryptsafe.db.StrangerRequestRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** №45b шаг 2: экран «Запросы» — кого показываем и как безопасно показать чужой текст. */
class StrangerRequestsTest {
    private val A = "3465-BFC2-71BB-A009"
    private val B = "D583-D7FB-12F2-B75C"
    private val E = "F9CA-80C8-5809-5377"
    private val TWIN = "0000-0000-0000-A009"

    @Test fun contactsAndBlockedHidden() {
        val rows = listOf(StrangerRequestRow(A, 3, 10), StrangerRequestRow(B, 2, 20), StrangerRequestRow(E, 1, 30))
        val out = StrangerRequests.build(rows, contacts = setOf(A), blocked = setOf(B))
        assertEquals(listOf(E), out.map { it.peerId })
    }
    @Test fun twinOfContactIsStillRequest() {          // хвост как у контакта — показываем, с ПОЛНЫМ ID
        val out = StrangerRequests.build(listOf(StrangerRequestRow(TWIN, 1, 5)), setOf(A), emptySet())
        assertEquals(listOf(TWIN), out.map { it.peerId })
    }
    @Test fun newestFirst() {
        val rows = listOf(StrangerRequestRow(A, 1, 10), StrangerRequestRow(B, 1, 30), StrangerRequestRow(E, 1, 20))
        assertEquals(listOf(B, E, A), StrangerRequests.build(rows, emptySet(), emptySet()).map { it.peerId })
    }
    @Test fun emptyCountsHidden() {
        assertTrue(StrangerRequests.build(listOf(StrangerRequestRow(E, 0, 1)), emptySet(), emptySet()).isEmpty())
    }
    @Test fun previewTruncated() {
        val p = StrangerRequests.preview("а".repeat(500))
        assertTrue("превью ${p.length}", p.length <= StrangerRequests.PREVIEW_MAX + 1)
        assertTrue(p.endsWith("…"))
    }
    @Test fun previewShortKeptAsIs() = assertEquals("привет, это Б", StrangerRequests.preview("привет, это Б"))
    @Test fun controlCharsAndNewlinesBecomeSpaces() {
        assertEquals("строка 1 строка 2 конец", StrangerRequests.preview("строка 1\n\n\tстрока 2\u0000конец"))
    }
    @Test fun bidiOverrideRemoved() {                  // RLO может «перевернуть» текст и спрятать ссылку/ID
        val s = StrangerRequests.safeText("платёж \u202Egnp.exe")
        assertFalse(s.any { it in '\u202A'..'\u202E' || it in '\u2066'..'\u2069' })
    }
    @Test fun zeroWidthRemoved() {
        val s = StrangerRequests.safeText("ad\u200Bmin\uFEFF")
        assertFalse(s.any { it in '\u200B'..'\u200F' || it == '\uFEFF' })
    }
    @Test fun fullTextLimited() {
        val s = StrangerRequests.safeText("x".repeat(100_000))
        assertTrue(s.length <= StrangerRequests.TEXT_MAX + 1)
    }
}
