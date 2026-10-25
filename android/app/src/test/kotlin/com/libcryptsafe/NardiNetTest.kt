package com.libcryptsafe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * Kotlin-бот == Python-бот на тех же весах. Эталон: bot/net_check.tsv (bot/export_check.py).
 * Входы сети — точно; выходы — до 1e-12; выбранный полный ход — точно (кроме почти-ничьих).
 */
object NardiNetCheck {
    data class Report(val rows: Int, val encBad: Int, val maxOutDiff: Double, val choiceBad: Int,
                      val nearTies: Int, val maxMs: Long, val weightsOk: Boolean)

    private fun ints(s: String) = if (s.isEmpty()) emptyList() else s.split(",").map { it.trim().toInt() }
    private fun dbls(s: String) = s.split(",").map { it.toDouble() }

    fun state(c: List<String>): NardiGameState {
        val b = ints(c[0])
        return NardiGameState(
            board = b.map { v -> PointState(kotlin.math.abs(v), if (v > 0) PlayerType.WHITE else if (v < 0) PlayerType.BLACK else PlayerType.NONE) },
            dice = ints(c[2]), turn = if (c[1] == "W") PlayerType.WHITE else PlayerType.BLACK,
            headCount = c[3].toInt(), isFirstTurn = false, isOpening = false,
            bornOffWhite = c[5].toInt(), bornOffBlack = c[6].toInt(), rolledDouble = c[4] == "1")
    }

    fun run(tsv: File, weights: File): Report {
        val wBytes = weights.readBytes()
        val sha = MessageDigest.getInstance("SHA-256").digest(wBytes).joinToString("") { "%02x".format(it) }
        val lines = tsv.readLines().filter { it.isNotBlank() }
        val weightsOk = lines.first() == "#sha256\t$sha"
        val net = NardiNet.load(wBytes)
        var encBad = 0; var maxOut = 0.0; var choiceBad = 0; var ties = 0; var maxMs = 0L
        for (l in lines.drop(1)) {
            val c = l.split("\t")
            val s = state(c)
            val x = NardiNet.encode(s)
            if (x.toList() != dbls(c[7])) encBad++
            val out = net.forward(x)
            dbls(c[8]).forEachIndexed { i, v ->
                val d = kotlin.math.abs(v - out[i])
                maxOut = if (d.isNaN()) Double.POSITIVE_INFINITY else maxOf(maxOut, d)   // NaN = расхождение
            }
            val t0 = System.nanoTime()
            val path = net.chooseTurn(s)
            maxMs = maxOf(maxMs, (System.nanoTime() - t0) / 1_000_000)
            var f = s
            for (m in path) f = botApplyMove(f, m)
            if (f.dice != null && f.turn == s.turn) f = burnTurn(f)       // как botStep: ходов больше нет
            if (path.isEmpty()) f = burnTurn(s)
            if (NardiNet.key(f) != c[9]) { if (c[10].toDouble() < 1e-9) ties++ else choiceBad++ }
        }
        return Report(lines.size - 1, encBad, maxOut, choiceBad, ties, maxMs, weightsOk)
    }
}

class NardiNetTest {
    // cwd = android/app.  Веса: src/main/assets/nardi_long.bin, эталон: ../../bot/net_check.tsv
    @Test fun kotlin_bot_matches_python() {
        val r = NardiNetCheck.run(File("../../bot/net_check.tsv"), File("src/main/assets/nardi_long.bin"))
        println("NardiNetCheck: $r")
        assertTrue("веса в assets не те, что в эталоне (sha256)", r.weightsOk)
        assertTrue("мало позиций", r.rows >= 100)
        assertEquals("входы сети расходятся", 0, r.encBad)
        assertTrue("выходы расходятся: ${r.maxOutDiff}", r.maxOutDiff < 1e-12)
        assertEquals("выбранный ход расходится", 0, r.choiceBad)
    }

    // отрицательный контроль: чужие веса -> эталон обязан это заметить
    @Test fun control_wrong_weights_detected() {
        val tsv = File("../../bot/net_check.tsv")
        val tmp = File.createTempFile("nardi_wrong", ".bin")
        val b = File("src/main/assets/nardi_long.bin").readBytes()
        val i = b.size - 5 * 8 + 7                                    // старший байт смещения выхода «победа»
        b[i] = (b[i].toInt() xor 0x40).toByte()                       // порядок числа -> вес становится другим
        tmp.writeBytes(b)
        // порча обязана быть замечена: загрузчик отверг файл ИЛИ выходы разошлись
        val detected = try {
            val r = NardiNetCheck.run(tsv, tmp)
            !r.weightsOk && r.maxOutDiff > 1e-12
        } catch (e: IllegalArgumentException) { true }
        tmp.delete()
        assertTrue("порча весов не замечена", detected)
    }

    // загрузчик отвергает NaN в весах (иначе бот сжигал бы ходы)
    @Test fun loader_rejects_nan() {
        val b = File("src/main/assets/nardi_long.bin").readBytes()
        val nan = java.lang.Double.doubleToRawLongBits(Double.NaN)
        val off = b.size - 8
        for (k in 0 until 8) b[off + k] = (nan ushr (8 * k)).toByte()   // последний вес = NaN (little-endian)
        val rejected = try { NardiNet.load(b); false } catch (e: IllegalArgumentException) { true }
        assertTrue("NaN в весах принят", rejected)
    }
}
