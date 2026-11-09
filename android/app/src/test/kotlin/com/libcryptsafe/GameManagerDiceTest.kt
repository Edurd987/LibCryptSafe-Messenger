package com.libcryptsafe

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/**
 * №48 шаг 3: бросок по сети. Кости не выбирает ни бросающий, ни соперник.
 * Мы — чёрные (приняли приглашение), соперник PEER — белые.
 */
class GameManagerDiceTest {
    private val PEER = "AAAA-BBBB-CCCC-DDDD"
    private val SALT = "00112233445566778899aabbccddeeff"

    private class Rec : GameCallback {
        val sent = mutableListOf<JSONObject>()
        val remote = mutableListOf<Pair<Int, Int>>()
        val local = mutableListOf<Pair<Int, Int>>()
        val sys = mutableListOf<String>()
        override fun onSendGameEvent(targetPeerId: String, gameJson: String) { sent += JSONObject(gameJson) }
        override fun onInviteReceived(fromPeerId: String) {}
        override fun onGameStarted(peerId: String, gameId: String) {}
        override fun onOpeningDone(first: PlayerType, myDie: Int, peerDie: Int) {}
        override fun onGameSystemMessage(text: String) { sys += text }
        override fun onOpponentLeft() {}
        override fun onRemoteMove(from: Int, to: Int, die: Int) {}
        override fun onRemoteRoll(a: Int, b: Int) { remote += a to b }
        override fun onLocalRoll(a: Int, b: Int) { local += a to b }
    }
    private fun j(s: String) = JSONObject(s)
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    private fun hexToBytes(h: String) = h.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** Партия ACTIVE. Возвращает следующий seq, который мы ждём от соперника (0). */
    private fun active(inviteDice: Int? = 2): Pair<GameManager, Rec> {
        val rec = Rec(); val gm = GameManager(rec)
        val dice = if (inviteDice == null) ""","ts":${System.currentTimeMillis()}""" else ""","dice":$inviteDice,"ts":${System.currentTimeMillis()}"""
        gm.handleGameEvent(PEER, j("""{"v":1,"type":"GAME_INVITE","gameId":"g1","variant":"LONG"$dice}"""))
        if (gm.state != GameManager.State.INVITED) return gm to rec
        gm.acceptInvite()
        val saltB = hexToBytes(SALT)
        for (d in List(30) { if (it % 2 == 0) 6 else 1 }) {
            if (gm.state == GameManager.State.ACTIVE) break
            gm.handleGameEvent(PEER, j("""{"v":1,"type":"GAME_OPEN_COMMIT","gameId":"g1","owner":"WHITE","hash":"${sha(byteArrayOf(d.toByte()) + saltB)}"}"""))
            gm.handleGameEvent(PEER, j("""{"v":1,"type":"GAME_OPEN_REVEAL","gameId":"g1","owner":"WHITE","die":$d,"salt":"$SALT"}"""))
        }
        assertEquals(GameManager.State.ACTIVE, gm.state)
        rec.sent.clear()
        return gm to rec
    }
    private fun peer(type: String, seq: Int, extra: String) =
        j("""{"v":1,"type":"$type","gameId":"g1","owner":"WHITE","seq":$seq$extra}""")

    // === Красный: старый бросок, где кости выбрал сам бросающий ===
    @Test fun plainRollWithChosenDiceRejected() {
        val (gm, rec) = active()
        gm.handleGameEvent(PEER, peer("GAME_ROLL", 0, ""","a":6,"b":6"""))
        assertTrue("бросок 6-6 «на слово» принят: ${rec.remote}", rec.remote.isEmpty())
    }

