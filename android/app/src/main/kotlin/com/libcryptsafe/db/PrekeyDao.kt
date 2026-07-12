package com.libcryptsafe.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface PrekeyDao {
    @Insert
    suspend fun insert(prekey: PrekeyEntity)

    @Insert
    suspend fun insertAll(prekeys: List<PrekeyEntity>)

    // текущий SPK (последний по timestamp)
    @Query("SELECT * FROM prekeys WHERE keyType='SPK' ORDER BY timestamp DESC LIMIT 1")
    suspend fun getCurrentSpk(): PrekeyEntity?

    // OPK по id (Боб достаёт приватную часть при обработке первого сообщения)
    @Query("SELECT * FROM prekeys WHERE keyType='OPK' AND keyId=:keyId LIMIT 1")
    suspend fun getOpkById(keyId: Int): PrekeyEntity?

    // УДАЛЕНИЕ израсходованного OPK (Forward Secrecy) — только после успеха
    @Query("DELETE FROM prekeys WHERE keyType='OPK' AND keyId=:keyId")
    suspend fun deleteOpkById(keyId: Int)

    // сколько OPK осталось (для пополнения пачки)
    @Query("SELECT COUNT(*) FROM prekeys WHERE keyType='OPK'")
    suspend fun countOpk(): Int

    // все публичные OPK (для публикации на relay)
    @Query("SELECT * FROM prekeys WHERE keyType='OPK'")
    suspend fun getAllOpk(): List<PrekeyEntity>
}
