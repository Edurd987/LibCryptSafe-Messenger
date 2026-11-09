package com.libcryptsafe

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** №52: у приглашения есть срок годности (5 мин + 2 мин допуск на часы). */
class InviteExpiryTest {
    private val PEER = "AAAA-BBBB-CCCC-DDDD"
    private val MIN = 60_000L
    private val T = 1_800_000_000_000L          // «сейчас» в тесте

    private class Rec : GameCallback {
        val sent = mutableListOf<JSONObject>(); val sys = mutableListOf<String>()
        override fun onSendGameEvent(targetPeerId: String, gameJson: String) { sent += JSONObject(gameJson) }
        override fun onInviteReceived(fromPeerId: String) {}
        override fun onGameStarted(peerId: String, gameId: String) {}
        override fun onOpeningDone(first: PlayerType, myDie: Int, peerDie: Int) {}
        override fun onGameSystemMessage(text: String) { sys += text }
        override fun onOpponentLeft() {}
        override fun onRemoteMove(from: Int, to: Int, die: Int) {}
        override fun onRemoteRoll(a: Int, b: Int) {}
    }
    private fun gm(now: Long): Triple<GameManager, Rec, LongArray> {
        val rec = Rec(); val g = GameManager(rec); val clock = longArrayOf(now)
        g.wallClock = { clock[0] }
        return Triple(g, rec, clock)
    }
    private fun invite(ts: Long?) = JSONObject(
        """{"v":1,"type":"GAME_INVITE","gameId":"g1","variant":"LONG","dice":2${if (ts == null) "" else ""","ts":$ts"""}}""")

    // --- получатель ---
    @Test fun freshInviteAccepted() {
        val (g, _, _) = gm(T); g.handleGameEvent(PEER, invite(T - 1 * MIN))
        assertEquals(GameManager.State.INVITED, g.state)
    }
    @Test fun staleInviteRejected() {          // живой случай 2026-11-08: приглашение двухдневной давности
        val (g, rec, _) = gm(T); g.handleGameEvent(PEER, invite(T - 2 * 24 * 60 * MIN))
        assertEquals("устаревшее приглашение принято", GameManager.State.IDLE, g.state)
        assertTrue(rec.sys.any { it.contains("устаревшее") })
    }
    @Test fun justOverLimitRejected() {
        val (g, _, _) = gm(T); g.handleGameEvent(PEER, invite(T - 7 * MIN - 1))
        assertEquals(GameManager.State.IDLE, g.state)
    }
    @Test fun clockSkewTolerated() {           // часы отправителя чуть вперёд / позади — в пределах допуска
        val (a, _, _) = gm(T); a.handleGameEvent(PEER, invite(T + 90_000))
        assertEquals(GameManager.State.INVITED, a.state)
        val (b, _, _) = gm(T); b.handleGameEvent(PEER, invite(T - 6 * MIN - 30_000))
        assertEquals(GameManager.State.INVITED, b.state)
    }
    @Test fun inviteFromFutureRejected() {
        val (g, _, _) = gm(T); g.handleGameEvent(PEER, invite(T + 5 * MIN))
        assertEquals(GameManager.State.IDLE, g.state)
    }
    @Test fun inviteWithoutTsRejected() {
        val (g, rec, _) = gm(T); g.handleGameEvent(PEER, invite(null))
        assertEquals(GameManager.State.IDLE, g.state)
        assertTrue(rec.sys.any { it.contains("старой версии") })
    }
    @Test fun acceptAfterDialogHungTooLongRefused() {
        val (g, rec, clock) = gm(T); g.handleGameEvent(PEER, invite(T))
        clock[0] = T + 8 * MIN                  // диалог «принять?» провисел 8 минут
        g.acceptInvite()
        assertEquals(GameManager.State.IDLE, g.state)
        assertTrue("устаревшее согласие ушло", rec.sent.none { it.getString("type") == "GAME_ACCEPT" })
    }

    // --- приглашающий ---
    @Test fun myInviteCarriesTs() {
        val (g, rec, _) = gm(T); g.sendInvite(PEER)
        assertEquals(T, rec.sent.single().getLong("ts"))
    }
    @Test fun timelyAcceptStartsGame() {
        val (g, _, clock) = gm(T); g.sendInvite(PEER)
        val id = g.gameId
        clock[0] = T + 2 * MIN
        g.handleGameEvent(PEER, JSONObject("""{"v":1,"type":"GAME_ACCEPT","gameId":"$id","dice":2}"""))
        assertEquals(GameManager.State.OPENING, g.state)
    }
    @Test fun lateAcceptEndsGameForBoth() {
        val (g, rec, clock) = gm(T); g.sendInvite(PEER)
        val id = g.gameId
        clock[0] = T + 9 * MIN
        g.handleGameEvent(PEER, JSONObject("""{"v":1,"type":"GAME_ACCEPT","gameId":"$id","dice":2}"""))
        assertEquals(GameManager.State.IDLE, g.state)
        assertTrue("сопернику не ушёл GAME_END", rec.sent.any { it.getString("type") == "GAME_END" && it.getString("gameId") == id })
    }
    @Test fun stuckInvitingClearedOnNextInvite() {
        val (g, rec, clock) = gm(T); g.sendInvite(PEER)
        clock[0] = T + 9 * MIN                  // соперник так и не ответил
        g.sendInvite("EEEE-EEEE-EEEE-EEEE")
        assertEquals(2, rec.sent.count { it.getString("type") == "GAME_INVITE" })
        assertEquals("EEEE-EEEE-EEEE-EEEE", g.peerId)
    }
    @Test fun secondInviteBlockedWhileFirstFresh() {
        val (g, rec, clock) = gm(T); g.sendInvite(PEER)
        clock[0] = T + 1 * MIN
        g.sendInvite("EEEE-EEEE-EEEE-EEEE")
        assertEquals(1, rec.sent.count { it.getString("type") == "GAME_INVITE" })
        assertEquals(PEER, g.peerId)
    }
}
