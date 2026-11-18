package com.libcryptsafe.media

import org.json.JSONObject

/**
 * №23: защита от повтора медиа. У медиа нет nonce и метки времени — только случайный
 * transferId (16 байт) в MEDIA_INIT. Сборщик после сборки о передаче забывает, поэтому повтор
 * записанных кадров (INIT + чанки + DONE) показывал фото снова. Запоминаем transferId каждого
 * INIT в seen_nonces (ключ "media:<id>") — ПОСТОЯННО, как "x3dh:": без метки времени окно не работает.
 * Повторный INIT отбрасывается; его чанки и DONE контроллер сам отбросит (нет ephKey / нет INIT).
 * Новая отправка того же файла — новый transferId, проходит.
 * Чистая логика (JVM-тест MediaReplayTest); markSeen даёт вызывающая сторона (БД, IO-поток).
 */
object MediaReplayGuard {
    const val PREFIX = "media:"
    private const val MAX_ID_LEN = 64            // Base64 от 16 байт = 24 символа; мусор в базу не пишем

    /** Ключ для seen_nonces, если это MEDIA_INIT с разумным transferId; иначе null. */
    fun initKey(raw: String): String? {
        val j = try { JSONObject(raw) } catch (e: Exception) { return null }
        if (j.optString("type", "") != ContentType.MEDIA_INIT.wire) return null
        val id = j.optString("transferId", "")
        if (id.isEmpty() || id.length > MAX_ID_LEN) return null
        return PREFIX + id
    }

    /** true — пропустить кадр; false — повтор MEDIA_INIT, выбросить.
     *  markSeen(key) = true, если ключ записан впервые (атомарно в БД). */
    fun admit(raw: String, markSeen: (String) -> Boolean): Boolean {
        val key = initKey(raw) ?: return true
        return markSeen(key)
    }
}
