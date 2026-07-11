#include "MessengerApp/KeyExchange.hpp"
#include <cstdio>
#include <cstring>

static std::string hex(const std::vector<uint8_t>& v) {
    std::string s; char b[3];
    for (auto c : v) { snprintf(b, 3, "%02x", c); s += b; }
    return s;
}

int main() {
    // RFC 5869, Test Case 1 (SHA-256)
    std::vector<uint8_t> ikm(22, 0x0b);              // 0x0b × 22
    std::vector<uint8_t> salt;                        // 000102...0c (13 байт)
    for (uint8_t i = 0; i <= 0x0c; ++i) salt.push_back(i);
    std::string info;                                 // f0f1...f9 (10 байт)
    for (uint8_t i = 0xf0; i <= 0xf9; ++i) info += static_cast<char>(i);

    auto okm = Crypto::KeyExchange::hkdf_sha256(ikm, salt, info, 42);

    const char* expect =
        "3cb25f25faacd57a90434f64d0362f2a"
        "2d2d0a90cf1a5a4c5db02d56ecc4c5bf"
        "34007208d5b887185865";

    std::string got = hex(okm);
    printf("получено:  %s\n", got.c_str());
    printf("ожидается: %s\n", expect);
    if (got == expect) { printf("\n>>> HKDF КОРРЕКТЕН (RFC 5869 TC1 сошёлся)\n"); return 0; }
    printf("\n>>> ОШИБКА: HKDF НЕ совпал с эталоном!\n");
    return 1;
}
