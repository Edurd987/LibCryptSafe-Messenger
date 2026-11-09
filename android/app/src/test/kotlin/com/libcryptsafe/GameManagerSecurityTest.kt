package com.libcryptsafe

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/**
 * GameManager: игровые кадры приходят от СОПЕРНИКА (контакт, E2E) — он может врать.
 * Красный -> зелёный: границы костей/ходов и лимит буфера ранних событий.
 */
class GameManagerSecurityTest {
    private val PEER = "AAAA-BBBB-CCCC-DDDD"

    private class Rec : GameCallback {
        val rolls = mutableListOf<Pair<Int, Int>>()
        val moves = mutableListOf<Triple<Int, Int, Int>>()
        override fun onSendGameEvent(targetPeerId: String, gameJson: String) {}
        override fun onInviteReceived(fromPeerId: String) {}
        override fun onGameStarted(peerId: String, gameId: String) {}
        override fun onOpeningDone(first: PlayerType, myDie: Int, peerDie: Int) {}
        override fun onGameSystemMessage(text: String) {}
        override fun onOpponentLeft() {}
        override fun onRemoteMove(from: Int, to: Int, die: Int) { moves += Triple(from, to, die) }
        override fun onRemoteRoll(a: Int, b: Int) { rolls += a to b }
    }

    private fun j(s: String) = JSONObject(s)
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    /** Довести партию до ACTIVE: мы приняли приглашение (чёрные), соперник — белые. */
    private fun active(): Pair<GameManager, Rec> {
        val rec = Rec(); val gm = GameManager(rec)
        gm.handleGameEvent(PEER, j("""{"v":1,"type":"GAME_INVITE","gameId":"g1","variant":"LONG","dice":2,"ts":${System.currentTimeMillis()}}"""))
        gm.acceptInvite()
        val salt = "00112233445566778899aabbccddeeff"
        val saltBytes = salt.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        var guard = 0
        while (gm.state != GameManager.State.ACTIVE && guard++ < 50) {   // ничья -> переброс
            val hash = sha(byteArrayOf(1) + saltBytes)
            gm.handleGameEvent(PEER, j("""{"v":1,"type":"GAME_OPEN_COMMIT","gameId":"g1","owner":"WHITE","hash":"$hash"}"""))
            gm.handleGameEvent(PEER, j("""{"v":1,"type":"GAME_OPEN_REVEAL","gameId":"g1","owner":"WHITE","die":1,"salt":"$salt"}"""))
        }
        assertEquals(GameManager.State.ACTIVE, gm.state)
        return gm to rec
    }
    private fun roll(seq: Int, a: Int, b: Int) =
        j("""{"v":1,"type":"GAME_ROLL","gameId":"g1","owner":"WHITE","seq":$seq,"a":$a,"b":$b}""")
    private fun move(seq: Int, from: Int, to: Int, die: Int) =
        j("""{"v":1,"type":"GAME_MOVE","gameId":"g1","owner":"WHITE","seq":$seq,"from":$from,"to":$to,"die":$die}""")

    // --- позитивные контроли ---
    @Test fun validEventsAppliedInSeqOrder() {
        val (gm, rec) = active()
        gm.handleGameEvent(PEER, move(1, 23, 20, 3))     // пришёл раньше первого
        gm.handleGameEvent(PEER, move(0, 23, 18, 5))
        assertEquals(listOf(Triple(23, 18, 5), Triple(23, 20, 3)), rec.moves)
    }
    @Test fun legitBearOffAndBarEntryPass() {
        val (gm, rec) = active()
        gm.handleGameEvent(PEER, move(0, 2, -1, -1))     // выброс: кость считает движок
        gm.handleGameEvent(PEER, move(1, -1, 18, 6))     // вход с бара (короткие)
        assertEquals(2, rec.moves.size)
    }
    @Test fun strangerEventsIgnored() {
        val (gm, rec) = active()
        gm.handleGameEvent("EEEE-EEEE-EEEE-EEEE", roll(0, 3, 5))
        assertTrue(rec.rolls.isEmpty())
    }

    // --- атаки соперника ---
    @Test fun rollOutOfDiceRangeRejected() {
        val (gm, rec) = active()
        gm.handleGameEvent(PEER, roll(0, 7, 3))
        gm.handleGameEvent(PEER, roll(1, 3, 99))
        assertTrue("кость вне 1..6 не должна дойти до доски: ${rec.rolls}", rec.rolls.isEmpty())
    }
    @Test fun moveOutOfBoardRejected() {
        val (gm, rec) = active()
        gm.handleGameEvent(PEER, move(0, 999, 5, 3))
        gm.handleGameEvent(PEER, move(1, 23, -7, 3))
        gm.handleGameEvent(PEER, move(2, -1, -1, 3))     // и вход, и выброс разом
        gm.handleGameEvent(PEER, move(3, 23, 20, 9))     // кость вне 1..6
        gm.handleGameEvent(PEER, move(4, 2, -1, 4))      // выброс с подсунутой костью
        assertTrue("мусорный ход не должен дойти до доски: ${rec.moves}", rec.moves.isEmpty())
    }
    @Test fun earlyEventBufferBounded() {
        val (gm, _) = active()
        for (s in 1..1000) gm.handleGameEvent(PEER, roll(s, 3, 5))   // seq 0 так и не пришёл
        assertTrue("буфер ${gm.bufferedEventCount()} > ${GameManager.MAX_BUFFERED}",
            gm.bufferedEventCount() <= GameManager.MAX_BUFFERED)
    }
    @Test fun duplicateEarlyEventStoredOnce() {
        val (gm, _) = active()
        repeat(100) { gm.handleGameEvent(PEER, roll(5, 3, 5)) }
        assertEquals(1, gm.bufferedEventCount())
    }
}
