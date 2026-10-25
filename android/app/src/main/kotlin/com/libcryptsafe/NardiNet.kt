package com.libcryptsafe

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Обученный бот длинных нард (TD-Gammon, обучен в bot/train.py). Чистый Kotlin, без Android:
 * проверяется JVM-тестом NardiNetTest против Python (bot/net_check.jsonl).
 *
 * Сеть 196 -> H (сигмоида) -> 5 (сигмоида), Double. Выходы — от лица того, чей ход:
 * победа, победа марсом+, победа коксом, поражение марсом+, поражение коксом.
 * Файл весов: "NRD1", hidden (int32 LE), затем W1,b1,W2,b2 как float64 LE.
 */
class NardiNet private constructor(
    private val hidden: Int,
    private val w1: DoubleArray, private val b1: DoubleArray,   // w1[h * N_IN + i]
    private val w2: DoubleArray, private val b2: DoubleArray,   // w2[o * hidden + h]
) {
    companion object {
        const val N_IN = 196
        const val N_OUT = 5

        fun load(bytes: ByteArray): NardiNet {
            require(bytes.size >= 8 && String(bytes, 0, 4, Charsets.US_ASCII) == "NRD1") { "не файл весов нард" }
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            bb.position(4)
            val h = bb.int
            require(h in 1..1024) { "странный размер скрытого слоя: $h" }
            fun take(n: Int) = DoubleArray(n) { bb.double }
            val need = 8 + 8 * (h * N_IN + h + N_OUT * h + N_OUT)
            require(bytes.size == need) { "размер файла ${bytes.size}, ожидалось $need" }
            val net = NardiNet(h, take(h * N_IN), take(h), take(N_OUT * h), take(N_OUT))
            // NaN/бесконечность в весах -> бот молча сжигал бы ходы. Такой файл не принимаем.
            require(listOf(net.w1, net.b1, net.w2, net.b2).all { a -> a.all { it.isFinite() } }) { "в весах NaN/бесконечность" }
            return net
        }

        private fun sig(z: Double) = 1.0 / (1.0 + Math.exp(-z))

        /** Кодирование как bot/encode.py: от лица state.turn. */
        fun encode(s: NardiGameState): DoubleArray {
            val me = s.turn
            val x = DoubleArray(N_IN)
            var pipMe = 0; var pipOp = 0
            for (idx in 0..23) {
                val pt = s.board[idx]
                if (pt.count <= 0 || pt.player == PlayerType.NONE) continue
                val pos = routeFor(pt.player).indexOf(idx)
                val n = pt.count
                val base = (if (pt.player == me) 0 else 96) + pos * 4
                x[base] = if (n >= 1) 1.0 else 0.0
                x[base + 1] = if (n >= 2) 1.0 else 0.0
                x[base + 2] = if (n >= 3) 1.0 else 0.0
                x[base + 3] = maxOf(n - 3, 0) / 12.0
                if (pt.player == me) pipMe += (24 - pos) * n else pipOp += (24 - pos) * n
            }
            val offMe = if (me == PlayerType.WHITE) s.bornOffWhite else s.bornOffBlack
            val offOp = if (me == PlayerType.WHITE) s.bornOffBlack else s.bornOffWhite
            x[192] = offMe / 15.0; x[193] = offOp / 15.0
            x[194] = pipMe / 360.0; x[195] = pipOp / 360.0
            return x
        }

        fun equity(v: DoubleArray) = 2 * v[0] - 1 + v[1] + v[2] - v[3] - v[4]

        /** Ключ позиции после хода — тот же формат, что bot/nardi_engine.key (для сверки). */
        fun key(s: NardiGameState): String =
            s.board.joinToString(",") {
                when (it.player) { PlayerType.WHITE -> it.count; PlayerType.BLACK -> -it.count; else -> 0 }.toString()
            } + "|${s.bornOffWhite}|${s.bornOffBlack}|${if (s.turn == PlayerType.WHITE) "W" else "B"}"

        private fun step(s: NardiGameState, m: NardiMove) = botApplyMove(s, m)

        /** Все позиции после ПОЛНОГО хода (по публичным правилам, R1/R2) -> путь шагов к каждой.
         *  Для одной позиции берётся первый найденный путь (порядок шагов детерминирован). */
        fun fullTurns(s: NardiGameState): Map<String, Pair<NardiGameState, List<NardiMove>>> {
            val out = HashMap<String, Pair<NardiGameState, List<NardiMove>>>()
            val seen = HashSet<String>()
            fun dfs(cur: NardiGameState, path: List<NardiMove>) {
                val moves = botCollectMoves(cur)
                if (moves.isEmpty()) {
                    val f = burnTurn(cur)
                    out.getOrPut(key(f)) { f to path }
                    return
                }
                for (m in moves) {
                    val n = step(cur, m)
                    val p = path + m
                    if (n.dice == null || n.turn != cur.turn) out.getOrPut(key(n)) { n to p }
                    else if (seen.add(key(n) + "|" + n.dice + "|" + n.headCount)) dfs(n, p)
                }
            }
            if (s.dice == null) return out
            dfs(s, emptyList())
            return out
        }
    }

    fun forward(x: DoubleArray): DoubleArray {
        val h = DoubleArray(hidden)
        for (j in 0 until hidden) {
            var z = b1[j]
            val off = j * N_IN
            for (i in 0 until N_IN) z += w1[off + i] * x[i]
            h[j] = sig(z)
        }
        return DoubleArray(N_OUT) { o ->
            var z = b2[o]
            val off = o * hidden
            for (j in 0 until hidden) z += w2[off + j] * h[j]
            sig(z)
        }
    }

    /** Лучший полный ход: максимум эквити ходящего (= минимум эквити соперника после хода).
     *  Ничьи — первый по ключу (как bot/train.choose). Пустой список = ходов нет. */
    fun chooseTurn(s: NardiGameState): List<NardiMove> {
        val fins = fullTurns(s)
        var best: List<NardiMove> = emptyList(); var bestV = Double.NEGATIVE_INFINITY
        for (k in fins.keys.sorted()) {
            val (f, path) = fins.getValue(k)
            val v = if (winner(f) != null) winType(f).toDouble() else -equity(forward(encode(f)))
            if (v > bestV) { bestV = v; best = path }
        }
        return best
    }
}
