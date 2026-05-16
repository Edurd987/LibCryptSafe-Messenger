#include <iostream>
#include <vector>
#include <cstring>
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

int main() {
    std::cout << "[SERVER] Starting on 0.0.0.0:9999\n";

#ifdef _WIN32
    WSADATA wsa;
    WSAStartup(MAKEWORD(2,2), &wsa);
#endif

    int srv = static_cast<int>(socket(AF_INET, SOCK_STREAM, 0));
    int opt = 1;
    setsockopt(srv, SOL_SOCKET, SO_REUSEADDR,
               reinterpret_cast<const char*>(&opt), sizeof(opt));

    // ── DoS защита: таймаут 30 секунд на accept ──
    struct timeval tv = {30, 0};
    setsockopt(srv, SOL_SOCKET, SO_RCVTIMEO,
               reinterpret_cast<const char*>(&tv), sizeof(tv));

    sockaddr_in addr{};
    addr.sin_family      = AF_INET;
    addr.sin_port        = htons(9999);
    addr.sin_addr.s_addr = INADDR_ANY;
    bind(srv, reinterpret_cast<sockaddr*>(&addr), sizeof(addr));
    listen(srv, 1); // Максимум 1 в очереди
    std::cout << "[SERVER] Waiting for client (timeout 30s)...\n";

    sockaddr_in cli_addr{};
    socklen_t cli_len = sizeof(cli_addr);
    int cli = static_cast<int>(
        accept(srv, reinterpret_cast<sockaddr*>(&cli_addr), &cli_len));

    if (cli < 0) {
        std::cout << "[SERVER] Timeout or error — no client connected\n";
#ifdef _WIN32
        closesocket(srv); WSACleanup();
#else
        close(srv);
#endif
        return 1;
    }

    char ip_buf[INET_ADDRSTRLEN];
    inet_ntop(AF_INET, &cli_addr.sin_addr, ip_buf, sizeof(ip_buf));
    std::cout << "[SERVER] Client connected: " << ip_buf << "\n";

    // ── ECDH Handshake ──
    Crypto::KeyExchange kex;
    auto server_pub = kex.export_public_key();

    // TOFU fingerprint — показываем всегда, пользователь сравнивает
    std::cout << "[SERVER] My fingerprint:\n  " << kex.get_fingerprint() << "\n";

    auto client_pub = recv_frame(cli);
    send_frame(cli, server_pub);

    auto aes_key = kex.compute_shared_key(client_pub);

    // HMAC подтверждение
    auto srv_confirm = Crypto::KeyExchange::compute_session_confirmation(
                           aes_key, "server_confirm");
    send_frame(cli, srv_confirm);

    auto cli_confirm_recv     = recv_frame(cli);
    auto cli_confirm_expected = Crypto::KeyExchange::compute_session_confirmation(
                                    aes_key, "client_confirm");

    if (cli_confirm_recv != cli_confirm_expected) {
        std::cerr << "[SERVER] HMAC MISMATCH — possible MitM! Abort.\n";
#ifdef _WIN32
        closesocket(cli); closesocket(srv); WSACleanup();
#else
        close(cli); close(srv);
#endif
        return 1;
    }

    std::cout << "[SERVER] HMAC confirmed — no MitM detected\n";

// ── Shared key ТОЛЬКО в Debug сборке ──
#ifndef NDEBUG
    std::cout << "[SERVER][DEBUG] Shared key: ";
    for (auto b : aes_key) printf("%02x", b);
    std::cout << "\n";
#endif

    std::cout << "[SERVER] Handshake complete!\n\n";

    LibCryptSafe::Cryptor cryptor(aes_key);
    OPENSSL_cleanse(aes_key.data(), aes_key.size());

    std::cout << "[SERVER] Waiting for messages...\n";
    while (true) {
        std::vector<uint8_t> frame;
        try { frame = recv_frame(cli); }
        catch (...) { std::cout << "[SERVER] Client disconnected\n"; break; }

        auto plain = cryptor.decrypt(frame);
        std::cout << "[SERVER] Decrypted: ";
        for (auto c : plain) std::cout << static_cast<char>(c);
        std::cout << "\n";
    }

#ifdef _WIN32
    closesocket(cli); closesocket(srv); WSACleanup();
#else
    close(cli); close(srv);
#endif
    return 0;
}
