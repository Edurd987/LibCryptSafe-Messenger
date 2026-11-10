package com.libcryptsafe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** №45a: приглашение от контакта — с именем; от незнакомца — отклоняется, заметка с антиспамом. */
class IncomingInviteTest {
    private val PHONE = "3465-BFC2-71BB-A009"
    private val book = listOf("ed" to PHONE, "Э" to "D583-D7FB-12F2-B75C")
    private val MIN = 60_000L

    @Test fun contactShownByNameAndTail() {
        assertEquals(IncomingInvite.Decision.Show("ed  (A009)"), IncomingInvite.decide(book, PHONE, null, 0))
    }
    @Test fun strangerNotShown() {
        val d = IncomingInvite.decide(book, "DEAD-BEEF-0000-1111", null, 0)
        assertEquals(IncomingInvite.Decision.Stranger(notify = true), d)
    }
    @Test fun lookAlikeTailIsStillStranger() {
        // атакующий подобрал ключ, чтобы ID кончался на A009, как у контакта «ed»
        val d = IncomingInvite.decide(book, "0BAD-0000-0000-A009", null, 0)
        assertTrue("похожий хвост принят за контакт: $d", d is IncomingInvite.Decision.Stranger)
    }
    @Test fun strangerNoteRateLimited() {
        val id = "DEAD-BEEF-0000-1111"
        assertEquals(IncomingInvite.Decision.Stranger(false), IncomingInvite.decide(book, id, 1_000, 1_000 + 3 * MIN))
        assertEquals(IncomingInvite.Decision.Stranger(true), IncomingInvite.decide(book, id, 1_000, 1_000 + 10 * MIN))
    }
    @Test fun strangerTextHasFullId() {
        assertTrue(IncomingInvite.strangerText("DEAD-BEEF-0000-1111").contains("DEAD-BEEF-0000-1111"))
    }
    @Test fun nameSanitized() {
        val long = "x".repeat(200) + "\n\nподделка"
        val d = IncomingInvite.decide(listOf(long to PHONE), PHONE, null, 0) as IncomingInvite.Decision.Show
        assertTrue("перевод строки в имени", !d.label.contains("\n"))
        assertTrue("имя не обрезано: ${d.label.length}", d.label.length <= 40 + 8)
        assertEquals("контакт  (A009)", (IncomingInvite.decide(listOf("   " to PHONE), PHONE, null, 0) as IncomingInvite.Decision.Show).label)
    }
    @Test fun emptyBookEverybodyStranger() {
        assertTrue(IncomingInvite.decide(emptyList(), PHONE, null, 0) is IncomingInvite.Decision.Stranger)
    }
}
