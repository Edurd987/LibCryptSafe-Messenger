package com.libcryptsafe

import com.libcryptsafe.util.SafeLogger

import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom

// Нарды: "мозг" онлайн-игры. Чистая логика + состояния, НЕ знает про View.
// Труба (handleDecrypted в MainActivity) зовёт handleGameEvent.
// Обратно к UI/сети — через GameCallback. INSTANCE — чтобы GameActivity звала endGame.
class GameManager(private val callback: GameCallback) {

    enum class State { IDLE, INVITING, INVITED, OPENING, ACTIVE }

    var state: State = State.IDLE; private set
    var peerId: String = ""; private set
    var gameId: String = ""; private set
    private var model: NardiGameState? = null
    var myColor: PlayerType = PlayerType.WHITE; private set   // мой цвет в сетевой партии
    var variant: NardiVariant = NardiVariant.LONG; private set   // вариант партии (обоим игрокам одинаков)
    // Маяк 4: честный розыгрыш первого хода (commit-reveal)
    private enum class OpeningPhase { NONE, WAIT_PEER_COMMIT, WAIT_PEER_REVEAL, DONE }
    private var openingPhase = OpeningPhase.NONE
    private var myOpeningDie = 0
    private var myOpeningSalt = ""      // hex
    private var peerCommitHash = ""     // hex, хэш соперника (сохраняем на фазе commit)
    private var outgoingSeq: Int = 0                          // мой счётчик исходящих событий
    private var expectedSeq: Int = 0                          // жду от соперника этот seq
    private val eventBuffer = mutableListOf<Pair<Int, JSONObject>>()  // ранние события (seq > expected)
    // Умная очередь: сеть может переставить кадры. Ранний кадр ждёт своего условия, а не выбрасывается.
    private var pendingPeerReveal: JSONObject? = null                 // reveal, обогнавший commit
    private val openingQueue = mutableListOf<JSONObject>()            // ROLL/MOVE, пришедшие во время OPENING

    fun sendInvite(targetPeerId: String, gameVariant: NardiVariant = NardiVariant.LONG) {
        if (state != State.IDLE) {
            callback.onGameSystemMessage("\u0443\u0436\u0435 \u0432 \u0438\u0433\u0440\u0435 \u0438\u043b\u0438 \u0436\u0434\u0451\u043c \u043e\u0442\u0432\u0435\u0442")
            return
        }
        peerId = targetPeerId
        gameId = java.util.UUID.randomUUID().toString()
        myColor = PlayerType.WHITE           // приглашающий играет белыми
        variant = gameVariant                // A задаёт вариант сам
        state = State.INVITING
        val invite = JSONObject().apply {
            put("v", 1); put("type", "GAME_INVITE"); put("gameId", gameId); put("gameType", "nardi")
            put("variant", gameVariant.name)  // едет сопернику, чтобы обе доски совпали
        }.toString()
        callback.onSendGameEvent(peerId, invite)
        callback.onGameSystemMessage("\u043f\u0440\u0438\u0433\u043b\u0430\u0448\u0435\u043d\u0438\u0435 \u043e\u0442\u043f\u0440\u0430\u0432\u043b\u0435\u043d\u043e")
    }

    fun handleGameEvent(fromPeerId: String, json: JSONObject) {
        when (json.optString("type")) {
            "GAME_INVITE" -> onInviteReceived(fromPeerId, json)
            "GAME_ACCEPT" -> onAcceptReceived(fromPeerId, json)
            "GAME_OPEN_COMMIT" -> onOpenCommitReceived(fromPeerId, json)
            "GAME_OPEN_REVEAL" -> onOpenRevealReceived(fromPeerId, json)
            "GAME_END" -> onEndReceived(fromPeerId, json)
            "GAME_MOVE" -> onMoveReceived(fromPeerId, json)
            "GAME_ROLL" -> onRollReceived(fromPeerId, json)
            else -> SafeLogger.w("GAME_MGR") { "unknown game type: ${json.optString("type")}" }
        }
    }

    private fun onInviteReceived(fromPeerId: String, json: JSONObject) {
        if (state != State.IDLE) return
        val incomingGameId = json.optString("gameId", "")
        if (incomingGameId.isEmpty() || incomingGameId.length > 64) return
        peerId = fromPeerId
        gameId = incomingGameId
        myColor = PlayerType.BLACK           // принимающий играет чёрными
        variant = try { NardiVariant.valueOf(json.optString("variant", "LONG")) }
                  catch (e: Exception) { NardiVariant.LONG }   // старый клиент без поля -> длинные
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
        model = if (variant == NardiVariant.SHORT) ShortNardiModel.initShortNardi() else initLongNardi()
        state = State.OPENING                 // сначала розыгрыш, НЕ ACTIVE
        callback.onGameStarted(peerId, gameId)
        startOpeningRoll()                    // оба игрока одновременно шлют commit
    }

