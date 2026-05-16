#pragma once
#include "interface.hpp"
#include <vector>
#include <memory>
#include <iostream>
#include <algorithm>

// Фабричные функции из транспортных модулей
namespace Transport {
    std::unique_ptr<ITransport> make_local_mesh();
    std::unique_ptr<ITransport> make_direct_p2p();
    std::unique_ptr<ITransport> make_tor_tunnel();
}

namespace Messenger {

// ── Приоритет транспортов (меньше = выше приоритет) ──
//    1. LocalMesh  — быстро, только локальная сеть
//    2. DirectP2P  — средняя скорость, интернет
//    3. TorTunnel  — медленно, максимальная анонимность
struct TransportEntry {
    std::unique_ptr<Transport::ITransport> transport;
    int                                    priority;
    bool                                   enabled;
};

class ConnectionManager {
public:
    ConnectionManager() {
        // Регистрируем все транспорты по приоритету
        transports_.push_back({ Transport::make_local_mesh(), 1, true });
        transports_.push_back({ Transport::make_direct_p2p(), 2, true });
        transports_.push_back({ Transport::make_tor_tunnel(), 3, true });

        // Сортируем по приоритету
        std::sort(transports_.begin(), transports_.end(),
            [](const TransportEntry& a, const TransportEntry& b) {
                return a.priority < b.priority;
            });
    }

    // ── Подключиться через лучший доступный транспорт ──
    bool connect(const std::string& address, uint16_t port) {
        for (auto& entry : transports_) {
            if (!entry.enabled) continue;

            std::cout << "[CM] Trying: " << entry.transport->name() << "\n";

            if (entry.transport->connect(address, port)) {
                active_ = entry.transport.get();
                std::cout << "[CM] Active transport: "
                          << active_->name() << "\n";
                return true;
            }

            std::cout << "[CM] Failed, trying next...\n";
        }

        std::cerr << "[CM] All transports failed!\n";
        return false;
    }

    // ── Отправить через активный транспорт ──
    bool send(const Transport::Message& msg) {
        if (!active_) {
            std::cerr << "[CM] No active transport\n";
            return false;
        }
        return active_->send(msg);
    }

    // ── Зарегистрировать обработчик входящих сообщений ──
    void on_receive(std::function<void(const Transport::Message&)> cb) {
        for (auto& entry : transports_) {
            entry.transport->on_receive(cb);
        }
    }

    // ── Принудительно выбрать транспорт по имени ──
    bool force_transport(const std::string& name) {
        for (auto& entry : transports_) {
            if (entry.transport->name() == name) {
                active_ = entry.transport.get();
                std::cout << "[CM] Forced transport: " << name << "\n";
                return true;
            }
        }
        std::cerr << "[CM] Transport not found: " << name << "\n";
        return false;
    }

    // ── Отключить конкретный транспорт (например Tor заблокирован) ──
    void disable_transport(const std::string& name) {
        for (auto& entry : transports_) {
            if (entry.transport->name() == name) {
                entry.enabled = false;
                std::cout << "[CM] Disabled: " << name << "\n";
            }
        }
    }

    // ── Статус активного транспорта ──
    std::string active_name() const {
        return active_ ? active_->name() : "none";
    }

    void disconnect() {
        for (auto& entry : transports_) {
            if (entry.transport->status() == Transport::Status::CONNECTED) {
                entry.transport->disconnect();
            }
        }
        active_ = nullptr;
    }

private:
    std::vector<TransportEntry>  transports_;
    Transport::ITransport*       active_ = nullptr;
};

} // namespace Messenger
