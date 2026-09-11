package com.libcryptsafe.media

import java.security.SecureRandom

/**
 * Крипто-слой медиа-передачи. "Полицейский пост" перед TransferManager: голые
 * чанки не ходят — каждый шифруется AES-256-GCM под ЭФЕМЕРНЫМ ключом файла.
 *
 * Путь A (решено после разведки): НЕ трогаем нативный JNI-код и НЕ задаём nonce
 * сами. Переиспользуем проверенный CryptoManager.encryptWithKey/decryptWithKey,
 * который сам берёт СЛУЧАЙНЫЙ 12-байтный nonce на каждый вызов и кладёт его в
 * выход ([12 nonce][ciphertext][16 tag]). Случайный nonce под ОДНОРАЗОВЫМ
 * ключом безопасен: ключ живёт ровно один файл, коллизия 12-байтного случайного
 * nonce на тысячах чанков ничтожна. Бумажная схема nonce=f(seq) отвергнута —
 * готовый безопасный API решает то же самое без правки JNI (12 байт/чанк
 * оверхеда = статистическая погрешность на файле).
 *
 * Свойства (доказаны тестом на пяти пунктах): round-trip, tamper-detection
 * (подмена байта -> расшифровка null -> SecurityException), wrong-key,
 * эфемерность (ключ одного файла не расшифровывает другой), nonce-уникальность
 * (два шифрования одного чанка дают разные блоба -> разные nonce).
 *
 * Тестируемость: класс зависит от интерфейса ChunkCipher, а не жёстко от
 * CryptoManager. В production подставляется нативный CryptoManager; в
 * desktop-тесте — javax.crypto AES-GCM (та же GCM-математика, тот же формат).
 */

/** Контракт шифра чанка. Реализация в бою — нативный CryptoManager. */
interface ChunkCipher {
    /** key(32B) + plaintext -> [12 nonce][ciphertext][16 tag], или null при ошибке. */
    fun encrypt(key: ByteArray, plaintext: ByteArray): ByteArray?
    /** key(32B) + blob -> plaintext, или null если ключ неверный / данные подделаны. */
    fun decrypt(key: ByteArray, blob: ByteArray): ByteArray?
}

class MediaCrypto(private val cipher: ChunkCipher) {

    /** Сгенерировать эфемерный ключ файла: 32 случайных байта (AES-256).
     *  Живёт только в памяти на время передачи; кладётся в MediaInit.ephemeralKey
     *  и там шифруется сессионным ключом на слое сериализации (не здесь). */
    fun newEphemeralKey(): ByteArray {
        val k = ByteArray(32)
        SecureRandom().nextBytes(k)
        return k
    }

    /** Зашифровать чанк: возвращает MediaChunk, где bytes = зашифрованный блоб. */
    fun encryptChunk(ephemeralKey: ByteArray, plainChunk: MediaChunk): MediaChunk {
        val blob = cipher.encrypt(ephemeralKey, plainChunk.bytes)
            ?: throw SecurityException("media chunk encrypt failed (seq=${plainChunk.seq})")
        return MediaChunk(plainChunk.transferId, plainChunk.seq, blob)
    }

    // ===== ХРАНИЛИЩЕ (media-сейф). Тот же ChunkCipher, отдельный STORAGE-ключ. =====
    // Транспортный эфемерный ключ и storage-ключ РАЗНЫЕ: передача расшифрована к
    // моменту сохранения, сейф шифрует plain-байты ЗАНОВО своим ключом (два слоя,
    // два атакующих: перехватчик канала vs вор диска). Формат блоба тот же
    // [12 nonce][ct][16 tag] — переиспускаем encrypt/decrypt, второй AES не заводим.
    // ПРЕДУПРЕЖДЕНИЕ будущему: encrypt/decrypt обязаны брать СЛУЧАЙНЫЙ nonce на
    // каждый вызов (как сейчас). Детерминированный nonce = key+nonce reuse ->
    // катастрофа и для транспорта, и для сейфа. Ловится nonce-uniqueness тестом.

    /** 32 случайных байта — per-media STORAGE-ключ (кладётся в строку БД). */
    fun newStorageKey(): ByteArray = newEphemeralKey()

    /** Зашифровать ВЕСЬ файл storage-ключом для хранения в сейфе. */
    fun encryptForStorage(storageKey: ByteArray, plain: ByteArray): ByteArray =
        cipher.encrypt(storageKey, plain)
            ?: throw SecurityException("media storage encrypt failed")

    /** Расшифровать файл из сейфа. null -> подделка/неверный ключ (или ключ уже
     *  затёрт при shred-удалении) -> SecurityException, не молчаливый мусор. */
    fun decryptForStorage(storageKey: ByteArray, blob: ByteArray): ByteArray =
        cipher.decrypt(storageKey, blob)
            ?: throw SecurityException("media storage decrypt failed — tamper/wrong-or-shredded key")

    /** Расшифровать чанк. null от cipher = подделка/неверный ключ -> прерываем
     *  передачу SecurityException (tamper detection, а не молчаливый мусор). */
    fun decryptChunk(ephemeralKey: ByteArray, encChunk: MediaChunk): MediaChunk {
        val plain = cipher.decrypt(ephemeralKey, encChunk.bytes)
            ?: throw SecurityException("media chunk decrypt failed — tamper or wrong key (seq=${encChunk.seq})")
        return MediaChunk(encChunk.transferId, encChunk.seq, plain)
    }
}
