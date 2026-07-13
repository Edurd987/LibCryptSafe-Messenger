package com.libcryptsafe

import android.content.Context
import com.libcryptsafe.db.AppDatabase
import com.libcryptsafe.db.KeyStoreManager
import com.libcryptsafe.db.PrekeyEntity
import java.nio.ByteBuffer

// X3DH оркестратор. Соединяет проверенное: генерация (JNI) + подпись (TEE) +
// хранение (SQLCipher). Ядро "глупое" — тут логика протокола.
object PrekeyManager {

    const val OPK_BATCH = 50           // размер пачки (Развилка: 50)
    const val SPK_KEY_ID = 1           // SPK один, фиксированный id

    // Собирает объект подписи SPK: SPK_pub(91) || timestamp(8,BE) || key_id(4,BE) = 103б
    // Формат зафиксирован в X3DH_DESIGN.md. Обе стороны собирают одинаково.
    fun buildSpkSignObject(spkPub: ByteArray, timestamp: Long, keyId: Int): ByteArray {
        val buf = ByteBuffer.allocate(spkPub.size + 8 + 4)  // BE по умолчанию
        buf.put(spkPub)
        buf.putLong(timestamp)
        buf.putInt(keyId)
        return buf.array()
    }

    // BOOTSTRAP (идемпотентный): создаёт SPK + пачку OPK, если их ещё нет.
    // Existующее НЕ трогает. Вызывать при старте приложения.
    suspend fun bootstrap(context: Context) {
        val dao = AppDatabase.getInstance(context).prekeyDao()

        // 1. SPK — создаём, только если его нет
        if (dao.getCurrentSpk() == null) {
            val pair = CryptoManager.generatePrekeyPair()
                ?: throw IllegalStateException("SPK: генерация не удалась")
            val spkPub = pair[0]   // 91б
            val spkPriv = pair[1]  // 121б
            val ts = System.currentTimeMillis()
            // подписываем объект SPK через KeyStore (TEE)
            val signObject = buildSpkSignObject(spkPub, ts, SPK_KEY_ID)
            val signature = KeyStoreManager.signData(signObject)
            dao.insert(PrekeyEntity(
                keyType = "SPK", keyId = SPK_KEY_ID,
                privateKey = spkPriv, publicKey = spkPub,
                signature = signature, timestamp = ts
            ))
            android.util.Log.d("PREKEY_MGR", "SPK создан и подписан")
        }

        // 2. OPK — дополняем пачку до OPK_BATCH, если не хватает
        val haveOpk = dao.countOpk()
        if (haveOpk < OPK_BATCH) {
            val toGenerate = OPK_BATCH - haveOpk
            // следующий свободный key_id (OPK нумеруются от 1)
            val existing = dao.getAllOpk().map { it.keyId }.toSet()
            val batch = mutableListOf<PrekeyEntity>()
            var nextId = 1
            var generated = 0
            val now = System.currentTimeMillis()
            val maxIterations = OPK_BATCH * 3   // защита от бесконечного цикла
            var iterations = 0
            while (generated < toGenerate) {
                if (++iterations > maxIterations)
                    throw IllegalStateException("OPK: не найден свободный key_id (битая база?)")
                if (nextId in existing) { nextId++; continue }
                val pair = CryptoManager.generatePrekeyPair()
                    ?: throw IllegalStateException("OPK: генерация не удалась")
                batch.add(PrekeyEntity(
                    keyType = "OPK", keyId = nextId,
                    privateKey = pair[1], publicKey = pair[0],
                    signature = null, timestamp = now
                ))
                nextId++; generated++
            }
            dao.insertAll(batch)   // атомарная пачка
            // лог: ТОЛЬКО числа, НИКОГДА не ключи (logcat читаем извне)
            android.util.Log.d("PREKEY_MGR", "OPK дополнено: +$generated (было $haveOpk)")
        }
    }

    // Собирает JSON публичной связки для relay (prekeys_upload).
    // ТОЛЬКО публичные части (publicKey), НИКОГДА privateKey.
    // key_id берём ИЗ БД как есть (единый источник — иначе рассинхрон с Бобом).
    suspend fun buildUploadJson(context: Context, senderId: String): String {
        val dao = AppDatabase.getInstance(context).prekeyDao()
        val b64 = { b: ByteArray -> android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP) }
        val keys = org.json.JSONArray()

        // IK — identity из KeyStore (публичный, X.509)
        val ikPub = KeyStoreManager.getIdentityPublicKeyEncoded(context)
        keys.put(org.json.JSONObject().apply {
            put("type", "IK"); put("id", 0); put("value", b64(ikPub))
        })

        // SPK — публичная часть + подпись, реальный keyId
        dao.getCurrentSpk()?.let { spk ->
            keys.put(org.json.JSONObject().apply {
                put("type", "SPK"); put("id", spk.keyId)
                put("value", b64(spk.publicKey))
                put("sig", spk.signature?.let { b64(it) })
            })
        }

        // OPK — только публичные части, реальные keyId из БД
        for (opk in dao.getAllOpk()) {
            keys.put(org.json.JSONObject().apply {
                put("type", "OPK"); put("id", opk.keyId)
                put("value", b64(opk.publicKey))
            })
        }

        return org.json.JSONObject().apply {
            put("type", "prekeys_upload")
            put("senderId", senderId)
            put("keys", keys)
        }.toString()
    }

    // Диагностика: сколько ключей в наличии
    suspend fun status(context: Context): String {
        val dao = AppDatabase.getInstance(context).prekeyDao()
        val spk = if (dao.getCurrentSpk() != null) "есть" else "НЕТ"
        val opk = dao.countOpk()
        return "SPK: $spk, OPK: $opk/$OPK_BATCH"
    }
}
