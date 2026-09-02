package com.libcryptsafe.media

/**
 * Боевая реализация Base64Codec поверх android.util.Base64 (NO_WRAP —
 * тот же флаг, что весь существующий протокол проекта).
 *
 * Отдельный файл: MediaSerializer зависит от интерфейса Base64Codec и остаётся
 * desktop-тестируемым (там подставляется java.util.Base64); эта привязка к
 * android.util компилируется только в реальном проекте.
 */
class AndroidBase64Codec : Base64Codec {
    override fun encode(data: ByteArray): String =
        android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP)
    override fun decode(s: String): ByteArray =
        android.util.Base64.decode(s, android.util.Base64.NO_WRAP)
}
