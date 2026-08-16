package com.libcryptsafe.db
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
@Dao
interface PostDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)   // дубль seq -> игнор
    suspend fun insert(post: PostEntity): Long
    @Query("SELECT * FROM posts WHERE channelId = :id ORDER BY seq ASC")
    suspend fun getForChannel(id: String): List<PostEntity>
    @Query("SELECT MAX(seq) FROM posts WHERE channelId = :id")
    suspend fun lastSeq(id: String): Long?            // для "тяни с seq>N" (polling)
    @Query("DELETE FROM posts WHERE channelId = :id")
    suspend fun deleteForChannel(id: String)
}
