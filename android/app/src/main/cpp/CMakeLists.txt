cmake_minimum_required(VERSION 3.10)
project(cryptosafe)

set(CMAKE_CXX_STANDARD 17)

# Путь к корню проекта (5 уровней вверх от cpp/)
get_filename_component(CORE_DIR
    ${CMAKE_CURRENT_SOURCE_DIR}/../../../../../..
    ABSOLUTE)

add_library(cryptosafe SHARED
    jni_bridge.cpp
    ${CORE_DIR}/LibCryptSafe/src/Cryptor.cpp
    ${CORE_DIR}/TransportLayer/local_mesh.cpp
    ${CORE_DIR}/TransportLayer/direct_p2p.cpp
    ${CORE_DIR}/TransportLayer/tor_tunnel.cpp
)

target_include_directories(cryptosafe PRIVATE
    ${CORE_DIR}/LibCryptSafe/include
    ${CORE_DIR}/MessengerApp
    ${CORE_DIR}/TransportLayer
)

find_package(OpenSSL REQUIRED)
target_link_libraries(cryptosafe
    OpenSSL::Crypto
    log
    android
)
