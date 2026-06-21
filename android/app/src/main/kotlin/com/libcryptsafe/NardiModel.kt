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
    val dice: Pair<Int, Int>?,    // текущие зары (null если не брошены)
    val turn: PlayerType          // чей ход
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
        turn = PlayerType.WHITE  // белые ходят первыми
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
    return state.copy(board = newBoard)
}


// ===== БРОСОК ЗАРОВ (кубики) =====
// Чистая функция: кидает два кубика 1..6, кладёт в dice, возвращает НОВОЕ состояние.
// Сам бросок НЕ раздаёт ходы при дубле — это правило применения (следующий этап).
// Совпадение значений (дубль) тут просто допустимый исход двух бросков.
// Не зависит от View: тот же бросок применится при синхронизации по сети.
fun rollDice(state: NardiGameState): NardiGameState {
    val a = (1..6).random()
    val b = (1..6).random()
    return state.copy(dice = Pair(a, b))
}


// ===== ЛЕГАЛЬНОСТЬ ХОДА (кирпич 1: направление + дистанция) =====
// Проверяет ТОЛЬКО: движение в верном направлении и дистанция == зар.
// НЕ проверяет: занятость пункта соперником, голову, bear-off — далее.
// Направление длинных нард: индекс убывает (23 -> 0).
fun moveDistance(fromIndex: Int, toIndex: Int): Int = fromIndex - toIndex

fun isLegalMove(state: NardiGameState, fromIndex: Int, toIndex: Int): Boolean {
    if (fromIndex !in 0..23 || toIndex !in 0..23) return false
    val dice = state.dice ?: return false           // зары не брошены
    val from = state.board[fromIndex]
    if (from.count <= 0 || from.player == PlayerType.NONE) return false
    val dist = moveDistance(fromIndex, toIndex)
    if (dist <= 0) return false                     // только убывание индекса
    return dist == dice.first || dist == dice.second
}
