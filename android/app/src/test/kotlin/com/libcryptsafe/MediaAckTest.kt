package com.libcryptsafe

import com.libcryptsafe.media.Base64Codec
import com.libcryptsafe.media.ChunkCipher
import com.libcryptsafe.media.MediaAck
import com.libcryptsafe.media.MediaController
import com.libcryptsafe.media.MediaCrypto
import com.libcryptsafe.media.MediaKind
import com.libcryptsafe.media.MediaSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * ✓✓ для медиа: отправитель помечает пузырь маркером MediaAck.nonce(id из своего MEDIA_INIT),
 * получатель после сборки шлёт обычный ACK с MediaAck.nonce(id собранной передачи).
 * Контракт: эти строки ОБЯЗАНЫ совпасть байт в байт — иначе ✓✓ не найдёт свой пузырь.
 */
class MediaAckTest {
    private class GcmCipher : ChunkCipher {
        private val rnd = java.security.SecureRandom()
        override fun encrypt(key: ByteArray, plaintext: ByteArray): ByteArray? = try {
            val iv = ByteArray(12).also { rnd.nextBytes(it) }
            val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            iv + c.doFinal(plaintext)
        } catch (e: Exception) { null }
        override fun decrypt(key: ByteArray, blob: ByteArray): ByteArray? = try {
            val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, blob, 0, 12))
            c.doFinal(blob, 12, blob.size - 12)
        } catch (e: Exception) { null }
    }
    private object JB64 : Base64Codec {
        override fun encode(data: ByteArray) = java.util.Base64.getEncoder().encodeToString(data)
        override fun decode(s: String): ByteArray = java.util.Base64.getDecoder().decode(s)
    }
    private fun ctrl() = MediaController(MediaCrypto(GcmCipher()), MediaSerializer(JB64))

    @Test fun senderAndReceiverNoncesMatch() {
        repeat(20) {                                   // разные случайные id, в т.ч. с '+' '/' '='
            val tx = ctrl(); val rx = ctrl()
            val env = tx.buildTransfer(MediaKind.PHOTO, ByteArray(50_000) { i -> (i * 13).toByte() }, tx.newEphemeralKeyForSend())
            val senderNonce = MediaAck.nonce(MediaAck.transferIdOf(env.first())!!)
            var receiverNonce: String? = null
            rx.onMediaComplete = { tid, _, _ -> receiverNonce = MediaAck.nonce(JB64.encode(tid.bytes)) }
            env.forEach { rx.onIncoming(it) }
            assertEquals(senderNonce, receiverNonce)
        }
    }
    @Test fun nonceHasMediaPrefix() = assertTrue(MediaAck.nonce("QUJD").startsWith("media:"))
    @Test fun transferIdOnlyFromInit() {
        val tx = ctrl()
        val env = tx.buildTransfer(MediaKind.VOICE, ByteArray(90_000) { 1 }, tx.newEphemeralKeyForSend())
        assertTrue(MediaAck.transferIdOf(env.first()) != null)
        assertEquals(null, MediaAck.transferIdOf(env[1]))           // чанк
        assertEquals(null, MediaAck.transferIdOf(env.last()))       // DONE
        assertEquals(null, MediaAck.transferIdOf("не json"))
        assertEquals(null, MediaAck.transferIdOf("""{"v":1,"a":"x"}"""))
    }
    @Test fun completionFiresOncePerTransfer() {      // ACK шлётся из onMediaComplete -> один раз на передачу
        val tx = ctrl(); val rx = ctrl(); var acks = 0
        rx.onMediaComplete = { _, _, _ -> acks++ }
        val env = tx.buildTransfer(MediaKind.PHOTO, ByteArray(100_000) { 5 }, tx.newEphemeralKeyForSend())
        env.forEach { rx.onIncoming(it) }
        env.drop(1).forEach { rx.onIncoming(it) }   // опоздавшие дубли чанков/DONE после сборки
        assertEquals(1, acks)
    }
}
