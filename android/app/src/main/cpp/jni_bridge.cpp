#include <memory>
#include <jni.h>
#include <string>
#include <vector>
#include "crypto_engine.hpp"
#include "KeyExchange.hpp"
#include "Cryptor.h"

static CryptoEngine::Session g_session;

extern "C" {

JNIEXPORT jbyteArray JNICALL
Java_com_libcryptsafe_CryptoManager_generateKeypair(
        JNIEnv* env, jobject) {
    std::vector<uint8_t> pub_key;
    if (CryptoEngine::generate_keypair(g_session, pub_key) != CryptoEngine::Result::OK)
        return nullptr;
    jbyteArray arr = env->NewByteArray(static_cast<jsize>(pub_key.size()));
    env->SetByteArrayRegion(arr, 0, static_cast<jsize>(pub_key.size()),
        reinterpret_cast<const jbyte*>(pub_key.data()));
    return arr;
}

// ═══ X3DH: генерация prekey-пары (STATELESS — не трогает g_session) ═══
// Возвращает массив [0]=pub_der(91б), [1]=priv_der(121б).
// Ядро "глупое": не знает, SPK это или OPK — решает Kotlin-оркестратор.
JNIEXPORT jobjectArray JNICALL
Java_com_libcryptsafe_CryptoManager_generatePrekeyPair(
        JNIEnv* env, jobject) {
    try {
        Crypto::KeyExchange kx;                       // новая ECDH-пара P-256
        std::vector<uint8_t> pub  = kx.export_public_key();
        std::vector<uint8_t> priv = kx.export_private_key();

        jclass byteArrCls = env->FindClass("[B");
        if (!byteArrCls) return nullptr;
        jobjectArray result = env->NewObjectArray(2, byteArrCls, nullptr);
        if (!result) return nullptr;

        jbyteArray jpub = env->NewByteArray(static_cast<jsize>(pub.size()));
        env->SetByteArrayRegion(jpub, 0, static_cast<jsize>(pub.size()),
            reinterpret_cast<const jbyte*>(pub.data()));
        env->SetObjectArrayElement(result, 0, jpub);
        env->DeleteLocalRef(jpub);

        jbyteArray jpriv = env->NewByteArray(static_cast<jsize>(priv.size()));
        env->SetByteArrayRegion(jpriv, 0, static_cast<jsize>(priv.size()),
            reinterpret_cast<const jbyte*>(priv.data()));
        env->SetObjectArrayElement(result, 1, jpriv);
        env->DeleteLocalRef(jpriv);

        OPENSSL_cleanse(priv.data(), priv.size());    // гигиена
        return result;
    } catch (const std::exception&) {
        return nullptr;
    }
}

// ═══ X3DH: проверка подписи SPK (stateless) ═══
// pub_der = X.509 identity Sign-ключа (91б, с Android KeyStore)
// data = объект подписи (SPK_pub ‖ timestamp ‖ key_id = 103б)
// sig_der = DER-подпись из KeyStore (~71-72б)
JNIEXPORT jboolean JNICALL
Java_com_libcryptsafe_CryptoManager_verifySignature(
        JNIEnv* env, jobject,
        jbyteArray pub_der, jbyteArray data, jbyteArray sig_der) {
    try {
        auto toVec = [&](jbyteArray arr) {
            jsize len = env->GetArrayLength(arr);
            std::vector<uint8_t> v(len);
            env->GetByteArrayRegion(arr, 0, len,
                reinterpret_cast<jbyte*>(v.data()));
            return v;
        };
        bool ok = Crypto::KeyExchange::verify_signature(
            toVec(pub_der), toVec(data), toVec(sig_der));
        return ok ? JNI_TRUE : JNI_FALSE;
    } catch (const std::exception&) {
        return JNI_FALSE;   // ошибка = не доверяем
    }
}

// ═══ X3DH инициатор (Алиса): 4 DH + деривация. STATELESS ═══
// Вход: наш IK_DH priv (DER), публичные Боба: IK_DH, SPK, OPK (или null).
// Выход: [0]=Kenc(32), [1]=Kauth(32), [2]=EK_pub(91) для первого сообщения.
// Эфемерный приватный ключ генерится и умирает внутри C++.
JNIEXPORT jobjectArray JNICALL
Java_com_libcryptsafe_CryptoManager_x3dhInitiator(
        JNIEnv* env, jobject,
        jbyteArray our_ik_dh_priv, jbyteArray peer_ik_dh,
        jbyteArray peer_spk, jbyteArray peer_opk) {
    try {
        auto toVec = [&](jbyteArray arr) -> std::vector<uint8_t> {
            if (arr == nullptr) return {};
            jsize len = env->GetArrayLength(arr);
            std::vector<uint8_t> v(len);
            env->GetByteArrayRegion(arr, 0, len,
                reinterpret_cast<jbyte*>(v.data()));
            return v;
        };

        auto ik_priv = toVec(our_ik_dh_priv);
        auto result = Crypto::KeyExchange::x3dh_initiator(
            ik_priv, toVec(peer_ik_dh), toVec(peer_spk), toVec(peer_opk));
        OPENSSL_cleanse(ik_priv.data(), ik_priv.size());   // гигиена

        jclass byteArrCls = env->FindClass("[B");
        if (!byteArrCls) return nullptr;
        jobjectArray out = env->NewObjectArray(3, byteArrCls, nullptr);
        if (!out) return nullptr;

        auto put = [&](int idx, std::vector<uint8_t>& v) {
            jbyteArray a = env->NewByteArray(static_cast<jsize>(v.size()));
            env->SetByteArrayRegion(a, 0, static_cast<jsize>(v.size()),
                reinterpret_cast<const jbyte*>(v.data()));
            env->SetObjectArrayElement(out, idx, a);
            env->DeleteLocalRef(a);
        };
        put(0, result.k_enc);
        put(1, result.k_auth);
        put(2, result.ek_pub);
        return out;
    } catch (const std::exception&) {
        return nullptr;
    }
}

// ═══ X3DH получатель (Боб): повтор 4 DH из первого сообщения. STATELESS ═══
// Вход: наши IK_DH, SPK приватные + IK_A, EK_A из сообщения + наш OPK (null->DH1-DH3).
// Выход: [0]=Kenc(32), [1]=Kauth(32). EK не генерим — он от Алисы.
JNIEXPORT jobjectArray JNICALL
Java_com_libcryptsafe_CryptoManager_x3dhResponder(
        JNIEnv* env, jobject,
        jbyteArray our_ik_dh_priv, jbyteArray our_spk_priv,
        jbyteArray peer_ik_dh, jbyteArray peer_ek, jbyteArray our_opk_priv) {
    try {
        auto toVec = [&](jbyteArray arr) -> std::vector<uint8_t> {
            if (arr == nullptr) return {};
            jsize len = env->GetArrayLength(arr);
            std::vector<uint8_t> v(len);
            env->GetByteArrayRegion(arr, 0, len,
                reinterpret_cast<jbyte*>(v.data()));
            return v;
        };

        auto ik_priv  = toVec(our_ik_dh_priv);
        auto spk_priv = toVec(our_spk_priv);
        auto opk_priv = toVec(our_opk_priv);
        auto keys = Crypto::KeyExchange::x3dh_responder(
            ik_priv, spk_priv, toVec(peer_ik_dh), toVec(peer_ek), opk_priv);
        OPENSSL_cleanse(ik_priv.data(), ik_priv.size());   // гигиена
        OPENSSL_cleanse(spk_priv.data(), spk_priv.size());
        if (!opk_priv.empty()) OPENSSL_cleanse(opk_priv.data(), opk_priv.size());

        jclass byteArrCls = env->FindClass("[B");
        if (!byteArrCls) return nullptr;
        jobjectArray out = env->NewObjectArray(2, byteArrCls, nullptr);
        if (!out) return nullptr;
        auto put = [&](int idx, std::vector<uint8_t>& v) {
            jbyteArray a = env->NewByteArray(static_cast<jsize>(v.size()));
            env->SetByteArrayRegion(a, 0, static_cast<jsize>(v.size()),
                reinterpret_cast<const jbyte*>(v.data()));
            env->SetObjectArrayElement(out, idx, a);
            env->DeleteLocalRef(a);
        };
        put(0, keys.k_enc);
        put(1, keys.k_auth);
        return out;
    } catch (const std::exception&) {
        return nullptr;
    }
}

// ═══ AES-256-GCM с ЯВНЫМ ключом (для X3DH Kenc). STATELESS ═══
// Формат: [12 nonce][ciphertext][16 tag]. Обёртка над Cryptor (не g_session!).
JNIEXPORT jbyteArray JNICALL
Java_com_libcryptsafe_CryptoManager_encryptWithKey(
        JNIEnv* env, jobject, jbyteArray key, jbyteArray plaintext) {
    try {
        auto toVec = [&](jbyteArray arr) {
            jsize len = env->GetArrayLength(arr);
            std::vector<uint8_t> v(len);
            env->GetByteArrayRegion(arr, 0, len, reinterpret_cast<jbyte*>(v.data()));
            return v;
        };
        LibCryptSafe::Cryptor c(toVec(key));   // ключ Kenc явно
        auto cipher = c.encrypt(toVec(plaintext));
        jbyteArray out = env->NewByteArray(static_cast<jsize>(cipher.size()));
        env->SetByteArrayRegion(out, 0, static_cast<jsize>(cipher.size()),
            reinterpret_cast<const jbyte*>(cipher.data()));
        return out;
    } catch (const std::exception&) {
        return nullptr;
    }
}

JNIEXPORT jbyteArray JNICALL
Java_com_libcryptsafe_CryptoManager_decryptWithKey(
        JNIEnv* env, jobject, jbyteArray key, jbyteArray ciphertext) {
    try {
        auto toVec = [&](jbyteArray arr) {
            jsize len = env->GetArrayLength(arr);
            std::vector<uint8_t> v(len);
            env->GetByteArrayRegion(arr, 0, len, reinterpret_cast<jbyte*>(v.data()));
            return v;
        };
        LibCryptSafe::Cryptor c(toVec(key));
        auto plain = c.decrypt(toVec(ciphertext));   // бросит при неверном tag
        jbyteArray out = env->NewByteArray(static_cast<jsize>(plain.size()));
        env->SetByteArrayRegion(out, 0, static_cast<jsize>(plain.size()),
            reinterpret_cast<const jbyte*>(plain.data()));
        return out;
    } catch (const std::exception&) {
        return nullptr;   // неверный ключ/tag -> null (аутентификация не прошла)
    }
}

JNIEXPORT jint JNICALL
Java_com_libcryptsafe_CryptoManager_computeSharedKey(
        JNIEnv* env, jobject, jbyteArray peer_pub_key) {
    jsize len = env->GetArrayLength(peer_pub_key);
    std::vector<uint8_t> peer_pub(len);
    env->GetByteArrayRegion(peer_pub_key, 0, len,
        reinterpret_cast<jbyte*>(peer_pub.data()));
    return static_cast<jint>(CryptoEngine::compute_shared_key(g_session, peer_pub));
}

JNIEXPORT jbyteArray JNICALL
Java_com_libcryptsafe_CryptoManager_encrypt(
        JNIEnv* env, jobject, jbyteArray plaintext) {
    jsize len = env->GetArrayLength(plaintext);
    std::vector<uint8_t> plain(len);
    env->GetByteArrayRegion(plaintext, 0, len,
        reinterpret_cast<jbyte*>(plain.data()));
    std::vector<uint8_t> cipher;
    if (CryptoEngine::encrypt(g_session, plain, cipher) != CryptoEngine::Result::OK)
        return nullptr;
    jbyteArray arr = env->NewByteArray(static_cast<jsize>(cipher.size()));
    env->SetByteArrayRegion(arr, 0, static_cast<jsize>(cipher.size()),
        reinterpret_cast<const jbyte*>(cipher.data()));
    return arr;
}

JNIEXPORT jbyteArray JNICALL
Java_com_libcryptsafe_CryptoManager_decrypt(
        JNIEnv* env, jobject, jbyteArray ciphertext) {
    jsize len = env->GetArrayLength(ciphertext);
    std::vector<uint8_t> cipher(len);
    env->GetByteArrayRegion(ciphertext, 0, len,
        reinterpret_cast<jbyte*>(cipher.data()));
    std::vector<uint8_t> plain;
    auto result = CryptoEngine::decrypt(g_session, cipher, plain);
    if (result == CryptoEngine::Result::ERR_TAMPERED) {
        env->ThrowNew(env->FindClass("java/lang/SecurityException"),
            "Message authentication failed: data tampered");
        return nullptr;
    }
    if (result != CryptoEngine::Result::OK) return nullptr;
    jbyteArray arr = env->NewByteArray(static_cast<jsize>(plain.size()));
    env->SetByteArrayRegion(arr, 0, static_cast<jsize>(plain.size()),
        reinterpret_cast<const jbyte*>(plain.data()));
    return arr;
}

JNIEXPORT jstring JNICALL
Java_com_libcryptsafe_CryptoManager_getFingerprint(
        JNIEnv* env, jobject) {
    return env->NewStringUTF(CryptoEngine::get_fingerprint(g_session).c_str());
}

JNIEXPORT jstring JNICALL
Java_com_libcryptsafe_CryptoManager_getErrorString(
        JNIEnv* env, jobject, jint code) {
    const char* msg;
    switch (static_cast<CryptoEngine::Result>(code)) {
        case CryptoEngine::Result::OK:              msg = "OK"; break;
        case CryptoEngine::Result::ERR_ENCRYPT:     msg = "Encryption failed"; break;
        case CryptoEngine::Result::ERR_DECRYPT:     msg = "Decryption failed"; break;
        case CryptoEngine::Result::ERR_KEYGEN:      msg = "Key generation failed"; break;
        case CryptoEngine::Result::ERR_HMAC:        msg = "HMAC failed"; break;
        case CryptoEngine::Result::ERR_INVALID_KEY: msg = "No handshake done"; break;
        case CryptoEngine::Result::ERR_TAMPERED:    msg = "Data tampered!"; break;
        default:                                    msg = "Unknown error"; break;
    }
    return env->NewStringUTF(msg);
}

} // extern "C"
