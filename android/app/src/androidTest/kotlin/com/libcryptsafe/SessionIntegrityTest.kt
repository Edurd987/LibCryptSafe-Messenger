package com.libcryptsafe

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.libcryptsafe.db.AppDatabase
import com.libcryptsafe.db.KeyStoreManager
import com.libcryptsafe.db.PrekeyDao
import com.libcryptsafe.db.SessionEntity
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec

// Изоляция сессий: ДВЕ разные identity. Боб = это устройство (настоящий KeyStore),
// Алиса = программные ключи теста. Проверяем: поддельное сообщение «от Алисы»
// отвергнуто И не перезаписало её настоящую сессию у Боба.
@RunWith(AndroidJUnit4::class)
class SessionIntegrityTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String) = Base64.decode(s, Base64.NO_WRAP)
    private lateinit var dao: PrekeyDao
    private lateinit var myId: String

    @Before fun setup() = runBlocking {
        myId = KeyStoreManager.getOrCreateStableId(ctx)   // identity ДО bootstrap
        PrekeyManager.bootstrap(ctx)
        dao = AppDatabase.getInstance(ctx).prekeyDao()
    }

    private fun ecKey(): KeyPair = KeyPairGenerator.getInstance("EC")
        .apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private fun sign(kp: KeyPair, data: ByteArray): ByteArray =
        Signature.getInstance("SHA256withECDSA").run { initSign(kp.private); update(data); sign() }

    // «Алиса» (aliceIdentity) строит первое сообщение Бобу; подписывает ключом signer.
    // signer == aliceIdentity -> честно; signer != aliceIdentity -> подделка.
    private suspend fun aliceInit(aliceIdentity: KeyPair, signer: KeyPair, text: String): JSONObject {
        val ikDh = dao.getPrekeyById("IK_DH", PrekeyManager.IK_DH_KEY_ID)!!
        val spk = dao.getCurrentSpk()!!
        val alicePair = CryptoManager.generatePrekeyPair()!!          // [0]=pub, [1]=priv
        val r = CryptoManager.x3dhInitiator(alicePair[1], ikDh.publicKey, spk.publicKey, null)!!
        val cipher = CryptoManager.encryptWithKey(r[0], text.toByteArray())!!
        val ikA = b64(alicePair[0]); val ekA = b64(r[2])
        val sig = sign(signer, "LCS-X3DH-v1|$ikA|$ekA|$myId".toByteArray(Charsets.UTF_8))
        return JSONObject().apply {
            put("type", "INITIAL_HANDSHAKE"); put("to", myId)
            put("ik_a", ikA); put("ik_sign_a", b64(aliceIdentity.public.encoded))
            put("ek_a", ekA); put("opk_id", JSONObject.NULL)
            put("auth_sig", b64(sig)); put("cipher", b64(cipher))
        }
    }

    // 1) КОНТРОЛЬ: настоящая Алиса принята, сессия под её ID создана
    @Test fun control_genuine_alice_accepted() = runBlocking {
        val alice = ecKey()
        val aliceId = KeyStoreManager.stableIdFromPublicKey(alice.public.encoded)
        val res = SessionManager.handleInitialMessage(ctx, aliceInit(alice, alice, "hi"))
        assertEquals("hi", res.content?.let { String(it) })
        assertEquals(aliceId, res.peerId)
        assertNotNull(AppDatabase.getInstance(ctx).sessionDao().getSession(aliceId))
        AppDatabase.getInstance(ctx).sessionDao().deleteSession(aliceId)
    }

    // 2) АТАКА: ключ Алисы, подпись атакующего -> отказ, сессия Алисы НЕ тронута
    @Test fun forged_alice_does_not_overwrite_session() = runBlocking {
        val sdao = AppDatabase.getInstance(ctx).sessionDao()
        val alice = ecKey(); val attacker = ecKey()
        val aliceId = KeyStoreManager.stableIdFromPublicKey(alice.public.encoded)
        val knownEnc = ByteArray(32) { 7 }; val knownAuth = ByteArray(32) { 9 }
        sdao.upsert(SessionEntity(aliceId, knownEnc, knownAuth, 1L))   // настоящая сессия

        val res = SessionManager.handleInitialMessage(ctx, aliceInit(alice, attacker, "evil"))

        assertNull("подделка должна быть отвергнута", res.content)
        val after = sdao.getSession(aliceId)!!
        assertArrayEquals("kEnc Алисы не должен измениться", knownEnc, after.kEnc)
        assertArrayEquals("kAuth Алисы не должен измениться", knownAuth, after.kAuth)
        sdao.deleteSession(aliceId)
    }
}
