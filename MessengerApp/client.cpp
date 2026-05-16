#include <iostream>
#include <vector>
#include <string>
#include <cstdint>
#include "KeyExchange.hpp"
#include "Cryptor.h"

#ifdef _WIN32
  #include <winsock2.h>
  #include <ws2tcpip.h>
#else
  #include <sys/socket.h>
  #include <netinet/in.h>
  #include <arpa/inet.h>
  #include <unistd.h>
#endif

static bool send_frame(int sock, const std::vector<uint8_t>& data) {
    uint32_t len = htonl(static_cast<uint32_t>(data.size()));
    if (send(sock, reinterpret_cast<const char*>(&len), 4, 0) != 4)
        return false;
    return send(sock, reinterpret_cast<const char*>(data.data()),
                static_cast<int>(data.size()), 0)
           == static_cast<int>(data.size());
}

static std::vector<uint8_t> recv_frame(int sock) {
    uint32_t net_len = 0;
    if (recv(sock, reinterpret_cast<char*>(&net_len), 4, MSG_WAITALL) != 4)
        throw std::runtime_error("recv header failed");
    uint32_t len = ntohl(net_len);
    if (len == 0 || len > 65536)
        throw std::runtime_error("Invalid frame length");
    std::vector<uint8_t> buf(len);
    if (recv(sock, reinterpret_cast<char*>(buf.data()),
             static_cast<int>(len), MSG_WAITALL) != static_cast<int>(len))
        throw std::runtime_error("recv data failed");
    return buf;
}

// IP сервера — меняй здесь для LAN теста
static constexpr const char* SERVER_IP   = "10.0.2.16";
static constexpr uint16_t    SERVER_PORT = 9999;

int main() {
    std::cout << "[CLIENT] Connecting to "
              << SERVER_IP << ":" << SERVER_PORT << "\n";

#ifdef _WIN32
    WSADATA wsa;
    WSAStartup(MAKEWORD(2,2), &wsa);
#endif

    int sock = static_cast<int>(socket(AF_INET, SOCK_STREAM, 0));

    // ── DoS защита: таймаут на recv ──
    struct timeval tv = {30, 0};
    setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO,
               reinterpret_cast<const char*>(&tv), sizeof(tv));

    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_port   = htons(SERVER_PORT);
    inet_pton(AF_INET, SERVER_IP, &addr.sin_addr);

    if (connect(sock, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) != 0) {
        perror("[CLIENT] connect failed");
        return 1;
    }
    std::cout << "[CLIENT] Connected!\n";

    Crypto::KeyExchange kex;
    auto client_pub = kex.export_public_key();

    std::cout << "[CLIENT] My fingerprint:\n  " << kex.get_fingerprint() << "\n";

    send_frame(sock, client_pub);
    auto server_pub = recv_frame(sock);
    std::cout << "[CLIENT] Server pubkey: " << server_pub.size() << " bytes\n";

    auto aes_key = kex.compute_shared_key(server_pub);

    auto srv_confirm_recv     = recv_frame(sock);
    auto srv_confirm_expected = Crypto::KeyExchange::compute_session_confirmation(
                                    aes_key, "server_confirm");

    if (srv_confirm_recv != srv_confirm_expected) {
        std::cerr << "[CLIENT] HMAC MISMATCH — possible MitM! Abort.\n";
#ifdef _WIN32
        closesocket(sock); WSACleanup();
#else
        close(sock);
#endif
        return 1;
    }

    auto cli_confirm = Crypto::KeyExchange::compute_session_confirmation(
                           aes_key, "client_confirm");
    send_frame(sock, cli_confirm);

    std::cout << "[CLIENT] HMAC confirmed — no MitM detected\n";

// ── Shared key ТОЛЬКО в Debug сборке ──
#ifndef NDEBUG
    std::cout << "[CLIENT][DEBUG] Shared key: ";
    for (auto b : aes_key) printf("%02x", b);
    std::cout << "\n";
#endif

    std::cout << "[CLIENT] Handshake complete!\n\n";

    LibCryptSafe::Cryptor cryptor(aes_key);
    OPENSSL_cleanse(aes_key.data(), aes_key.size());

    std::vector<std::string> messages = {
        "Hello from client!",
        "AES-256-GCM over real TCP",
        "HMAC handshake confirmed!"
    };

    for (const auto& msg : messages) {
        std::vector<uint8_t> plain(msg.begin(), msg.end());
        auto encrypted = cryptor.encrypt(plain);
        send_frame(sock, encrypted);
        std::cout << "[CLIENT] Sent: \"" << msg
                  << "\" (" << encrypted.size() << " bytes)\n";
    }

    std::cout << "[CLIENT] Done\n";

#ifdef _WIN32
    closesocket(sock); WSACleanup();
#else
    close(sock);
#endif
    return 0;
}
