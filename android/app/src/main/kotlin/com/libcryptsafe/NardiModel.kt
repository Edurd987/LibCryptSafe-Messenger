package com.libcryptsafe

// ===== МОДЕЛЬ ДАННЫХ ДЛИННЫХ НАРД (ЭТАП 1а) =====
// Чистая структура состояния. БЕЗ графики, БЕЗ логики ходов.
// Сквозная индексация пунктов: 0..23 (всего 24 пункта).

enum class PlayerType { WHITE, BLACK, NONE }

// Состояние одного пункта доски
data class PointState(
    val count: Int,         // сколько шашек на пункте (0..15)
    val player: PlayerType  // чьи шашки (или NONE если пусто)
)

// Полное состояние партии длинных нард
data class NardiGameState(
    val board: List<PointState>,  // ровно 24 пункта
    val dice: List<Int>?,         // оставшиеся зары (null = не брошены/ход завершён)
    val turn: PlayerType,         // чей ход
    val headUsed: Boolean = false // снята ли уже шашка с головы в этом ходу
)

// Начальная расстановка длинных нард:
// все 15 белых на "голове" белых (пункт 23),
// все 15 чёрных на "голове" чёрных (пункт 11).
fun initLongNardi(): NardiGameState {
    val board = MutableList(24) { PointState(0, PlayerType.NONE) }
    board[23] = PointState(15, PlayerType.WHITE)  // голова белых
    board[11] = PointState(15, PlayerType.BLACK)  // голова чёрных
    return NardiGameState(
        board = board,
        dice = null,
        turn = PlayerType.WHITE,  // белые ходят первыми
        headUsed = false
    )
}


// ===== ХОД (ЭТАП Б, кирпич 1): "тупое" перемещение шашки =====
// Чистая функция: состояние + (откуда, куда) -> НОВОЕ состояние.
// БЕЗ правил игры (дистанция, занятость соперником, зары) — только механика.
// Не зависит от View: эта же функция применит ход соперника из сети (GAME_MOVE).
fun applyMove(state: NardiGameState, fromIndex: Int, toIndex: Int): NardiGameState {
    // Защита от мусорного ввода — возвращаем состояние без изменений
    if (fromIndex !in 0..23 || toIndex !in 0..23) return state
    if (fromIndex == toIndex) return state

    val from = state.board[fromIndex]
    if (from.count <= 0 || from.player == PlayerType.NONE) return state  // ход из пустого

    val mover = from.player  // цвет берём от двигаемой шашки, не от turn (turn позже)
    val to = state.board[toIndex]

    val newBoard = state.board.toMutableList()

    // 1. Снять одну шашку с fromIndex
    val newFromCount = from.count - 1
    newBoard[fromIndex] = if (newFromCount == 0)
        PointState(0, PlayerType.NONE)          // пункт опустел -> владельца сбрасываем
    else
        PointState(newFromCount, mover)

    // 2. Добавить одну шашку на toIndex (владелец = тот, кто ходил)
    newBoard[toIndex] = PointState(to.count + 1, mover)

    // dice и turn пока не трогаем — это кирпичи следующих этапов
    val head = if (mover == PlayerType.WHITE) 23 else 11
    val headUsed = state.headUsed || fromIndex == head
    return state.copy(board = newBoard, headUsed = headUsed)
}


// ===== БРОСОК ЗАРОВ (кубики) =====
// Чистая функция: кидает два кубика 1..6, кладёт в dice, возвращает НОВОЕ состояние.
// Сам бросок НЕ раздаёт ходы при дубле — это правило применения (следующий этап).
// Совпадение значений (дубль) тут просто допустимый исход двух бросков.
// Не зависит от View: тот же бросок применится при синхронизации по сети.
fun rollDice(state: NardiGameState): NardiGameState {
    val a = (1..6).random()
    val b = (1..6).random()
    val dice = if (a == b) listOf(a, a, a, a) else listOf(a, b)  // дубль -> 4 хода
    return state.copy(dice = dice)
}


// ===== ЛЕГАЛЬНОСТЬ ХОДА (кирпич 1: направление + дистанция) =====
// Проверяет ТОЛЬКО: движение в верном направлении и дистанция == зар.
// НЕ проверяет: занятость пункта соперником, голову, bear-off — далее.
// Направление длинных нард: индекс убывает (23 -> 0).
// Маршруты-кольца: позиция = шаг от головы к дому. Дистанция по маршруту,
// а не по разности номеров -> корректны заворот и движение по полю.
private val WHITE_ROUTE = listOf(23,22,21,20,19,18,17,16,15,14,13,12,11,10,9,8,7,6,5,4,3,2,1,0)
private val BLACK_ROUTE = listOf(11,10,9,8,7,6,5,4,3,2,1,0,23,22,21,20,19,18,17,16,15,14,13,12)
private fun routeFor(player: PlayerType): List<Int> =
    if (player == PlayerType.BLACK) BLACK_ROUTE else WHITE_ROUTE
fun moveDistance(player: PlayerType, fromIndex: Int, toIndex: Int): Int {
    val route = routeFor(player)
    val pf = route.indexOf(fromIndex); val pt = route.indexOf(toIndex)
    if (pf < 0 || pt < 0) return -1
    return pt - pf
}

fun isLegalMove(state: NardiGameState, fromIndex: Int, toIndex: Int): Boolean {
    if (fromIndex !in 0..23 || toIndex !in 0..23) return false
    val dice = state.dice ?: return false           // зары не брошены
    val from = state.board[fromIndex]
    if (from.count <= 0 || from.player == PlayerType.NONE) return false
    if (from.player != state.turn) return false     // только своим цветом
    val head = if (from.player == PlayerType.WHITE) 23 else 11
    if (fromIndex == head && state.headUsed) return false  // с головы только 1 за ход
    val to = state.board[toIndex]
    if (to.count > 0 && to.player != from.player) return false  // пункт занят соперником
    val dist = moveDistance(from.player, fromIndex, toIndex)
    if (dist <= 0) return false                     // только убывание индекса
    return dist in dice
}


// ===== ТРАТА ЗАРА после легального хода =====
// Убирает ОДИН зар, равный пройденной дистанции. Список пуст -> dice = null.
fun consumeDie(state: NardiGameState, dist: Int): NardiGameState {
    val dice = state.dice ?: return state
    val idx = dice.indexOf(dist)
    if (idx < 0) return state                       // такого зара нет
    val remaining = dice.toMutableList().apply { removeAt(idx) }
    if (remaining.isEmpty()) {
        val next = if (state.turn == PlayerType.WHITE) PlayerType.BLACK else PlayerType.WHITE
        return state.copy(dice = null, turn = next, headUsed = false)  // ход сопернику, сброс головы
    }
    return state.copy(dice = remaining)
}
