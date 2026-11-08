package com.libcryptsafe

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/**
 * Сеть может переставить кадры соперника (параллельная расшифровка, ретраи релея).
 * Ранний кадр не выбрасываем — откладываем до выполнения условия.
 * Честность commit-reveal при этом не ослабляется.
 */
class GameManagerReorderTest {
    private val PEER = "AAAA-BBBB-CCCC-DDDD"
    private val SALT = "00112233445566778899aabbccddeeff"

    private class Rec : GameCallback {
        val sent = mutableListOf<JSONObject>()
        val rolls = mutableListOf<Pair<Int, Int>>()
        val moves = mutableListOf<Triple<Int, Int, Int>>()
        var opened = 0
        override fun onSendGameEvent(targetPeerId: String, gameJson: String) { sent += JSONObject(gameJson) }
        override fun onInviteReceived(fromPeerId: String) {}
        override fun onGameStarted(peerId: String, gameId: String) {}
        override fun onOpeningDone(first: PlayerType, myDie: Int, peerDie: Int) { opened++ }
        override fun onGameSystemMessage(text: String) {}
        override fun onOpponentLeft() {}
        override fun onRemoteMove(from: Int, to: Int, die: Int) { moves += Triple(from, to, die) }
        override fun onRemoteRoll(a: Int, b: Int) { rolls += a to b }
    }

    private fun j(s: String) = JSONObject(s)
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    private fun saltBytes() = SALT.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** Мы — чёрные, партия в OPENING. */
    private fun opening(): Pair<GameManager, Rec> {
        val rec = Rec(); val gm = GameManager(rec)
        gm.handleGameEvent(PEER, j("""{"v":1,"type":"GAME_INVITE","gameId":"g1","variant":"LONG","dice":2}"""))
        gm.acceptInvite()
        assertEquals(GameManager.State.OPENING, gm.state)
        return gm to rec
    }
    /** Моя кость розыгрыша — из моего reveal; берём кость соперника, отличную от неё (без ничьей). */
    private fun myDie(rec: Rec) = rec.sent.last { it.optString("type") == "GAME_OPEN_REVEAL" }.getInt("die")
    private fun commit(die: Int) = j("""{"v":1,"type":"GAME_OPEN_COMMIT","gameId":"g1","owner":"WHITE","hash":"${sha(byteArrayOf(die.toByte()) + saltBytes())}"}""")
    private fun reveal(die: Int) = j("""{"v":1,"type":"GAME_OPEN_REVEAL","gameId":"g1","owner":"WHITE","die":$die,"salt":"$SALT"}""")
    private fun roll(seq: Int, a: Int, b: Int) = j("""{"v":1,"type":"GAME_ROLL","gameId":"g1","owner":"WHITE","seq":$seq,"a":$a,"b":$b}""")
    private fun move(seq: Int, from: Int, to: Int, die: Int) = j("""{"v":1,"type":"GAME_MOVE","gameId":"g1","owner":"WHITE","seq":$seq,"from":$from,"to":$to,"die":$die}""")

    /** Узнать свою кость: нужен commit соперника (после него мы шлём reveal). Поэтому отдельный прогон. */
    private fun peerDieNotTie(gmRec: Pair<GameManager, Rec>, deliver: (GameManager, Int) -> Unit) {
        // Кость соперника выбираем 6 или 1 — одна из них точно ≠ нашей, но ничья тоже обработана как переброс.
        for (d in List(20) { if (it % 2 == 0) 6 else 1 }) {
            deliver(gmRec.first, d)
            if (gmRec.first.state == GameManager.State.ACTIVE) return
        }
    }

    // === Красный 1: живой баг 6 ноября — reveal обогнал commit ===
    @Test fun revealBeforeCommitStillFinishesOpening() {
        val (gm, rec) = opening()
        peerDieNotTie(gm to rec) { g, d ->
            g.handleGameEvent(PEER, reveal(d))   // пришёл РАНЬШЕ
            g.handleGameEvent(PEER, commit(d))
        }
        assertEquals("reveal раньше commit не должен вешать партию", GameManager.State.ACTIVE, gm.state)
        assertEquals(1, rec.opened)
    }

