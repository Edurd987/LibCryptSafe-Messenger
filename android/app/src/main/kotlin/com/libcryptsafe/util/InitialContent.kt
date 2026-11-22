package com.libcryptsafe.util

import org.json.JSONObject

/**
 * Что несёт X3DH-рукопожатие. Новый формат — тот же JSON v1 сообщения чата {v:1, n, t, ts}, что и обычное
 * сообщение: тогда первое сообщение идёт общим конвейером (handleDecrypted: ACK, защита от повтора по n/ts,
 * политика незнакомцев, порядок через inChat). Старый формат — голый текст (deliverIncoming, как раньше).
 */
object InitialContent {
    fun isChatV1(content: String): Boolean {
        val s = content.trimStart()
        if (!s.startsWith("{")) return false
        return try {
            val j = JSONObject(s)
            j.optInt("v", 0) == 1 && j.has("t") && j.optString("n", "").isNotEmpty()
        } catch (e: Exception) { false }
    }
}
