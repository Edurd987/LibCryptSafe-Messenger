package com.libcryptsafe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Чёрный список: полное совпадение ID, ленивая загрузка один раз, обновление, сбой БД. */
class BlockListTest {
    private val BAD = "D583-D7FB-12F2-B75C"
    private val TWIN = "0000-0000-0000-B75C"     // тот же хвост — подбирается перебором
    private val GOOD = "3465-BFC2-71BB-A009"

    private fun fresh(): IntArray { BlockList.resetForTest(); return intArrayOf(0) }

    @Test fun blockedIdIsBlocked() {
        fresh()
        assertTrue(BlockList.isBlocked(BAD) { listOf(BAD) })
    }
    @Test fun otherIdNotBlocked() {
        fresh()
        assertFalse(BlockList.isBlocked(GOOD) { listOf(BAD) })
    }
    @Test fun sameTailIsNotEnough() {
        fresh()
        assertFalse("хвост совпал — заблокирован чужой ID", BlockList.isBlocked(TWIN) { listOf(BAD) })
    }
    @Test fun loadsFromDbOnlyOnce() {
        val calls = fresh()
        repeat(5) { BlockList.isBlocked(BAD) { calls[0]++; listOf(BAD) } }
        assertEquals(1, calls[0])
    }
    @Test fun replaceAllTakesEffectImmediately() {
        fresh()
        assertTrue(BlockList.isBlocked(BAD) { listOf(BAD) })
        BlockList.replaceAll(emptyList())                       // разблокировал
        assertFalse(BlockList.isBlocked(BAD) { listOf(BAD) })
        BlockList.replaceAll(listOf(GOOD))                      // заблокировал другого
        assertTrue(BlockList.isBlocked(GOOD) { emptyList() })
    }
    @Test fun dbFailureDoesNotBlockAndRetries() {
        val calls = fresh()
        assertFalse(BlockList.isBlocked(BAD) { calls[0]++; throw IllegalStateException("db") })
        assertTrue("после сбоя список должен загрузиться при следующем входящем",
            BlockList.isBlocked(BAD) { calls[0]++; listOf(BAD) })
        assertEquals(2, calls[0])
    }
}
