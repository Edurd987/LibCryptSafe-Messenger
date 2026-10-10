package com.libcryptsafe

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.libcryptsafe.db.AppDatabase
import com.libcryptsafe.db.KeyStoreManager
import com.libcryptsafe.db.PrekeyDao
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import android.util.Base64

// Негативные тесты X3DH: телефон делает ЧЕСТНОЕ рукопожатие сам себе через настоящие
// buildInitialMessage/handleInitialMessage, затем портит пакет как атакующий.
@RunWith(AndroidJUnit4::class)
class X3dhSecurityTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String) = Base64.decode(s, Base64.NO_WRAP)
    private lateinit var dao: PrekeyDao
    private lateinit var myId: String

    @Before fun setup() = runBlocking {
        // identity-ключ должен существовать ДО bootstrap (он подписывает SPK им).
        // В приложении это делает MainActivity при старте; в тесте создаём явно.
        myId = KeyStoreManager.getOrCreateStableId(ctx)
        PrekeyManager.bootstrap(ctx)
        dao = AppDatabase.getInstance(ctx).prekeyDao()
    }

    // Связка «самому себе» в формате ответа relay.
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

    // honest INITIAL_HANDSHAKE от себя к себе
    private suspend fun honest(text: String = "hello"): JSONObject =
        SessionManager.buildInitialMessage(ctx, myId, myBundle(), text.toByteArray())!!


    // (a) КОНТРОЛЬ: честное сообщение принимается
    @Test fun a_honest_accepted() = runBlocking {
        val r = SessionManager.handleInitialMessage(ctx, honest("control"))
        assertNotNull("честное сообщение должно расшифроваться", r.content)
        assertEquals("control", String(r.content!!))
    }


    // ПРИМЕЧАНИЕ: в петле-на-себя инициатор и получатель — один ID, а buildInitialMessage
    // сам создаёт сессию инициатора. Поэтому счётчик сессий здесь не индикатор атаки;
    // доказательство отказа — content == null (расшифровка не прошла).

    // ПРИМЕЧАНИЕ: в петле-на-себя инициатор и получатель — один ID, а buildInitialMessage
    // сам создаёт сессию инициатора. Поэтому счётчик сессий здесь не индикатор атаки;
    // доказательство отказа — content == null (расшифровка не прошла).
    // (b) нет auth_sig -> отказ
    @Test fun b_missing_sig_rejected() = runBlocking {
        val msg = honest().apply { remove("auth_sig") }
        assertNull(SessionManager.handleInitialMessage(ctx, msg).content)
    }

    // (c) подмена ik_a (подделка отправителя) -> отказ
    @Test fun c_swapped_ika_rejected() = runBlocking {
        val attacker = com.libcryptsafe.CryptoManager.generatePrekeyPair()!![0]
        val msg = honest().apply { put("ik_a", b64(attacker)) }
        assertNull(SessionManager.handleInitialMessage(ctx, msg).content)
    }

    // (d) подмена ik_sign_a на чужой ключ -> отказ
    @Test fun d_swapped_iksign_rejected() = runBlocking {
        val other = com.libcryptsafe.CryptoManager.generateKeypair()!!
        val msg = honest().apply { put("ik_sign_a", b64(other)) }
        assertNull(SessionManager.handleInitialMessage(ctx, msg).content)
    }

    // (e) фикс 1: связка с ключом, не совпадающим с ID -> инициатор вернул null
    @Test fun e_bundle_key_mismatch_rejected() = runBlocking {
        val bundle = myBundle()
        val fakeIkSign = com.libcryptsafe.CryptoManager.generateKeypair()!!
        bundle.put("ik_sign", b64(fakeIkSign))   // ключ больше не соответствует myId
        assertNull(SessionManager.buildInitialMessage(ctx, myId, bundle, "x".toByteArray()))
    }
}
