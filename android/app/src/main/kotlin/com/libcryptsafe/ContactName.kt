package com.libcryptsafe

// Имя контакта: одно правило для добавления и переименования (JVM-тест ContactNameTest).
//  - управляющие символы (перевод строки, табы, \u0000 и т.п.) становятся пробелом (слова не склеиваются);
//  - пробелы по краям срезаются, внутренние схлопываются в один;
//  - длина от 2 до 40 символов (считаем символы, а не байты: эмодзи = 1).
// Почему минимум 2: имя «Э» дважды спутали с «эмулятором» (2026-11-09) и отправили приглашение не туда.
object ContactName {
    const val MIN = 2
    const val MAX = 40

    /** Чистое имя или null, если после очистки оно короче MIN или длиннее MAX. */
    fun clean(input: String): String? {
        val s = input.map { if (it.isISOControl()) ' ' else it }.joinToString("").trim().replace(Regex("\\s+"), " ")
        val n = s.codePointCount(0, s.length)
        return if (n in MIN..MAX) s else null
    }
}
