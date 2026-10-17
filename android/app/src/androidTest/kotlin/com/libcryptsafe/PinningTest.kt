package com.libcryptsafe

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

// Пиннинг: каждый пин из PinnedHttp проверяется ОТДЕЛЬНО на живом relay.
// Контроль — ложный пин обязан оборвать соединение (иначе пиннинг не работает).
@RunWith(AndroidJUnit4::class)
class PinningTest {
    private val host = "cryptsafe-relay.duckdns.org"

    private fun connect(pin: String): Throwable? = try {
        val c = okhttp3.OkHttpClient.Builder()
            .certificatePinner(okhttp3.CertificatePinner.Builder().add(host, pin).build())
            .build()
        c.newCall(okhttp3.Request.Builder().url("https://$host/").build()).execute().close()
        null
    } catch (e: Throwable) { e }

    @Test fun root_ye_pin_alone_valid() =
        assertNull("пин корня Root YE должен сам удерживать соединение", connect("sha256/sCkq5UWXjg+7mKu9lMhhYF5bGLsy7VI/UNW3tccdR7w="))

    @Test fun intermediate_ye1_pin_alone_valid() =
        assertNull("пин промежуточного YE1 должен сам удерживать соединение", connect("sha256/brzvtCELCIZUo4sD/qPX0ccRtPsd3DY6RfmxpOU9oB4="))

    // Пины верны — но использует ли их НАСТОЯЩИЙ клиент приложения (Activity + сервис)?
    @Test fun app_client_enforces_pins() {
        val p = com.libcryptsafe.util.PinnedHttp.client.certificatePinner
        assertNotSame("клиент приложения работает БЕЗ пиннинга", okhttp3.CertificatePinner.DEFAULT, p)
        assertEquals("ожидаем 3 пина (leaf, YE1, Root YE)", 3, p.pins.size)
    }

    @Test fun bogus_pin_rejected() {
        val e = connect("sha256/" + "A".repeat(43) + "=")
        assertTrue("ложный пин обязан оборвать соединение, получено: $e", e is javax.net.ssl.SSLPeerUnverifiedException)
    }
}
