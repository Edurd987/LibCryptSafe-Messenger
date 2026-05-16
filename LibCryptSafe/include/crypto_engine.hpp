#pragma once
#include "Cryptor.h"
#include "KeyExchange.hpp"
#include <vector>
#include <string>
#include <cstdint>

// ── Единая точка входа для JNI ──
// Все функции возвращают код ошибки или данные
// Никаких исключений наружу — только коды возврата

namespace CryptoEngine {

// Коды результата — JNI передаст их в Kotlin
enum class Result : int {
    OK              =  0,
    ERR_ENCRYPT     = -1,
    ERR_DECRYPT     = -2,
    ERR_KEYGEN      = -3,
    ERR_HMAC        = -4,
    ERR_INVALID_KEY = -5,
    ERR_TAMPERED    = -6,
};

// ── Контекст сессии — хранит ключи и состояние ──
struct Session {
    std::vector<uint8_t>            aes_key;
    std::unique_ptr<Crypto::KeyExchange> kex;
    bool                            handshake_done = false;

    Session() {
        try {
            kex = std::make_unique<Crypto::KeyExchange>();
        } catch (...) {
            kex = nullptr;
        }
    }
};

// ── Генерация пары ключей ──
// out_pub_der: публичный ключ для отправки собеседнику
inline Result generate_keypair(Session& session,
                                std::vector<uint8_t>& out_pub_der) {
    try {
        if (!session.kex) session.kex = std::make_unique<Crypto::KeyExchange>();
        out_pub_der = session.kex->export_public_key();
        return Result::OK;
    } catch (...) {
        return Result::ERR_KEYGEN;
    }
}

// ── ECDH: вычислить общий ключ из публичного ключа собеседника ──
inline Result compute_shared_key(Session& session,
                                  const std::vector<uint8_t>& peer_pub_der) {
    try {
        if (!session.kex) return Result::ERR_KEYGEN;
        session.aes_key = session.kex->compute_shared_key(peer_pub_der);
        session.handshake_done = true;
        return Result::OK;
    } catch (...) {
        return Result::ERR_KEYGEN;
    }
}

// ── Шифрование буфера ──
inline Result encrypt(const Session& session,
                       const std::vector<uint8_t>& plaintext,
                       std::vector<uint8_t>& out_ciphertext) {
    try {
        if (!session.handshake_done) return Result::ERR_INVALID_KEY;
        LibCryptSafe::Cryptor cryptor(session.aes_key);
        out_ciphertext = cryptor.encrypt(plaintext);
        return Result::OK;
    } catch (...) {
        return Result::ERR_ENCRYPT;
    }
}

// ── Дешифрование буфера ──
inline Result decrypt(const Session& session,
                       const std::vector<uint8_t>& ciphertext,
                       std::vector<uint8_t>& out_plaintext) {
    try {
        if (!session.handshake_done) return Result::ERR_INVALID_KEY;
        LibCryptSafe::Cryptor cryptor(session.aes_key);
        out_plaintext = cryptor.decrypt(ciphertext);
        return Result::OK;
    } catch (const std::runtime_error&) {
        // GCM тег не совпал — данные подделаны
        return Result::ERR_TAMPERED;
    } catch (...) {
        return Result::ERR_DECRYPT;
    }
}

// ── TOFU fingerprint ──
inline std::string get_fingerprint(const Session& session) {
    try {
        if (!session.kex) return "";
        return session.kex->get_fingerprint();
    } catch (...) {
        return "";
    }
}

// ── HMAC подтверждение сессии ──
inline Result compute_confirmation(const Session& session,
                                    const std::string& label,
                                    std::vector<uint8_t>& out_hmac) {
    try {
        if (!session.handshake_done) return Result::ERR_INVALID_KEY;
        out_hmac = Crypto::KeyExchange::compute_session_confirmation(
                       session.aes_key, label);
        return Result::OK;
    } catch (...) {
        return Result::ERR_HMAC;
    }
}

} // namespace CryptoEngine
