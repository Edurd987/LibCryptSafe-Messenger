package com.libcryptsafe

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.util.Base64
import com.libcryptsafe.db.AppDatabase
import com.libcryptsafe.db.KeyStoreManager
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

// ФАЗЗИНГ двери X3DH: любой может прислать первое сообщение. Проверяем, что НИКАКОЙ
// искажённый пакет не роняет процесс, не висит >2с и не течёт по памяти.
// Детекторы: исключение — ок (перехвачено), краш — провал, timeout — провал, OOM/рост — провал.
@RunWith(AndroidJUnit4::class)
class FuzzInitialMessageTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private lateinit var dao: com.libcryptsafe.db.PrekeyDao

    @Before fun setup() = runBlocking<Unit> {
        KeyStoreManager.getOrCreateStableId(ctx)
        PrekeyManager.bootstrap(ctx)
        dao = AppDatabase.getInstance(ctx).prekeyDao()
    }

    // честный пакет самому себе — основа для мутаций
    private suspend fun myBundle(): JSONObject {
        val ikSign = KeyStoreManager.getIdentityPublicKeyEncoded(ctx)
        val ikDh = dao.getPrekeyById("IK_DH", PrekeyManager.IK_DH_KEY_ID)!!
        val spk = dao.getCurrentSpk()!!
        val opk = dao.getAllOpk().firstOrNull()
        return JSONObject().apply {
            put("ik_sign", b64(ikSign)); put("ik_dh", b64(ikDh.publicKey))
            put("spk", JSONObject().apply {
                put("value", b64(spk.publicKey)); put("sig", b64(spk.signature!!)); put("keyId", spk.keyId)
            })
            put("opk", opk?.let { JSONObject().apply { put("value", b64(it.publicKey)); put("id", it.keyId) } } ?: JSONObject.NULL)
        }
    }

    // честный пакет самому себе — через ПРОВЕРЕННЫЙ myBundle() из X3dhSecurityTest
    private suspend fun honest(): JSONObject =
        SessionManager.buildInitialMessage(ctx, KeyStoreManager.getOrCreateStableId(ctx), myBundle(), "hi".toByteArray())!!

    // вызов с жёстким таймаутом; null => timeout/краш корутины
    private fun callGuarded(msg: JSONObject): Throwable? = runBlocking<Throwable?> {
        try {
            withTimeout(2000) {
                try { SessionManager.handleInitialMessage(ctx, msg); null }
                catch (e: Throwable) { e }   // перехваченное исключение — допустимо
            }
        } catch (t: TimeoutCancellationException) { t }
    }

    private val fields = listOf("ik_a", "ik_sign_a", "ek_a", "cipher", "auth_sig", "opk_id")
    private val evil = listOf(
        "", "!!!not base64!!!", "A", "=", "AAAA=AAA",
        "A".repeat(2_000_000),                      // гигантская строка
        "\u0000\u0000", " \t\n", "../../etc/passwd"
    )

    @Test fun fuzz_each_field_no_crash() = runBlocking<Unit> {
        val base = honest()
        var cases = 0
        for (f in fields) for (v in evil) {
            val m = JSONObject(base.toString())
            m.put(f, v)                              // строка вместо ожидаемого
            assertNoHang(f, v, callGuarded(m)); cases++
            val m2 = JSONObject(base.toString())
            m2.put(f, 123456)                        // число вместо строки
            assertNoHang(f, "int", callGuarded(m2)); cases++
            val m3 = JSONObject(base.toString())
            m3.remove(f)                             // поля нет вовсе
            assertNoHang(f, "missing", callGuarded(m3)); cases++
        }
        android.util.Log.i("FUZZ", "X3DH: $cases мутаций пройдено без краша/зависания")
    }

    @Test fun fuzz_deep_json_no_crash() {
        var s = "\"x\""; repeat(5000) { s = "{\"k\":$s}" }   // глубина 5000
        val m = try { JSONObject("{\"ik_a\":$s}") } catch (e: Throwable) { JSONObject().put("ik_a", "deep") }
        assertNoHang("deep", "nested", callGuarded(m))
    }

    @Test fun fuzz_giant_repeated_no_oom() = runBlocking<Unit> {
        val rt = Runtime.getRuntime()
        System.gc(); val before = rt.totalMemory() - rt.freeMemory()
        val nBefore = android.os.Debug.getNativeHeapAllocatedSize()
        repeat(200) {
            val m = JSONObject(honest().toString()).put("ik_a", "A".repeat(1_000_000))
            callGuarded(m)
        }
        System.gc(); Thread.sleep(300); System.gc()
        val after = rt.totalMemory() - rt.freeMemory()
        val grewMb = (after - before) / (1024 * 1024)
        val nGrewMb = (android.os.Debug.getNativeHeapAllocatedSize() - nBefore) / (1024 * 1024)
        android.util.Log.i("FUZZ", "после 200 гигантов: Java +${grewMb}MB, native(C++) +${nGrewMb}MB")
        assertTrue("утечка Java-памяти: +${grewMb}MB", grewMb < 50)
        assertTrue("утечка нативной памяти (C++): +${nGrewMb}MB", nGrewMb < 50)
    }

    private fun assertNoHang(field: String, variant: String, r: Throwable?) {
        if (r is TimeoutCancellationException)
            fail("ЗАВИСАНИЕ на поле=$field вариант=$variant (>2с)")
        if (r is OutOfMemoryError)
            fail("OOM на поле=$field вариант=$variant")
        // прочие исключения — норма: вход отвергнут
    }
}
