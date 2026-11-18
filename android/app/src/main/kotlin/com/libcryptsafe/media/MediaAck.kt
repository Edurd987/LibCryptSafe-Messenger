package com.libcryptsafe.media

import org.json.JSONObject

/**
 * ✓✓ для медиа — без нового протокола, через существующий ACK {"v":1,"a":<маркер>}.
 * Отправитель помечает пузырь маркером nonce(transferId из своего MEDIA_INIT);
 * получатель после сборки шлёт ACK с nonce(transferId собранной передачи) — ТОЛЬКО контакту (№45b).
 * transferId — Base64 (NO_WRAP, стандартный алфавит) от 16 случайных байт, ровно как в MediaSerializer.
 * Старые клиенты: незнакомый маркер ни с чем не совпадёт — безвредно.
 */
object MediaAck {
    const val PREFIX = "media:"

    fun nonce(transferIdB64: String): String = PREFIX + transferIdB64

    /** transferId из конверта MEDIA_INIT (как его отправит отправитель); для остальных кадров — null. */
    fun transferIdOf(envelopeJson: String): String? {
        val j = try { JSONObject(envelopeJson) } catch (e: Exception) { return null }
        if (j.optString("type", "") != ContentType.MEDIA_INIT.wire) return null
        return j.optString("transferId", "").ifEmpty { null }
    }
}
