package com.libcryptsafe

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GAME_DECLINE: контакт нажал «Отклонить» -> приглашающий сразу перестаёт ждать.
 * Незнакомцу — по-прежнему НИЧЕГО (даже «нет» выдаёт, что мы онлайн).
 */
class GameDeclineTest {
    private val PEER = "AAAA-BBBB-CCCC-DDDD"
    private val EVIL = "EEEE-EEEE-EEEE-EEEE"

    private class Rec : GameCallback {
        val sent = mutableListOf<Pair<String, JSONObject>>(); val sys = mutableListOf<String>()
        override fun onSendGameEvent(targetPeerId: String, gameJson: String) { sent += targetPeerId to JSONObject(gameJson) }
        override fun onInviteReceived(fromPeerId: String) {}
        override fun onGameStarted(peerId: String, gameId: String) {}
        override fun onOpeningDone(first: PlayerType, myDie: Int, peerDie: Int) {}
        override fun onGameSystemMessage(text: String) { sys += text }
        override fun onOpponentLeft() {}
        override fun onRemoteMove(from: Int, to: Int, die: Int) {}
        override fun onRemoteRoll(a: Int, b: Int) {}
    }
    private fun j(s: String) = JSONObject(s)

    /** Меня пригласили (я — получатель). */
    private fun invited(): Pair<GameManager, Rec> {
        val rec = Rec(); val g = GameManager(rec)
        g.handleGameEvent(PEER, j("""{"v":1,"type":"GAME_INVITE","gameId":"g1","variant":"LONG","dice":2,"ts":${System.currentTimeMillis()}}"""))
        assertEquals(GameManager.State.INVITED, g.state)
        return g to rec
    }
    /** Я пригласил (я — приглашающий), gameId — из отправленного кадра. */
    private fun inviting(): Triple<GameManager, Rec, String> {
        val rec = Rec(); val g = GameManager(rec)
        g.sendInvite(PEER)
        assertEquals(GameManager.State.INVITING, g.state)
        return Triple(g, rec, rec.sent.last().second.getString("gameId"))
    }
    private fun decline(gid: String) = j("""{"v":1,"type":"GAME_DECLINE","gameId":"$gid"}""")

    // --- получатель ---
    @Test fun contactDeclineSendsExactlyOneDecline() {
        val (g, rec) = invited()
        g.declineInvite(notifyPeer = true)
        assertEquals(GameManager.State.IDLE, g.state)
        assertEquals(1, rec.sent.size)
        assertEquals(PEER, rec.sent[0].first)
        assertEquals("GAME_DECLINE", rec.sent[0].second.getString("type"))
        assertEquals("g1", rec.sent[0].second.getString("gameId"))
    }
    @Test fun silentDeclineSendsNothing() {          // путь незнакомца — значение по умолчанию
        val (g, rec) = invited()
        g.declineInvite()
        assertEquals(GameManager.State.IDLE, g.state)
        assertTrue("незнакомцу ушёл кадр: ${rec.sent}", rec.sent.isEmpty())
    }
    @Test fun declineWithoutInviteSendsNothing() {
        val rec = Rec(); val g = GameManager(rec)
        g.declineInvite(notifyPeer = true)
        assertTrue(rec.sent.isEmpty())
    }

    // --- приглашающий ---
    @Test fun inviterStopsWaitingOnDecline() {
        val (g, rec, gid) = inviting()
        g.handleGameEvent(PEER, decline(gid))
        assertEquals("приглашающий всё ещё ждёт", GameManager.State.IDLE, g.state)
        assertTrue(rec.sys.any { it.contains("отклонил") })
    }
    @Test fun canInviteAgainAfterDecline() {
        val (g, rec, gid) = inviting()
        g.handleGameEvent(PEER, decline(gid))
        g.sendInvite(PEER)
        assertEquals(GameManager.State.INVITING, g.state)
        assertTrue(rec.sent.last().second.getString("gameId") != gid)
    }
    @Test fun declineFromOtherPeerIgnored() {
        val (g, _, gid) = inviting()
        g.handleGameEvent(EVIL, decline(gid))
        assertEquals(GameManager.State.INVITING, g.state)
    }
    @Test fun declineOfOtherGameIgnored() {
        val (g, _, _) = inviting()
        g.handleGameEvent(PEER, decline("old-game"))
        assertEquals(GameManager.State.INVITING, g.state)
    }
    @Test fun declineAfterAcceptIgnored() {          // партия уже идёт — DECLINE не роняет её
        val (g, _, gid) = inviting()
        g.handleGameEvent(PEER, j("""{"v":1,"type":"GAME_ACCEPT","gameId":"$gid","dice":2}"""))
        assertEquals(GameManager.State.OPENING, g.state)
        g.handleGameEvent(PEER, decline(gid))
        assertEquals(GameManager.State.OPENING, g.state)
    }
}
