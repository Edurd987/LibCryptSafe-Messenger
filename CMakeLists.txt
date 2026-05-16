
cmake_minimum_required(VERSION 3.10)
project(LibCryptSafe-Messenger VERSION 1.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_package(OpenSSL REQUIRED)

# ── Модуль 1: LibCryptSafe ──
add_library(CryptSafe STATIC
    LibCryptSafe/src/Cryptor.cpp
)
target_include_directories(CryptSafe PUBLIC LibCryptSafe/include)
target_link_libraries(CryptSafe PUBLIC OpenSSL::Crypto)

# ── Модуль 2: TransportLayer ──
add_library(TransportLayer STATIC
    TransportLayer/local_mesh.cpp
    TransportLayer/direct_p2p.cpp
    TransportLayer/tor_tunnel.cpp
)
target_include_directories(TransportLayer PUBLIC TransportLayer)
if(WIN32)
    target_link_libraries(TransportLayer PUBLIC ws2_32)
endif()

# ── Модуль 3: crypto_test ──
add_executable(crypto_test MessengerApp/crypto_test.cpp)
target_link_libraries(crypto_test PRIVATE CryptSafe)

# ── Модуль 4: transport_test ──
add_executable(transport_test MessengerApp/transport_test.cpp)
target_link_libraries(transport_test PRIVATE TransportLayer CryptSafe)

# ── Модуль 5: cm_test ──
add_executable(cm_test MessengerApp/connection_manager_test.cpp)
target_include_directories(cm_test PRIVATE
    TransportLayer LibCryptSafe/include MessengerApp)
target_link_libraries(cm_test PRIVATE TransportLayer CryptSafe)

# ── Модуль 6: keyexchange_test ──
add_executable(keyexchange_test MessengerApp/keyexchange_test.cpp)
target_include_directories(keyexchange_test PRIVATE
    MessengerApp LibCryptSafe/include)
target_link_libraries(keyexchange_test PRIVATE CryptSafe)

# ── Модуль 7: server + client ──
add_executable(server MessengerApp/server.cpp)
target_include_directories(server PRIVATE MessengerApp LibCryptSafe/include)
target_link_libraries(server PRIVATE CryptSafe TransportLayer)

add_executable(client MessengerApp/client.cpp)
target_include_directories(client PRIVATE MessengerApp LibCryptSafe/include)
target_link_libraries(client PRIVATE CryptSafe TransportLayer)

# ── Модуль 8: engine_test ──
add_executable(engine_test MessengerApp/engine_test.cpp)
target_include_directories(engine_test PRIVATE
    MessengerApp LibCryptSafe/include)
target_link_libraries(engine_test PRIVATE CryptSafe)
