package com.libcryptsafe

// №45b: входящее от НЕЗНАКОМЦА — ID, которого нет в СВОИХ контактах (сверка по ПОЛНОМУ ID;
// хвост в 16 бит подбирается перебором). Удалённый контакт — тоже незнакомец.
// Заблокированных к этому моменту уже выбросил BlockList (MessengerService).
//  - ACK незнакомцу НЕ шлём: квитанция выдаёт, что мы в сети (атака Careless Whisper, 2024);
//  - медиа и кадры будущих версий протокола выбрасываем ещё в сервисе (объём, мусор в ленте);
//  - текст храним (будущий экран «Запросы»), но не больше MAX_STORED_PER_STRANGER с одного ID;
//    считаются ТОЛЬКО сообщения с пометкой fromStranger (не старая переписка удалённого контакта —
//    ошибка 2026-11-15: лимит считал всю историю и не сохранял ничего);
//  - уведомление о незнакомцах — не чаще раза в NOTIFY_COOLDOWN_MS на ВСЕХ незнакомцев сразу
//    (новых ID можно наделать сколько угодно — счётчик на ID спам не остановит).
// Контакт — всё как раньше. Чистые функции: JVM-тест StrangerPolicyTest.
object StrangerPolicy {
    const val MAX_STORED_PER_STRANGER = 20
    const val NOTIFY_COOLDOWN_MS = 10 * 60_000L

    data class ChatVerdict(val store: Boolean, val ack: Boolean)

    /** Когда последний раз уведомляли о незнакомце (общий для сервиса и Activity, в памяти процесса). */
    @Volatile var lastStrangerNotifyAt: Long? = null

    /** Выбросить ещё в сервисе (до Activity и уведомления)? */
    fun dropInService(isContact: Boolean, raw: String): Boolean {
        if (isContact) return false
        val j = try { org.json.JSONObject(raw) } catch (e: Exception) { return false }  // старый текст — дальше, там лимит
        if (j.optInt("v", 0) >= 2) return true                                        // будущий протокол: только «обновитесь» в ленту
        return j.optString("type", "").startsWith("media_")
    }

    /** Чат-сообщение: хранить ли (fresh — первый раз видим этот nonce) и слать ли ACK. */
    fun chat(isContact: Boolean, fresh: Boolean, storedFromPeer: Int): ChatVerdict =
        if (isContact) ChatVerdict(store = fresh, ack = true)
        else ChatVerdict(store = fresh && storedFromPeer < MAX_STORED_PER_STRANGER, ack = false)

    /** Уведомлять ли. lastStrangerNotifyAt — когда последний раз уведомляли о ЛЮБОМ незнакомце. */
    fun notify(isContact: Boolean, userMessage: Boolean, lastStrangerNotifyAt: Long?, now: Long): Boolean {
        if (!userMessage) return false
        if (isContact) return true
        return lastStrangerNotifyAt == null || now - lastStrangerNotifyAt >= NOTIFY_COOLDOWN_MS
    }
}

// Кэш ID своих контактов (как BlockList): первая проверка лениво грузит из БД (с IO-потока входящих),
// после добавления/удаления контакта — replaceAll. Сбой БД -> «незнакомец» (безопасная сторона:
// без ACK и без медиа) и повторная загрузка при следующем входящем.
object ContactSet {
    @Volatile private var ids: Set<String>? = null

    fun isContact(peerId: String, load: () -> List<String>): Boolean {
        val s = ids ?: synchronized(this) {
            ids ?: try { load().toSet().also { ids = it } }
                   catch (e: Exception) { com.libcryptsafe.util.SafeLogger.e("STRANGER") { "контакты не загружены: ${e.javaClass.simpleName}" }; return false }
        }
        return peerId in s
    }

    /** По уже загруженному кэшу, БД не трогает (для главного потока). Не загружен -> false. */
    fun isKnown(peerId: String): Boolean = ids?.contains(peerId) == true

    fun replaceAll(newIds: Collection<String>) { ids = newIds.toSet() }

    internal fun resetForTest() { ids = null }
}
