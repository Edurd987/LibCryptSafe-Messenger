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

    // Вшитый якорь (res/raw/root_ye) — действительно Root YE: его ключ = пин sCkq.
    @Test fun bundled_root_matches_pin() {
        val ctx = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val cert = ctx.resources.openRawResource(R.raw.root_ye).use {
            java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(it) as java.security.cert.X509Certificate
        }
        val spki = java.security.MessageDigest.getInstance("SHA-256").digest(cert.publicKey.encoded)
        assertEquals("sCkq5UWXjg+7mKu9lMhhYF5bGLsy7VI/UNW3tccdR7w=", android.util.Base64.encodeToString(spki, android.util.Base64.NO_WRAP))
    }

    // HTTP без TLS: клиент приложения обязан отказать ДО выхода в сеть (network_security_config).
    // Контроль — текст исключения: именно политика CLEARTEXT, а не сетевой сбой.
    private fun cleartext(url: String): Throwable? = try {
        com.libcryptsafe.util.PinnedHttp.client.newCall(okhttp3.Request.Builder().url(url).build()).execute().close(); null
    } catch (e: Throwable) { e }

    @Test fun cleartext_blocked_for_relay() {
        val e = cleartext("http://cryptsafe-relay.duckdns.org/")
        assertTrue("ожидали запрет CLEARTEXT, получено: $e", e is java.net.UnknownServiceException && e.message!!.contains("CLEARTEXT"))
    }

    @Test fun cleartext_blocked_for_any_host() {
        val e = cleartext("http://example.com/")
        assertTrue("ожидали запрет CLEARTEXT, получено: $e", e is java.net.UnknownServiceException && e.message!!.contains("CLEARTEXT"))
    }

    @Test fun bogus_pin_rejected() {
        val e = connect("sha256/" + "A".repeat(43) + "=")
        assertTrue("ложный пин обязан оборвать соединение, получено: $e", e is javax.net.ssl.SSLPeerUnverifiedException)
    }
}
