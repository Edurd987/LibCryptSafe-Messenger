package com.libcryptsafe.util

import java.util.concurrent.TimeUnit

/**
 * ЕДИНЫЙ OkHttp-клиент с certificate pinning для Activity и MessengerService.
 * Одна точка правды: при ротации сертификата пины меняются ТОЛЬКО здесь.
 * Защита от MITM даже при компрометации CA (гос-во выдаёт свой корневой сертификат).
 */
object PinnedHttp {
    private val pinner = okhttp3.CertificatePinner.Builder()
        // YE1 intermediate — переживает обновление leaf
        .add("cryptsafe-relay.duckdns.org", "sha256/brzvtCELCIZUo4sD/qPX0ccRtPsd3DY6RfmxpOU9oB4=")
        // ISRG Root YE — резерв на случай ротации intermediate
        .add("cryptsafe-relay.duckdns.org", "sha256/sCkq5UWXjg+7mKu9lMhhYF5bGLsy7VI/UNW3tccdR7w=")
        // текущий leaf — переходный, можно убрать позже
        .add("cryptsafe-relay.duckdns.org", "sha256/khIJt119KS3MHja5jvJhrYarWJUv+0WchZoq+Cz8S6I=")
        .build()

    val client: okhttp3.OkHttpClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            // Медленная сеть сливает буфер сокета >10с (дефолт) -> обрыв посреди медиа.
            .writeTimeout(60, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .certificatePinner(pinner)
            .build()
    }
}
