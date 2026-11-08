package com.libcryptsafe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** №51: приглашение — только явно выбранному контакту. */
class InvitePickerTest {
    private val B = "B754-9A6F-0000-1111"
    private val EMU = "F9CA-80C8-5809-5377"
    private val STRANGER = "DEAD-BEEF-0000-2222"
    private val book = listOf("Эмулятор" to EMU, "Телефон B" to B, "аня" to "AAAA-0000-1111-2222")

    @Test fun currentChatContactFirstAndMarked() {
        val o = InvitePicker.options(book, EMU)
        assertEquals(EMU, o.first().peerId)
        assertTrue(o.first().label.contains("текущий чат"))
        assertEquals("ровно одна пометка", 1, o.count { it.label.contains("текущий чат") })
    }
    @Test fun allContactsOfferedNotOnlyCurrent() {
        // суть №51: B можно выбрать, даже если открыт чат с эмулятором
        assertTrue(InvitePicker.options(book, EMU).any { it.peerId == B })
        assertEquals(3, InvitePicker.options(book, EMU).size)
    }
    @Test fun strangerNeverOffered() {
        // текущий собеседник — незнакомец (пришло сообщение не от контакта): в списке его нет
        val o = InvitePicker.options(book, STRANGER)
        assertTrue(o.none { it.peerId == STRANGER })
        assertTrue(o.none { it.label.contains("текущий чат") })
    }
    @Test fun restSortedByNameIgnoringCase() {
        assertEquals(listOf("аня", "Телефон B", "Эмулятор"),
            InvitePicker.options(book, "UNKNOWN").map { it.label.substringBefore("  (") })
    }
    @Test fun labelShowsIdTailToTellApartSameNames() {
        val o = InvitePicker.options(listOf("Аня" to "AAAA-0000-1111-2222", "Аня" to "BBBB-0000-1111-3333"), "UNKNOWN")
        assertEquals(setOf("Аня  (2222)", "Аня  (3333)"), o.map { it.label }.toSet())
    }
    @Test fun malformedAndDuplicateIdsDropped() {
        val o = InvitePicker.options(listOf("x" to "UNKNOWN", "y" to "", "z" to "b754-9a6f-0000-1111", "B" to B, "B2" to B), "UNKNOWN")
        assertEquals(listOf(B), o.map { it.peerId })
    }
    @Test fun emptyBookGivesEmptyList() {
        assertTrue(InvitePicker.options(emptyList(), B).isEmpty())
    }
}
