package com.libcryptsafe

import com.libcryptsafe.media.Base64Codec
import com.libcryptsafe.media.ChunkCipher
import com.libcryptsafe.media.MediaController
import com.libcryptsafe.media.MediaCrypto
import com.libcryptsafe.media.MediaKind
import com.libcryptsafe.media.MediaReplayGuard
import com.libcryptsafe.media.MediaSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * №23: повтор медиа. У медиа нет nonce и метки времени — только случайный transferId из INIT.
 * После сборки сборщик о передаче забывает -> повтор записанных кадров (INIT + чанки + DONE)
 * показывал бы фото СНОВА. MediaReplayGuard запоминает transferId в seen_nonces (постоянно, как x3dh:).
 * Сквозной тест: настоящий MediaController/MediaCrypto/MediaSerializer, шифр — javax AES-GCM (тот же формат).
 */
class MediaReplayTest {
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
    private fun controller() = MediaController(MediaCrypto(GcmCipher()), MediaSerializer(JB64))

    /** Получатель: guard с «базой» seen (переживает перезапуск), счётчик показанных файлов. */
    private class Receiver(val seen: MutableSet<String> = HashSet()) {
        var ctrl = MediaController(MediaCrypto(GcmCipher()), MediaSerializer(JB64))
        var shown = 0
        init { ctrl.onMediaComplete = { _, _, _ -> shown++ } }
        fun restart() { ctrl = MediaController(MediaCrypto(GcmCipher()), MediaSerializer(JB64)); ctrl.onMediaComplete = { _, _, _ -> shown++ } }
        fun deliver(frames: List<String>) = frames.forEach { f ->
            if (MediaReplayGuard.admit(f) { k -> seen.add(k) }) ctrl.onIncoming(f)
        }
    }
    private val sender = controller()
    private fun transfer(bytes: ByteArray = ByteArray(100_000) { (it * 7).toByte() }) =
        sender.buildTransfer(MediaKind.PHOTO, bytes, sender.newEphemeralKeyForSend())

    @Test fun honestTransferShownOnce() {
        val r = Receiver(); r.deliver(transfer())
        assertEquals(1, r.shown)
    }
    @Test fun replayOfWholeTransferNotShownAgain() {
        val r = Receiver(); val t = transfer()
        r.deliver(t); r.deliver(t)
        assertEquals("повтор записанных кадров показал фото снова", 1, r.shown)
    }
    @Test fun replayAfterAppRestartNotShown() {            // память контроллера пуста, seen — из «базы»
        val r = Receiver(); val t = transfer()
        r.deliver(t); r.restart(); r.deliver(t)
        assertEquals(1, r.shown)
    }
    @Test fun sameFileSentAgainIsNewTransfer() {          // контроль: новая отправка того же фото — новый transferId
        val r = Receiver(); val bytes = ByteArray(60_000) { 3 }
        r.deliver(transfer(bytes)); r.deliver(transfer(bytes))
        assertEquals(2, r.shown)
    }
    @Test fun duplicateInitMidTransferHarmless() {        // повтор INIT посреди передачи (переотправка) — файл один
        val r = Receiver(); val t = transfer()
        r.deliver(listOf(t[0], t[0]) + t.drop(1))
        assertEquals(1, r.shown)
    }
    @Test fun keyOnlyForInit() {
        val t = transfer()
        val k = MediaReplayGuard.initKey(t[0])
        assertTrue(k != null && k.startsWith(MediaReplayGuard.PREFIX))
        assertEquals(null, MediaReplayGuard.initKey(t[1]))                 // чанк
        assertEquals(null, MediaReplayGuard.initKey(t.last()))             // DONE
        assertEquals(null, MediaReplayGuard.initKey("""{"v":1,"n":"x","t":"hi"}"""))
        assertEquals(null, MediaReplayGuard.initKey("не json"))
    }
    @Test fun nonInitFramesNeverTouchDb() {
        var calls = 0
        val t = transfer()
        for (f in t.drop(1)) assertTrue(MediaReplayGuard.admit(f) { calls++; true })
        assertTrue(MediaReplayGuard.admit("""{"v":1,"n":"x","t":"hi"}""") { calls++; true })
        assertEquals(0, calls)
    }
    @Test fun oversizedTransferIdIgnored() {               // мусорный id не раздувает базу; контроллер сам отвергнет INIT
        assertEquals(null, MediaReplayGuard.initKey("""{"type":"media_init","transferId":"${"A".repeat(500)}"}"""))
    }
}
