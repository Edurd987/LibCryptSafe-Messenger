package com.libcryptsafe.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface MediaDao {
    @Insert
    suspend fun insert(media: MediaEntity): Long

    @Query("SELECT * FROM media ORDER BY timestamp DESC")
    suspend fun getAll(): List<MediaEntity>

    @Query("SELECT * FROM media WHERE id = :id")
    suspend fun getById(id: Long): MediaEntity?

    @Query("SELECT * FROM media WHERE peerId = :peerId ORDER BY timestamp DESC")
    suspend fun getByPeer(peerId: String): List<MediaEntity>

    // ТОЧЕЧНЫЙ КРИПТО-SHRED (удаление «по одной»): сперва ЗАТИРАЕМ storageKey
    // случайными байтами (НЕ null — чтобы значение реально перезаписалось, а не
    // пометилось спец-случаем), потом удаляем строку. Без ключа encryptedBlob —
    // шум, даже если страница осталась в свободном месте БД до VACUUM.
    @Query("UPDATE media SET storageKey = :zeros WHERE id = :id")
    suspend fun shredKey(id: Long, zeros: ByteArray)

    @Query("DELETE FROM media WHERE id = :id")
    suspend fun deleteRow(id: Long)

    @Query("DELETE FROM media")
    suspend fun clearAll()
}
