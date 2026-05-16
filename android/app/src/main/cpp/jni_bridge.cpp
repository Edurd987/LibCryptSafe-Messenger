#include <memory>
#include <jni.h>
#include <string>
#include <vector>
#include "crypto_engine.hpp"

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
