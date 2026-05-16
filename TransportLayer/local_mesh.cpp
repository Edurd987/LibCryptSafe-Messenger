#include "interface.hpp"
#include <iostream>
#include <thread>
#include <atomic>
#include <stdexcept>

// Для Windows/MSYS2 (winsock)
#ifdef _WIN32
  #include <winsock2.h>
  #include <ws2tcpip.h>
  #pragma comment(lib, "ws2_32.lib")
#else
  #include <sys/socket.h>
  #include <netinet/in.h>
  #include <arpa/inet.h>
  #include <unistd.h>
#endif

namespace Transport {

class LocalMesh : public ITransport {
public:
    LocalMesh() : status_(Status::DISCONNECTED), socket_fd_(-1) {
#ifdef _WIN32
        WSADATA wsa;
        WSAStartup(MAKEWORD(2,2), &wsa);
#endif
    }

    ~LocalMesh() override {
        disconnect();
#ifdef _WIN32
        WSACleanup();
#endif
    }

    // ── Подключение к узлу локальной сети ──
    bool connect(const std::string& address, uint16_t port) override {
        status_ = Status::CONNECTING;
        std::cout << "[LocalMesh] Connecting to " << address << ":" << port << "\n";

        socket_fd_ = static_cast<int>(socket(AF_INET, SOCK_STREAM, 0));
        if (socket_fd_ < 0) {
            status_ = Status::TRANSPORT_ERROR;
            std::cerr << "[LocalMesh] Failed to create socket\n";
            return false;
        }

        sockaddr_in addr{};
        addr.sin_family = AF_INET;
        addr.sin_port   = htons(port);
        inet_pton(AF_INET, address.c_str(), &addr.sin_addr);

        // TODO: реальное подключение когда будет второй узел
        // if (::connect(socket_fd_, (sockaddr*)&addr, sizeof(addr)) != 0) { ... }

        // Пока симулируем успешное подключение
        status_ = Status::CONNECTED;
        std::cout << "[LocalMesh] Connected (simulated)\n";
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
        std::cout << "[LocalMesh] Disconnected\n";
    }

    // ── Отправка зашифрованного сообщения ──
    bool send(const Message& msg) override {
        if (status_ != Status::CONNECTED) {
            std::cerr << "[LocalMesh] Not connected\n";
            return false;
        }

        std::cout << "[LocalMesh] Sending " << msg.payload.size()
                  << " bytes from [" << msg.sender_id << "]\n";

        // TODO: реальная отправка через сокет
        // ::send(socket_fd_, msg.payload.data(), msg.payload.size(), 0);

        // Симулируем доставку — вызываем callback сразу
        if (receive_cb_) {
            receive_cb_(msg);
        }
        return true;
    }

    void on_receive(std::function<void(const Message&)> callback) override {
        receive_cb_ = std::move(callback);
    }

    Status      status() const override { return status_; }
    std::string name()   const override { return "LocalMesh-LAN"; }

private:
    Status   status_;
    int      socket_fd_;
    std::function<void(const Message&)> receive_cb_;
};

// ── Фабричная функция — создаёт транспорт без знания о конкретном классе ──
std::unique_ptr<ITransport> make_local_mesh() {
    return std::make_unique<LocalMesh>();
}

} // namespace Transport
