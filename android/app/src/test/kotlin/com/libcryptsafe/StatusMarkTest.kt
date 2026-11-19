package com.libcryptsafe

import com.libcryptsafe.util.StatusMark
import org.junit.Assert.assertEquals
import org.junit.Test

/** Статусы доставки: PENDING -> SENT -> DELIVERED, ровно один значок, входящие не трогаем. */
class StatusMarkTest {
    private val CLK = "  \uD83D\uDD53"
    private val ONE = "  \u2713"
    private val TWO = "  \u2713\u2713"
    private fun r(t: String, s: String) = StatusMark.render(t, true, s)

    @Test fun pendingShowsClock() = assertEquals("hi$CLK", r("hi", "PENDING"))
    @Test fun sentShowsOne() = assertEquals("hi$ONE", r("hi", "SENT"))
    @Test fun deliveredShowsTwo() = assertEquals("hi$TWO", r("hi", "DELIVERED"))
    // перерисовка УЖЕ отрисованного текста (markDelivered/onRelayAccepted берут tv.text)
    @Test fun chainKeepsSingleMark() {
        val a = r("hi", "PENDING"); val b = r(a, "SENT"); val c = r(b, "DELIVERED")
        assertEquals("hi$ONE", b); assertEquals("hi$TWO", c)
    }
    @Test fun clockStraightToDelivered() = assertEquals("hi$TWO", r(r("hi", "PENDING"), "DELIVERED"))
    @Test fun idempotent() = assertEquals("hi$TWO", r(r("hi", "DELIVERED"), "DELIVERED"))
    @Test fun incomingUntouched() = assertEquals("a$ONE", StatusMark.render("a$ONE", false, "DELIVERED"))
    @Test fun noneNoMark() = assertEquals("foto (96KB)", r("foto (96KB)", "NONE"))
    @Test fun noneThenDelivered() = assertEquals("foto$TWO", r(r("foto", "NONE"), "DELIVERED"))
    // текст пользователя с ОДНИМ пробелом перед галочкой — не наш значок, не снимаем
    @Test fun userCheckmarkKept() = assertEquals("ok \u2713$CLK", r("ok \u2713", "PENDING"))
}
