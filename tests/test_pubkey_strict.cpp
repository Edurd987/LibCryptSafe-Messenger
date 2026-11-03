// №44: чужой публичный ключ (от незнакомца, до любых проверок) принимаем ТОЛЬКО
// как P-256, именованная кривая, несжатая точка, ровно 91 байт DER.
// Всё остальное (RSA, P-384, явные параметры кривой, сжатая точка, мусор в хвосте)
// не должно доходить до криптографии. Сборка (CLANG64):
// /clang64/bin/clang++ -std=c++17 -I. tests/test_pubkey_strict.cpp -lcrypto -o ~/tps.exe && PATH=/clang64/bin:$PATH ~/tps.exe
#include "MessengerApp/KeyExchange.hpp"
#include <openssl/core_names.h>
#include <openssl/rsa.h>
#include <cstdio>
using Crypto::KeyExchange;
using V = std::vector<uint8_t>;
static int ok = 0, bad = 0;
static void check(const char* name, bool good) { printf("%s %s\n", good ? "ЗАЩИЩЕНО" : "ПРОБОЙ  ", name); good ? ok++ : bad++; }

static V pubDer(EVP_PKEY* k) { uint8_t* d = nullptr; int n = i2d_PUBKEY(k, &d); V v(d, d + n); OPENSSL_free(d); return v; }
static V privDer(EVP_PKEY* k) { uint8_t* d = nullptr; int n = i2d_PrivateKey(k, &d); V v(d, d + n); OPENSSL_free(d); return v; }
static V sign(EVP_PKEY* k, const V& data) {
    EVP_MD_CTX* c = EVP_MD_CTX_new(); size_t n = 0;
    EVP_DigestSignInit(c, nullptr, EVP_sha256(), nullptr, k);
    EVP_DigestSign(c, nullptr, &n, data.data(), data.size());
    V s(n); EVP_DigestSign(c, s.data(), &n, data.data(), data.size()); s.resize(n);
    EVP_MD_CTX_free(c); return s;
}
static EVP_PKEY* p256WithEncoding(const char* enc, const char* fmt) {
    EVP_PKEY* k = EVP_EC_gen("prime256v1");
    if (enc) EVP_PKEY_set_utf8_string_param(k, OSSL_PKEY_PARAM_EC_ENCODING, enc);
    if (fmt) EVP_PKEY_set_utf8_string_param(k, OSSL_PKEY_PARAM_EC_POINT_CONVERSION_FORMAT, fmt);
    return k;
}
static bool dhAccepts(const KeyExchange& me, const V& peer) {
    try { me.compute_raw_dh(peer); return true; } catch (const std::exception&) { return false; }
}

int main() {
    const V data = {'L','C','S','-','X','3','D','H'};
    KeyExchange me;

    // --- позитивный контроль: честный P-256 работает как раньше ---
    EVP_PKEY* good = EVP_EC_gen("prime256v1");
    V goodDer = pubDer(good);
    printf("контроль: честный P-256 DER = %zu байт\n", goodDer.size());
    check("контроль: честный P-256 подпись принята", KeyExchange::verify_signature(goodDer, data, sign(good, data)));
    check("контроль: честный P-256 ECDH работает", dhAccepts(me, goodDer));
    check("контроль: export_public_key = 91 байт", me.export_public_key().size() == 91);

    // --- атаки: чужие типы/кодировки ключа ---
    EVP_PKEY* rsa = EVP_RSA_gen(2048);
    check("RSA-ключ с верной RSA-подписью отвергнут", !KeyExchange::verify_signature(pubDer(rsa), data, sign(rsa, data)));
    EVP_PKEY* p384 = EVP_EC_gen("secp384r1");
    check("P-384 с верной подписью отвергнут", !KeyExchange::verify_signature(pubDer(p384), data, sign(p384, data)));
    EVP_PKEY* expl = p256WithEncoding(OSSL_PKEY_EC_ENCODING_EXPLICIT, nullptr);
    V explDer = pubDer(expl);
    printf("явные параметры: DER = %zu байт\n", explDer.size());
    check("P-256 с ЯВНЫМИ параметрами кривой: подпись отвергнута", !KeyExchange::verify_signature(explDer, data, sign(expl, data)));
    check("P-256 с ЯВНЫМИ параметрами кривой: ECDH отвергнут", !dhAccepts(me, explDer));
    EVP_PKEY* comp = p256WithEncoding(nullptr, OSSL_PKEY_EC_POINT_CONVERSION_FORMAT_COMPRESSED);
    V compDer = pubDer(comp);
    printf("сжатая точка: DER = %zu байт\n", compDer.size());
    check("P-256 сжатая точка: подпись отвергнута", !KeyExchange::verify_signature(compDer, data, sign(comp, data)));
    V tail = goodDer; tail.push_back(0x00);
    check("честный DER + мусорный байт в хвосте: подпись отвергнута", !KeyExchange::verify_signature(tail, data, sign(good, data)));
    check("честный DER + мусорный байт в хвосте: ECDH отвергнут", !dhAccepts(me, tail));
    V offCurve = goodDer; offCurve[90] ^= 0x01;
    check("точка вне кривой отвергнута (контроль OpenSSL)", !dhAccepts(me, offCurve));
    check("пустой ключ отвергнут", !KeyExchange::verify_signature(V{}, data, sign(good, data)));

    // свой приватный ключ (из нашей БД) по-прежнему восстанавливается
    KeyExchange restored(privDer(good));
    check("контроль: восстановление своего приватного ключа", restored.export_public_key() == goodDer);

    printf("ЗАЩИЩЕНО: %d | ПРОБОЕВ: %d\n", ok, bad);
    EVP_PKEY_free(good); EVP_PKEY_free(rsa); EVP_PKEY_free(p384); EVP_PKEY_free(expl); EVP_PKEY_free(comp);
    return bad == 0 ? 0 : 1;
}
