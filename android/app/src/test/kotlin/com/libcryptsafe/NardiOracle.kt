package com.libcryptsafe

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Random

/**
 * ОРАКУЛ ПРАВИЛ длинных нард: Kotlin (истина, тот же код, что в APK) пишет
 * случайные позиции + ответы движка в bot/oracle_long.jsonl. Python-движок бота
 * обязан ответить ТОЧНО так же (bot/check_oracle.py). Расхождение = красный.
 *
 * Строка: b (24 числа: +белые, -чёрные), t (W/B), d (кости), h (снято с головы),
 * rd (бросок-дубль), ow/ob (выброшено), steps (легальные шаги [from,to], to=-1 выброс),
 * max (maxDiceUsable), finals (все позиции после ПОЛНОГО хода, отсортированы).
 */
object NardiOracle {
    private fun sgn(p: PointState) = when (p.player) { PlayerType.WHITE -> p.count; PlayerType.BLACK -> -p.count; else -> 0 }
    fun key(s: NardiGameState) = s.board.joinToString(",") { sgn(it).toString() } +
        "|${s.bornOffWhite}|${s.bornOffBlack}|${if (s.turn == PlayerType.WHITE) "W" else "B"}"

    fun steps(s: NardiGameState): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        if (s.dice == null) return out
        for (f in 0..23) {
            val p = s.board[f]
            if (p.count <= 0 || p.player != s.turn) continue
            for (t in 0..23) if (isLegalMove(s, f, t)) out.add(f to t)
            if (canBearOff(s, f)) out.add(f to -1)
        }
        return out
    }
    fun apply(s: NardiGameState, f: Int, t: Int): NardiGameState =
        if (t < 0) bearOff(s, f) else consumeDie(applyMove(s, f, t), moveDistance(s.board[f].player, f, t))

    // Все позиции после полного хода — через ПУБЛИЧНЫЕ правила (как играет человек/бот).
    fun finals(s: NardiGameState, memo: HashMap<String, Set<String>> = HashMap()): Set<String> {
        val mk = key(s) + "|" + s.dice + "|" + s.headCount
        memo[mk]?.let { return it }
        val st = steps(s)
        val res: Set<String> = if (st.isEmpty()) setOf(key(burnTurn(s))) else {
            val acc = HashSet<String>()
            for ((f, t) in st) {
                val n = apply(s, f, t)
                if (n.dice == null || n.turn != s.turn) acc.add(key(n)) else acc.addAll(finals(n, memo))
            }
            acc
        }
        memo[mk] = res
        return res
    }

    private fun line(s: NardiGameState): String {
        val b = s.board.joinToString(",") { sgn(it).toString() }
        val st = steps(s).sortedWith(compareBy({ it.first }, { it.second })).joinToString(",") { "[${it.first},${it.second}]" }
        val fin = finals(s).sorted().joinToString(",") { "\"$it\"" }
        return "{\"b\":[$b],\"t\":\"${if (s.turn == PlayerType.WHITE) "W" else "B"}\",\"d\":${s.dice},\"h\":${s.headCount}," +
            "\"rd\":${s.rolledDouble},\"ow\":${s.bornOffWhite},\"ob\":${s.bornOffBlack},\"steps\":[$st],\"max\":${maxDiceUsable(s)},\"finals\":[$fin]}"
    }

    /** Случайные партии случайными ЛЕГАЛЬНЫМИ шагами (не ботом — шире разнообразие). */
    fun dump(out: File, positions: Int, seed: Long): Int {
        val rnd = Random(seed)
        val lines = ArrayList<String>()
        while (lines.size < positions) {
            // розыгрыш как rollOpening, но детерминированно (seed)
            var a: Int; var c: Int
            do { a = rnd.nextInt(6) + 1; c = rnd.nextInt(6) + 1 } while (a == c)
            var s = initLongNardi().copy(dice = listOf(a, c), turn = if (a > c) PlayerType.WHITE else PlayerType.BLACK,
                isOpening = false, rolledDouble = false)
            lines.add(line(s))                                           // ход розыгрыша
            var guard = 0
            while (winner(s) == null && guard++ < 2000 && lines.size < positions) {
                if (s.dice == null) {
                    val head = if (s.turn == PlayerType.WHITE) 23 else 11
                    val firstTurn = s.board[head].count == 15
                    // первый ход игрока: часто подсовываем особый дубль 6/4/3 (R1)
                    val (x, y) = if (firstTurn && rnd.nextInt(2) == 0) intArrayOf(6, 4, 3)[rnd.nextInt(3)].let { it to it }
                                 else (rnd.nextInt(6) + 1) to (rnd.nextInt(6) + 1)
                    s = applyRoll(s, x, y)
                    lines.add(line(s))                                   // начало хода — всегда
                } else if (rnd.nextInt(3) == 0) lines.add(line(s))       // середина хода — иногда
                val st = steps(s)
                if (st.isEmpty()) { s = burnTurn(s); continue }
                val (f, t) = st[rnd.nextInt(st.size)]
                s = apply(s, f, t)
            }
        }
        out.parentFile?.mkdirs()
        out.writeText(lines.take(positions).joinToString("\n") + "\n")
        return positions
    }
}

