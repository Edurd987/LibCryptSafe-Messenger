package com.libcryptsafe.util

import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

/**
 * Один поток НА ПРОЦЕСС для записи сообщений и их статусов.
 * Гарантия: операции выполняются в порядке постановки в очередь. Вставка пузыря ставится
 * ДО шифрования/отправки, UPDATE по relay_rcvd / E2E-ACK — только после ответа сети,
 * значит UPDATE никогда не обгонит INSERT (иначе rows=0 и вечные часы).
 */
object DbSerial {
    val d = Executors.newSingleThreadExecutor { r -> Thread(r, "db-serial").apply { isDaemon = true } }
        .asCoroutineDispatcher()
}
