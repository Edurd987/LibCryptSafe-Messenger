#include "MessengerApp/KeyExchange.hpp"
#include <openssl/pem.h>
#include <cstdio>
using namespace Crypto;

// имитация Android KeyStore: подписать SHA256withECDSA -> DER
static std::vector<uint8_t> sign_der(EVP_PKEY* priv,
                                     const std::vector<uint8_t>& data) {
    EVP_MD_CTX* ctx = EVP_MD_CTX_new();
    EVP_DigestSignInit(ctx, nullptr, EVP_sha256(), nullptr, priv);
    EVP_DigestSignUpdate(ctx, data.data(), data.size());
    size_t len = 0;
    EVP_DigestSignFinal(ctx, nullptr, &len);
    std::vector<uint8_t> sig(len);
    EVP_DigestSignFinal(ctx, sig.data(), &len);
    sig.resize(len);
    EVP_MD_CTX_free(ctx);
    return sig;
}

int main() {
    // P-256 ключ (как identity Sign в KeyStore)
    EVP_PKEY_CTX* kctx = EVP_PKEY_CTX_new_id(EVP_PKEY_EC, nullptr);
    EVP_PKEY_keygen_init(kctx);
    EVP_PKEY_CTX_set_ec_paramgen_curve_nid(kctx, NID_X9_62_prime256v1);
    EVP_PKEY* key = nullptr;
    EVP_PKEY_keygen(kctx, &key);
    EVP_PKEY_CTX_free(kctx);

    // публичный ключ в X.509 (как publicKey.encoded с Android)
    uint8_t* pub_raw = nullptr;
    int pub_len = i2d_PUBKEY(key, &pub_raw);
    std::vector<uint8_t> pub_der(pub_raw, pub_raw + pub_len);
    OPENSSL_free(pub_raw);
    printf("pubkey X.509 DER: %d байт (ожидаем ~91 для P-256)\n\n", pub_len);

    // объект подписи по спеке: SPK_pub || timestamp || key_id
    std::vector<uint8_t> data = {'S','P','K','_','p','u','b'};
    for (int i = 0; i < 8; ++i) data.push_back(0x11);   // timestamp
    data.push_back(0x07);                               // key_id

    auto sig = sign_der(key, data);
    printf("подпись DER: %zu байт (ECDSA P-256 обычно 70-72)\n\n", sig.size());

    // ТЕСТ 1: верная подпись -> true
    bool ok1 = KeyExchange::verify_signature(pub_der, data, sig);
    printf("ТЕСТ 1 (верная подпись): %s\n", ok1 ? ">>> ПРИНЯТА (верно)" : ">>> ПРОВАЛ");

    // ТЕСТ 2: испорченные данные -> false
    auto bad_data = data; bad_data[0] ^= 0xFF;
    bool ok2 = !KeyExchange::verify_signature(pub_der, bad_data, sig);
    printf("ТЕСТ 2 (подменены данные): %s\n", ok2 ? ">>> ОТВЕРГНУТА (верно)" : ">>> ПРОВАЛ!");

    // ТЕСТ 3: испорченная подпись -> false
    auto bad_sig = sig; bad_sig[sig.size()/2] ^= 0xFF;
    bool ok3 = !KeyExchange::verify_signature(pub_der, data, bad_sig);
    printf("ТЕСТ 3 (испорчена подпись): %s\n", ok3 ? ">>> ОТВЕРГНУТА (верно)" : ">>> ПРОВАЛ!");

    // ТЕСТ 4: чужой ключ -> false
    EVP_PKEY_CTX* k2 = EVP_PKEY_CTX_new_id(EVP_PKEY_EC, nullptr);
    EVP_PKEY_keygen_init(k2);
    EVP_PKEY_CTX_set_ec_paramgen_curve_nid(k2, NID_X9_62_prime256v1);
    EVP_PKEY* eve = nullptr;
    EVP_PKEY_keygen(k2, &eve);
    EVP_PKEY_CTX_free(k2);
    uint8_t* eve_raw = nullptr;
    int eve_len = i2d_PUBKEY(eve, &eve_raw);
    std::vector<uint8_t> eve_pub(eve_raw, eve_raw + eve_len);
    OPENSSL_free(eve_raw);
    bool ok4 = !KeyExchange::verify_signature(eve_pub, data, sig);
    printf("ТЕСТ 4 (чужой pubkey): %s\n", ok4 ? ">>> ОТВЕРГНУТА (верно)" : ">>> ПРОВАЛ!");

    // ТЕСТ 5: мусор вместо ключа -> false, без краха
    std::vector<uint8_t> junk = {0x01, 0x02, 0x03};
    bool ok5 = !KeyExchange::verify_signature(junk, data, sig);
    printf("ТЕСТ 5 (мусор вместо ключа): %s\n", ok5 ? ">>> ОТВЕРГНУТА, без краха (верно)" : ">>> ПРОВАЛ!");

    EVP_PKEY_free(key); EVP_PKEY_free(eve);

    bool all = ok1 && ok2 && ok3 && ok4 && ok5;
    printf("\n====================================\n");
    printf("%s\n", all ? "ВСЕ 5 ПРОЙДЕНЫ - verify_signature корректен"
                       : "ЕСТЬ ПРОВАЛЫ - РАЗБИРАТЬ");
    return all ? 0 : 1;
}
