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

    // ===== МАРШРУТЫ (backgammon, игроки идут НАВСТРЕЧУ) =====
    // ТОПОЛОГИЯ ДОКАЗАНА по стандарту международного backgammon (6 источников):
    // у каждого игрока СВОЙ отсчёт пунктов 1..24, оба идут "к своему дому, 24->1".
    // Пункт N белого = пункт (25-N) чёрного (одна физическая точка, зеркальная
    // нумерация). Отображение на board[0..23] (физические пункты, индекс = пункт
    // БЕЛОГО минус 1):
    //   пункт N БЕЛОГО  -> board[N-1]      (белый 24 -> board[23], белый 1 -> board[0])
    //   пункт N ЧЁРНОГО -> board[24-N]     (чёрный 24 -> board[0],  чёрный 1 -> board[23])
    // Отсюда: WHITE идёт board[23]->board[0] (дом board[0..5]);
    //         BLACK идёт board[0]->board[23] (дом board[18..23]). Навстречу.
    // moveDistanceShort = разница позиций в маршруте = ход в "пунктах игрока"
    // (то, чем считается кость). Определены ЯВНО массивами (как у длинных).
    val SHORT_WHITE_ROUTE: List<Int> = (23 downTo 0).toList()   // 23,22,...,1,0
    val SHORT_BLACK_ROUTE: List<Int> = (0..23).toList()          // 0,1,...,22,23

    fun routeForShort(player: PlayerType): List<Int> = when (player) {
        PlayerType.WHITE -> SHORT_WHITE_ROUTE
        PlayerType.BLACK -> SHORT_BLACK_ROUTE
        PlayerType.NONE -> emptyList()
    }

    /** Дистанция хода по маршруту игрока (число пунктов вперёд). >0 = вперёд к дому,
     *  <=0 = назад/на месте (нелегально). Пример: WHITE 23->20 = 3 пункта. */
    fun moveDistanceShort(player: PlayerType, fromIndex: Int, toIndex: Int): Int {
        val route = routeForShort(player)
        val fp = route.indexOf(fromIndex)
        val tp = route.indexOf(toIndex)
        if (fp < 0 || tp < 0) return -1
        return tp - fp   // >0: продвижение вперёд по маршруту
    }

    /**
     * Начальная расстановка международного backgammon (стандарт 2-5-3-5, ДОКАЗАНО).
     * Каждый игрок: 2 на своём 24, 5 на 13, 3 на 8, 5 на 6 = 15 фишек.
     * Транслировано в board[0..23] по топологии выше:
     *   WHITE (пункт N -> board[N-1]): 24->b23, 13->b12, 8->b7, 6->b5
     *   BLACK (пункт N -> board[24-N]): 24->b0, 13->b11, 8->b16, 6->b18
     * dice=null, WHITE ходит первым, бар пуст. Стартовый pip-count 167 у обоих.
     */
    fun initShortNardi(): NardiGameState {
        val board = MutableList(24) { PointState(0, PlayerType.NONE) }
        // WHITE
        board[23] = PointState(2, PlayerType.WHITE)
        board[12] = PointState(5, PlayerType.WHITE)
        board[7]  = PointState(3, PlayerType.WHITE)
        board[5]  = PointState(5, PlayerType.WHITE)
        // BLACK (зеркально)
        board[0]  = PointState(2, PlayerType.BLACK)
        board[11] = PointState(5, PlayerType.BLACK)
        board[16] = PointState(3, PlayerType.BLACK)
        board[18] = PointState(5, PlayerType.BLACK)
        return NardiGameState(
            board = board,
            dice = null,
            turn = PlayerType.WHITE,
            headCount = 0,
            barWhite = 0,
            barBlack = 0
        )
    }

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

    /**
     * Легальность ОБЫЧНОГО хода коротких нард (ПОД-КИРПИЧ 2a: пункт->пункт внутри
     * доски). НЕ покрывает вход с бара (from==-1, кирпич 2b) и выброс (to==-1,
     * отдельный кирпич) — для них возвращает false (не наш случай здесь).
     *
     * Порядок проверок (приоритет важен):
     *  1. Базовая валидация: from/to в 0..23, кости брошены, from не пуст,
     *     ход СВОИМ цветом в СВОЙ ход (from.player == turn).
     *  2. Бар-приоритет: если у игрока есть фишки на баре (barX>0) — обычные
     *     ходы ЗАПРЕЩЕНЫ (сначала войти с бара, кирпич 2b). Здесь -> false.
     *  3. Дистанция по кости: moveDistanceShort > 0 (вперёд) И равна одной из костей.
     *  4. Закрытый пункт: to занят 2+ ЧУЖИМИ -> нелегально.
     * NB: backgammon НЕ имеет правила 6-блока (prime легален) — creates6Block НЕ зовём.
     */
    fun isLegalMoveShort(state: NardiGameState, fromIndex: Int, toIndex: Int): Boolean {
        // Спец-ходы (бар/выброс) — не этот кирпич.
        if (fromIndex !in 0..23 || toIndex !in 0..23) return false
        val dice = state.dice ?: return false
        val from = state.board[fromIndex]
        if (from.count <= 0 || from.player == PlayerType.NONE) return false
        val mover = from.player
        if (mover != state.turn) return false   // только своим цветом в свой ход

        // 2. Бар-приоритет: пока фишка на баре — обычные ходы запрещены.
        val myBar = if (mover == PlayerType.WHITE) state.barWhite else state.barBlack
        if (myBar > 0) return false   // должен сперва войти с бара (кирпич 2b)

        // 3. Дистанция по маршруту игрока == одна из костей, и только ВПЕРЁД.
        val dist = moveDistanceShort(mover, fromIndex, toIndex)
        if (dist <= 0) return false              // назад/на месте нелегально
        if (dist !in dice) return false          // нет такой кости

        // 4. Закрытый пункт: to занят 2+ чужими фишками.
        val to = state.board[toIndex]
        if (to.count >= 2 && to.player != PlayerType.NONE && to.player != mover) return false

        return true
    }

    // ===== ВХОД С БАРА (ПОД-КИРПИЧ 2b) =====
    // Отдельные методы — доказанные applyMoveShort/isLegalMoveShort НЕ трогаем.
    // ФОРМУЛЫ ВХОДА (доказаны по стандарту backgammon + нашей топологии):
    //   фишка с бара по кости D входит на пункт D дома СОПЕРНИКА:
    //     WHITE -> board[24-D]  (кость 1->b23 ... кость 6->b18, дом чёрных b18..23)
    //     BLACK -> board[D-1]   (кость 1->b0  ... кость 6->b5,  дом белых b0..5)
    // Правила: пока фишка на баре — ТОЛЬКО вход (обычные ходы блокирует
    // isLegalMoveShort п.2); вход костью D нелегален, если пункт входа ЗАКРЫТ
    // (2+ чужих); при входе можно СБИТЬ blot (1 чужая) на бар соперника.

    /** Пункт входа с бара для игрока по кости D (1..6). board-индекс. */
    fun barEntryPoint(player: PlayerType, die: Int): Int = when (player) {
        PlayerType.WHITE -> 24 - die     // 1->23 ... 6->18
        PlayerType.BLACK -> die - 1      // 1->0  ... 6->5
        PlayerType.NONE  -> -1
    }

    /**
     * Легален ли вход с бара костью die для игрока turn.
     * Требует: у игрока есть фишка на баре, кость в 1..6, пункт входа НЕ закрыт
     * (не 2+ чужих). Занят своими/пустой/blot — вход легален.
     */
    fun isLegalBarEntry(state: NardiGameState, die: Int): Boolean {
        val player = state.turn
        val myBar = if (player == PlayerType.WHITE) state.barWhite else state.barBlack
        if (myBar <= 0) return false             // на баре пусто — входить нечего
        if (die !in 1..6) return false
        val dice = state.dice ?: return false
        if (die !in dice) return false           // такой кости нет
        val idx = barEntryPoint(player, die)
        if (idx !in 0..23) return false
        val pt = state.board[idx]
        // закрыт, если 2+ чужих
        if (pt.count >= 2 && pt.player != PlayerType.NONE && pt.player != player) return false
        return true
    }

    /**
     * Применить вход с бара костью die. Считает ход УЖЕ легальным (проверка —
     * isLegalBarEntry). Убирает фишку с бара игрока, ставит в пункт входа;
     * если там blot соперника — сбивает его на бар соперника. НЕ трогает dice
     * (трата кости — на уровне выше, как у обычных ходов).
     */
    fun applyBarEntry(state: NardiGameState, die: Int): NardiGameState {
        val player = state.turn
        val idx = barEntryPoint(player, die)
        if (idx !in 0..23) return state
        val nb = state.board.toMutableList()
        var barWhite = state.barWhite
        var barBlack = state.barBlack
        // снять одну свою фишку с бара
        if (player == PlayerType.WHITE) barWhite -= 1 else barBlack -= 1

        val pt = nb[idx]
        val isBlot = pt.count == 1 && pt.player != PlayerType.NONE && pt.player != player
        if (isBlot) {
            // сбитая чужая -> на бар СВОЕГО цвета
            when (pt.player) {
                PlayerType.WHITE -> barWhite += 1
                PlayerType.BLACK -> barBlack += 1
                PlayerType.NONE -> {}
            }
            nb[idx] = PointState(1, player)       // одна моя фишка (чужая ушла)
        } else {
            nb[idx] = PointState(pt.count + 1, player)  // пусто/свои -> +1
        }
        return state.copy(board = nb, barWhite = barWhite, barBlack = barBlack)
    }
}
