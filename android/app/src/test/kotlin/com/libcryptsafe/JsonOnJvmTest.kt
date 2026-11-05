package com.libcryptsafe

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Подготовка к тестам GameManager: JVM-тесты должны видеть НАСТОЯЩИЙ org.json,
 * а не заглушку из android.jar (там любой вызов = "Method ... not mocked").
 * Красный без testImplementation 'org.json:json', зелёный — с ней.
 */
class JsonOnJvmTest {
    @Test fun realOrgJsonOnClasspath() {
        val j = JSONObject("""{"type":"GAME_ROLL","a":3,"b":5}""")
        assertEquals("GAME_ROLL", j.optString("type"))
        assertEquals(3, j.optInt("a"))
        assertEquals(-1, j.optInt("missing", -1))
    }
}
