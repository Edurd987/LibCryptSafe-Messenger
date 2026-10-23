package com.libcryptsafe

import org.junit.Assert.*
import org.junit.Test

// Правила длинных нард. JVM-тест (без телефона): ./gradlew testDebugUnitTest
class NardiRulesTest {
    private val W = PlayerType.WHITE; private val B = PlayerType.BLACK
    private fun st(m: Map<Int, Pair<Int, PlayerType>>, dice: List<Int>) = NardiGameState(
        board = List(24) { i -> m[i]?.let { PointState(it.first, it.second) } ?: PointState(0, PlayerType.NONE) },
        dice = dice, turn = W, isFirstTurn = false, isOpening = false)
    private fun mv(s: NardiGameState, f: Int, t: Int) =
        consumeDie(applyMove(s, f, t), moveDistance(s.board[f].player, f, t))

    // R1: первый ход ЧЁРНЫХ дублем 6-6 -> можно снять ДВЕ с головы
    private fun blackFirst66(): NardiGameState {
        var s = initLongNardi().copy(isOpening = false, dice = listOf(5, 2))
        s = mv(s, 23, 18); s = mv(s, 18, 16)                 // первый ход белых
        assertEquals(B, s.turn)
        s = applyRoll(s, 6, 6)
        return mv(s, 11, 5)                                  // первая с головы
    }
    @Test fun r1_black_first_double_two_from_head() =
        assertTrue("вторая с головы при первом 6-6 обязана быть легальна", isLegalMove(blackFirst66(), 11, 5))
    @Test fun r1_control_third_from_head_forbidden() =
        assertFalse(isLegalMove(mv(blackFirst66(), 11, 5), 11, 5))

    // R2a: можно сыграть только один зар -> обязан больший (3), не 1
    @Test fun r2a_only_one_die_must_play_larger() {
        val s = st(mapOf(10 to (1 to W), 0 to (14 to W), 6 to (15 to B)), listOf(3, 1))
        assertTrue("контроль: больший зар легален", isLegalMove(s, 10, 7))
        assertFalse("меньший зар при сгорании большего нелегален", isLegalMove(s, 10, 9))
    }
    // R2b: если можно сыграть оба — ход, сжигающий второй зар, нелегален
    @Test fun r2b_must_use_both_dice() {
        val s = st(mapOf(10 to (1 to W), 4 to (1 to W), 0 to (13 to W), 6 to (14 to B), 3 to (1 to B)), listOf(3, 1))
        assertTrue("контроль: 10->9 ведёт к игре обоих", isLegalMove(s, 10, 9))
        assertFalse("10->7 сжигает зар 1 при доступной игре обоих", isLegalMove(s, 10, 7))
    }
}
