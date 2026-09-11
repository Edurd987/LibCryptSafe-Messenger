package com.libcryptsafe.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// Сохранённое фото медиа-сейфа (состояние PRIVATE). Лежит в той же зашифрованной
// SQLCipher-базе (общий ключ из TEE), НО дополнительно каждое фото зашифровано
// СВОИМ storageKey — точечный крипто-shred при удалении «по одной»: затёр
// storageKey строки -> encryptedBlob превращается в шум даже до VACUUM.
// Эфемерные (несохранённые) фото сюда НЕ попадают — они живут в RAM и гибнут
// при рестарте (состояние DEFAULT).
@Entity(tableName = "media", indices = [Index(value = ["peerId"])])
data class MediaEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val peerId: String?,              // от кого фото (null допустим)
    val storageKey: ByteArray,        // per-media ключ (сам под общим ключом БД); shred -> затираем
    val encryptedBlob: ByteArray,     // фото, зашифрованное storageKey ([12 nonce][ct][16 tag])
    val timestamp: Long = System.currentTimeMillis(),
    val mediaKind: String = "photo"
) {
    // Identity СТРОГО по PrimaryKey id. НЕ по крипто-полям: storageKey мутабелен
    // (shredKey его затирает), а equals/hashCode на мутабельном поле ломают
    // HashMap/HashSet/RecyclerView-diffing — объект «теряется» в коллекции после
    // shred. id стабилен весь жизненный цикл записи — единственно верный ключ.
    override fun equals(other: Any?): Boolean = other is MediaEntity && other.id == id
    override fun hashCode(): Int = id.hashCode()
}
