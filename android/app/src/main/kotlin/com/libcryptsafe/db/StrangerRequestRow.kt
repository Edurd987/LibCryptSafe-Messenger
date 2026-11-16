package com.libcryptsafe.db

// №45b: строка выборки «Запросы» (Room сопоставляет столбцы по именам: peerId, cnt, lastTs).
data class StrangerRequestRow(val peerId: String, val cnt: Int, val lastTs: Long)