    // === Красный 2: ход соперника пришёл, пока мы ещё в OPENING ===
    @Test fun rollDuringOpeningAppliedAfterOpening() {
        val (gm, rec) = opening()
        gm.handleGameEvent(PEER, move(0, 23, 21, 2))      // соперник уже ходит, а мы ещё разыгрываем
        gm.handleGameEvent(PEER, move(1, 23, 22, 1))
        assertTrue("до конца розыгрыша доска не трогается", rec.rolls.isEmpty() && rec.moves.isEmpty())
        peerDieNotTie(gm to rec) { g, d ->
            g.handleGameEvent(PEER, commit(d)); g.handleGameEvent(PEER, reveal(d))
        }
        assertEquals(GameManager.State.ACTIVE, gm.state)
        assertEquals(listOf(Triple(23, 21, 2), Triple(23, 22, 1)), rec.moves)
    }

    // === Позитивные контроли честности (должны быть зелёными и до, и после) ===
    @Test fun earlyRevealWithWrongHashStillEndsGame() {
        val (gm, _) = opening()
        gm.handleGameEvent(PEER, reveal(5))               // раскрыл 5...
        gm.handleGameEvent(PEER, commit(2))               // ...а коммитил 2
        assertEquals("подтасовка в отложенном reveal ловится", GameManager.State.IDLE, gm.state)
    }
    @Test fun wrongHashInOrderStillEndsGame() {
        val (gm, _) = opening()
        gm.handleGameEvent(PEER, commit(2))
        gm.handleGameEvent(PEER, reveal(5))
        assertEquals(GameManager.State.IDLE, gm.state)
    }
    @Test fun earlyEventsFromStrangerNotQueued() {
        val (gm, rec) = opening()
        gm.handleGameEvent("EEEE-EEEE-EEEE-EEEE", move(0, 23, 17, 6))
        gm.handleGameEvent(PEER, j("""{"v":1,"type":"GAME_MOVE","gameId":"other","owner":"WHITE","seq":0,"from":23,"to":17,"die":6}"""))
        peerDieNotTie(gm to rec) { g, d -> g.handleGameEvent(PEER, commit(d)); g.handleGameEvent(PEER, reveal(d)) }
        assertEquals(GameManager.State.ACTIVE, gm.state)
        assertTrue("чужие/не той партии кадры не копятся: ${rec.moves}", rec.moves.isEmpty())
    }
    @Test fun openingQueueIsBounded() {
        val (gm, rec) = opening()
        for (s in 0 until 1000) gm.handleGameEvent(PEER, move(s, 23, 20, 3))
        peerDieNotTie(gm to rec) { g, d -> g.handleGameEvent(PEER, commit(d)); g.handleGameEvent(PEER, reveal(d)) }
        assertTrue("очередь розыгрыша ограничена: применено ${rec.moves.size}", rec.moves.size <= GameManager.MAX_BUFFERED + 1)
        assertNotEquals(0, rec.moves.size)
    }

    // === Память под атакой: соперник раздувает каждый ранний кадр до ~60 КБ мусорных полей ===
    @Test fun bufferBudgetIsSmall() {
        assertTrue("MAX_BUFFERED=${GameManager.MAX_BUFFERED} вне 10..50", GameManager.MAX_BUFFERED in 10..50)
    }
    @Test fun paddedEarlyFramesStoredSlim() {
        val (gm, rec) = opening()
        val junk = "x".repeat(60_000)
        for (s in 0 until 200) gm.handleGameEvent(PEER, j("""{"v":1,"type":"GAME_ROLL","gameId":"g1","owner":"WHITE","seq":${s + 1},"a":3,"b":4,"pad":"$junk"}"""))
        peerDieNotTie(gm to rec) { g, d -> g.handleGameEvent(PEER, commit(d)); g.handleGameEvent(PEER, reveal(d)) }
        // seq 0 не пришёл -> всё лежит в буфере seq
        assertTrue("в буфере ${gm.bufferedEventCount()}", gm.bufferedEventCount() in 1..GameManager.MAX_BUFFERED)
        assertTrue("буфер держит ${gm.bufferedBytes()} байт — мусорные поля не должны храниться",
            gm.bufferedBytes() <= GameManager.MAX_BUFFERED * 256)
    }
}
