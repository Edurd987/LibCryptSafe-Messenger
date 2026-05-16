#include <iostream>
#include <vector>
#include <cassert>
#include <chrono>
#include "interface.hpp"
#include "Cryptor.h"
#include <openssl/rand.h>

// Фабричная функция из local_mesh.cpp
namespace Transport {
    std::unique_ptr<ITransport> make_local_mesh();
}

int main() {
    std::cout << "===== TransportLayer + CryptSafe Integration Test =====\n\n";

    // 1. Генерируем общий ключ (в реальности — обмен по DH)
    std::vector<uint8_t> shared_key(32);
    RAND_bytes(shared_key.data(), 32);
    LibCryptSafe::Cryptor cryptor(shared_key);

    // 2. Создаём транспорт через фабрику
    auto transport = Transport::make_local_mesh();
    std::cout << "Transport: " << transport->name() << "\n";
    assert(transport->status() == Transport::Status::DISCONNECTED);

    // 3. Регистрируем обработчик входящих сообщений
    bool received = false;
    std::vector<uint8_t> received_plain;

    transport->on_receive([&](const Transport::Message& msg) {
        std::cout << "[RECV] " << msg.payload.size()
                  << " bytes from [" << msg.sender_id << "]\n";

        // Расшифровываем на принимающей стороне
        auto plain = cryptor.decrypt(msg.payload);
        received_plain = plain;
        received = true;

        std::cout << "[RECV] Decrypted: ";
        for (auto c : plain) std::cout << (char)c;
        std::cout << "\n";
    });

    // 4. Подключаемся
    bool ok = transport->connect("192.168.1.100", 9999);
    assert(ok && "Connect failed");
    assert(transport->status() == Transport::Status::CONNECTED);

    // 5. Шифруем и отправляем
    std::vector<uint8_t> plain = {'H','i',' ','f','r','o','m',' ','M','e','s','h'};
    auto encrypted = cryptor.encrypt(plain);

    Transport::Message msg;
    msg.payload   = encrypted;
    msg.sender_id = "node-A";
    msg.timestamp = std::chrono::system_clock::now().time_since_epoch().count();

    bool sent = transport->send(msg);
    assert(sent && "Send failed");

    // 6. Проверяем что данные дошли без изменений
    assert(received && "Message not received");
    assert(received_plain == plain && "Data mismatch after decrypt!");

    std::cout << "\nResult: encrypt -> send -> receive -> decrypt OK\n";
    std::cout << "=======================================================\n";
    std::cout << "transport_test: PASSED\n";

    return 0;
}
