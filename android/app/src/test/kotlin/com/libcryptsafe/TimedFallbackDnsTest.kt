package com.libcryptsafe

import com.libcryptsafe.util.TimedFallbackDns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException

/** №42: зависший или отказавший системный DNS не вешает подключение к релею. */
class TimedFallbackDnsTest {
    private val relay = "cryptsafe-relay.duckdns.org"
    private val fbIp = "87.199.204.168"
    private fun ip(s: String) = InetAddress.getByName(s)
    private fun dns(behaviour: (String) -> List<InetAddress>) = TimedFallbackDns(
        primary = object : okhttp3.Dns { override fun lookup(hostname: String) = behaviour(hostname) },
        fallback = mapOf(relay to listOf(fbIp)), timeoutMs = 200L)
    private fun hang(): List<InetAddress> { Thread.sleep(10_000); return emptyList() }
    private fun timed(block: () -> Unit): Long { val t = System.nanoTime(); block(); return (System.nanoTime() - t) / 1_000_000 }

    // главное: резолвер завис (Private DNS/DoT, 2026-11-02 — 12 минут) -> запасной IP быстро
    @Test fun hangingDnsFallsBackFast() {
        var r: List<InetAddress> = emptyList()
        val ms = timed { r = dns { hang() }.lookup(relay) }
        assertEquals(listOf(ip(fbIp)), r); assertTrue("ждали $ms мс", ms < 1500)
    }
    @Test fun unknownHostFallsBack() =
        assertEquals(listOf(ip(fbIp)), dns { throw UnknownHostException(it) }.lookup(relay))
    // ответ системного DNS может быть «чёрной дырой» -> запасной IP следующим в списке, OkHttp перейдёт к нему
    @Test fun systemFirstThenFallback() =
        assertEquals(listOf(ip("1.2.3.4"), ip(fbIp)), dns { listOf(ip("1.2.3.4")) }.lookup(relay))
    @Test fun noDuplicate() = assertEquals(listOf(ip(fbIp)), dns { listOf(ip(fbIp)) }.lookup(relay))
    @Test fun otherHostHangingFailsFast() {
        var thrown = false
        val ms = timed { try { dns { hang() }.lookup("example.org") } catch (e: UnknownHostException) { thrown = true } }
        assertTrue(thrown); assertTrue("ждали $ms мс", ms < 1500)
    }
    @Test fun otherHostUnknownRethrown() {
        var thrown = false
        try { dns { throw UnknownHostException(it) }.lookup("example.org") } catch (e: UnknownHostException) { thrown = true }
        assertTrue(thrown)
    }
}
