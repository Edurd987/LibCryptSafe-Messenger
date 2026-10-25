package com.libcryptsafe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Random

// «Пропускает ли бот ходы?» — фактом: каждый ход играется как в NardiBoardView.botStep,
// сыгранные зары сравниваются с maxDiceUsable в начале хода.
object BotPlayAudit {
    data class Stats(var turns: Int = 0, var violations: Int = 0, var fallbacks: Int = 0,
                     var fullBurns: Int = 0, var partial: Int = 0, var maxMs: Long = 0, var totalMs: Long = 0,
                     val examples: MutableList<String> = mutableListOf())

    private fun legal(s: NardiGameState, m: NardiMove) =
        if (m.to == -1) canBearOff(s, m.from) else isLegalMove(s, m.from, m.to)

    fun boardTurn(start: NardiGameState, net: NardiNet?, st: Stats): NardiGameState {
        val me = start.turn
        val max = maxDiceUsable(start)
        st.turns++
        if (max == 0) st.fullBurns++ else if (max < start.dice!!.size) st.partial++
        if (!hasAnyLegalMove(start)) return burnTurn(start)
        val plan = ArrayDeque<NardiMove>()
        if (net != null) {
            val t0 = System.nanoTime()
            plan.addAll(net.chooseTurn(start))
            val ms = (System.nanoTime() - t0) / 1_000_000
            st.totalMs += ms; st.maxMs = maxOf(st.maxMs, ms)
        }
        var s = start; var used = 0
        while (s.turn == me && s.dice != null) {
            var m: NardiMove? = if (net == null) botChooseMove(s) else plan.removeFirstOrNull()
            if (net != null && m != null && !legal(s, m)) { st.fallbacks++; plan.clear(); m = botChooseMove(s) }
            if (m == null) { s = burnTurn(s); break }
            s = botApplyMove(s, m); used++
            if (s.dice != null && !hasAnyLegalMove(s)) s = burnTurn(s)
        }
        if (used != max) {
            st.violations++
            if (st.examples.size < 5) st.examples += "dice=${start.dice} max=$max сыграно=$used ${NardiNet.key(start)}"
        }
        return s
    }

    fun run(net: NardiNet, games: Int, seed: Long): Triple<Stats, Stats, Int> {
        val rnd = Random(seed)
        val sNet = Stats(); val sEasy = Stats(); var netWins = 0
        repeat(games) {
            var a: Int; var c: Int
            do { a = rnd.nextInt(6) + 1; c = rnd.nextInt(6) + 1 } while (a == c)
            var s = initLongNardi().copy(dice = listOf(a, c), turn = if (a > c) PlayerType.WHITE else PlayerType.BLACK,
                isOpening = false, rolledDouble = false)
            var guard = 0
            while (winner(s) == null && guard++ < 1000) {
                if (s.dice == null) s = applyRoll(s, rnd.nextInt(6) + 1, rnd.nextInt(6) + 1)
                s = if (s.turn == PlayerType.BLACK) boardTurn(s, net, sNet) else boardTurn(s, null, sEasy)
            }
            if (winner(s) == PlayerType.BLACK) netWins++
        }
        return Triple(sNet, sEasy, netWins)
    }
}

class NardiBotPlayTest {
    @Test fun bot_never_skips_playable_dice() {
        val net = NardiNet.load(File("src/main/assets/nardi_long.bin").readBytes())
        val games = 200
        val (n, e, wins) = BotPlayAudit.run(net, games, 20261025L)
        println("СИЛЬНЫЙ: ходов ${n.turns}, нарушений ${n.violations}, страховок ${n.fallbacks}, " +
            "ходов без единого хода ${n.fullBurns}, сыграно меньше всех заров ${n.partial}, думал max ${n.maxMs} мс, " +
            "в среднем ${n.totalMs / maxOf(n.turns, 1)} мс")
        println("ЛЁГКИЙ:  ходов ${e.turns}, нарушений ${e.violations}, без единого хода ${e.fullBurns}, меньше всех заров ${e.partial}")
        println("Побед сильного: $wins из $games")
        n.examples.forEach { println("  пример: $it") }
        assertEquals("сильный бот сыграл меньше заров, чем мог", 0, n.violations)
        assertEquals("лёгкий бот сыграл меньше заров, чем мог", 0, e.violations)
        assertEquals("план сети оказался нелегальным", 0, n.fallbacks)
        assertTrue("контроль: партии реально шли", n.turns > games * 20)
    }
}
