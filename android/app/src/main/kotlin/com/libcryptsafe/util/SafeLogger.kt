package com.libcryptsafe.util

import com.libcryptsafe.BuildConfig

/**
 * Логгер с ГЕЙТИНГОМ по типу сборки. В release (BuildConfig.DEBUG == false) все
 * методы — no-op: НИ ОДНА строка не пишется в logcat. В debug — работают как
 * обычный android.util.Log.
 *
 * ЗАЧЕМ: наши логи светят метаданные (peerId, targetId, размеры, e.message с
 * путями) — на изъятом телефоне через logcat это обнажает граф связей. В release
 * этого быть НЕ должно. Плюс Google Play Pre-launch scanner блокирует утечку.
 *
 * ФУНДАМЕНТ ПОД КОШЕЛЁК: тот же принцип — ключи/seed НИКОГДА в лог, даже debug.
 *
 * №18: сообщение — ЛЕНИВАЯ лямбда { "..." }. inline + BuildConfig.DEBUG=false ->
 * R8 выкидывает тело целиком: в release-APK нет ни строк логов, ни их вычислений
 * (замер: строки логов в dex = 0). Строковой перегрузки НЕТ намеренно —
 * компилятор не даст написать неленивый лог.
 */
object SafeLogger {
    inline fun d(tag: String, msg: () -> String) { if (BuildConfig.DEBUG) android.util.Log.d(tag, msg()) }
    inline fun i(tag: String, msg: () -> String) { if (BuildConfig.DEBUG) android.util.Log.i(tag, msg()) }
    inline fun w(tag: String, msg: () -> String) { if (BuildConfig.DEBUG) android.util.Log.w(tag, msg()) }
    // e тоже гейтим: e.message часто содержит пути/состояние -> утечка в release.
    inline fun e(tag: String, msg: () -> String) { if (BuildConfig.DEBUG) android.util.Log.e(tag, msg()) }
}
