package com.libcryptsafe.media

import org.json.JSONObject
import java.security.MessageDigest

/**
 * "Завод" медиа-логики (путь B, Single Responsibility): знает ТОЛЬКО про медиа —
 * нарезку, шифрование (MediaCrypto), упаковку (MediaSerializer), сборку
 * (TransferManager). НЕ знает про Context, БД, сессии, сокеты.
 *
 * ОТПРАВКА: sendMedia() возвращает список JSON-строк (init + чанки + done);
 * MainActivity рассылает КАЖДУЮ через существующий sendGameEvent(peerId, json)
 * — та же проверенная труба, что игровые события (сессионное шифрование ->
 * CHAT_ENCRYPTED -> msg). Relay видит обычные msg, медиа неотличимо от текста.
 *
 * ПРИЁМ: onIncoming(rawDecrypted) — если это media_* (по MediaSerializer.typeOf),
 * контроллер берёт управление; иначе возвращает false, и вызывающая сторона
 * обрабатывает как обычный текст.
 *
 * МАТРЁШКА (кто что шифрует): чанк шифруется ЭФЕМЕРНЫМ ключом здесь
 * (MediaCrypto); затем весь media-JSON шифруется СЕССИОННЫМ ключом ВЫШЕ (в
 * sendGameEvent). ephKey едет в INIT, зашифрованный сессионным ключом — но это
 * шифрование делает вызывающая сторона (у неё сессия), сюда ephKey приходит
 * УЖЕ зашифрованным (sendMedia принимает его параметром).
 *
 * ПАМЯТЬ: чанки/сборка в RAM (фото <=100MB ок; видео позже — на диск). НЕ
 * потокобезопасен: подавать чанки одного transferId последовательно.
 */
class MediaController(
    private val crypto: MediaCrypto,
    private val serializer: MediaSerializer
) {
    private val assembler = TransferManager()
    // ephKey каждой идущей передачи (открытый, из расшифрованного INIT).
    private val ephKeys = HashMap<TransferId, ByteArray>()

    /** Колбэк готового файла: (transferId, mediaKind, собранные байты). */
    var onMediaComplete: ((TransferId, MediaKind, ByteArray) -> Unit)? = null

    // ---------------- ОТПРАВКА ----------------

    /**
     * Подготовить передачу файла. Возвращает список JSON-строк для отправки
     * (init, затем чанки по порядку, затем done). Вызывающая сторона шлёт
     * каждую через sendGameEvent.
     *
     * @param ephKeyPlain эфемерный ключ файла (32B). Им шифруются чанки здесь;
     *        он же кладётся ОТКРЫТЫМ в INIT — весь INIT-конверт затем шифруется
     *        СЕССИОННЫМ ключом на слое отправки, так что ephKey защищён им.
     */
    fun buildTransfer(
        kind: MediaKind,
        fileBytes: ByteArray,
        ephKeyPlain: ByteArray
    ): List<String> {
        val out = ArrayList<String>()
        val transferId = TransferId(randomBytes(16))

        // нарезка (отправитель): 40KB-чанки, последний короче
        val chunkSize = MediaLimits.CHUNK_SIZE
        val totalChunks = (fileBytes.size + chunkSize - 1) / chunkSize
        val sha = sha256(fileBytes)

        // INIT (ephKey — уже зашифрованный сессионным, кладём как есть)
        val init = MediaInit(
            transferId = transferId,
            mediaKind = kind,
            totalBytes = fileBytes.size.toLong(),
            totalChunks = totalChunks,
            chunkSize = chunkSize,
            sha256Full = sha,
            ephemeralKey = ByteArray(0)   // в модели пусто; зашифрованный едет в JSON отдельно
        )
        // ephKey едет ОТКРЫТЫМ внутри INIT: весь конверт шифруется СЕССИОННЫМ
        // ключом на слое отправки (encryptMessage), так что relay видит только
        // зашифрованный конверт. Двойного шифрования ephKey не делаем.
        out.add(serializer.serializeInit(init, ephKeyPlain).toString())

        // CHUNKS: каждый шифруется ЭФЕМЕРНЫМ ключом, потом сериализуется
        var seq = 0
        var off = 0
        while (off < fileBytes.size) {
            val end = minOf(off + chunkSize, fileBytes.size)
            val plain = MediaChunk(transferId, seq, fileBytes.copyOfRange(off, end))
            val enc = crypto.encryptChunk(ephKeyPlain, plain)   // -> bytes зашифрованы
            out.add(serializer.serializeChunk(enc).toString())
            seq++; off += chunkSize
        }

        // DONE
        out.add(serializer.serializeDone(MediaDone(transferId)).toString())
        android.util.Log.i("MEDIA_SEND", "transfer готов: $totalChunks чанков, ${fileBytes.size}B")
        return out
    }

    // ---------------- ПРИЁМ ----------------

    /**
     * Обработать расшифрованный (сессионным ключом) контент. Возвращает true,
     * если это медиа (контроллер обработал), false — если обычный текст.
     *
     * ephKey контроллер хранит сам из расшифрованного INIT — вызывающей стороне
     * не нужно его прокидывать.
     */
    fun onIncoming(rawDecrypted: String): Boolean {
        val json = try { JSONObject(rawDecrypted) } catch (e: Exception) { return false }
        val type = serializer.typeOf(json) ?: return false   // не медиа -> обычный текст

        when (type) {
            ContentType.MEDIA_INIT -> {
                val parsed = serializer.parseInit(json) ?: return true
                val (init, ephKeyPlain) = parsed   // ephKey открытый (конверт уже расшифрован сессионным)
                assembler.onInit(init)
                ephKeys[init.transferId] = ephKeyPlain
                android.util.Log.i("MEDIA_RECV", "INIT: ${init.totalChunks} чанков ждём")
            }
            ContentType.MEDIA_CHUNK -> {
                val enc = serializer.parseChunk(json) ?: return true
                val key = ephKeys[enc.transferId] ?: run {
                    android.util.Log.w("MEDIA_RECV", "нет ephKey (INIT не пришёл?) — чанк пропущен")
                    return true
                }
                val plain = try { crypto.decryptChunk(key, enc) }
                    catch (e: SecurityException) {
                        android.util.Log.e("MEDIA_RECV", "чанк не расшифрован (tamper?): ${e.message}")
                        return true
                    }
                assembler.onChunk(plain)
                android.util.Log.d("MEDIA_RECV", "CHUNK seq=${plain.seq} принят")
            }
            ContentType.MEDIA_DONE -> {
                val done = serializer.parseDone(json) ?: return true
                val miss = assembler.missing(done.transferId)
                if (miss.isEmpty()) {
                    val file = assembler.onDone(done.transferId)
                    if (file != null) {
                        android.util.Log.i("MEDIA_RECV", "ГОТОВО: ${file.size}B собрано")
                        // mediaKind знаем из INIT — упрощённо PHOTO (уточним при UI)
                        onMediaComplete?.invoke(done.transferId, MediaKind.PHOTO, file)
                        assembler.forget(done.transferId)
                        ephKeys.remove(done.transferId)
                    }
                } else {
                    android.util.Log.w("MEDIA_RECV", "DONE, но не хватает ${miss.size} чанков: $miss")
                    // TODO под-кирпич добора: отправить CONTROL{missing} обратно
                }
            }
            else -> return false   // CALL_*/TEXT/CONTROL — не для этого контроллера сейчас
        }
        return true
    }

    // ---------------- helpers ----------------

    private fun randomBytes(n: Int): ByteArray =
        ByteArray(n).also { java.security.SecureRandom().nextBytes(it) }

    private fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)
}