    // Маяк 4: генерю свою кость+соль, шлю commit (hash), жду commit соперника.
    private fun startOpeningRoll() {
        myOpeningDie = (1..6).random()
        val saltBytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        myOpeningSalt = saltBytes.toHex()
        val hash = sha256(byteArrayOf(myOpeningDie.toByte()) + saltBytes)
        val commit = JSONObject().apply {
            put("v", 1); put("type", "GAME_OPEN_COMMIT"); put("gameId", gameId)
            put("owner", myColor.name); put("hash", hash)
        }.toString()
        callback.onSendGameEvent(peerId, commit)
        openingPhase = OpeningPhase.WAIT_PEER_COMMIT
        SafeLogger.i("GAME_SEQ") { "OPEN -> COMMIT hash=${hash.take(12)}..." }
    }

    // Приём commit соперника: сохраняю его хэш (кирпич 2 пошлёт reveal).
    private fun onOpenCommitReceived(fromPeerId: String, json: JSONObject) {
        if (state != State.OPENING) return
        if (fromPeerId != peerId) return
        if (json.optString("gameId", "") != gameId) return
        val owner = json.optString("owner", "")
        if (owner == myColor.name) return     // своё эхо (защита)
        peerCommitHash = json.optString("hash", "")
        SafeLogger.i("GAME_SEQ") { "OPEN <- COMMIT peer hash=${peerCommitHash.take(12)}..." }
        sendOpenReveal()
        val early = pendingPeerReveal ?: return
        pendingPeerReveal = null
        SafeLogger.i("GAME_SEQ") { "OPEN <- REVEAL из очереди" }
        onOpenRevealReceived(fromPeerId, early)                      // та же проверка хэша
    }

    // Шлю свою кость+соль (reveal) — только ПОСЛЕ получения commit соперника.
    private fun sendOpenReveal() {
        openingPhase = OpeningPhase.WAIT_PEER_REVEAL
        val reveal = JSONObject().apply {
            put("v", 1); put("type", "GAME_OPEN_REVEAL"); put("gameId", gameId)
            put("owner", myColor.name); put("die", myOpeningDie); put("salt", myOpeningSalt)
        }.toString()
        callback.onSendGameEvent(peerId, reveal)
        SafeLogger.i("GAME_SEQ") { "OPEN -> REVEAL die=$myOpeningDie" }
    }

    private fun onOpenRevealReceived(fromPeerId: String, json: JSONObject) {
        if (state != State.OPENING) return
        if (fromPeerId != peerId) return
        if (json.optString("gameId", "") != gameId) return
        val owner = json.optString("owner", "")
        if (owner == myColor.name) return                    // своё эхо
        // ЗАЩИТА: reveal без предшествующего commit -> отвергаем (атака "проскочить commit")
        // Reveal обогнал commit (переупорядочивание) -> ждём commit. Честность не страдает:
        // после commit этот reveal пройдёт ту же проверку хэша.
        if (peerCommitHash.isEmpty()) {
            if (pendingPeerReveal == null) pendingPeerReveal = json
            SafeLogger.w("NARDI_NET") { "OPEN reveal раньше commit — отложен" }
            return
        }
        val peerDie = json.optInt("die", 0)
        val peerSalt = json.optString("salt", "")
        if (peerDie < 1 || peerDie > 6 || peerSalt.isEmpty()) return
        // ВЕРИФИКАЦИЯ: хэш раскрытого совпадает с commit соперника?
        val check = sha256(byteArrayOf(peerDie.toByte()) + peerSalt.hexToBytes())
        if (check != peerCommitHash) {
            SafeLogger.e("NARDI_NET") { "OPEN ПОДТАСОВКА! hash mismatch — соперник сжульничал" }
            endGame()                                        // честность нарушена -> стоп
            return
        }
        SafeLogger.i("GAME_SEQ") { "OPEN <- REVEAL peer die=$peerDie (проверен)" }
        // Оба честны. Сравниваем кости.
        when {
            myOpeningDie > peerDie -> finishOpening(myColor, myOpeningDie, peerDie)
            myOpeningDie < peerDie -> finishOpening(if (myColor == PlayerType.WHITE) PlayerType.BLACK else PlayerType.WHITE, myOpeningDie, peerDie)
            else -> {                                        // ничья -> переброс
                SafeLogger.i("GAME_SEQ") { "OPEN ничья $myOpeningDie=$peerDie -> переброс" }
                peerCommitHash = ""
                pendingPeerReveal = null
                startOpeningRoll()                           // свежие кость+соль
            }
        }
    }

