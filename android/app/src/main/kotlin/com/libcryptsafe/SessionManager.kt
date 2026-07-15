package com.libcryptsafe

import android.content.Context
import android.util.Base64
import com.libcryptsafe.db.AppDatabase
import com.libcryptsafe.db.KeyStoreManager
import org.json.JSONObject

// X3DH оркестратор сессий (Развилка 5.3 + 6). Обе роли.
// Собирает из проверенных примитивов: x3dhInitiator/Responder + AES-GCM(Kenc).
// Хранит SK (Kenc+Kauth) per-peer. Double Ratchet — отдельная веха (НЕ здесь).
object SessionManager {

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String) = Base64.decode(s, Base64.NO_WRAP)

    // ── РОЛЬ АЛИСЫ: собрать первое сообщение из связки Боба ──
    // peerBundle — ответ relay (ik_sign, ik_dh, spk{value,sig,keyId}, opk{id,value}|null).
    // Возвращает JSON первого сообщения (INITIAL_HANDSHAKE) или null при ошибке.
    // Проверяет подпись SPK ключом ik_sign Боба (MITM-защита) — иначе отказ.
    suspend fun buildInitialMessage(
        context: Context, recipientId: String,
        peerBundle: JSONObject, plaintext: ByteArray
    ): JSONObject? {
        val dao = AppDatabase.getInstance(context).prekeyDao()

        // 1. извлекаем связку Боба
        val ikSign = unb64(peerBundle.getString("ik_sign"))
        val ikDh   = unb64(peerBundle.getString("ik_dh"))
        val spkObj = peerBundle.getJSONObject("spk")
        val spkPub = unb64(spkObj.getString("value"))
        val spkSig = unb64(spkObj.getString("sig"))
        val spkKeyId = spkObj.getInt("keyId")
        val opkObj = peerBundle.optJSONObject("opk")   // может быть null
        val opkPub = opkObj?.let { unb64(it.getString("value")) }
        val opkId  = opkObj?.getInt("id")

        // 2. ПРОВЕРКА ПОДПИСИ SPK (Развилка 1 — защита от подмены на relay)
        // Объект подписи = SPK_pub || keyId (95б, без ts — ротация отложена).
        // Алиса собирает ТОТ ЖЕ объект, что подписал Боб -> подпись сходится.
        val signObject = PrekeyManager.buildSpkSignObject(spkPub, spkKeyId)
        val sigValid = CryptoManager.verifySignature(ikSign, signObject, spkSig)
        if (!sigValid) {
            android.util.Log.e("SESSION", "подпись SPK не сошлась — отказ (возможна подмена)")
            return null
        }

        // 3. наш IK_DH приватный
        val myIkDh = dao.getPrekeyById("IK_DH", PrekeyManager.IK_DH_KEY_ID) ?: return null

        // 4. X3DH инициатор -> Kenc, Kauth, EK_pub
        val r = CryptoManager.x3dhInitiator(myIkDh.privateKey, ikDh, spkPub, opkPub) ?: return null
        val kEnc = r[0]; val kAuth = r[1]; val ekPub = r[2]

        // 5. шифруем payload ключом Kenc
        val cipher = CryptoManager.encryptWithKey(kEnc, plaintext) ?: return null

        // 6. наш IK_DH публичный (для заголовка — Боб посчитает DH2)
        val myIkDhPub = myIkDh.publicKey

        // TODO: сохранить сессию (Kenc, Kauth) для recipientId

        // собираем пакет по Развилке 5.3
        return JSONObject().apply {
            put("type", "INITIAL_HANDSHAKE")   // Слой 1
            put("to", recipientId)
            // Слой 2 — X3DH-заголовок (открытый)
            put("ik_a", b64(myIkDhPub))
            put("ek_a", b64(ekPub))
            put("opk_id", opkId ?: JSONObject.NULL)
            // Слой 3 — payload (зашифрован)
            put("cipher", b64(cipher))
        }
    }

    // ── РОЛЬ БОБА: обработать первое сообщение -> расшифровать ──
    // Возвращает расшифрованный текст или null. Удаляет OPK_priv ПОСЛЕ успеха.
    suspend fun handleInitialMessage(context: Context, msg: JSONObject): ByteArray? {
        val dao = AppDatabase.getInstance(context).prekeyDao()

        val ikA = unb64(msg.getString("ik_a"))
        val ekA = unb64(msg.getString("ek_a"))
        val opkId = if (msg.isNull("opk_id")) null else msg.getInt("opk_id")
        val cipher = unb64(msg.getString("cipher"))

        // наши приватные ключи
        val myIkDh = dao.getPrekeyById("IK_DH", PrekeyManager.IK_DH_KEY_ID) ?: return null
        val mySpk  = dao.getCurrentSpk() ?: return null
        val myOpk  = opkId?.let { dao.getOpkById(it) }   // приватный OPK по id

        // X3DH responder -> Kenc
        val r = CryptoManager.x3dhResponder(
            myIkDh.privateKey, mySpk.privateKey, ikA, ekA, myOpk?.privateKey) ?: return null
        val kEnc = r[0]

        // расшифровываем (GCM-tag проверяется внутри -> null при подделке)
        val plain = CryptoManager.decryptWithKey(kEnc, cipher) ?: run {
            android.util.Log.e("SESSION", "расшифровка не удалась (tag/ключ)")
            return null
        }

        // УСПЕХ -> удаляем OPK_priv (Forward Secrecy, Развилка 6)
        opkId?.let { dao.deleteOpkById(it) }
        // TODO: сохранить сессию (Kenc, r[1]) для отправителя

        return plain
    }
}
