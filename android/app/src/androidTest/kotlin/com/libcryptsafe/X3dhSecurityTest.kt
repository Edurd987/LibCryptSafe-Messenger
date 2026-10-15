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
        // Патч 3 отвергает повторное рукопожатие при живой сессии. Тесты шлют себе
        // через один myId -> чистим сессию перед каждым тестом (иначе ловим сами себя).
        AppDatabase.getInstance(ctx).sessionDao().deleteSession(myId)
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

    // (f) REPLAY X3DH: повтор первого сообщения не перезаписывает живую сессию
    @Test fun f_replay_initial_rejected() = runBlocking {
        val sdao = AppDatabase.getInstance(ctx).sessionDao()
        val msg = honest("first")
        val r1 = SessionManager.handleInitialMessage(ctx, msg)      // первое — принято
        assertEquals("first", r1.content?.let { String(it) })
        val s1 = sdao.getSession(r1.peerId)!!
        val r2 = SessionManager.handleInitialMessage(ctx, msg)      // ПОВТОР того же пакета
        assertNull("повтор INITIAL_HANDSHAKE должен быть отвергнут", r2.content)
        val s2 = sdao.getSession(r1.peerId)!!
        assertArrayEquals("сессия не должна перезаписаться повтором", s1.kEnc, s2.kEnc)
        sdao.deleteSession(r1.peerId)
    }

    // (g) окно nonce: повтор (peer, nonce) отвергается, первый — принят
    @Test fun g_seen_nonce_window() = runBlocking {
        val dao = AppDatabase.getInstance(ctx).seenNonceDao()
        val peer = "TEST-PEER-0001"; val nn = "nonce-" + System.currentTimeMillis()
        assertTrue("первый nonce — новый", dao.markSeen(peer, nn))
        assertFalse("повтор того же nonce — дубль", dao.markSeen(peer, nn))
        assertTrue("другой nonce того же peer — новый", dao.markSeen(peer, nn + "x"))
    }

    // (h) очистка: старый чат удаляется (контроль), X3DH той же давности — остаётся
    @Test fun h_purge_keeps_x3dh() = runBlocking {
        val dao = AppDatabase.getInstance(ctx).seenNonceDao()
        val peer = "TEST-PURGE-0001"
        val old = System.currentTimeMillis() - 40L * 24 * 3600 * 1000
        dao.insert(com.libcryptsafe.db.SeenNonceEntity(peerId = peer, nonce = "chat-old-$old", timestamp = old))
        dao.insert(com.libcryptsafe.db.SeenNonceEntity(peerId = peer, nonce = "x3dh:old-$old", timestamp = old))
        dao.purgeChatOlderThan(System.currentTimeMillis() - com.libcryptsafe.util.ReplayWindow.RETENTION_MS)
        assertTrue("старый чат удалён -> снова 'новый' (очистка сработала)", dao.markSeen(peer, "chat-old-$old"))
        assertFalse("X3DH НЕ удалён -> всё ещё дубль (откат сессии невозможен)", dao.markSeen(peer, "x3dh:old-$old"))
    }

    // (e) фикс 1: связка с ключом, не совпадающим с ID -> инициатор вернул null
    @Test fun e_bundle_key_mismatch_rejected() = runBlocking {
        val bundle = myBundle()
        val fakeIkSign = com.libcryptsafe.CryptoManager.generateKeypair()!!
        bundle.put("ik_sign", b64(fakeIkSign))   // ключ больше не соответствует myId
        assertNull(SessionManager.buildInitialMessage(ctx, myId, bundle, "x".toByteArray()))
    }
}
