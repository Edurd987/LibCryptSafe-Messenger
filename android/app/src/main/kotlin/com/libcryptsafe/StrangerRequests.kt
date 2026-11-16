package com.libcryptsafe

import com.libcryptsafe.db.StrangerRequestRow

// №45b шаг 2: экран «Запросы» — незнакомцы, приславшие текст (messages.fromStranger = 1).
// Чистая логика (JVM-тест StrangerRequestsTest): кого показывать и как показать безопасно.
//  - только по ПОЛНОМУ ID; контакты и заблокированные не показываются (у них не «запрос»);
//  - свежие сверху;
//  - превью: управляющие символы -> пробел (нет «невидимых» строк и подмены направления текста),
//    пробелы схлопнуты, длина ограничена — чужой текст не ломает экран.
object StrangerRequests {
    const val PREVIEW_MAX = 60
    const val TEXT_MAX = 2000      // одно сообщение в окне просмотра

    data class Row(val peerId: String, val count: Int, val lastTs: Long)

    fun build(rows: List<StrangerRequestRow>, contacts: Set<String>, blocked: Set<String>): List<Row> =
        rows.filter { it.peerId !in contacts && it.peerId !in blocked && it.cnt > 0 }
            .sortedByDescending { it.lastTs }
            .map { Row(it.peerId, it.cnt, it.lastTs) }

    /** Безопасный показ чужого текста: без управляющих и «направляющих» символов, с ограничением длины. */
    fun safeText(raw: String, max: Int = TEXT_MAX): String {
        val sb = StringBuilder(minOf(raw.length, max + 1))
        var lastSpace = false
        for (ch in raw) {
            val bad = Character.isISOControl(ch) || ch in '\u200B'..'\u200F' || ch in '\u202A'..'\u202E' ||
                      ch in '\u2066'..'\u2069' || ch == '\uFEFF'
            val c = if (bad || ch.isWhitespace()) ' ' else ch
            if (c == ' ') { if (lastSpace) continue; lastSpace = true } else lastSpace = false
            sb.append(c)
            if (sb.length > max) break
        }
        val s = sb.toString().trim()
        return if (s.length > max) s.take(max).trimEnd() + "…" else s
    }

    fun preview(raw: String): String = safeText(raw, PREVIEW_MAX)
}
