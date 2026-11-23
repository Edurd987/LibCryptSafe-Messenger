package com.libcryptsafe

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Правдивость интерфейса: панель «Сеть» не должна утверждать о шифровании то, чего нет в ядре.
 * 2026-11-23: строка шифра говорила X25519, а ядро (jni_bridge.cpp) работает на P-256.
 * Тест читает исходники модуля (рабочая папка unit-тестов Gradle — android/app).
 */
class UiClaimsTest {
    private val locales = listOf("src/main/res/values/strings.xml", "src/main/res/values-ru/strings.xml")
    private fun cipher(xml: String) =
        Regex("<string name=\"net_cipher\">([^<]*)</string>").find(xml)?.groupValues?.get(1) ?: ""

    @Test fun cipherStringMatchesCoreCurve() {
        assertTrue("ядро должно быть на P-256", File("src/main/cpp/jni_bridge.cpp").readText().contains("P-256"))
        for (f in locales) {
            val c = cipher(File(f).readText())
            assertTrue("$f: «$c» — нет P-256", c.contains("P-256"))
            assertFalse("$f: «$c» — X25519, которого нет в ядре", c.contains("X25519"))
        }
    }
}
