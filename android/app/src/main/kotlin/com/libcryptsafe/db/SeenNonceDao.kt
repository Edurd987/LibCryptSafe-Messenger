package com.libcryptsafe.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface SeenNonceDao {
    // true = новый (вставлен), false = повтор (конфликт по unique). Атомарно в SQLite.
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(e: SeenNonceEntity): Long

    // Только чтение: проверить повтор, ничего не записывая (№34).
    @Query("SELECT EXISTS(SELECT 1 FROM seen_nonces WHERE peerId = :peerId AND nonce = :nonce)")
    suspend fun isSeen(peerId: String, nonce: String): Boolean

    suspend fun markSeen(peerId: String, nonce: String): Boolean =
        insert(SeenNonceEntity(peerId = peerId, nonce = nonce)) != -1L

    // №23: синхронно — только с IO-потока входящих (MessengerService), не с главного.
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertBlocking(e: SeenNonceEntity): Long

    @Query("DELETE FROM seen_nonces WHERE timestamp < :cutoff")
    suspend fun purgeOlderThan(cutoff: Long): Int

    // Только записи чата. X3DH (x3dh:...) не удаляем: их мало, а защита от отката сессии постоянная.
    // №23: media:... тоже не удаляем — у медиа нет метки времени, после удаления повтор снова прошёл бы.
    @Query("DELETE FROM seen_nonces WHERE timestamp < :cutoff AND nonce NOT LIKE 'x3dh:%' AND nonce NOT LIKE 'media:%'")
    suspend fun purgeChatOlderThan(cutoff: Long): Int
}
