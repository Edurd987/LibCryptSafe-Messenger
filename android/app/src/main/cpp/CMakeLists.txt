cmake_minimum_required(VERSION 3.10)
project(cryptosafe)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384")

# Абсолютный путь — работает на Windows/MSYS2
set(CORE_DIR "C:/msys64/home/79384/LibCryptSafe-Messenger")
set(OPENSSL_ROOT "${CORE_DIR}/android/android_openssl-master/ssl_3")

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
    ${OPENSSL_ROOT}/include
)

target_link_libraries(cryptosafe
    ${OPENSSL_ROOT}/${ANDROID_ABI}/libcrypto.a
    log
    android
)
