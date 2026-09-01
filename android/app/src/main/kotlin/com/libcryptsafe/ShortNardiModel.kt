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

    /** Ход с БОЕМ: если to занят одной чужой фишкой — сбить её на бар.
     *  Та же сигнатура, что applyMove длинных (для чистого диспетчера).
     *  ПОД-КИРПИЧ 1. */
    fun applyMoveShort(state: NardiGameState, fromIndex: Int, toIndex: Int): NardiGameState =
        notYet("applyMoveShort (бой)")

    /** Легальность хода коротких + правило "с бара входить первым".
     *  ПОД-КИРПИЧ 2. */
    fun isLegalMoveShort(state: NardiGameState, fromIndex: Int, toIndex: Int): Boolean =
        notYet("isLegalMoveShort (правила + вход с бара)")
}
