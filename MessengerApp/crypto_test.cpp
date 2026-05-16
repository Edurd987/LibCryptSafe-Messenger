#include <iostream>
#include <vector>
#include <cassert>
#include "Cryptor.h"
#include <openssl/rand.h>

// Вспомогательная функция — печать байтов в hex
void print_hex(const std::string& label, const std::vector<uint8_t>& data) {
    std::cout << label;
    for (auto b : data) printf("%02x ", b);
    std::cout << std::endl;
}

// ───── ТЕСТ 1: Базовый round-trip ─────
bool test_roundtrip() {
    std::cout << "\n[TEST 1] Round-trip encrypt/decrypt\n";

    std::vector<uint8_t> key(32);
    RAND_bytes(key.data(), 32);

    LibCryptSafe::Cryptor cryptor(key);

    std::vector<uint8_t> plain = {'H','e','l','l','o',' ','W','o','r','l','d'};
    std::cout << "Plain:     ";
    for (auto c : plain) std::cout << (char)c;
    std::cout << std::endl;

    auto encrypted = cryptor.encrypt(plain);
    std::cout << "Encrypted length: " << encrypted.size() << " bytes (expected "
              << plain.size() + 12 + 16 << ")\n";

    auto decrypted = cryptor.decrypt(encrypted);
    std::cout << "Decrypted: ";
    for (auto c : decrypted) std::cout << (char)c;
    std::cout << std::endl;

    assert(decrypted == plain && "Round-trip FAILED: data mismatch!");
    std::cout << "PASSED\n";
    return true;
}

// ───── ТЕСТ 2: Разные ключи — расшифровка должна провалиться ─────
bool test_wrong_key() {
    std::cout << "\n[TEST 2] Wrong key — must throw\n";

    std::vector<uint8_t> key1(32), key2(32);
    RAND_bytes(key1.data(), 32);
    RAND_bytes(key2.data(), 32);

    LibCryptSafe::Cryptor enc(key1);
    LibCryptSafe::Cryptor dec(key2);

    std::vector<uint8_t> plain = {'S','e','c','r','e','t'};
    auto encrypted = enc.encrypt(plain);

    try {
        auto result = dec.decrypt(encrypted);
        std::cout << "FAILED — должно было бросить исключение!\n";
        return false;
    } catch (const std::exception& e) {
        std::cout << "Caught expected: " << e.what() << "\n";
        std::cout << "PASSED\n";
        return true;
    }
}

// ───── ТЕСТ 3: Подделка данных — тег должен не совпасть ─────
bool test_tampered_data() {
    std::cout << "\n[TEST 3] Tampered ciphertext — must throw\n";

    std::vector<uint8_t> key(32);
    RAND_bytes(key.data(), 32);
    LibCryptSafe::Cryptor cryptor(key);

    std::vector<uint8_t> plain = {'T','e','s','t'};
    auto encrypted = cryptor.encrypt(plain);

    // Портим один байт в середине (после nonce, до тега)
    encrypted[14] ^= 0xFF;
    std::cout << "Flipped byte at index 14\n";

    try {
        auto result = cryptor.decrypt(encrypted);
        std::cout << "FAILED — должно было бросить исключение!\n";
        return false;
    } catch (const std::exception& e) {
        std::cout << "Caught expected: " << e.what() << "\n";
        std::cout << "PASSED\n";
        return true;
    }
}

// ───── ТЕСТ 4: Пустые данные ─────
bool test_empty_data() {
    std::cout << "\n[TEST 4] Empty plaintext\n";

    std::vector<uint8_t> key(32);
    RAND_bytes(key.data(), 32);
    LibCryptSafe::Cryptor cryptor(key);

    std::vector<uint8_t> plain = {};
    auto encrypted = cryptor.encrypt(plain);
    std::cout << "Encrypted empty: " << encrypted.size() << " bytes (expected 28)\n";

    auto decrypted = cryptor.decrypt(encrypted);
    assert(decrypted.empty() && "Empty round-trip FAILED!");
    std::cout << "PASSED\n";
    return true;
}

// ───── ТЕСТ 5: Каждое шифрование даёт разный nonce ─────
bool test_unique_nonces() {
    std::cout << "\n[TEST 5] Unique nonces per encryption\n";

    std::vector<uint8_t> key(32);
    RAND_bytes(key.data(), 32);
    LibCryptSafe::Cryptor cryptor(key);

    std::vector<uint8_t> plain = {'A','B','C'};

    auto enc1 = cryptor.encrypt(plain);
    auto enc2 = cryptor.encrypt(plain);

    // Первые 12 байт — nonce
    bool same_nonce = std::equal(enc1.begin(), enc1.begin()+12, enc2.begin());
    if (same_nonce) {
        std::cout << "FAILED — nonce повторился!\n";
        return false;
    }
    std::cout << "Nonce 1: "; for(int i=0;i<12;i++) printf("%02x",enc1[i]);
    std::cout << "\nNonce 2: "; for(int i=0;i<12;i++) printf("%02x",enc2[i]);
    std::cout << "\nPASSED\n";
    return true;
}

int main() {
    std::cout << "========== LibCryptSafe Security Tests ==========\n";

    int passed = 0, total = 5;

    if (test_roundtrip())     passed++;
    if (test_wrong_key())     passed++;
    if (test_tampered_data()) passed++;
    if (test_empty_data())    passed++;
    if (test_unique_nonces()) passed++;

    std::cout << "\n=================================================\n";
    std::cout << "Results: " << passed << "/" << total << " tests passed\n";

    return (passed == total) ? 0 : 1;
}
