package com.libcryptsafe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContactNameTest {
    @Test fun normalNameKept() = assertEquals("Телефон B", ContactName.clean("Телефон B"))
    @Test fun spacesTrimmedAndCollapsed() = assertEquals("Телефон B", ContactName.clean("  Телефон    B  "))
    @Test fun oneLetterRejected() = assertNull("имя из одной буквы («Э») пропущено", ContactName.clean("Э"))
    @Test fun emptyAndBlankRejected() { assertNull(ContactName.clean("")); assertNull(ContactName.clean("    ")) }
    @Test fun controlCharsRemoved() = assertEquals("Аня Петрова", ContactName.clean("Аня\nПетрова\u0000"))
    @Test fun newlineCannotSpoofSecondLine() =
        assertEquals("ed подделка", ContactName.clean("ed\n\nподделка"))
    @Test fun tooLongRejected() = assertNull(ContactName.clean("x".repeat(41)))
    @Test fun exactlyMaxKept() = assertEquals(40, ContactName.clean("x".repeat(40))!!.length)
    @Test fun emojiCountedAsOneChar() = assertEquals("🙂🙂", ContactName.clean("🙂🙂"))
}