    // Розыгрыш завершён: назначаю turn первого игрока, вхожу в ACTIVE.
    private fun finishOpening(first: PlayerType, myDie: Int, peerDie: Int) {
        openingPhase = OpeningPhase.DONE
        state = State.ACTIVE
        SafeLogger.i("GAME_SEQ") { "OPEN DONE -> первый ход: $first (мой цвет $myColor)" }
        callback.onOpeningDone(first, myDie, peerDie)
        val queued = openingQueue.toList(); openingQueue.clear()
        if (queued.isNotEmpty()) SafeLogger.i("GAME_SEQ") { "OPEN DONE -> из очереди ${queued.size} событий" }
        queued.forEach { processIncomingEvent(it) }                  // обычный seq/owner/границы
    }

    // ROLL/MOVE до конца розыгрыша: ACTIVE -> сразу; OPENING -> в очередь (с лимитом); иначе игнор.
    private fun acceptPlayEvent(fromPeerId: String, json: JSONObject) {
        if (fromPeerId != peerId) return
        if (json.optString("gameId", "") != gameId) return
        when (state) {
            State.ACTIVE -> processIncomingEvent(json)
            State.OPENING -> {
                if (openingQueue.size >= MAX_BUFFERED) { SafeLogger.w("GAME_SEQ") { "<- DROP (очередь розыгрыша полна)" }; return }
                openingQueue.add(slim(json))
                SafeLogger.i("GAME_SEQ") { "<- QUEUE до конца розыгрыша seq=${json.optInt("seq", -1)}" }
            }
            else -> return
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun String.hexToBytes(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()

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
        if (json.optString("gameId", "") != gameId) return   // конец чужой/старой партии
        resetToIdle()
        callback.onOpponentLeft()
        callback.onGameSystemMessage("\u0441\u043e\u043f\u0435\u0440\u043d\u0438\u043a \u0432\u044b\u0448\u0435\u043b \u0438\u0437 \u043f\u0430\u0440\u0442\u0438\u0438")
    }

    // Исходящий ход: board уведомил через onMoveMade -> шлём в трубу.
    fun sendRoll(a: Int, b: Int) {
        if (state != State.ACTIVE) return
        val s = outgoingSeq++
        val roll = JSONObject().apply {
            put("v", 1); put("type", "GAME_ROLL"); put("gameId", gameId)
            put("owner", myColor.name); put("seq", s); put("a", a); put("b", b)
        }.toString()
        callback.onSendGameEvent(peerId, roll)
        SafeLogger.i("GAME_SEQ") { "-> ROLL seq=$s $a,$b" }
    }

    private fun onRollReceived(fromPeerId: String, json: JSONObject) = acceptPlayEvent(fromPeerId, json)

    fun sendMove(from: Int, to: Int, die: Int) {
        if (state != State.ACTIVE) return
        val s = outgoingSeq++
        val move = JSONObject().apply {
            put("v", 1); put("type", "GAME_MOVE"); put("gameId", gameId)
            put("owner", myColor.name); put("seq", s); put("die", die); put("from", from); put("to", to)
        }.toString()
        callback.onSendGameEvent(peerId, move)
        SafeLogger.i("GAME_SEQ") { "-> MOVE seq=$s die=$die $from->$to" }
    }

    // Входящий ход соперника: применяем к доске, которую видит игрок (board = истина).
    private fun onMoveReceived(fromPeerId: String, json: JSONObject) = acceptPlayEvent(fromPeerId, json)

    // СЕРДЦЕ ФИКСА: строгий порядок событий по seq (сквозной для ROLL+MOVE).
    private fun processIncomingEvent(json: JSONObject) {
        // Owner-фильтр: событие валидно только из потока СОПЕРНИКА.
        // Своё (эхо/ошибка) и любую третью сторону — игнорируем. Изоляция namespace.
        val owner = json.optString("owner", "")
        if (owner == myColor.name) { SafeLogger.i("GAME_SEQ") { "<- OWN owner=$owner игнор" }; return }
        val peerColor = if (myColor == PlayerType.WHITE) PlayerType.BLACK else PlayerType.WHITE
        if (owner != peerColor.name) { SafeLogger.i("GAME_SEQ") { "<- ALIEN owner=$owner игнор" }; return }
        val seq = json.optInt("seq", -1)
        if (seq < 0) return
        // Защита памяти: событие слишком далеко впереди — соперник шлёт мусор.
        if (seq > expectedSeq + MAX_BUFFERED) { SafeLogger.w("GAME_SEQ") { "<- DROP seq=$seq (ждём $expectedSeq)" }; return }
        when {
            seq < expectedSeq -> SafeLogger.i("GAME_SEQ") { "<- DUP seq=$seq (ждём $expectedSeq) игнор" }
            seq > expectedSeq -> {
                if (eventBuffer.any { it.first == seq }) return   // дубль раннего события — уже лежит
                eventBuffer.add(seq to slim(json))
                SafeLogger.i("GAME_SEQ") { "<- BUFFER seq=$seq (ждём $expectedSeq)" }
            }
            else -> {
                applyEvent(json)
                expectedSeq++
                drainBuffer()
            }
        }
    }

    // Применить событие по типу (ROLL или MOVE) -> callback наружу к board.
    private fun applyEvent(json: JSONObject) {
        when (json.optString("type", "")) {
            "GAME_ROLL" -> {
                val a = json.optInt("a", 0); val b = json.optInt("b", 0)
                if (a in 1..6 && b in 1..6) { SafeLogger.i("GAME_SEQ") { "<- ROLL seq=${json.optInt("seq")} $a,$b" }; callback.onRemoteRoll(a, b) }
            }
            "GAME_MOVE" -> {
                val from = json.optInt("from", -1); val to = json.optInt("to", -1)
                val die = json.optInt("die", -1)
                // Пункты 0..23; -1 = вход с бара (from) или выброс (to), не оба сразу.
                // Выброс шлёт die=-1 (кость считает движок), остальные ходы — die 1..6.
                val posOk = from in -1..23 && to in -1..23 && !(from == -1 && to == -1)
                val dieOk = if (to == -1) die == -1 else die in 1..6
                if (posOk && dieOk) { SafeLogger.i("GAME_SEQ") { "<- MOVE seq=${json.optInt("seq")} die=$die $from->$to" }; callback.onRemoteMove(from, to, die) }
            }
        }
    }

    // Разбор буфера: пока в нём лежит следующий ожидаемый seq — применяем.
    private fun drainBuffer() {
        while (true) {
            val idx = eventBuffer.indexOfFirst { it.first == expectedSeq }
            if (idx < 0) break
            val (_, j) = eventBuffer.removeAt(idx)
            SafeLogger.i("GAME_SEQ") { "<- DRAIN seq=$expectedSeq" }
            applyEvent(j)
            expectedSeq++
        }
    }

    private fun resetToIdle() {
        state = State.IDLE; peerId = ""; gameId = ""; model = null
        outgoingSeq = 0; expectedSeq = 0; eventBuffer.clear()
        pendingPeerReveal = null; openingQueue.clear(); peerCommitHash = ""; openingPhase = OpeningPhase.NONE
    }

    /** Сколько ранних (seq > ожидаемого) событий соперника лежит в буфере. */
    internal fun bufferedEventCount(): Int = eventBuffer.size
    /** Сколько байт (длина JSON) держат отложенные события — для теста бюджета памяти. */
    internal fun bufferedBytes(): Int =
        eventBuffer.sumOf { it.second.toString().length } + openingQueue.sumOf { it.toString().length }

    // Копия только с игровыми полями: худший случай памяти = 2 * MAX_BUFFERED * ~120 Б.
    private fun slim(json: JSONObject): JSONObject {
        val out = JSONObject()
        for (k in PLAY_FIELDS) if (json.has(k)) {
            val v = json.opt(k)
            if (v is Number || (v is String && v.length <= 64)) out.put(k, v)   // длинные строки отбрасываем
        }
        return out
    }

    companion object {
        @Volatile var INSTANCE: GameManager? = null
        const val MAX_BUFFERED = 32   // окно ранних событий; дальше — дроп (защита памяти)
        // Поля, которые реально нужны ROLL/MOVE. Всё прочее (мусор, раздувающий кадр) не храним.
        private val PLAY_FIELDS = listOf("v", "type", "gameId", "owner", "seq", "a", "b", "from", "to", "die")
    }
}

// Связь GameManager -> MainActivity (UI + отправка). MainActivity реализует.
interface GameCallback {
    fun onSendGameEvent(targetPeerId: String, gameJson: String)
    fun onInviteReceived(fromPeerId: String)
    fun onGameStarted(peerId: String, gameId: String)
    fun onOpeningDone(first: PlayerType, myDie: Int, peerDie: Int)
    fun onGameSystemMessage(text: String)
    fun onOpponentLeft()
    fun onRemoteMove(from: Int, to: Int, die: Int)
    fun onRemoteRoll(a: Int, b: Int)
}
