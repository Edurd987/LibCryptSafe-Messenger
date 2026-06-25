package com.libcryptsafe

// Ход бота. to = -1 означает выброс (bear-off) с пункта from.
data class NardiMove(val from: Int, val to: Int)

// Все легальные ходы для текущего игрока (state.turn).
fun botCollectMoves(state: NardiGameState): List<NardiMove> {
    val moves = mutableListOf<NardiMove>()
    if (state.dice == null) return moves
    for (from in 0..23) {
        val pt = state.board[from]
        if (pt.count <= 0 || pt.player != state.turn) continue
        for (to in 0..23) {
            if (isLegalMove(state, from, to)) moves.add(NardiMove(from, to))
        }
        if (canBearOff(state, from)) moves.add(NardiMove(from, -1))
    }
    return moves
}

// Жадный выбор: есть выброс — берём его, иначе случайный ход.
// null = ходов нет (вызывающий сжигает ход).
fun botChooseMove(state: NardiGameState): NardiMove? {
    val moves = botCollectMoves(state)
    if (moves.isEmpty()) return null
    val bearOffMove = moves.firstOrNull { it.to == -1 }
    return bearOffMove ?: moves.random()
}


// Применяет один ход бота (обычный или выброс), тратит зар.
fun botApplyMove(state: NardiGameState, move: NardiMove): NardiGameState {
    return if (move.to == -1) {
        bearOff(state, move.from)                   // выброс (bearOff сам тратит зар)
    } else {
        val mover = state.board[move.from].player
        val dist = moveDistance(mover, move.from, move.to)
        val s2 = applyMove(state, move.from, move.to)
        consumeDie(s2, dist)
    }
}
