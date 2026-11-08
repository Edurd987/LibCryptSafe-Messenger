package com.libcryptsafe

// №51: кому уходит приглашение в нарды — выбирает ЧЕЛОВЕК из своих контактов.
// Было: приглашение молча уходило текущему собеседнику чата (2026-11-06 — на эмулятор
// вместо телефона B). Чистая логика списка (JVM-тест InvitePickerTest), диалог — в MainActivity.
object InvitePicker {
    data class Option(val peerId: String, val label: String)

    private val ID = Regex("^[0-9A-F]{4}(-[0-9A-F]{4}){3}$")

    /**
     * contacts: (имя, ID) из локальной адресной книги. Только контакты — незнакомцу
     * (currentPeerId от входящего сообщения, №38) пригласить нельзя.
     * Текущий собеседник, если он контакт, — первым и с пометкой; остальные по имени.
     */
    fun options(contacts: List<Pair<String, String>>, currentPeerId: String): List<Option> {
        val valid = contacts.filter { (_, id) -> ID.matches(id) }.distinctBy { it.second }
        val (cur, rest) = valid.partition { it.second == currentPeerId }
        return (cur + rest.sortedBy { it.first.lowercase() }).map { (name, id) ->
            val mark = if (id == currentPeerId) " · текущий чат" else ""
            Option(id, "$name  (${id.takeLast(4)})$mark")
        }
    }
}
