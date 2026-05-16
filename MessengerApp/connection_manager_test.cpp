#include <iostream>
#include <vector>
#include <cassert>
#include <chrono>
#include "ConnectionManager.hpp"
#include "Cryptor.h"
#include <openssl/rand.h>

int main() {
    std::cout << "===== ConnectionManager Test =====\n\n";

    // ── ТЕСТ 1: Автовыбор транспорта (должен выбрать LocalMesh) ──
    std::cout << "[TEST 1] Auto-select best transport\n";
    {
        Messenger::ConnectionManager cm;

        bool ok = cm.connect("192.168.1.100", 9999);
        assert(ok && "Connect failed");
        assert(cm.active_name() == "LocalMesh-LAN");
        std::cout << "Active: " << cm.active_name() << " — PASSED\n\n";
        cm.disconnect();
    }

    // ── ТЕСТ 2: Принудительный выбор TorTunnel ──
    std::cout << "[TEST 2] Force TorTunnel transport\n";
    {
        Messenger::ConnectionManager cm;
        cm.connect("192.168.1.100", 9999);
        cm.force_transport("TorTunnel-SOCKS5");

        assert(cm.active_name() == "TorTunnel-SOCKS5");
        std::cout << "Active: " << cm.active_name() << " — PASSED\n\n";
        cm.disconnect();
    }

    // ── ТЕСТ 3: Отключение LocalMesh — должен выбрать DirectP2P ──
    std::cout << "[TEST 3] Disable LocalMesh — fallback to DirectP2P\n";
    {
        Messenger::ConnectionManager cm;
        cm.disable_transport("LocalMesh-LAN");

        bool ok = cm.connect("10.0.0.1", 8888);
        assert(ok && "Fallback connect failed");
        assert(cm.active_name() == "DirectP2P-UDP");
        std::cout << "Active: " << cm.active_name() << " — PASSED\n\n";
        cm.disconnect();
    }

    // ── ТЕСТ 4: Полный цикл encrypt -> CM -> decrypt ──
    std::cout << "[TEST 4] Full pipeline: CryptSafe + ConnectionManager\n";
    {
        std::vector<uint8_t> key(32);
        RAND_bytes(key.data(), 32);
        LibCryptSafe::Cryptor cryptor(key);

        Messenger::ConnectionManager cm;

        std::vector<uint8_t> received_plain;
        cm.on_receive([&](const Transport::Message& msg) {
            auto plain = cryptor.decrypt(msg.payload);
            received_plain = plain;
            std::cout << "[RECV] Decrypted: ";
            for (auto c : plain) std::cout << (char)c;
            std::cout << "\n";
        });

        cm.connect("192.168.1.1", 7777);

        std::vector<uint8_t> plain = {'S','e','c','u','r','e',' ','M','s','g'};
        Transport::Message msg;
        msg.payload   = cryptor.encrypt(plain);
        msg.sender_id = "user-42";
        msg.timestamp = std::chrono::system_clock::now()
                            .time_since_epoch().count();

        cm.send(msg);

        assert(received_plain == plain && "Pipeline data mismatch!");
        std::cout << "Pipeline — PASSED\n\n";
        cm.disconnect();
    }

    std::cout << "==================================\n";
    std::cout << "ConnectionManager: 4/4 PASSED\n";
    return 0;
}
