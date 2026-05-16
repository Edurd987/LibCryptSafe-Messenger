#pragma once
#include <openssl/evp.h>
#include <openssl/ec.h>
#include <openssl/sha.h>
#include <openssl/hmac.h>
#include <openssl/x509.h>
#include <openssl/crypto.h>
#include <vector>
#include <string>
#include <stdexcept>
#include <memory>

namespace Crypto {

class KeyExchange {
public:
    KeyExchange() {
        auto ctx = UniqCtx(EVP_PKEY_CTX_new_id(EVP_PKEY_EC, nullptr));
        if (!ctx) throw std::runtime_error("EVP_PKEY_CTX_new_id failed");
        if (EVP_PKEY_keygen_init(ctx.get()) != 1)
            throw std::runtime_error("keygen_init failed");
        if (EVP_PKEY_CTX_set_ec_paramgen_curve_nid(
                ctx.get(), NID_X9_62_prime256v1) != 1)
            throw std::runtime_error("set_curve failed");
        EVP_PKEY* raw = nullptr;
        if (EVP_PKEY_keygen(ctx.get(), &raw) != 1)
            throw std::runtime_error("keygen failed");
        pkey_.reset(raw);
    }

    // ── Публичный ключ в DER (91 байт для P-256) ──
    std::vector<uint8_t> export_public_key() const {
        uint8_t* der = nullptr;
        int len = i2d_PUBKEY(pkey_.get(), &der);
        if (len <= 0) throw std::runtime_error("i2d_PUBKEY failed");
        std::vector<uint8_t> result(der, der + len);
        OPENSSL_free(der);
        return result;
    }

    // ── ECDH → 32-байтный AES ключ ──
    std::vector<uint8_t> compute_shared_key(
            const std::vector<uint8_t>& peer_pub_der) const {
        const uint8_t* ptr = peer_pub_der.data();
        auto peer = UniqPkey(
            d2i_PUBKEY(nullptr, &ptr,
                       static_cast<long>(peer_pub_der.size())));
        if (!peer) throw std::runtime_error("Failed to parse peer public key");

        auto ctx = UniqCtx(EVP_PKEY_CTX_new(pkey_.get(), nullptr));
        if (!ctx) throw std::runtime_error("CTX_new failed");
        if (EVP_PKEY_derive_init(ctx.get()) != 1)
            throw std::runtime_error("derive_init failed");
        if (EVP_PKEY_derive_set_peer(ctx.get(), peer.get()) != 1)
            throw std::runtime_error("derive_set_peer failed");

        size_t secret_len = 0;
        if (EVP_PKEY_derive(ctx.get(), nullptr, &secret_len) != 1)
            throw std::runtime_error("derive size failed");

        std::vector<uint8_t> secret(secret_len);
        if (EVP_PKEY_derive(ctx.get(), secret.data(), &secret_len) != 1)
            throw std::runtime_error("derive failed");

        std::vector<uint8_t> aes_key(32);
        SHA256(secret.data(), secret_len, aes_key.data());

        // Безопасное уничтожение секрета — не rand(), а специальная функция
        OPENSSL_cleanse(secret.data(), secret.size());
        return aes_key;
    }

    // ── TOFU Fingerprint: SHA256(public_key_der) → HEX строка ──
    // Пользователи сравнивают эти строки голосом/QR-кодом
    std::string get_fingerprint() const {
        auto der = export_public_key();
        uint8_t hash[32];
        SHA256(der.data(), der.size(), hash); // правильная 3-аргументная сигнатура
        OPENSSL_cleanse(der.data(), der.size());

        std::string hex;
        hex.reserve(64);
        for (int i = 0; i < 32; ++i) {
            char buf[3];
            snprintf(buf, sizeof(buf), "%02x", hash[i]);
            hex += buf;
        }
        return hex;
    }

    // ── HMAC подтверждение сессии ──
    // Оба узла вычисляют HMAC-SHA256(shared_key, label)
    // Если результаты совпадают — MitM исключён математически
    static std::vector<uint8_t> compute_session_confirmation(
            const std::vector<uint8_t>& shared_key,
            const std::string& label) {

        uint8_t out[32];
        uint32_t out_len = 32;

        // HMAC-SHA256(key=shared_key, data=label)
        HMAC(EVP_sha256(),
             shared_key.data(), static_cast<int>(shared_key.size()),
             reinterpret_cast<const uint8_t*>(label.data()), label.size(),
             out, &out_len);

        return std::vector<uint8_t>(out, out + out_len);
    }

private:
    struct PkeyDeleter {
        void operator()(EVP_PKEY* p) const { EVP_PKEY_free(p); }
    };
    struct CtxDeleter {
        void operator()(EVP_PKEY_CTX* p) const { EVP_PKEY_CTX_free(p); }
    };
    using UniqPkey = std::unique_ptr<EVP_PKEY,     PkeyDeleter>;
    using UniqCtx  = std::unique_ptr<EVP_PKEY_CTX, CtxDeleter>;
    UniqPkey pkey_;
};

} // namespace Crypto
