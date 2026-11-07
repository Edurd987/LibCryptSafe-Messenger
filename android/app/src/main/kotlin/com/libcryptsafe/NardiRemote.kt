package com.libcryptsafe

// ===== ДИСПЕТЧЕР ДВИЖКА: единственная точка выбора Long/Short =====
// Вынесено из NardiBoardView (там это были private eng*-обёртки), чтобы
// правила были чистыми функциями и проверялись JVM-тестами без Android.
object NardiEngine {
    fun legal(v: NardiVariant, s: NardiGameState, f: Int, t: Int): Boolean =
        if (v == NardiVariant.SHORT) ShortNardiModel.isLegalMoveShort(s, f, t) else isLegalMove(s, f, t)

    fun move(v: NardiVariant, s: NardiGameState, f: Int, t: Int): NardiGameState =
        if (v == NardiVariant.SHORT) ShortNardiModel.applyMoveShort(s, f, t) else applyMove(s, f, t)

    fun distance(v: NardiVariant, mover: PlayerType, f: Int, t: Int): Int =
        if (v == NardiVariant.SHORT) ShortNardiModel.moveDistanceShort(mover, f, t) else moveDistance(mover, f, t)

    fun hasAnyMove(v: NardiVariant, s: NardiGameState): Boolean =
        if (v == NardiVariant.SHORT) ShortNardiModel.hasAnyLegalMoveShort(s) else hasAnyLegalMove(s)

    fun winnerOf(v: NardiVariant, s: NardiGameState): PlayerType? =
        if (v == NardiVariant.SHORT) ShortNardiModel.winnerShort(s) else winner(s)

    // Short canBearOffShort проверяет ОДНУ кость -> перебираем кости тем же правилом, что Long внутри.
    fun canOff(v: NardiVariant, s: NardiGameState, from: Int): Boolean {
        if (v != NardiVariant.SHORT) return canBearOff(s, from)
        val dice = s.dice ?: return false
        return dice.distinct().any { die -> ShortNardiModel.canBearOffShort(s, from, die) }
    }

    // Шашки на баре у того, чей ход (в длинных бара нет -> 0).
    fun barCount(v: NardiVariant, s: NardiGameState): Int =
        if (v != NardiVariant.SHORT) 0 else if (s.turn == PlayerType.WHITE) s.barWhite else s.barBlack

    // Пункты, куда можно войти с бара текущими костями (пусто: бар пуст / кости не брошены / длинные).
    fun barEntryTargets(v: NardiVariant, s: NardiGameState): List<Int> {
        if (barCount(v, s) <= 0) return emptyList()
        val dice = s.dice ?: return emptyList()
        return dice.distinct().filter { ShortNardiModel.isLegalBarEntry(s, it) }
            .map { ShortNardiModel.barEntryPoint(s.turn, it) }.distinct()
    }

    // Выброс. Short: кость = need или наименьший overshoot, снять фишку, потратить кость.
    fun off(v: NardiVariant, s: NardiGameState, from: Int): NardiGameState {
        if (v != NardiVariant.SHORT) return bearOff(s, from)
        val dice = s.dice ?: return s
        val mover = s.board[from].player
        val need = ShortNardiModel.distToOffShort(mover, from)
        if (need <= 0) return s
        val die = if (need in dice) need else dice.filter { it > need }.minOrNull() ?: return s
        if (!ShortNardiModel.canBearOffShort(s, from, die)) return s
        return consumeDie(ShortNardiModel.applyBearOffShort(s, from), die)
    }
}

// ===== ХОД/БРОСОК СОПЕРНИКА ИЗ СЕТИ =====
// Чистые функции: вернуть новое состояние или null (кадр отвергнут, доска не тронута).
// peer — цвет соперника (кадры приходят только от него).
object NardiRemote {
    fun applyMove(v: NardiVariant, s: NardiGameState, peer: PlayerType, from: Int, to: Int, die: Int): NardiGameState? {
        if (s.isOpening || s.turn != peer || s.dice == null) return null   // не его ход / кости не брошены
        var st: NardiGameState
        if (from == -1) {                                    // вход с бара (короткие)
            if (!(v == NardiVariant.SHORT && ShortNardiModel.isLegalBarEntry(s, die))) return null
            if (to != ShortNardiModel.barEntryPoint(peer, die)) return null   // пункт не тот, что даёт кость
            st = consumeDie(ShortNardiModel.applyBarEntry(s, die), die)
        } else if (to == -1) {                               // выброс: кость считает движок
            if (!NardiEngine.canOff(v, s, from)) return null
            st = NardiEngine.off(v, s, from)
        } else {
            if (!NardiEngine.legal(v, s, from, to)) return null
            val walked = NardiEngine.distance(v, peer, from, to)     // кость = пройденное расстояние
            if (die != walked) return null                           // соперник назвал другую — не верим
            st = consumeDie(NardiEngine.move(v, s, from, to), walked)
        }
        if (NardiEngine.winnerOf(v, st) == null && st.dice != null && !NardiEngine.hasAnyMove(v, st)) st = burnTurn(st)
        return st
    }

    fun applyRoll(v: NardiVariant, s: NardiGameState, peer: PlayerType, a: Int, b: Int): NardiGameState? {
        if (a !in 1..6 || b !in 1..6) return null
        if (s.isOpening || s.turn != peer || s.dice != null) return null   // чужой ход / переброс
        var st = com.libcryptsafe.applyRoll(s, a, b)
        if (st.dice != null && !NardiEngine.hasAnyMove(v, st)) st = burnTurn(st)
        return st
    }
}
