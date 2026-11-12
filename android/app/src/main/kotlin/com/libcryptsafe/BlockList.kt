package com.libcryptsafe

// Чёрный список: ID, от которых входящее молча выбрасывается (сообщения, игры, медиа, ACK).
// Одна точка проверки — прокси MessengerService: туда приходит ВСЁ входящее, и с Activity, и без неё.
// Кэш в памяти процесса; первая проверка лениво грузит список из БД (вызывается с IO-потока
// входящих, не с главного). Сверка — только по ПОЛНОМУ ID (хвост в 16 бит подбирается перебором).
// Если БД не открылась — НЕ блокируем (лог) и попробуем загрузить снова при следующем входящем:
// чёрный список — защита от навязчивых, а не криптография; терять всю почту из-за сбоя хуже.
object BlockList {
    @Volatile private var ids: Set<String>? = null

    fun isBlocked(peerId: String, load: () -> List<String>): Boolean {
        val s = ids ?: synchronized(this) {
            ids ?: try { load().toSet().also { ids = it } }
                   catch (e: Exception) { com.libcryptsafe.util.SafeLogger.e("BLOCK") { "список не загружен: ${e.javaClass.simpleName}" }; return false }
        }
        return peerId in s
    }

    /** После блокировки/разблокировки — свежий список из БД. */
    fun replaceAll(newIds: Collection<String>) { ids = newIds.toSet() }

    internal fun resetForTest() { ids = null }
}
