#include <memory>
#include <iostream>
#include <cassert>
#include "crypto_engine.hpp"

int main() {
    std::cout << "===== CryptoEngine API Test =====\n\n";

    // Симулируем два устройства
    CryptoEngine::Session alice, bob;

    // 1. Генерация ключей
    std::vector<uint8_t> alice_pub, bob_pub;
    assert(CryptoEngine::generate_keypair(alice, alice_pub) == CryptoEngine::Result::OK);
    assert(CryptoEngine::generate_keypair(bob,   bob_pub)   == CryptoEngine::Result::OK);
    std::cout << "PASS: keypairs generated\n";
    std::cout << "  Alice fingerprint: " << CryptoEngine::get_fingerprint(alice) << "\n";
    std::cout << "  Bob   fingerprint: " << CryptoEngine::get_fingerprint(bob)   << "\n\n";

    // 2. ECDH
    assert(CryptoEngine::compute_shared_key(alice, bob_pub)   == CryptoEngine::Result::OK);
    assert(CryptoEngine::compute_shared_key(bob,   alice_pub) == CryptoEngine::Result::OK);
    std::cout << "PASS: shared keys computed\n\n";

    // 3. Шифрование
    std::vector<uint8_t> plain = {'H','e','l','l','o',' ','A','n','d','r','o','i','d'};
    std::vector<uint8_t> cipher, decrypted;

    auto r = CryptoEngine::encrypt(alice, plain, cipher);
    assert(r == CryptoEngine::Result::OK);
    std::cout << "PASS: encrypted " << plain.size()
              << " -> " << cipher.size() << " bytes\n";

    // 4. Дешифрование
    r = CryptoEngine::decrypt(bob, cipher, decrypted);
    assert(r == CryptoEngine::Result::OK);
    assert(decrypted == plain);
    std::cout << "PASS: decrypted correctly\n\n";

    // 5. Подделка данных — должно вернуть ERR_TAMPERED, не бросить исключение
    cipher[20] ^= 0xFF;
    r = CryptoEngine::decrypt(bob, cipher, decrypted);
    assert(r == CryptoEngine::Result::ERR_TAMPERED);
    std::cout << "PASS: tamper detected, returned ERR_TAMPERED (no crash)\n";

    // 6. Нет handshake — должно вернуть ERR_INVALID_KEY
    CryptoEngine::Session empty;
    r = CryptoEngine::encrypt(empty, plain, cipher);
    assert(r == CryptoEngine::Result::ERR_INVALID_KEY);
    std::cout << "PASS: no-handshake returns ERR_INVALID_KEY (no crash)\n\n";

    std::cout << "=================================\n";
    std::cout << "CryptoEngine: 6/6 PASSED\n";
    std::cout << "Ready for JNI bridge\n";
    return 0;
}
