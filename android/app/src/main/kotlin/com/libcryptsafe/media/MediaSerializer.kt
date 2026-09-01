package com.libcryptsafe.media

import org.json.JSONObject

/**
 * Сериализатор медиа-конвертов: структуры <-> JSON. Чистый упаковщик.
 *
 * ГРАНИЦЫ (осознанно узкие):
 *  - НЕ знает про крипто. ephKey приходит УЖЕ зашифрованным (сессионным ключом
 *    этажом выше); сериализатор просто кодирует его в Base64. chunk.bytes тоже
 *    приходят уже зашифрованными из MediaCrypto.
 *  - НЕ знает про сеть/сокеты. Отдаёт JSONObject; кто и как его шлёт — не его дело.
 *  - Встаёт в существующий СТРОКОВЫЙ JSON-протокол ("msg"/"channel_post"/...),
 *    так что relay прокидывает медиа-конверт вслепую как обычное сообщение.
 *
 * СИММЕТРИЯ (главная гарантия, доказана round-trip тестом):
 *   parse(serialize(x)) == x  байт-в-байт для каждого типа.
 * Ловушки, которые тест закрывает: Long не обрезать (getLong, не getInt),
 * Base64 одним флагом (NO_WRAP туда и обратно), мусор от relay -> null (не краш).
 *
 * Base64 через абстракцию Base64Codec: в проде — android.util.Base64.NO_WRAP,
 * в desktop-тесте — java.util.Base64. Один алгоритм, разные библиотеки.
 */

/** Абстракция Base64, чтобы сериализатор тестировался на desktop. */
interface Base64Codec {
    fun encode(data: ByteArray): String
    fun decode(s: String): ByteArray   // бросает при битой строке -> ловим в parse
}

class MediaSerializer(private val b64: Base64Codec) {

    // ---------- SERIALIZE (структура -> JSON) ----------

    /** ephKeyEncrypted — уже зашифрованный сессионным ключом (этажом выше). */
    fun serializeInit(init: MediaInit, ephKeyEncrypted: ByteArray): JSONObject =
        JSONObject().apply {
            put("type", ContentType.MEDIA_INIT.wire)
            put("transferId", b64.encode(init.transferId.bytes))
            put("mediaKind", init.mediaKind.id)
            put("totalBytes", init.totalBytes)      // Long — на парсинге getLong!
            put("totalChunks", init.totalChunks)
            put("chunkSize", init.chunkSize)
            put("sha256", b64.encode(init.sha256Full))
            put("ephKey", b64.encode(ephKeyEncrypted))
        }

    /** chunk.bytes — уже зашифрованный блоб из MediaCrypto. */
    fun serializeChunk(chunk: MediaChunk): JSONObject =
        JSONObject().apply {
            put("type", ContentType.MEDIA_CHUNK.wire)
            put("transferId", b64.encode(chunk.transferId.bytes))
            put("seq", chunk.seq)
            put("data", b64.encode(chunk.bytes))
        }

    fun serializeDone(done: MediaDone): JSONObject =
        JSONObject().apply {
            put("type", ContentType.MEDIA_DONE.wire)
            put("transferId", b64.encode(done.transferId.bytes))
        }

    fun serializeControl(ctrl: MediaControl): JSONObject =
        JSONObject().apply {
            put("type", ContentType.CONTROL.wire)
            put("transferId", b64.encode(ctrl.transferId.bytes))
            put("missing", org.json.JSONArray().apply { ctrl.missing.forEach { put(it) } })
        }

    // ---------- typeOf (для будущего диспетчера в NetworkManager) ----------

    /** Какой это медиа-тип (или null, если не наш — game/channel/msg обрабатывают
     *  свои модули). Следующий кирпич (интеграция) вызовет это в разборе входящих. */
    fun typeOf(json: JSONObject): ContentType? =
        json.optString("type", "").let { if (it.isEmpty()) null else ContentType.fromWire(it) }

    // ---------- PARSE (JSON -> структура), устойчиво: мусор -> null ----------

    /** Возвращает пару (MediaInit, ephKeyEncrypted) или null при любой порче. */
    fun parseInit(json: JSONObject): Pair<MediaInit, ByteArray>? = try {
        val tid = TransferId(b64.decode(json.getString("transferId")))
        val kind = MediaKind.entries.first { it.id == json.getInt("mediaKind") }
        val init = MediaInit(
            transferId = tid,
            mediaKind = kind,
            totalBytes = json.getLong("totalBytes"),     // Long, не Int!
            totalChunks = json.getInt("totalChunks"),
            chunkSize = json.getInt("chunkSize"),
            sha256Full = b64.decode(json.getString("sha256")),
            ephemeralKey = ByteArray(0)   // расшифрованный ключ не тут; ephKey отдельно
        )
        val ephEnc = b64.decode(json.getString("ephKey"))
        Pair(init, ephEnc)
    } catch (e: Exception) { null }

    fun parseChunk(json: JSONObject): MediaChunk? = try {
        MediaChunk(
            transferId = TransferId(b64.decode(json.getString("transferId"))),
            seq = json.getInt("seq"),
            bytes = b64.decode(json.getString("data"))
        )
    } catch (e: Exception) { null }

    fun parseDone(json: JSONObject): MediaDone? = try {
        MediaDone(TransferId(b64.decode(json.getString("transferId"))))
    } catch (e: Exception) { null }

    fun parseControl(json: JSONObject): MediaControl? = try {
        val arr = json.getJSONArray("missing")
        val miss = IntArray(arr.length()) { arr.getInt(it) }
        MediaControl(TransferId(b64.decode(json.getString("transferId"))), miss)
    } catch (e: Exception) { null }
}
