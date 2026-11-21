package com.libcryptsafe.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Записи сообщений и статусов — строго по очереди, одна очередь на процесс.
 * Прежняя версия (однопоточный диспетчер) порядок НЕ гарантировала: DAO Room — suspend, они идут на
 * исполнителе Room и отпускают наш поток, так что следующий UPDATE мог стартовать до конца INSERT
 * (тот же механизм, что ломал порядок входящих). Теперь — один потребитель (OrderedInbound):
 * следующая операция начинается только после ПОЛНОГО завершения предыдущей.
 */
object DbSerial {
    private val q = OrderedInbound(CoroutineScope(SupervisorJob() + Dispatchers.IO), Dispatchers.IO) { t ->
        SafeLogger.w("DB_SERIAL") { "операция не выполнена: ${t.javaClass.simpleName}" }
    }

    fun submit(block: suspend () -> Unit) = q.submit(block)
}
