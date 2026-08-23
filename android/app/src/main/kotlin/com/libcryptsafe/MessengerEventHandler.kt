package com.libcryptsafe

// L2 Кирпич 2b: связь NetworkManager -> получатель событий.
// MainActivity реализует и рисует UI; в 2c сервис реализует и пишет в БД.
// NetworkManager НЕ знает про View/Context отрисовки — только про эти события.
interface MessengerEventHandler {
    fun onStatusChanged(connected: Boolean, reconnects: Int)
    fun onHandshakeDone(fingerprint: String)
    fun onSystemMessage(text: String)
    fun onPeerIdResolved(peerId: String)
    fun onChatReceived(peerId: String, rawDecrypted: String)
    fun onInitialHandshakeReceived(peerId: String, content: String)
    fun onChannelPosts(channelId: String, posts: List<IncomingPost>)
}

// Ключи системных сообщений (строковые ресурсы разрешает получатель — у него Context UI)
object SysMsg {
    const val CONNECTED = "@connected"
    const val DECRYPT_ERROR = "@decrypt_error"
}

// Пост канала, пришедший с relay (для проверки подписи + сохранения).
data class IncomingPost(val seq: Long, val ts: Long, val content: String, val sig: ByteArray)
