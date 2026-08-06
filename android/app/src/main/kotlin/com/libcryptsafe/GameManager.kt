package com.libcryptsafe

import org.json.JSONObject

// Нарды: "мозг" онлайн-игры. Чистая логика + состояния, НЕ знает про View.
// Труба (handleDecrypted в MainActivity) зовёт handleGameEvent.
// Обратно к UI/сети — через GameCallback. INSTANCE — чтобы GameActivity звала endGame.
class GameManager(private val callback: GameCallback) {

    enum class State { IDLE, INVITING, INVITED, ACTIVE }

    var state: State = State.IDLE; private set
    var peerId: String = ""; private set
    var gameId: String = ""; private set
    private var model: NardiGameState? = null
    var myColor: PlayerType = PlayerType.WHITE; private set   // мой цвет в сетевой партии

    fun sendInvite(targetPeerId: String) {
        if (state != State.IDLE) {
            callback.onGameSystemMessage("\u0443\u0436\u0435 \u0432 \u0438\u0433\u0440\u0435 \u0438\u043b\u0438 \u0436\u0434\u0451\u043c \u043e\u0442\u0432\u0435\u0442")
            return
        }
        peerId = targetPeerId
        gameId = java.util.UUID.randomUUID().toString()
        myColor = PlayerType.WHITE           // приглашающий играет белыми
        state = State.INVITING
        val invite = JSONObject().apply {
            put("v", 1); put("type", "GAME_INVITE"); put("gameId", gameId); put("gameType", "nardi")
        }.toString()
        callback.onSendGameEvent(peerId, invite)
        callback.onGameSystemMessage("\u043f\u0440\u0438\u0433\u043b\u0430\u0448\u0435\u043d\u0438\u0435 \u043e\u0442\u043f\u0440\u0430\u0432\u043b\u0435\u043d\u043e")
    }

    fun handleGameEvent(fromPeerId: String, json: JSONObject) {
        when (json.optString("type")) {
            "GAME_INVITE" -> onInviteReceived(fromPeerId, json)
            "GAME_ACCEPT" -> onAcceptReceived(fromPeerId, json)
            "GAME_END" -> onEndReceived(fromPeerId, json)
            "GAME_MOVE" -> onMoveReceived(fromPeerId, json)
            "GAME_ROLL" -> onRollReceived(fromPeerId, json)
            else -> android.util.Log.w("GAME_MGR", "unknown game type: ${json.optString("type")}")
        }
    }

    private fun onInviteReceived(fromPeerId: String, json: JSONObject) {
        if (state != State.IDLE) return
        val incomingGameId = json.optString("gameId", "")
        if (incomingGameId.isEmpty() || incomingGameId.length > 64) return
        peerId = fromPeerId
        gameId = incomingGameId
        myColor = PlayerType.BLACK           // принимающий играет чёрными
        state = State.INVITED
        callback.onInviteReceived(fromPeerId)
    }

    fun acceptInvite() {
        if (state != State.INVITED) return
        val accept = JSONObject().apply {
            put("v", 1); put("type", "GAME_ACCEPT"); put("gameId", gameId)
        }.toString()
        callback.onSendGameEvent(peerId, accept)
        startGame()
    }

    fun declineInvite() { resetToIdle() }

    private fun onAcceptReceived(fromPeerId: String, json: JSONObject) {
        if (state != State.INVITING) return
        if (json.optString("gameId", "") != gameId) return
        if (fromPeerId != peerId) return
        startGame()
    }

    private fun startGame() {
        model = initLongNardi()
        state = State.ACTIVE
        callback.onGameStarted(peerId, gameId)
    }

    // Выход из партии: GAME_END сопернику + сброс. No-op при IDLE (офлайн не трогает).
    fun endGame() {
        if (state == State.IDLE) return
        val end = JSONObject().apply {
            put("v", 1); put("type", "GAME_END"); put("gameId", gameId)
        }.toString()
        callback.onSendGameEvent(peerId, end)
        resetToIdle()
        callback.onGameSystemMessage("\u043f\u0430\u0440\u0442\u0438\u044f \u0437\u0430\u0432\u0435\u0440\u0448\u0435\u043d\u0430")
    }

    private fun onEndReceived(fromPeerId: String, json: JSONObject) {
        if (fromPeerId != peerId) return
        resetToIdle()
        callback.onOpponentLeft()
        callback.onGameSystemMessage("\u0441\u043e\u043f\u0435\u0440\u043d\u0438\u043a \u0432\u044b\u0448\u0435\u043b \u0438\u0437 \u043f\u0430\u0440\u0442\u0438\u0438")
    }

    // Исходящий ход: board уведомил через onMoveMade -> шлём в трубу.
    fun sendRoll(a: Int, b: Int) {
        if (state != State.ACTIVE) return
        val roll = JSONObject().apply {
            put("v", 1); put("type", "GAME_ROLL"); put("gameId", gameId)
            put("a", a); put("b", b)
        }.toString()
        callback.onSendGameEvent(peerId, roll)
        android.util.Log.i("GAME_ROLL", "-> $a,$b")
    }

    private fun onRollReceived(fromPeerId: String, json: JSONObject) {
        if (state != State.ACTIVE) return
        if (fromPeerId != peerId) return
        if (json.optString("gameId", "") != gameId) return
        val a = json.optInt("a", 0); val b = json.optInt("b", 0)
        if (a < 1 || b < 1) return
        android.util.Log.i("GAME_ROLL", "<- $a,$b")
        callback.onRemoteRoll(a, b)
    }

    fun sendMove(from: Int, to: Int) {
        if (state != State.ACTIVE) return
        val move = JSONObject().apply {
            put("v", 1); put("type", "GAME_MOVE"); put("gameId", gameId)
            put("from", from); put("to", to)
        }.toString()
        callback.onSendGameEvent(peerId, move)
        android.util.Log.i("GAME_MOVE", "-> $from->$to")
    }

    // Входящий ход соперника: применяем к доске, которую видит игрок (board = истина).
    private fun onMoveReceived(fromPeerId: String, json: JSONObject) {
        if (state != State.ACTIVE) return
        if (fromPeerId != peerId) return
        if (json.optString("gameId", "") != gameId) return
        val from = json.optInt("from", -1)
        val to = json.optInt("to", -1)
        if (from < 0 || to < 0) return
        android.util.Log.i("GAME_MOVE", "<- $from->$to")
        callback.onRemoteMove(from, to)   // MainActivity -> GameActivity.CURRENT -> board
    }

    private fun resetToIdle() {
        state = State.IDLE; peerId = ""; gameId = ""; model = null
    }

    companion object {
        @Volatile var INSTANCE: GameManager? = null
    }
}

// Связь GameManager -> MainActivity (UI + отправка). MainActivity реализует.
interface GameCallback {
    fun onSendGameEvent(targetPeerId: String, gameJson: String)
    fun onInviteReceived(fromPeerId: String)
    fun onGameStarted(peerId: String, gameId: String)
    fun onGameSystemMessage(text: String)
    fun onOpponentLeft()
    fun onRemoteMove(from: Int, to: Int)
    fun onRemoteRoll(a: Int, b: Int)
}
