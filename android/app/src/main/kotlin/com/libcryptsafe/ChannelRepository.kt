package com.libcryptsafe

import android.content.Context
import com.libcryptsafe.db.AppDatabase
import com.libcryptsafe.db.ChannelEntity
import com.libcryptsafe.db.PostEntity

// Мозг каналов: связывает крипто (подпись/проверка) и хранилище (Room).
// Единственная точка доступа к каналам для UI.
class ChannelRepository(context: Context) {
    private val channelDao = AppDatabase.getInstance(context).channelDao()
    private val postDao = AppDatabase.getInstance(context).postDao()

    // Создать свой канал (владелец): генерит пару, channelId = Base64(pub), priv в SQLCipher.
    suspend fun createChannel(title: String): ChannelEntity? {
        val pair = CryptoManager.channelGenerateKeypair() ?: return null
        if (pair.size < 2) return null
        val channelId = ChannelSerializer.channelIdFromPubKey(pair[0])
        val entity = ChannelEntity(
            channelId = channelId, title = title,
            isOwned = true, privKey = pair[1]
        )
        channelDao.insert(entity)   // IGNORE если channelId занят
        return entity
    }

    // Подписаться на чужой канал (только channelId, без privKey — читаю, не пишу).
    suspend fun subscribe(channelId: String, title: String) {
        channelDao.insert(ChannelEntity(channelId, title, isOwned = false, privKey = null))
    }

    // Владелец пишет пост: seq = lastSeq+1, подписывает, сохраняет.
    suspend fun publishPost(channelId: String, content: String): PostEntity? {
        val ch = channelDao.getById(channelId) ?: return null
        val priv = ch.privKey ?: return null            // не владелец -> писать нельзя
        val seq = (postDao.lastSeq(channelId) ?: 0L) + 1
        val ts = System.currentTimeMillis()
        val sig = ChannelSerializer.signPost(priv, seq, ts, content) ?: return null
        val post = PostEntity(channelId, seq, ts, content, sig)
        postDao.insert(post)
        return post
    }

    // ГЛАВНАЯ ЗАЩИТА: сохранить входящий пост ТОЛЬКО если подпись валидна.
    // Подделка/битые байты -> SecurityException (громкий инцидент, не тихий пропуск).
    suspend fun saveIncomingPost(post: PostEntity) {
        val valid = ChannelSerializer.verifyPost(
            post.channelId, post.seq, post.timestamp, post.content, post.signature
        )
        if (!valid) {
            android.util.Log.e("CHAN_SEC",
                "ПОДДЕЛКА поста channelId=${post.channelId.take(16)}... seq=${post.seq} — ОТКЛОНЁН")
            throw SecurityException("Невалидная подпись поста в канале ${post.channelId.take(16)}...")
        }
        postDao.insert(post)   // IGNORE если seq уже есть (дубль)
    }

    suspend fun getChannels(): List<ChannelEntity> = channelDao.getAll()
    suspend fun getPosts(channelId: String): List<PostEntity> = postDao.getForChannel(channelId)
    suspend fun deleteChannel(channelId: String) {
        channelDao.delete(channelId); postDao.deleteForChannel(channelId)
    }
}
