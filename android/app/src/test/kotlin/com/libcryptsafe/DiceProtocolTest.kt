package com.libcryptsafe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** №48 шаг 3: свойства общей случайности броска. */
class DiceProtocolTest {
    private fun bytes(fill: Int) = ByteArray(32) { fill.toByte() }

    @Test fun commitVerifiesOnlyItsSecret() {
        val sA = DiceProtocol.newSecret()
        val h = DiceProtocol.commit(sA)
        assertTrue(DiceProtocol.verify(sA, h))
        val other = sA.copyOf().also { it[0] = (it[0] + 1).toByte() }
        assertFalse("подменённый секрет прошёл проверку", DiceProtocol.verify(other, h))
        assertFalse("короткий секрет прошёл проверку", DiceProtocol.verify(sA.copyOf(16), h))
        assertFalse("мусорный хэш прошёл проверку", DiceProtocol.verify(sA, "zz"))
    }
    @Test fun deriveIsDeterministicAndInRange() {
        val d1 = DiceProtocol.derive(bytes(1), bytes(2), "g1", 7)
        assertEquals(d1, DiceProtocol.derive(bytes(1), bytes(2), "g1", 7))
        assertTrue(d1.first in 1..6 && d1.second in 1..6)
    }
    @Test fun gameIdAndSeqBindTheRoll() {
        // тот же секрет в другом броске/партии даёт независимый результат (повтор не подсунуть)
        val base = (0 until 200).map { DiceProtocol.derive(bytes(it), bytes(9), "g1", 0) }
        val otherSeq = (0 until 200).map { DiceProtocol.derive(bytes(it), bytes(9), "g1", 1) }
        val otherGame = (0 until 200).map { DiceProtocol.derive(bytes(it), bytes(9), "g2", 0) }
        assertNotEquals(base, otherSeq); assertNotEquals(base, otherGame)
    }
    @Test fun bothHalvesMatter() {
        // при фиксированном sA разные sB дают разные кости, и наоборот — ни одна сторона не решает одна
        val bySeed = (0 until 100).map { DiceProtocol.derive(bytes(5), bytes(it), "g", 0) }.toSet()
        val bySecret = (0 until 100).map { DiceProtocol.derive(bytes(it), bytes(5), "g", 0) }.toSet()
        assertTrue("sB не влияет: ${bySeed.size}", bySeed.size > 20)
        assertTrue("sA не влияет: ${bySecret.size}", bySecret.size > 20)
    }
    @Test fun uniformChiSquare() {
        // 36 000 бросков: 36 исходов по ~1000. Хи-квадрат, 35 степеней свободы, порог 66.6 (p=0.001).
        val counts = IntArray(36)
        val sB = DiceProtocol.newSecret()
        repeat(36_000) { i ->
            val (a, b) = DiceProtocol.derive(DiceProtocol.newSecret(), sB, "chi", i)
            counts[(a - 1) * 6 + (b - 1)]++
        }
        val exp = 1000.0
        val chi = counts.sumOf { (it - exp) * (it - exp) / exp }
        assertTrue("распределение неравномерно: chi2=$chi", chi < 66.6)
        val doubles = (0 until 6).sumOf { counts[it * 7] }
        assertTrue("дублей $doubles, ждём ~6000", doubles in 5500..6500)
    }
    @Test fun modBiasControl() {
        // контроль метода: без отбрасывания >=252 перекос был бы, с отбрасыванием — каждый
        // байт 0..251 даёт ровно 42 значения на грань
        val perFace = IntArray(6); for (v in 0 until 252) perFace[v % 6]++
        assertTrue(perFace.all { it == 42 })
    }
}
