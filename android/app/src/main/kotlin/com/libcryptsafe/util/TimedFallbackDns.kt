package com.libcryptsafe.util

import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * DNS для релея (№42). Было: Dns.SYSTEM без таймаута — при зависшем резолвере (Private DNS/DoT, фильтры
 * InviZible) подключение висело минутами (2026-11-02: 12 мин); запасной IP — только при UnknownHostException.
 * Стало:
 *  - системный DNS ждём не дольше timeoutMs; завис или «нет такого имени» -> запасной IP;
 *  - ответил -> [его адреса…, запасной IP]: OkHttp сам перейдёт к следующему адресу, если первый не отвечает
 *    («чёрная дыра»). Подмена сервера сюда не проходит: пиннинг рвёт TLS, и перебор на этом останавливается;
 *  - для имён без запасного адреса — честная ошибка через timeoutMs, а не через минуту.
 * Пиннинг и проверка имени в TLS не меняются — меняется только поиск IP.
 */
class TimedFallbackDns(
    private val primary: okhttp3.Dns = okhttp3.Dns.SYSTEM,
    private val fallback: Map<String, List<String>> = mapOf("cryptsafe-relay.duckdns.org" to listOf("87.199.204.168")),
    private val timeoutMs: Long = 4_000L,
) : okhttp3.Dns {

    override fun lookup(hostname: String): List<InetAddress> {
        val fb = fallback[hostname]?.map { InetAddress.getByName(it) } ?: emptyList()   // литерал IP: без DNS
        val task = POOL.submit(Callable { primary.lookup(hostname) })
        val sys: List<InetAddress> = try {
            task.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            task.cancel(true); emptyList()
        } catch (e: ExecutionException) {
            if (fb.isEmpty()) throw (e.cause as? UnknownHostException ?: UnknownHostException(hostname))
            emptyList()
        }
        val all = (sys + fb).distinct()
        if (all.isEmpty()) throw UnknownHostException("DNS: нет адреса для $hostname за $timeoutMs мс")
        return all
    }

    companion object {
        // Зависший системный вызов прервать нельзя — поток-демон просто доживёт сам.
        private val POOL: ExecutorService = Executors.newCachedThreadPool { r -> Thread(r, "dns-lookup").apply { isDaemon = true } }
    }
}
