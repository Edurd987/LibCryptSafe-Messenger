package com.libcryptsafe

import android.util.Base64
import java.nio.ByteBuffer
import java.nio.ByteOrder

// ═══ КАНАЛЫ Кирпич 1: сериализация поста + модели данных ═══
// Формат подписи ДОЛЖЕН совпадать с channel_sign в C++ (KeyExchange.hpp):
// [8B seq BE][8B timestamp BE][content UTF-8]. Один и тот же на подписанте и читателе.

object ChannelSerializer {

    // Детерминированная сборка байтов поста — ТОЧНО как в C++ channel_sign.
    // Подписант и читатель зовут ЭТУ функцию, значит байты идентичны -> подпись сходится.
    fun serializePost(seq: Long, timestamp: Long, content: String): ByteArray {
        val body = content.toByteArray(Charsets.UTF_8)
        return ByteBuffer.allocate(16 + body.size)
            .order(ByteOrder.BIG_ENDIAN)
            .putLong(seq)          // 8 байт BE
            .putLong(timestamp)    // 8 байт BE
            .put(body)             // content UTF-8
            .array()
    }

    // channelId = Base64 публичного ключа (адрес канала).
    fun channelIdFromPubKey(pubKey: ByteArray): String =
        Base64.encodeToString(pubKey, Base64.NO_WRAP)

    fun pubKeyFromChannelId(channelId: String): ByteArray =
        Base64.decode(channelId, Base64.NO_WRAP)

    // Владелец: собрать байты -> подписать -> получить подпись поста.
    fun signPost(privKey: ByteArray, seq: Long, timestamp: Long, content: String): ByteArray? {
        val data = serializePost(seq, timestamp, content)
        return CryptoManager.channelSign(privKey, data)
    }

    // Читатель: собрать те же байты -> проверить подпись публичным ключом (channelId).
    fun verifyPost(channelId: String, seq: Long, timestamp: Long, content: String, signature: ByteArray): Boolean {
        val data = serializePost(seq, timestamp, content)
        val pubKey = pubKeyFromChannelId(channelId)
        return CryptoManager.verifySignature(pubKey, data, signature)
    }
}

// Канал: id = Base64(pubKey). privKey не null только если Я владелец.
data class Channel(
    val channelId: String,       // Base64 публичного ключа = адрес
    val title: String,           // локальное имя (не на relay)
    val isOwned: Boolean,        // я создатель (есть privKey) или подписчик?
    val privKey: ByteArray? = null   // только у владельца, хранится в SQLCipher
)

// Пост канала: подписан владельцем, проверяется читателем.
data class Post(
    val channelId: String,
    val seq: Long,
    val timestamp: Long,
    val content: String,
    val signature: ByteArray
)
