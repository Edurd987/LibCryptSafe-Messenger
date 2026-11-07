package com.libcryptsafe

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Короткие нарды: шашка на баре. Доска должна знать, КУДА можно войти, чтобы подсветить.
 * Позиция — со скриншота партии 2026-11-06 (белые: 14 на доске + 1 на баре, кости 1 и 6).
 */
class NardiBarTest {
    private fun screenshotPosition(): NardiGameState {
        val b = MutableList(24) { PointState(0, PlayerType.NONE) }
        fun w(i: Int, n: Int) { b[i] = PointState(n, PlayerType.WHITE) }
        fun k(i: Int, n: Int) { b[i] = PointState(n, PlayerType.BLACK) }
        w(13, 3); w(0, 1); w(1, 2); w(2, 2); w(3, 2); w(5, 2); w(10, 2)
        k(21, 2); k(20, 2); k(19, 1); k(18, 5); k(6, 1); k(8, 1); k(11, 3)
        return applyRoll(NardiGameState(board = b, dice = null, turn = PlayerType.WHITE,
            isOpening = false, barWhite = 1), 1, 6)
    }

    @Test fun positionIsConsistent() {           // контроль: 15 белых и 15 чёрных
        val s = screenshotPosition()
        assertEquals(14, s.board.filter { it.player == PlayerType.WHITE }.sumOf { it.count })
        assertEquals(15, s.board.filter { it.player == PlayerType.BLACK }.sumOf { it.count })
    }
    @Test fun entryTargetsFromScreenshot() {
        // кость 6 -> пункт 18 (5 чёрных, закрыт); кость 1 -> пункт 23 (пусто) — единственный вход
        assertEquals(listOf(23), NardiEngine.barEntryTargets(NardiVariant.SHORT, screenshotPosition()))
    }
    @Test fun noBarNoTargets() {
        assertEquals(emptyList<Int>(), NardiEngine.barEntryTargets(NardiVariant.SHORT, screenshotPosition().copy(barWhite = 0)))
    }
    @Test fun longHasNoBar() {
        assertEquals(emptyList<Int>(), NardiEngine.barEntryTargets(NardiVariant.LONG, screenshotPosition()))
    }
    @Test fun noDiceNoTargets() {
        assertEquals(emptyList<Int>(), NardiEngine.barEntryTargets(NardiVariant.SHORT, screenshotPosition().copy(dice = null)))
    }
    @Test fun doubleGivesOneTarget() {          // дубль 1,1: кость повторяется, пункт один
        val s = applyRoll(screenshotPosition().copy(dice = null), 1, 1)
        assertEquals(listOf(23), NardiEngine.barEntryTargets(NardiVariant.SHORT, s))
    }
}
