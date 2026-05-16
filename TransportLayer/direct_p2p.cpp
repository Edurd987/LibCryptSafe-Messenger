#include "interface.hpp"
#include <iostream>
#include <chrono>

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

class DirectP2P : public ITransport {
public:
    DirectP2P() : status_(Status::DISCONNECTED), socket_fd_(-1) {
#ifdef _WIN32
        WSADATA wsa;
        WSAStartup(MAKEWORD(2,2), &wsa);
#endif
    }

    ~DirectP2P() override {
        disconnect();
#ifdef _WIN32
        WSACleanup();
#endif
    }

    bool connect(const std::string& address, uint16_t port) override {
        status_ = Status::CONNECTING;
        std::cout << "[DirectP2P] Connecting to " << address << ":" << port << "\n";

        socket_fd_ = static_cast<int>(socket(AF_INET, SOCK_DGRAM, 0)); // UDP
        if (socket_fd_ < 0) {
            status_ = Status::TRANSPORT_ERROR;
            std::cerr << "[DirectP2P] Failed to create UDP socket\n";
            return false;
        }

        // Сохраняем адрес назначения для sendto()
        peer_.sin_family = AF_INET;
        peer_.sin_port   = htons(port);
        inet_pton(AF_INET, address.c_str(), &peer_.sin_addr);

        // TODO: реальный UDP punch-through когда будет второй узел
        status_ = Status::CONNECTED;
        std::cout << "[DirectP2P] UDP socket ready (simulated)\n";
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
        std::cout << "[DirectP2P] Disconnected\n";
    }

    bool send(const Message& msg) override {
        if (status_ != Status::CONNECTED) {
            std::cerr << "[DirectP2P] Not connected\n";
            return false;
        }

        std::cout << "[DirectP2P] Sending " << msg.payload.size()
                  << " bytes (UDP) from [" << msg.sender_id << "]\n";

        // TODO: реальная отправка
        // sendto(socket_fd_, msg.payload.data(), msg.payload.size(),
        //        0, (sockaddr*)&peer_, sizeof(peer_));

        // Симулируем доставку
        if (receive_cb_) receive_cb_(msg);
        return true;
    }

    void on_receive(std::function<void(const Message&)> callback) override {
        receive_cb_ = std::move(callback);
    }

    Status      status() const override { return status_; }
    std::string name()   const override { return "DirectP2P-UDP"; }

private:
    Status      status_;
    int         socket_fd_;
    sockaddr_in peer_{};
    std::function<void(const Message&)> receive_cb_;
};

std::unique_ptr<ITransport> make_direct_p2p() {
    return std::make_unique<DirectP2P>();
}

} // namespace Transport
