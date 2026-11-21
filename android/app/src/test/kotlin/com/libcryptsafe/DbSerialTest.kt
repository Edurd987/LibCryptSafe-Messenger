package com.libcryptsafe

import com.libcryptsafe.util.DbSerial
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Collections

/**
 * INSERT пузыря (медленный: первый запрос к холодному SQLCipher) и UPDATE статусов после него.
 * Старый DbSerial (однопоточный диспетчер) давал UPDATE раньше INSERT -> rows=0 и вечные часы.
 */
class DbSerialTest {
    private suspend fun room(ms: Long) = withContext(Dispatchers.IO) { Thread.sleep(ms) }   // как suspend-DAO Room

    @Test fun updateNeverOvertakesInsert() = runBlocking {
        val out = Collections.synchronizedList(mutableListOf<String>())
        DbSerial.submit { room(300); out.add("INSERT") }
        DbSerial.submit { room(5); out.add("UPDATE SENT") }
        DbSerial.submit { room(5); out.add("UPDATE DELIVERED") }
        withTimeout(5000) { while (out.size < 3) delay(10) }
        assertEquals(listOf("INSERT", "UPDATE SENT", "UPDATE DELIVERED"), out.toList())
    }
}
