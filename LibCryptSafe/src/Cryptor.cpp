#include "Cryptor.h"
#include <openssl/evp.h>
#include <openssl/rand.h>
#include <stdexcept>
#include <cstring>

namespace LibCryptSafe {

Cryptor::Cryptor(const std::vector<uint8_t>& key) : key_(key) {}
Cryptor::~Cryptor() {}

// Формат вывода: [12 байт nonce][ciphertext][16 байт tag]
std::vector<uint8_t> Cryptor::encrypt(const std::vector<uint8_t>& data) const {
    uint8_t nonce[12];
    if (!RAND_bytes(nonce, sizeof(nonce)))
        throw std::runtime_error("Failed to generate nonce");

    EVP_CIPHER_CTX* ctx = EVP_CIPHER_CTX_new();
    if (!ctx) throw std::runtime_error("Failed to create context");

    std::vector<uint8_t> result(12 + data.size() + 16);

    // Записываем nonce в начало
    memcpy(result.data(), nonce, 12);

    if (EVP_EncryptInit_ex(ctx, EVP_aes_256_gcm(), nullptr,
                           key_.data(), nonce) != 1) {
        EVP_CIPHER_CTX_free(ctx);
        throw std::runtime_error("Failed to init encrypt");
    }

    int out_len = 0;
    if (EVP_EncryptUpdate(ctx, result.data() + 12, &out_len,
                          data.data(), static_cast<int>(data.size())) != 1) {
        EVP_CIPHER_CTX_free(ctx);
        throw std::runtime_error("Failed to encrypt");
    }

    int final_len = 0;
    if (EVP_EncryptFinal_ex(ctx, result.data() + 12 + out_len, &final_len) != 1) {
        EVP_CIPHER_CTX_free(ctx);
        throw std::runtime_error("Failed to finalize encrypt");
    }

    // Записываем GCM тег в конец
    if (EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_GCM_GET_TAG, 16,
                             result.data() + 12 + out_len + final_len) != 1) {
        EVP_CIPHER_CTX_free(ctx);
        throw std::runtime_error("Failed to get tag");
    }

    EVP_CIPHER_CTX_free(ctx);
    return result;
}

// Ожидает формат: [12 байт nonce][ciphertext][16 байт tag]
std::vector<uint8_t> Cryptor::decrypt(const std::vector<uint8_t>& data) const {
    constexpr size_t NONCE_LEN = 12;
    constexpr size_t TAG_LEN   = 16;

    if (data.size() < NONCE_LEN + TAG_LEN)
        throw std::runtime_error("Data too short");

    const uint8_t* nonce      = data.data();
    const uint8_t* ciphertext = data.data() + NONCE_LEN;
    size_t cipher_len         = data.size() - NONCE_LEN - TAG_LEN;
    // тег — последние 16 байт (нам нужен non-const указатель для OpenSSL)
    uint8_t tag[TAG_LEN];
    memcpy(tag, data.data() + NONCE_LEN + cipher_len, TAG_LEN);

    EVP_CIPHER_CTX* ctx = EVP_CIPHER_CTX_new();
    if (!ctx) throw std::runtime_error("Failed to create context");

    if (EVP_DecryptInit_ex(ctx, EVP_aes_256_gcm(), nullptr,
                           key_.data(), nonce) != 1) {
        EVP_CIPHER_CTX_free(ctx);
        throw std::runtime_error("Failed to init decrypt");
    }

    // Передаём тег до финализации
    if (EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_GCM_SET_TAG, TAG_LEN, tag) != 1) {
        EVP_CIPHER_CTX_free(ctx);
        throw std::runtime_error("Failed to set tag");
    }

    std::vector<uint8_t> plain(cipher_len);
    int out_len = 0;
    if (EVP_DecryptUpdate(ctx, plain.data(), &out_len,
                          ciphertext, static_cast<int>(cipher_len)) != 1) {
        EVP_CIPHER_CTX_free(ctx);
        throw std::runtime_error("Failed to decrypt");
    }

    int final_len = 0;
    // EVP_DecryptFinal_ex вернёт 1 только если тег совпал — защита от подделки
    if (EVP_DecryptFinal_ex(ctx, plain.data() + out_len, &final_len) != 1) {
        EVP_CIPHER_CTX_free(ctx);
        throw std::runtime_error("Authentication failed — data tampered!");
    }

    EVP_CIPHER_CTX_free(ctx);
    plain.resize(out_len + final_len);
    return plain;
}

} // namespace LibCryptSafe
