package com.libcryptsafe

import com.libcryptsafe.util.OrderedInbound
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Collections

/**
 * Порядок входящих. Кадр 0 = INIT медиа, медленный (первый запрос к холодному SQLCipher),
 * 1..5 = чанки. Со старой схемой (scope.launch(limitedParallelism(1)) на кадр) получалось
 * [1, 4, 2, 3, 5, 0] — INIT последним, чанки выброшены «нет ephKey».
 */
class OrderedInboundTest {
    private suspend fun decrypt(i: Int) = withContext(Dispatchers.IO) { Thread.sleep(if (i == 0) 300L else 5L) }

    private fun run(failAt: Int, errors: MutableList<String>): List<Int> = runBlocking {
        val out = Collections.synchronizedList(mutableListOf<Int>())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val ordered = OrderedInbound(scope, Dispatchers.IO.limitedParallelism(1)) { errors.add(it.javaClass.simpleName) }
        (0..5).forEach { i -> ordered.submit { decrypt(i); if (i == failAt) error("битый"); out.add(i) } }
        withTimeout(5000) { while (out.size + errors.size < 6) delay(10) }
        scope.cancel(); out.toList()
    }

    @Test fun relayOrderKeptWhenInitIsSlow() = assertEquals((0..5).toList(), run(-1, mutableListOf()))

    @Test fun brokenFrameDoesNotStopQueue() {
        val e = mutableListOf<String>()
        assertEquals(listOf(0, 1, 3, 4, 5), run(2, e)); assertEquals(1, e.size)
    }
}
