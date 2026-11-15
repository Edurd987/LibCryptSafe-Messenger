package com.libcryptsafe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * №45b: входящее от НЕЗНАКОМЦА (ID не в контактах, сверка по ПОЛНОМУ ID).
 *  - ACK не шлём (иначе по квитанциям видно, что мы в сети — Careless Whisper);
 *  - медиа и кадры будущих версий протокола выбрасываем ещё в сервисе;
 *  - текст храним, но не больше MAX_STORED_PER_STRANGER (защита базы);
 *  - уведомление о незнакомцах — не чаще раза в 10 минут (на всех незнакомцев сразу).
 * Контакт — всё как раньше.
 */
class StrangerPolicyTest {
    private val CONTACT = "3465-BFC2-71BB-A009"
    private val STRANGER = "D583-D7FB-12F2-B75C"
    private val TWIN = "0000-0000-0000-A009"          // хвост как у контакта — подбирается перебором
    private val MIN = 60_000L
    private val CAP = StrangerPolicy.MAX_STORED_PER_STRANGER

    private fun fresh(): IntArray { ContactSet.resetForTest(); return intArrayOf(0) }

    // --- кто контакт ---
    @Test fun contactRecognizedByFullId() {
        fresh()
        assertTrue(ContactSet.isContact(CONTACT) { listOf(CONTACT) })
        assertFalse(ContactSet.isContact(STRANGER) { listOf(CONTACT) })
    }
    @Test fun sameTailIsStillStranger() {
        fresh()
        assertFalse("хвост совпал — незнакомец принят за контакт", ContactSet.isContact(TWIN) { listOf(CONTACT) })
    }
    @Test fun contactsLoadedOnceAndRefreshed() {
        val calls = fresh()
        repeat(4) { ContactSet.isContact(CONTACT) { calls[0]++; listOf(CONTACT) } }
        assertEquals(1, calls[0])
        ContactSet.replaceAll(listOf(STRANGER))        // добавил незнакомца в контакты, удалил старый
        assertTrue(ContactSet.isContact(STRANGER) { emptyList() })
        assertFalse(ContactSet.isContact(CONTACT) { emptyList() })
    }
    @Test fun dbFailureMeansStrangerAndRetries() {   // безопасная сторона: без ACK, без медиа
        val calls = fresh()
        assertFalse(ContactSet.isContact(CONTACT) { calls[0]++; throw IllegalStateException("db") })
        assertTrue(ContactSet.isContact(CONTACT) { calls[0]++; listOf(CONTACT) })
        assertEquals(2, calls[0])
    }

    @Test fun isKnownUsesOnlyLoadedCache() {         // главный поток: БД не трогаем
        fresh()
        assertFalse("кэш не загружен — никого не «знаем»", ContactSet.isKnown(CONTACT))
        ContactSet.isContact(STRANGER) { listOf(CONTACT) }
        assertTrue(ContactSet.isKnown(CONTACT))
        assertFalse(ContactSet.isKnown(STRANGER))
        assertFalse(ContactSet.isKnown(TWIN))
    }

    // --- что выбрасывает сервис ---
    private val media = """{"v":1,"type":"media_init","id":"x"}"""
    private val future = """{"v":2,"n":"abc","t":"hi"}"""
    private val chat = """{"v":1,"n":"abc","ts":1,"t":"{\"v\":1,\"type\":\"CHAT\",\"text\":\"hi\"}"}"""
    private val ack = """{"v":1,"a":"abc"}"""
    private val game = """{"v":1,"type":"GAME_INVITE","gameId":"g"}"""

    @Test fun strangerMediaDropped() = assertTrue(StrangerPolicy.dropInService(isContact = false, raw = media))
    @Test fun strangerFutureVersionDropped() = assertTrue(StrangerPolicy.dropInService(false, future))
    @Test fun contactMediaPasses() = assertFalse(StrangerPolicy.dropInService(true, media))
    @Test fun contactFutureVersionPasses() = assertFalse(StrangerPolicy.dropInService(true, future))
    @Test fun strangerChatAckGamePass() {           // чат — в «запросы», ACK — только наши ✓✓, игру решает №45a
        assertFalse(StrangerPolicy.dropInService(false, chat))
        assertFalse(StrangerPolicy.dropInService(false, ack))
        assertFalse(StrangerPolicy.dropInService(false, game))
    }
    @Test fun garbageNotDroppedHere() {              // старый текстовый формат — дальше по цепочке, там лимит
        assertFalse(StrangerPolicy.dropInService(false, "просто текст"))
    }

    // --- чат: хранить? ACK? ---
    @Test fun contactChatStoredAndAcked() {
        assertEquals(StrangerPolicy.ChatVerdict(store = true, ack = true), StrangerPolicy.chat(true, fresh = true, storedFromPeer = 999))
    }
    @Test fun contactDuplicateStillAcked() {         // первый ACK мог потеряться — как было
        assertEquals(StrangerPolicy.ChatVerdict(store = false, ack = true), StrangerPolicy.chat(true, fresh = false, storedFromPeer = 0))
    }
    @Test fun strangerChatStoredWithoutAck() {
        assertEquals(StrangerPolicy.ChatVerdict(store = true, ack = false), StrangerPolicy.chat(false, true, 0))
    }
    @Test fun strangerCapReached() {
        assertEquals(StrangerPolicy.ChatVerdict(store = true, ack = false), StrangerPolicy.chat(false, true, CAP - 1))
        assertEquals(StrangerPolicy.ChatVerdict(store = false, ack = false), StrangerPolicy.chat(false, true, CAP))
    }
    @Test fun strangerDuplicateNothing() {
        assertEquals(StrangerPolicy.ChatVerdict(store = false, ack = false), StrangerPolicy.chat(false, false, 0))
    }
    @Test fun capIsSmall() = assertTrue(CAP in 5..50)

    // --- уведомления ---
    @Test fun contactAlwaysNotifies() {
        assertTrue(StrangerPolicy.notify(true, userMessage = true, lastStrangerNotifyAt = 0L, now = 1_000L))
    }
    @Test fun nonUserMessageNeverNotifies() {
        assertFalse(StrangerPolicy.notify(true, false, null, 0L))
        assertFalse(StrangerPolicy.notify(false, false, null, 0L))
    }
    @Test fun strangerNotifyCooldown() {
        val t = 1_800_000_000_000L
        assertTrue(StrangerPolicy.notify(false, true, null, t))
        assertFalse(StrangerPolicy.notify(false, true, t, t + 9 * MIN))
        assertTrue(StrangerPolicy.notify(false, true, t, t + 10 * MIN))
    }
}
