#include <iostream>
#include <cassert>
#include "KeyExchange.hpp"

int main() {
    std::cout << "===== ECDH KeyExchange Test =====\n\n";

    // Симулируем два узла
    Crypto::KeyExchange alice;
    Crypto::KeyExchange bob;

    // Экспортируем публичные ключи
    auto alice_pub = alice.export_public_key();
    auto bob_pub   = bob.export_public_key();

    std::cout << "Alice pub key: " << alice_pub.size() << " bytes\n";
    std::cout << "Bob   pub key: " << bob_pub.size()   << " bytes\n";

    // Каждый вычисляет shared key из публичного ключа другого
    auto alice_shared = alice.compute_shared_key(bob_pub);
    auto bob_shared   = bob.compute_shared_key(alice_pub);

    // Ключи ДОЛЖНЫ совпадать
    assert(alice_shared == bob_shared && "Shared keys don't match!");
    assert(alice_shared.size() == 32  && "Key must be 32 bytes!");

    std::cout << "Shared key (hex): ";
    for (auto b : alice_shared) printf("%02x", b);
    std::cout << "\n\n";

    // Проверяем что разные пары дают разные ключи
    Crypto::KeyExchange carol;
    auto carol_shared = carol.compute_shared_key(alice_pub);
    assert(carol_shared != alice_shared && "Different peers must give different keys!");

    std::cout << "PASSED: Alice и Bob получили одинаковый ключ\n";
    std::cout << "PASSED: Carol получила другой ключ\n";
    std::cout << "=================================\n";
    std::cout << "KeyExchange: 3/3 PASSED\n";
    return 0;
}
