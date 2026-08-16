package com.libcryptsafe.db
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
@Dao
interface ChannelDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)   // channelId занят -> игнор
    suspend fun insert(channel: ChannelEntity): Long
    @Query("SELECT * FROM channels ORDER BY createdAt DESC")
    suspend fun getAll(): List<ChannelEntity>
    @Query("SELECT * FROM channels WHERE channelId = :id")
    suspend fun getById(id: String): ChannelEntity?
    @Query("DELETE FROM channels WHERE channelId = :id")
    suspend fun delete(id: String)
}
