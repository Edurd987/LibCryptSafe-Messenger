package com.libcryptsafe.media

/**
 * Структуры данных медиа-передачи. Прямой перенос того, что доказано в
 * tests/TransferTest.kt (сборка байт-в-байт, SHA-256 сходится).
 *
 * ЗАГЛУШКА-КАРКАС: это только МОДЕЛИ ДАННЫХ. Логика сборки (TransferManager),
 * крипто (эфемерный ключ + nonce=f(seq)), сериализация в байты конверта, сеть
 * и UI — следующие кирпичи, каждый со своим тестом. Здесь двигатель не заведён.
 */

/**
 * Идентификатор передачи: 16 СЛУЧАЙНЫХ байт (не счётчик — счётчик утёк бы
 * метаданными "это N-й файл пользователя").
 *
 * ЛОВУШКА (доказана в Kotlin-порте): голый ByteArray в Kotlin сравнивается по
 * ССЫЛКЕ, поэтому как ключ Map он ломается — два одинаковых массива считаются
 * разными. Обёртка с equals/hashCode через contentEquals это чинит. НЕ убирать.
 */
class TransferId(val bytes: ByteArray) {
    init { require(bytes.size == 16) { "transferId must be 16 bytes" } }
    override fun equals(other: Any?): Boolean =
        other is TransferId && bytes.contentEquals(other.bytes)
    override fun hashCode(): Int = bytes.contentHashCode()
}

/** Под-тип медиа. Ядру всё равно, что внутри — тип живёт в шифротексте. */
enum class MediaKind(val id: Int) {
    PHOTO(0), VIDEO(1), VOICE(2), FILE(3)
}

/**
 * MEDIA_INIT — старт передачи. Несёт метаданные всего файла и эфемерный ключ.
 * ephemeralKey шифруется сессионным ключом на слое сериализации (не здесь);
 * в этой модели он просто поле. sha256Full — хеш ВСЕГО файла для сверки сборки.
 */
class MediaInit(
    val transferId: TransferId,
    val mediaKind: MediaKind,
    val totalBytes: Long,
    val totalChunks: Int,
    val chunkSize: Int,
    val sha256Full: ByteArray,      // 32 байта, хеш целого файла
    val ephemeralKey: ByteArray     // 32 байта, одноразовый AES-256 ключ файла
)

/** MEDIA_CHUNK — один кусок. seq уникален в пределах transferId; из него же
 *  детерминированно выводится GCM-nonce на крипто-слое (правило nonce=f(seq)). */
class MediaChunk(
    val transferId: TransferId,
    val seq: Int,
    val bytes: ByteArray
)

/** MEDIA_DONE — сигнал "все чанки отправлены". Приёмник проверяет полноту. */
class MediaDone(
    val transferId: TransferId
)

/**
 * CONTROL — служебное сообщение добора: приёмник сообщает, каких seq не хватает,
 * отправитель их пере-шлёт (путь gap-refill, доказанный в тестах).
 */
class MediaControl(
    val transferId: TransferId,
    val missing: IntArray
)

// Блюпринт-константы (зеркалят бумажный чертёж и тесты).
object MediaLimits {
    const val CHUNK_SIZE = 40 * 1024            // 40 KB (влезть в 64KB DoS-лимит после overhead)
    const val MAX_TRANSFER = 100L * 1024 * 1024 // 100 MB (видео сверх лимита — TODO)
}
