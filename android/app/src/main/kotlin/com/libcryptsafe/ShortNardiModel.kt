package com.libcryptsafe

/**
 * Движок КОРОТКИХ нард (международный backgammon). ЖЁСТКАЯ ИЗОЛЯЦИЯ:
 * этот файл — отдельная кодовая база правил. NardiModel.kt (длинные нарды)
 * НЕ редактируется из-за коротких — если короткие забагуют, длинные физически
 * не могут пострадать, их applyMove/isLegalMove здесь не вызываются и не менялись.
 *
 * ОБЩЕЕ с длинными (переиспускаем как есть, НЕ дублируем):
 *   PointState, PlayerType, NardiGameState (структуры данных).
 * РАЗДЕЛЬНОЕ (свой код здесь):
 *   applyMoveShort (с БОЕМ), isLegalMoveShort (+ вход с бара), initShortNardi
 *   (расстановка backgammon), маршруты НАВСТРЕЧУ.
 *
 * Диспетчер (GameActivity/GameManager) по NardiVariant решает, ЧЕЙ движок звать:
 *   LONG  -> applyMove (NardiModel.kt, не тронут)
 *   SHORT -> applyMoveShort (здесь)
 * Ноль ветвлений if(variant) внутри самих applyMove — чистый полиморфизм.
 *
 * ПОД-КИРПИЧ 0 (сейчас): только КАРКАС. Все методы — заглушки, реальной логики
 * НЕТ. Задача под-кирпича 0 — доказать, что длинные нарды целы после добавления
 * полей бара; короткие правила приходят под-кирпичами 1..3, каждый со своим
 * desktop-тестом.
 *
 * МАЯКИ на будущее:
 *   // TODO под-кирпич 1: БОЙ — to занят ОДНОЙ чужой -> barOpponent++, blot сбит
 *   // TODO под-кирпич 2: ВХОД С БАРА — при barX>0 легален только вход
 *   // TODO под-кирпич 3: РАССТАНОВКА backgammon + SHORT_ROUTE навстречу
 *   // TODO bear-off short: выброс с учётом дома (backgammon-специфика)
 *   // TODO MEDIUM: средние нарды — третий вариант, после коротких
 */
object ShortNardiModel {

    /** Признак нереализованного под-кирпича — заглушки бросают это, чтобы
     *  случайный ранний вызов был громким, а не тихо давал мусор. */
    private fun notYet(brick: String): Nothing =
        throw NotImplementedError("ShortNardiModel: $brick ещё не реализован (под-кирпич 0 — только каркас)")

    /** Начальная расстановка коротких нард (backgammon). ПОД-КИРПИЧ 3. */
    fun initShortNardi(): NardiGameState = notYet("initShortNardi (расстановка)")

    /** Ход с БОЕМ (ПОД-КИРПИЧ 1). Применяет УЖЕ ЛЕГАЛЬНЫЙ ход (легальность —
     *  isLegalMoveShort, под-кирпич 2). Та же сигнатура, что applyMove длинных
     *  (чистый полиморфизм для диспетчера).
     *
     *  to пусто / свои  -> обычное перемещение (встать / укрепить).
     *  to = ОДНА чужая (blot) -> сбить её на бар СВОЕГО цвета, встать одной фишкой.
     *  (to = 2+ чужих -> сюда не дойдёт: это отсеет isLegalMoveShort.)
     *
     *  headCount НЕ трогаем — в коротких нардах головы нет. mover берём от
     *  ФИШКИ (from.player), не от turn, чтобы ход соперника из сети применялся
     *  идентично локальному (event-sourcing). */
    fun applyMoveShort(state: NardiGameState, fromIndex: Int, toIndex: Int): NardiGameState {
        // Защита от мусорного ввода — как у длинных, возвращаем состояние без изменений.
        if (fromIndex !in 0..23 || toIndex !in 0..23) return state
        if (fromIndex == toIndex) return state
        val from = state.board[fromIndex]
        if (from.count <= 0 || from.player == PlayerType.NONE) return state  // ход из пустого
        val mover = from.player  // цвет от двигаемой шашки, НЕ от turn
        val to = state.board[toIndex]
        val newBoard = state.board.toMutableList()

        // 1. Снять одну шашку с fromIndex.
        val newFromCount = from.count - 1
        newBoard[fromIndex] = if (newFromCount == 0)
            PointState(0, PlayerType.NONE)   // пункт опустел -> владелец сброшен
        else
            PointState(newFromCount, mover)

        // 2. Занять toIndex. Определяем: бой или обычный ход.
        var barWhite = state.barWhite
        var barBlack = state.barBlack
        val isBlot = to.count == 1 && to.player != PlayerType.NONE && to.player != mover
        if (isBlot) {
            // БОЙ: сбитая фишка (цвет to.player) уходит на бар СВОЕГО цвета.
            when (to.player) {
                PlayerType.WHITE -> barWhite += 1
                PlayerType.BLACK -> barBlack += 1
                PlayerType.NONE -> {} // недостижимо (isBlot требует != NONE)
            }
            newBoard[toIndex] = PointState(1, mover)   // РОВНО одна моя фишка (чужая ушла)
        } else {
            // Пусто или свои: обычное перемещение.
            newBoard[toIndex] = PointState(to.count + 1, mover)
        }

        // headCount НЕ трогаем (короткие нарды головы не имеют).
        return state.copy(board = newBoard, barWhite = barWhite, barBlack = barBlack)
    }

    /** Легальность хода коротких + правило "с бара входить первым".
     *  ПОД-КИРПИЧ 2. */
    fun isLegalMoveShort(state: NardiGameState, fromIndex: Int, toIndex: Int): Boolean =
        notYet("isLegalMoveShort (правила + вход с бара)")
}