/**
 * ОРАКУЛ «ЛЁГКОГО» БОТА (NardiBot.kt — тот, что сейчас в APK): позиция в начале
 * хода -> evaluateState для обеих сторон + позиция после ПОЛНОГО хода бота (как
 * botStep в NardiBoardView: жадно по одному шагу, затем сжигание, если ходов нет).
 * Python-копия (bot/easy_bot.py) обязана совпасть — тогда это честный соперник.
 */
object EasyBotOracle {
    private fun opp(p: PlayerType) = if (p == PlayerType.WHITE) PlayerType.BLACK else PlayerType.WHITE

    fun botTurn(start: NardiGameState): NardiGameState {
        var s = start
        val me = s.turn
        while (s.turn == me && s.dice != null) {
            val m = botChooseMove(s) ?: return burnTurn(s)
            s = botApplyMove(s, m)
            if (s.dice != null && !hasAnyLegalMove(s)) s = burnTurn(s)
        }
        return s
    }

    fun dump(out: File, positions: Int, seed: Long): Int {
        val rnd = Random(seed)
        val lines = ArrayList<String>()
        while (lines.size < positions) {
            var a: Int; var c: Int
            do { a = rnd.nextInt(6) + 1; c = rnd.nextInt(6) + 1 } while (a == c)
            var s = initLongNardi().copy(dice = listOf(a, c), turn = if (a > c) PlayerType.WHITE else PlayerType.BLACK,
                isOpening = false, rolledDouble = false)
            var guard = 0
            while (winner(s) == null && guard++ < 500 && lines.size < positions) {
                if (s.dice == null) s = applyRoll(s, rnd.nextInt(6) + 1, rnd.nextInt(6) + 1)
                val b = s.board.joinToString(",") { when (it.player) { PlayerType.WHITE -> it.count; PlayerType.BLACK -> -it.count; else -> 0 }.toString() }
                val fin = botTurn(s)
                lines.add("{\"b\":[$b],\"t\":\"${if (s.turn == PlayerType.WHITE) "W" else "B"}\",\"d\":${s.dice},\"h\":${s.headCount}," +
                    "\"rd\":${s.rolledDouble},\"ow\":${s.bornOffWhite},\"ob\":${s.bornOffBlack}," +
                    "\"ev\":${evaluateState(s, s.turn)},\"evo\":${evaluateState(s, opp(s.turn))},\"fin\":\"${NardiOracle.key(fin)}\"}")
                // продолжаем партию СЛУЧАЙНЫМИ полными ходами (разнообразие позиций)
                val fins = NardiOracle.finals(s).sorted()
                val k = fins[rnd.nextInt(fins.size)]
                s = fromKey(k)
            }
        }
        out.parentFile?.mkdirs()
        out.writeText(lines.take(positions).joinToString("\n") + "\n")
        return positions
    }

    private fun fromKey(k: String): NardiGameState {
        val parts = k.split("|")
        val b = parts[0].split(",").map { it.toInt() }
        return NardiGameState(
            board = b.map { v -> PointState(kotlin.math.abs(v), if (v > 0) PlayerType.WHITE else if (v < 0) PlayerType.BLACK else PlayerType.NONE) },
            dice = null, turn = if (parts[3] == "W") PlayerType.WHITE else PlayerType.BLACK,
            isFirstTurn = false, isOpening = false, bornOffWhite = parts[1].toInt(), bornOffBlack = parts[2].toInt())
    }
}

class NardiOracleTest {
    @Test fun dump_easy_bot_oracle() {
        val f = File("../../bot/oracle_easy.jsonl")
        EasyBotOracle.dump(f, 1500, 20261025L)
        assertTrue(f.readLines().size == 1500)
    }

    // ./gradlew testDebugUnitTest --tests com.libcryptsafe.NardiOracleTest  (cwd = android/app)
    @Test fun dump_oracle() {
        val f = File("../../bot/oracle_long.jsonl")
        NardiOracle.dump(f, 3000, 20261024L)
        assertTrue(f.readLines().size == 3000)
    }
}
