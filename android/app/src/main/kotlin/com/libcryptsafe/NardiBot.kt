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

// ===== ОЦЕНКА ПОЗИЦИИ =====
// Чем выше балл, тем лучше позиция для player после хода.
fun evaluateState(state: NardiGameState, player: PlayerType): Int {
    var score = 0
    val home = if (player == PlayerType.WHITE) 0..5 else 12..17
    // 1. Выброшенные шашки — абсолютный приоритет
    val off = if (player == PlayerType.WHITE) state.bornOffWhite else state.bornOffBlack
    score += off * 1000
    var pip = 0
    var curChain = 0          // длина непрерывной стены подряд (по маршруту)
    var occupiedPoints = 0    // сколько РАЗНЫХ пунктов занято (>=2) — ширина контроля
    var overstack = 0         // лишние шашки в башнях (>2 на пункте) — расточительство
    var inHome = 0
    val route = routeFor(player)
    for (idx in route) {
        val pt = state.board[idx]
        if (pt.player == player && pt.count > 0) {
            pip += distToOff(player, idx) * pt.count
            if (idx in home) inHome += pt.count
            if (pt.count >= 2) {
                occupiedPoints++                         // +1 занятый пункт (ширина)
                overstack += pt.count - 2                // всё свыше 2 — мёртвый груз
                curChain++
                if (curChain >= 2) score += curChain * curChain * 20  // стена режет логистику
            } else {
                curChain = 0                             // одиночка рвёт стену, но НЕ штрафуем жёстко
            }
        } else {
            curChain = 0
        }
    }
    score -= pip * 3                                     // продвижение (вес снижен)
    score += occupiedPoints * 60                         // ГЛАВНОЕ: ширина — много разных занятых пунктов
    score -= overstack * 35                              // ШТРАФ за башни (лишние шашки на пункте)
    score += inHome * 15                                 // шашки в доме
    return score
}

// Выбор хода: примеряем каждый, оцениваем итоговую позицию, берём лучший.
// null = ходов нет.
fun botChooseMove(state: NardiGameState): NardiMove? {
    val moves = botCollectMoves(state)
    if (moves.isEmpty()) return null
    var best: NardiMove? = null
    var bestScore = Int.MIN_VALUE
    val me = state.turn
    for (m in moves) {
        val sim = botApplyMove(state, m)
        val sc = evaluateState(sim, me)
        if (sc > bestScore) { bestScore = sc; best = m }
    }
    return best
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
