package com.libcryptsafe.util

/**
 * Очередь сообщений новому контакту до прихода prekeys (X3DH).
 * Было: Map<адресат, текст> — второе сообщение ЗАТИРАЛО первое ещё до ответа релея (молчаливая потеря),
 * каждое сообщение слало свой prekeys_request (лишние одноразовые prekeys получателя), а второй ответ
 * мог построить второй X3DH поверх первой сессии.
 * Стало: очередь на адресата; один запрос (повтор — только если ответа нет retryMs);
 * ответ забирает ВСЁ (begin): первое уходит X3DH, остальные — обычным CHAT_ENCRYPTED в новой сессии;
 * пришедшее, пока сессия строится, забирает more(); повторный ответ во время сборки игнорируется.
 */
class PendingX3dh(private val retryMs: Long = 30_000L, private val now: () -> Long = { System.currentTimeMillis() }) {
    data class Item(val text: String, val tag: String?)
    private class Slot { val q = ArrayDeque<Item>(); var requestedAt: Long? = null; var establishing = false }
    private val slots = HashMap<String, Slot>()

    /** Положить сообщение. true — нужно отправить prekeys_request. */
    @Synchronized fun stash(target: String, text: String, tag: String?): Boolean {
        val s = slots.getOrPut(target) { Slot() }
        s.q.addLast(Item(text, tag))
        if (s.establishing) return false                       // сессия строится — more() заберёт
        val t = now()
        val r = s.requestedAt
        if (r != null && t - r < retryMs) return false   // запрос уже в пути
        s.requestedAt = t
        return true
    }

    /** Пришёл prekeys_response: забрать всё и перейти в «строим сессию». Пусто или уже строим — пустой список. */
    @Synchronized fun begin(target: String): List<Item> {
        val s = slots[target] ?: return emptyList()
        if (s.establishing || s.q.isEmpty()) return emptyList()
        s.establishing = true
        return s.q.toList().also { s.q.clear() }
    }

    /** Сессия построена и отправлено забранное: взять добавленное за это время; пусто — слот снимается. */
    @Synchronized fun more(target: String): List<Item> {
        val s = slots[target] ?: return emptyList()
        if (s.q.isEmpty()) { slots.remove(target); return emptyList() }
        return s.q.toList().also { s.q.clear() }
    }

    /** Отказ (нет ключей / ошибка сборки): снять слот, вернуть неотправленное. */
    @Synchronized fun abort(target: String): List<Item> = slots.remove(target)?.q?.toList() ?: emptyList()
}
