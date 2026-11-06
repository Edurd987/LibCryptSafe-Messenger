package com.libcryptsafe

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/**
 * №48 шаг 2: ход и бросок СОПЕРНИКА из сети. Соперник может врать:
 * назвать не ту кость, ходить в чужой ход моими шашками, перебросить мои кости.
 * Кадр, который не сходится с доской, отвергается (null) — доска не меняется.
 * Мы — чёрные, соперник — белые.
 */
class NardiRemoteTest {
    private val L = NardiVariant.LONG
    private val S = NardiVariant.SHORT
    private val ME = PlayerType.BLACK
    private val PEER = PlayerType.WHITE

    private fun long(turn: PlayerType, a: Int? = null, b: Int? = null): NardiGameState {
        val s = initLongNardi().copy(isOpening = false, turn = turn)
        return if (a != null && b != null) applyRoll(s, a, b) else s
    }

    // --- позитивные контроли: честная игра проходит ---
    @Test fun honestMoveSpendsWalkedDie() {
        val st = NardiRemote.applyMove(L, long(PEER, 6, 1), PEER, 23, 17, 6)
        assertTrue("честный ход отвергнут", st != null)
        assertEquals(listOf(1), st!!.dice)
        assertEquals(PointState(1, PlayerType.WHITE), st.board[17])
    }
    @Test fun honestRollOnPeerTurnApplied() {
        val st = NardiRemote.applyRoll(L, long(PEER), PEER, 3, 5)
        assertTrue("честный бросок отвергнут", st != null)
        assertEquals(listOf(3, 5), st!!.dice)
    }

    // --- атаки: подмена кости ---
    @Test fun moveWithSwappedDieRejected() {
        // кости 6,1: шашка прошла 6, а соперник называет 1 -> осталась бы 6 = лишний ход
        assertEquals(null, NardiRemote.applyMove(L, long(PEER, 6, 1), PEER, 23, 17, 1))
    }
    @Test fun moveWithAbsentDieRejected() {
        // кость 5 не выпадала -> consumeDie ничего не тратит -> кости не кончаются
        assertEquals(null, NardiRemote.applyMove(L, long(PEER, 6, 1), PEER, 23, 17, 5))
    }

    // --- атаки: чужая очередь ---
    @Test fun peerCannotMoveMyCheckersOnMyTurn() {
        val s = long(ME, 6, 1)
        val my = (0..23).firstOrNull { t -> NardiEngine.legal(L, s, 11, t) }
        assertTrue("в тесте нужен легальный ход чёрных с головы", my != null)
        assertEquals("соперник двигает МОИ шашки в МОЙ ход", null, NardiRemote.applyMove(L, s, PEER, 11, my!!, 6))
    }
    @Test fun rollOnMyTurnRejected() {
        assertEquals("соперник бросает в мой ход", null, NardiRemote.applyRoll(L, long(ME), PEER, 6, 6))
    }
    @Test fun rollOverExistingDiceRejected() {
        assertEquals("переброс уже выпавших костей", null, NardiRemote.applyRoll(L, long(PEER, 3, 2), PEER, 6, 6))
    }
    @Test fun rollDuringOpeningRejected() {
        val s = initLongNardi().copy(turn = PEER)   // isOpening = true
        assertEquals(null, NardiRemote.applyRoll(L, s, PEER, 6, 6))
    }

    // --- короткие: вход с бара ---
    private fun shortBar(turn: PlayerType) =
        applyRoll(ShortNardiModel.initShortNardi().copy(isOpening = false, turn = turn, barWhite = 1, barBlack = 1), 3, 5)
    @Test fun honestBarEntryApplied() {
        val st = NardiRemote.applyMove(S, shortBar(PEER), PEER, -1, ShortNardiModel.barEntryPoint(PEER, 3), 3)
        assertTrue("честный вход с бара отвергнут", st != null)
        assertEquals(0, st!!.barWhite)
    }
    @Test fun barEntryWithWrongTargetRejected() {
        assertEquals("to не совпадает с пунктом входа", null, NardiRemote.applyMove(S, shortBar(PEER), PEER, -1, 10, 3))
    }
    @Test fun peerCannotEnterMyBarOnMyTurn() {
        val s = shortBar(ME)
        assertEquals(null, NardiRemote.applyMove(S, s, PEER, -1, ShortNardiModel.barEntryPoint(ME, 3), 3))
    }

    // --- GAME_END чужой партии ---
    private class Rec : GameCallback {
        override fun onSendGameEvent(targetPeerId: String, gameJson: String) {}
        override fun onInviteReceived(fromPeerId: String) {}
        override fun onGameStarted(peerId: String, gameId: String) {}
        override fun onOpeningDone(first: PlayerType, myDie: Int, peerDie: Int) {}
        override fun onGameSystemMessage(text: String) {}
        override fun onOpponentLeft() {}
        override fun onRemoteMove(from: Int, to: Int, die: Int) {}
        override fun onRemoteRoll(a: Int, b: Int) {}
    }
    private fun invited(): GameManager {
        val gm = GameManager(Rec())
        gm.handleGameEvent("AAAA-BBBB-CCCC-DDDD", JSONObject("""{"v":1,"type":"GAME_INVITE","gameId":"g1","variant":"LONG"}"""))
        gm.acceptInvite()
        return gm
    }
    @Test fun endOfOtherGameIgnored() {
        val gm = invited()
        gm.handleGameEvent("AAAA-BBBB-CCCC-DDDD", JSONObject("""{"v":1,"type":"GAME_END","gameId":"old-game"}"""))
        assertEquals("GAME_END чужой партии закрыл текущую", GameManager.State.OPENING, gm.state)
    }
    @Test fun endOfThisGameStops() {
        val gm = invited()
        gm.handleGameEvent("AAAA-BBBB-CCCC-DDDD", JSONObject("""{"v":1,"type":"GAME_END","gameId":"g1"}"""))
        assertEquals(GameManager.State.IDLE, gm.state)
    }
}