    // === Позитив: соперник бросает честно ===
    @Test fun honestPeerRoll() {
        val (gm, rec) = active()
        val sA = DiceProtocol.newSecret()
        gm.handleGameEvent(PEER, peer("GAME_ROLL_COMMIT", 0, ""","h":"${DiceProtocol.commit(sA)}""""))
        val seed = rec.sent.single { it.getString("type") == "GAME_ROLL_SEED" }
        assertEquals(0, seed.getInt("rollSeq"))
        val sB = hexToBytes(seed.getString("s"))
        gm.handleGameEvent(PEER, peer("GAME_ROLL_REVEAL", 1, ""","rollSeq":0,"s":"${DiceProtocol.hex(sA)}""""))
        assertEquals(listOf(DiceProtocol.derive(sA, sB, "g1", 0)), rec.remote)
        assertFalse(gm.isPeerRevealOverdue(Long.MAX_VALUE))
    }

    // === Позитив: я бросаю честно ===
    @Test fun honestMyRoll() {
        val (gm, rec) = active()
        assertTrue(gm.requestRoll())
        assertFalse("второй запрос до завершения первого", gm.requestRoll())
        val commit = rec.sent.single { it.getString("type") == "GAME_ROLL_COMMIT" }
        assertTrue("в commit нет костей", !commit.has("a") && !commit.has("b"))
        val sB = DiceProtocol.newSecret()
        gm.handleGameEvent(PEER, peer("GAME_ROLL_SEED", 0, ""","rollSeq":${commit.getInt("seq")},"s":"${DiceProtocol.hex(sB)}""""))
        val reveal = rec.sent.single { it.getString("type") == "GAME_ROLL_REVEAL" }
        val sA = hexToBytes(reveal.getString("s"))
        assertTrue("раскрыт не тот секрет", DiceProtocol.verify(sA, commit.getString("h")))
        assertEquals(listOf(DiceProtocol.derive(sA, sB, "g1", commit.getInt("seq"))), rec.local)
        assertTrue(gm.requestRoll())   // следующий бросок снова возможен
    }

    // === Атаки ===
    @Test fun revealNotMatchingCommitEndsGame() {
        val (gm, rec) = active()
        gm.handleGameEvent(PEER, peer("GAME_ROLL_COMMIT", 0, ""","h":"${DiceProtocol.commit(DiceProtocol.newSecret())}""""))
        gm.handleGameEvent(PEER, peer("GAME_ROLL_REVEAL", 1, ""","rollSeq":0,"s":"${DiceProtocol.hex(DiceProtocol.newSecret())}""""))
        assertTrue("подменённый бросок применён", rec.remote.isEmpty())
        assertEquals(GameManager.State.IDLE, gm.state)
    }
    @Test fun secondCommitBeforeRevealIgnored() {
        // «перебросить»: увидел seed, результат не понравился — шлёт новый commit вместо reveal
        val (gm, rec) = active()
        gm.handleGameEvent(PEER, peer("GAME_ROLL_COMMIT", 0, ""","h":"${DiceProtocol.commit(DiceProtocol.newSecret())}""""))
        gm.handleGameEvent(PEER, peer("GAME_ROLL_COMMIT", 1, ""","h":"${DiceProtocol.commit(DiceProtocol.newSecret())}""""))
        assertEquals("на второй commit seed не выдаётся", 1, rec.sent.count { it.getString("type") == "GAME_ROLL_SEED" })
        assertTrue("таймаут раскрытия идёт", gm.isPeerRevealOverdue(Long.MAX_VALUE))
    }
    @Test fun revealWithoutCommitIgnored() {
        val (gm, rec) = active()
        gm.handleGameEvent(PEER, peer("GAME_ROLL_REVEAL", 0, ""","rollSeq":0,"s":"${DiceProtocol.hex(DiceProtocol.newSecret())}""""))
        assertTrue(rec.remote.isEmpty()); assertEquals(GameManager.State.ACTIVE, gm.state)
    }
    @Test fun seedForUnknownRollIgnored() {
        val (gm, rec) = active()
        gm.handleGameEvent(PEER, peer("GAME_ROLL_SEED", 0, ""","rollSeq":5,"s":"${DiceProtocol.hex(DiceProtocol.newSecret())}""""))
        assertTrue(rec.local.isEmpty()); assertTrue(rec.sent.none { it.getString("type") == "GAME_ROLL_REVEAL" })
    }
    @Test fun replayedRevealAppliedOnce() {
        val (gm, rec) = active()
        val sA = DiceProtocol.newSecret()
        gm.handleGameEvent(PEER, peer("GAME_ROLL_COMMIT", 0, ""","h":"${DiceProtocol.commit(sA)}""""))
        val rv = peer("GAME_ROLL_REVEAL", 1, ""","rollSeq":0,"s":"${DiceProtocol.hex(sA)}"""")
        gm.handleGameEvent(PEER, rv); gm.handleGameEvent(PEER, JSONObject(rv.toString()))
        assertEquals(1, rec.remote.size)
    }
    @Test fun badHashInCommitRejected() {
        val (gm, rec) = active()
        gm.handleGameEvent(PEER, peer("GAME_ROLL_COMMIT", 0, ""","h":"6-6""""))
        assertTrue(rec.sent.none { it.getString("type") == "GAME_ROLL_SEED" })
    }
    @Test fun revealTimeoutReported() {
        val (gm, _) = active()
        gm.handleGameEvent(PEER, peer("GAME_ROLL_COMMIT", 0, ""","h":"${DiceProtocol.commit(DiceProtocol.newSecret())}""""))
        assertFalse("сразу после seed — не просрочено", gm.isPeerRevealOverdue(gm.monoClock()))
        assertTrue(gm.isPeerRevealOverdue(gm.monoClock() + DiceProtocol.REVEAL_TIMEOUT_MS + 1))
    }

    @Test fun claimOnlyAfterTimeoutAndExtendResets() {
        val (gm, _) = active()
        gm.handleGameEvent(PEER, peer("GAME_ROLL_COMMIT", 0, ""","h":"${DiceProtocol.commit(DiceProtocol.newSecret())}""""))
        val now = gm.monoClock()
        assertFalse("победа до таймаута", gm.claimRevealTimeout(now))
        assertEquals(GameManager.State.ACTIVE, gm.state)
        val later = now + DiceProtocol.REVEAL_TIMEOUT_MS + 1
        gm.extendRevealWait(later)                                   // «подождать ещё»
        assertFalse("после «подождать» таймаут начался заново", gm.claimRevealTimeout(later + 1000))
        assertTrue(gm.claimRevealTimeout(later + DiceProtocol.REVEAL_TIMEOUT_MS + 1))
        assertEquals(GameManager.State.IDLE, gm.state)
    }
    @Test fun noClaimWithoutPendingRoll() {
        val (gm, _) = active()
        assertFalse(gm.claimRevealTimeout(Long.MAX_VALUE))
        assertEquals(GameManager.State.ACTIVE, gm.state)
    }

    // === Версия протокола ===
    @Test fun oldClientInviteRefused() {
        val (gm, rec) = active(inviteDice = null)
        assertEquals("приглашение старого клиента принято", GameManager.State.IDLE, gm.state)
        assertTrue(rec.sys.any { it.contains("старая версия") })
    }
    @Test fun myInviteCarriesVersion() {
        val rec = Rec(); val gm = GameManager(rec)
        gm.sendInvite(PEER)
        assertEquals(DiceProtocol.VERSION, rec.sent.single().getInt("dice"))
    }
}
