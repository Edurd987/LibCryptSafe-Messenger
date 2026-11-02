package com.libcryptsafe.media

/**
 * Сборщик входящих медиа-передач. Приёмная сторона: копит чанки по transferId,
 * детектит пропуски, отдаёт собранный файл на MEDIA_DONE.
 *
 * Логика доказана офлайн в tests/transfer_test.cpp (C++) и tests/TransferTest.kt
 * (Kotlin) — хаотичный порядок + потери + дубли -> сборка байт-в-байт, SHA-256
 * сходится. Это ПРЯМОЙ порт той логики в production; структуры данных
 * (MediaInit/Chunk/Done, TransferId, MediaLimits) берутся из MediaModels.kt,
 * НЕ дублируются здесь.
 *
 * Что этот кирпич делает: чистая математика сборки. Чего НЕ делает (следующие
 * кирпичи, каждый со своим тестом): крипто (расшифровка чанков эфемерным
 * ключом), сеть (приём чанков из сокета), UI (показ фото). Здесь двигатель
 * логики есть, но он ни к чему не подключён.
 *
 * ПАМЯТЬ: чанки держатся в RAM до DONE. Для фото (<=100MB) ок; для ВИДЕО позже
 * сборку надо будет лить на диск потоком, а не буферизовать в памяти (маяк).
 *
 * Потокобезопасность: этот класс НЕ синхронизирован. Вызывающая сторона (сеть)
 * должна подавать onInit/onChunk/onDone для одного transferId последовательно
 * (например, с одного корутин-диспетчера). Маяк для сетевого кирпича.
 */
class TransferManager(private val now: () -> Long = System::currentTimeMillis) {

    companion object {
        const val MAX_ACTIVE = 16                         // одновременных входящих передач
        const val MAX_RESERVED = MediaLimits.MAX_TRANSFER // сумма totalBytes активных передач
        const val TTL_MS = 10L * 60 * 1000                // INIT без DONE живёт 10 минут
    }

    /** Состояние одной идущей передачи: её INIT + накопленные чанки. */
    private class TransferState(val init: MediaInit, val createdAt: Long) {
        // Неупорядоченная сборка: seq -> байты. Файловые чанки независимы
        // (в отличие от nardi seq, где пропуск ДОЛЖЕН блокировать — там ходы
        // причинны). Поэтому просто копим что пришло, полноту проверяем на DONE.
        val chunks = HashMap<Int, ByteArray>()
    }

    private val states = HashMap<TransferId, TransferState>()

    /** MEDIA_INIT: завести приёмную корзину. Возвращает false, если файл
     *  больше лимита (100MB) — такую передачу не принимаем. */
    fun onInit(init: MediaInit): Boolean {
        // №41: INIT шлёт контакт — он может быть злонамеренным. Всё проверяем.
        if (init.totalBytes < 0 || init.totalBytes > MediaLimits.MAX_TRANSFER) return false
        if (init.chunkSize < 1 || init.chunkSize > MediaLimits.CHUNK_SIZE) return false
        val expected = (init.totalBytes + init.chunkSize - 1) / init.chunkSize
        if (init.totalChunks.toLong() != expected) return false   // иначе missing() крутит до 2^31
        if (states.containsKey(init.transferId)) return false     // повторный INIT не сбрасывает корзину
        val t = now()
        states.values.removeAll { t - it.createdAt > TTL_MS }     // INIT без DONE не держит слот вечно
        if (states.size >= MAX_ACTIVE) return false
        if (states.values.sumOf { it.init.totalBytes } + init.totalBytes > MAX_RESERVED) return false
        states[init.transferId] = TransferState(init, t)
        return true
    }

    /** Идёт ли сейчас такая передача (принят INIT, не forget/не истёк). */
    fun isActive(id: TransferId): Boolean = states.containsKey(id)

    /** MEDIA_CHUNK: положить кусок в корзину. Идемпотентно — дубль seq
     *  игнорируется (пере-присылки при доборе не портят состояние).
     *  Чанк до INIT игнорируется. */
    fun onChunk(chunk: MediaChunk) {
        val st = states[chunk.transferId] ?: return
        if (chunk.seq < 0 || chunk.seq >= st.init.totalChunks) return   // №41: seq вне диапазона
        if (chunk.bytes.size > st.init.chunkSize) return                 // №41: чанк больше заявленного
        if (st.chunks.containsKey(chunk.seq)) return
        st.chunks[chunk.seq] = chunk.bytes
    }

    /** Каких seq не хватает. Пустой список = передача полна.
     *  По нему строится MediaControl{missing} для добора. */
    fun missing(id: TransferId): List<Int> {
        val st = states[id] ?: return emptyList()
        val miss = ArrayList<Int>()
        for (s in 0 until st.init.totalChunks)
            if (!st.chunks.containsKey(s)) miss.add(s)
        return miss
    }

    /** MEDIA_DONE: если все чанки на месте — склеить файл и вернуть его байты.
     *  Если есть пропуски или размер не сошёлся — null (ждём добора).
     *  ВНИМАНИЕ: SHA-256 из init.sha256Full здесь НЕ проверяется — это делает
     *  вызывающая сторона (у неё же лежит расшифровка). Здесь только сборка. */
    fun onDone(id: TransferId): ByteArray? {
        val st = states[id] ?: return null
        if (missing(id).isNotEmpty()) return null
        val out = java.io.ByteArrayOutputStream(st.init.totalBytes.toInt())
        for (s in 0 until st.init.totalChunks)
            out.write(st.chunks[s]!!)
        val file = out.toByteArray()
        return if (file.size.toLong() == st.init.totalBytes) file else null
    }

    /** Сколько байт чанков сейчас буферизовано для передачи (для тестов/диагностики). */
    fun bufferedBytes(id: TransferId): Long =
        states[id]?.chunks?.values?.sumOf { it.size.toLong() } ?: 0L

    /** Освободить память после завершённой (или отменённой) передачи. */
    fun forget(id: TransferId) {
        states.remove(id)
    }
}
