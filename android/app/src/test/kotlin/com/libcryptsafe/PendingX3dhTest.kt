package com.libcryptsafe

import com.libcryptsafe.util.PendingX3dh
import com.libcryptsafe.util.PendingX3dh.Item
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** X3DH-очередь: ни одно сообщение новому контакту не теряется, один prekeys_request, один X3DH. */
class PendingX3dhTest {
    private var t = 0L
    private val p = PendingX3dh(retryMs = 30_000L, now = { t })

    // главная дыра: раньше Map — «как дела» затирало «привет» ещё до ответа релея
    @Test fun secondMessageDoesNotOverwriteFirst() {
        p.stash("A", "привет", "n1"); p.stash("A", "как дела", "n2")
        assertEquals(listOf(Item("привет", "n1"), Item("как дела", "n2")), p.begin("A"))
    }
    @Test fun oneRequestUntilRetry() {
        assertTrue(p.stash("A", "1", "n1"))
        t = 10_000; assertFalse(p.stash("A", "2", "n2"))
        t = 31_000; assertTrue(p.stash("A", "3", "n3"))       // ответа нет 30 с — повторить запрос
    }
    @Test fun secondResponseIgnoredWhileEstablishing() {
        p.stash("A", "1", "n1"); assertEquals(1, p.begin("A").size)
        assertEquals(emptyList<Item>(), p.begin("A"))           // второй ответ не строит второй X3DH
    }
    @Test fun messagesDuringEstablishGoAfter() {
        p.stash("A", "1", "n1"); p.begin("A")
        assertFalse(p.stash("A", "2", "n2"))                    // запрос не нужен — сессия строится
        assertEquals(listOf(Item("2", "n2")), p.more("A"))
        assertEquals(emptyList<Item>(), p.more("A"))            // пусто -> слот снят
        assertTrue(p.stash("A", "3", "n3"))                     // новый цикл (если сессии вдруг нет)
    }
    @Test fun abortReturnsAndClears() {
        p.stash("A", "1", "n1"); p.stash("A", "2", "n2")
        assertEquals(2, p.abort("A").size); assertEquals(emptyList<Item>(), p.begin("A"))
    }
    @Test fun targetsIndependent() {
        assertTrue(p.stash("A", "a", "na")); assertTrue(p.stash("B", "b", "nb"))
        assertEquals(listOf(Item("b", "nb")), p.begin("B")); assertEquals(listOf(Item("a", "na")), p.begin("A"))
    }
}
