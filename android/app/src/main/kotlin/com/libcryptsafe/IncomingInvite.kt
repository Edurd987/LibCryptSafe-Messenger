package com.libcryptsafe

// №45a: входящее приглашение в игру. Решение — чистая функция (JVM-тест IncomingInviteTest),
// диалог/тост — в MainActivity.
//  - контакт   -> диалог «Приглашение от <имя> (<хвост ID>)»;
//  - незнакомец -> диалога НЕТ, приглашение отклоняется; заметка «от незнакомца <ПОЛНЫЙ ID>»
//    не чаще раза в 10 минут на один ID (антиспам). Незнакомцу ничего не отвечаем.
// Почему полный ID: хвост — 16 бит, похожий ID («…A009») подбирается перебором ключей за секунды.
// Узнаём контакт только по ПОЛНОМУ совпадению ID, имя берём из СВОЕЙ адресной книги.
object IncomingInvite {
    const val STRANGER_NOTE_COOLDOWN_MS = 10 * 60_000L
    private const val NAME_MAX = 40

    sealed class Decision {
        data class Show(val label: String) : Decision()
        data class Stranger(val notify: Boolean) : Decision()
    }

    /** book: (имя, ID) своих контактов; lastNotedAtMs: когда последний раз показывали заметку об этом ID. */
    fun decide(book: List<Pair<String, String>>, fromPeerId: String, lastNotedAtMs: Long?, nowMs: Long): Decision {
        val contact = book.firstOrNull { it.second == fromPeerId }
        if (contact != null) {
            val name = contact.first.trim().replace(Regex("\\s+"), " ").take(NAME_MAX).ifEmpty { "контакт" }
            return Decision.Show("$name  (${fromPeerId.takeLast(4)})")
        }
        val notify = lastNotedAtMs == null || nowMs - lastNotedAtMs >= STRANGER_NOTE_COOLDOWN_MS
        return Decision.Stranger(notify)
    }

    fun strangerText(fromPeerId: String) =
        "Приглашение в нарды от незнакомца $fromPeerId отклонено. Чтобы играть — добавь его в контакты."
}
