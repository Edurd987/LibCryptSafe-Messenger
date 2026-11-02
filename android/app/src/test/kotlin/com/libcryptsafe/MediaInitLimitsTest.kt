package com.libcryptsafe

import com.libcryptsafe.media.MediaChunk
import com.libcryptsafe.media.MediaInit
import com.libcryptsafe.media.MediaKind
import com.libcryptsafe.media.MediaLimits
import com.libcryptsafe.media.TransferId
import com.libcryptsafe.media.TransferManager
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * №41 (аудит №14, слой 2): злонамеренный КОНТАКТ шлёт MEDIA_INIT/CHUNK,
 * чтобы выжрать память/CPU приёмника. Релей сюда не дотягивается (E2E).
 */
class MediaInitLimitsTest {
    private val C = MediaLimits.CHUNK_SIZE
    private var n = 0
    private fun id(): TransferId = TransferId(ByteArray(16).also { it[0] = (++n).toByte(); it[1] = (n shr 8).toByte() })
    private fun init(tid: TransferId, total: Long, chunks: Int, cs: Int = C) =
        MediaInit(tid, MediaKind.PHOTO, total, chunks, cs, ByteArray(32), ByteArray(32))
    private fun ceil(total: Long, cs: Int) = ((total + cs - 1) / cs).toInt()

    // --- позитивный контроль: честная передача собирается байт-в-байт ---
    @Test fun validTransferAssembles() {
        val tm = TransferManager(); val t = id()
        val file = ByteArray(50_000) { (it * 31).toByte() }
        assertTrue(tm.onInit(init(t, 50_000, ceil(50_000, C))))
        for (s in listOf(2, 0, 1)) tm.onChunk(MediaChunk(t, s, file.copyOfRange(s * C, minOf(file.size, (s + 1) * C))))
        assertTrue(tm.missing(t).isEmpty())
        assertArrayEquals(file, tm.onDone(t))
    }

    // --- INIT ---
    @Test fun hugeTotalChunksRejected() {        // missing() крутил бы 2^31 итераций
        assertFalse(TransferManager().onInit(init(id(), 1000, Int.MAX_VALUE)))
    }
    @Test fun negativeTotalBytesRejected() {
        assertFalse(TransferManager().onInit(init(id(), -1, 1)))
    }
    @Test fun badChunkSizeRejected() {
        assertFalse(TransferManager().onInit(init(id(), 1000, 1, cs = 0)))
        assertFalse(TransferManager().onInit(init(id(), 1000, 1, cs = -5)))
        assertFalse(TransferManager().onInit(init(id(), 1000, 1, cs = C + 1)))
    }
    @Test fun inconsistentChunkCountRejected() {
        assertFalse(TransferManager().onInit(init(id(), 50_000, 5)))   // должно быть 3
        assertFalse(TransferManager().onInit(init(id(), 50_000, 0)))
    }
    @Test fun duplicateInitDoesNotResetTransfer() {
        val tm = TransferManager(); val t = id()
        assertTrue(tm.onInit(init(t, 1000, 1)))
        tm.onChunk(MediaChunk(t, 0, ByteArray(1000)))
        assertFalse(tm.onInit(init(t, 1000, 1)))
        assertEquals(1000L, tm.bufferedBytes(t))
    }
    @Test fun concurrentTransferCountCapped() {
        val tm = TransferManager()
        repeat(TransferManager.MAX_ACTIVE) { assertTrue(tm.onInit(init(id(), 1000, 1))) }
        assertFalse(tm.onInit(init(id(), 1000, 1)))
    }
    @Test fun reservedBytesBudgetCapped() {      // 16 x 100MB = 1.6GB — не должно пройти
        val tm = TransferManager(); val big = 60L * 1024 * 1024
        assertTrue(tm.onInit(init(id(), big, ceil(big, C))))
        assertFalse(tm.onInit(init(id(), big, ceil(big, C))))
    }
    @Test fun staleTransfersExpire() {           // INIT без DONE не держит слот вечно
        var now = 0L
        val tm = TransferManager { now }
        repeat(TransferManager.MAX_ACTIVE) { assertTrue(tm.onInit(init(id(), 1000, 1))) }
        now += TransferManager.TTL_MS + 1
        assertTrue(tm.onInit(init(id(), 1000, 1)))
    }

    // --- CHUNK ---
    @Test fun outOfRangeSeqIgnored() {
        val tm = TransferManager(); val t = id()
        assertTrue(tm.onInit(init(t, 1000, 1)))
        for (s in listOf(1, 7, -1, Int.MAX_VALUE, Int.MIN_VALUE)) tm.onChunk(MediaChunk(t, s, ByteArray(1000)))
        assertEquals(0L, tm.bufferedBytes(t))
    }
    @Test fun oversizedChunkIgnored() {
        val tm = TransferManager(); val t = id()
        assertTrue(tm.onInit(init(t, 2L * C, 2)))
        tm.onChunk(MediaChunk(t, 0, ByteArray(C + 1)))
        assertEquals(0L, tm.bufferedBytes(t))
    }
}
