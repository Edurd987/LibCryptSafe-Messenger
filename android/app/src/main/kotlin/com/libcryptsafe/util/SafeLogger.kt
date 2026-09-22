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
 * R8 в release дополнительно ВЫРЕЖЕТ эти вызовы из байткода (proguard-rules:
 * -assumenosideeffects), так что в release-APK не останется даже строк логов.
 */
object SafeLogger {
    fun d(tag: String, msg: String) { if (BuildConfig.DEBUG) android.util.Log.d(tag, msg) }
    fun i(tag: String, msg: String) { if (BuildConfig.DEBUG) android.util.Log.i(tag, msg) }
    fun w(tag: String, msg: String) { if (BuildConfig.DEBUG) android.util.Log.w(tag, msg) }
    // e тоже гейтим: e.message часто содержит пути/состояние -> утечка в release.
    fun e(tag: String, msg: String) { if (BuildConfig.DEBUG) android.util.Log.e(tag, msg) }
}
