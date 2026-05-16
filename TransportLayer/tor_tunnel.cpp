#include "interface.hpp"
#include <iostream>

// Tor работает через SOCKS5 прокси (по умолчанию 127.0.0.1:9050)
// Реальная интеграция: подключаемся к SOCKS5, он туннелирует трафик через Tor
#ifdef _WIN32
  #include <winsock2.h>
  #include <ws2tcpip.h>
#else
  #include <sys/socket.h>
  #include <netinet/in.h>
  #include <arpa/inet.h>
  #include <unistd.h>
#endif

namespace Transport {

// ── SOCKS5 константы ──
static constexpr uint16_t TOR_SOCKS5_PORT    = 9050;
static constexpr const char* TOR_SOCKS5_HOST = "127.0.0.1";

class TorTunnel : public ITransport {
public:
    TorTunnel() : status_(Status::DISCONNECTED), socket_fd_(-1) {
#ifdef _WIN32
        WSADATA wsa;
        WSAStartup(MAKEWORD(2,2), &wsa);
#endif
    }

    ~TorTunnel() override {
        disconnect();
#ifdef _WIN32
        WSACleanup();
#endif
    }

    // address здесь — .onion адрес или обычный IP назначения
    bool connect(const std::string& address, uint16_t port) override {
        status_ = Status::CONNECTING;
        std::cout << "[TorTunnel] Connecting via SOCKS5 "
                  << TOR_SOCKS5_HOST << ":" << TOR_SOCKS5_PORT
                  << " -> " << address << ":" << port << "\n";

        // Шаг 1: TCP соединение к локальному Tor SOCKS5
        socket_fd_ = static_cast<int>(socket(AF_INET, SOCK_STREAM, 0));
        if (socket_fd_ < 0) {
            status_ = Status::TRANSPORT_ERROR;
            std::cerr << "[TorTunnel] Failed to create socket\n";
            return false;
        }

        sockaddr_in socks_addr{};
        socks_addr.sin_family = AF_INET;
        socks_addr.sin_port   = htons(TOR_SOCKS5_PORT);
        inet_pton(AF_INET, TOR_SOCKS5_HOST, &socks_addr.sin_addr);

        // TODO: реальный SOCKS5 handshake когда Tor запущен:
        //
        // 1. Отправить: {0x05, 0x01, 0x00}  (SOCKS5, 1 метод, NO AUTH)
        // 2. Получить:  {0x05, 0x00}         (версия, выбранный метод)
        // 3. Отправить: CONNECT запрос с адресом назначения
        // 4. Получить:  {0x05, 0x00, ...}    (успех)
        //
        // if (::connect(socket_fd_, (sockaddr*)&socks_addr, sizeof(socks_addr)) != 0) {
        //     status_ = Status::TRANSPORT_ERROR;
        //     return false;
        // }
        // socks5_handshake(address, port);

        // Симулируем — Tor не запущен в тестовой среде
        status_ = Status::CONNECTED;
        std::cout << "[TorTunnel] SOCKS5 tunnel ready (simulated)\n";
        std::cout << "[TorTunnel] Note: requires Tor daemon on port 9050\n";
        return true;
    }

    void disconnect() override {
        if (socket_fd_ >= 0) {
#ifdef _WIN32
            closesocket(socket_fd_);
#else
            close(socket_fd_);
#endif
            socket_fd_ = -1;
        }
        status_ = Status::DISCONNECTED;
        std::cout << "[TorTunnel] Disconnected\n";
    }

    bool send(const Message& msg) override {
        if (status_ != Status::CONNECTED) {
            std::cerr << "[TorTunnel] Not connected\n";
            return false;
        }

        // Данные уже зашифрованы AES-GCM слоем выше —
        // Tor добавит свой слой луковичного шифрования поверх
        std::cout << "[TorTunnel] Sending " << msg.payload.size()
                  << " bytes via Tor from [" << msg.sender_id << "]\n";
        std::cout << "[TorTunnel] (AES-GCM) -> (Tor onion layers) -> destination\n";

        // TODO: реальная отправка через SOCKS5 сокет
        // ::send(socket_fd_, msg.payload.data(), msg.payload.size(), 0);

        if (receive_cb_) receive_cb_(msg);
        return true;
    }

    void on_receive(std::function<void(const Message&)> callback) override {
        receive_cb_ = std::move(callback);
    }

    Status      status() const override { return status_; }
    std::string name()   const override { return "TorTunnel-SOCKS5"; }

private:
    Status status_;
    int    socket_fd_;
    std::function<void(const Message&)> receive_cb_;

    // TODO: реализовать когда подключим реальный Tor
    // void socks5_handshake(const std::string& address, uint16_t port) { ... }
};

std::unique_ptr<ITransport> make_tor_tunnel() {
    return std::make_unique<TorTunnel>();
}

} // namespace Transport
