package com.libcryptsafe

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Отмена своего приглашения: не ждать 8 минут, если соперник молчит
 * (не в сети, заблокировал нас, просто не ответил).
 */
class GameCancelTest {
    private val PEER = "AAAA-BBBB-CCCC-DDDD"
    private val EVIL = "EEEE-EEEE-EEEE-EEEE"
    private val MIN = 60_000L

    private class Rec : GameCallback {
        val sent = mutableListOf<Pair<String, JSONObject>>(); val sys = mutableListOf<String>()
        var left = 0
        override fun onSendGameEvent(targetPeerId: String, gameJson: String) { sent += targetPeerId to JSONObject(gameJson) }
        override fun onInviteReceived(fromPeerId: String) {}
        override fun onGameStarted(peerId: String, gameId: String) {}
        override fun onOpeningDone(first: PlayerType, myDie: Int, peerDie: Int) {}
        override fun onGameSystemMessage(text: String) { sys += text }
        override fun onOpponentLeft() { left++ }
        override fun onRemoteMove(from: Int, to: Int, die: Int) {}
        override fun onRemoteRoll(a: Int, b: Int) {}
    }
    private fun j(s: String) = JSONObject(s)

    private fun inviting(): Triple<GameManager, Rec, String> {
        val rec = Rec(); val g = GameManager(rec)
        g.sendInvite(PEER)
        return Triple(g, rec, rec.sent.last().second.getString("gameId"))
    }
    private fun invited(): Pair<GameManager, Rec> {
        val rec = Rec(); val g = GameManager(rec)
        g.handleGameEvent(PEER, j("""{"v":1,"type":"GAME_INVITE","gameId":"g1","variant":"LONG","dice":2,"ts":${System.currentTimeMillis()}}"""))
        assertEquals(GameManager.State.INVITED, g.state)
        return g to rec
    }
    private fun end(gid: String) = j("""{"v":1,"type":"GAME_END","gameId":"$gid"}""")

    // --- приглашающий ---
    @Test fun cancelSendsEndAndResets() {
        val (g, rec, gid) = inviting()
        g.cancelInvite()
        assertEquals(GameManager.State.IDLE, g.state)
        val (to, last) = rec.sent.last()
        assertEquals(PEER, to)
        assertEquals("GAME_END", last.getString("type"))
        assertEquals(gid, last.getString("gameId"))
    }
    @Test fun canInviteRightAfterCancel() {
        val (g, rec, gid) = inviting()
        g.cancelInvite()
        g.sendInvite(PEER)
        assertEquals(GameManager.State.INVITING, g.state)
        assertTrue(rec.sent.last().second.getString("gameId") != gid)
        assertTrue("после отмены не должно быть «ждём ответ»", rec.sys.none { it.contains("ждём ответ") })
    }
    @Test fun cancelWithoutInviteSendsNothing() {
        val rec = Rec(); val g = GameManager(rec)
        g.cancelInvite()
        assertTrue(rec.sent.isEmpty())
    }
    @Test fun cancelDoesNotTouchRunningGame() {      // отмена — только для ожидания, не выход из партии
        val (g, rec, gid) = inviting()
        g.handleGameEvent(PEER, j("""{"v":1,"type":"GAME_ACCEPT","gameId":"$gid","dice":2}"""))
        assertEquals(GameManager.State.OPENING, g.state)
        val before = rec.sent.size
        g.cancelInvite()
        assertEquals(GameManager.State.OPENING, g.state)
        assertTrue(rec.sent.drop(before).none { it.second.getString("type") == "GAME_END" })
    }
    @Test fun pendingInvitePeerVisibleOnlyWhileWaiting() {
        val rec = Rec(); val g = GameManager(rec); val clock = longArrayOf(1_800_000_000_000L)
        g.wallClock = { clock[0] }
        assertEquals(null, g.pendingInvitePeer())
        g.sendInvite(PEER)
        assertEquals(PEER, g.pendingInvitePeer())
        clock[0] += 9 * MIN                           // просрочено: sendInvite снимет сам, диалог не нужен
        assertEquals(null, g.pendingInvitePeer())
        clock[0] -= 9 * MIN
        g.cancelInvite()
        assertEquals(null, g.pendingInvitePeer())
    }

    // --- приглашённый: диалог «принять?» ещё открыт, а приглашение отменили ---
    @Test fun inviteeSeesCancelNotLeave() {
        val (g, rec) = invited()
        g.handleGameEvent(PEER, end("g1"))
        assertEquals(GameManager.State.IDLE, g.state)
        assertTrue("нет сообщения об отмене: ${rec.sys}", rec.sys.any { it.contains("отменил") })
        assertEquals("«покинул игру» — неверно, партии ещё не было", 0, rec.left)
    }
    @Test fun acceptAfterCancelSaysNoLongerValid() {
        val (g, rec) = invited()
        g.handleGameEvent(PEER, end("g1"))
        val before = rec.sent.size
        g.acceptInvite()
        assertEquals(before, rec.sent.size)
        assertTrue(rec.sys.any { it.contains("не действует") })
    }
    @Test fun cancelFromOtherPeerIgnored() {
        val (g, _) = invited()
        g.handleGameEvent(EVIL, end("g1"))
        assertEquals(GameManager.State.INVITED, g.state)
    }
}
