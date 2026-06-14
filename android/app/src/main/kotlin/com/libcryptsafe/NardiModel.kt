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
