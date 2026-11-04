cmake_minimum_required(VERSION 3.10)
project(cryptosafe)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384")

# Абсолютный путь — работает на Windows/MSYS2
set(CORE_DIR "C:/msys64/home/79384/LibCryptSafe-Messenger")
# №44: OpenSSL 3.5.9 LTS, воспроизводимая сборка в CI (scripts/build_openssl_android.sh,
# паспорт android/openssl-3.5.9/OPENSSL_BUILD.txt). Хеш закреплён ЗДЕСЬ, отдельно от файла:
# подмена libcrypto.a = сборка APK падает (при конфигурации и перед каждой линковкой).
set(OPENSSL_ROOT "${CORE_DIR}/android/openssl-3.5.9")
set(LCS_LIBCRYPTO "${OPENSSL_ROOT}/${ANDROID_ABI}/libcrypto.a")
set(LCS_LIBCRYPTO_SHA256 "a4120f632707ed0d8c943f2dbed6e3d0165f4b1eeb1928943ed1760c29484301")
execute_process(COMMAND ${CMAKE_COMMAND} -DLIB=${LCS_LIBCRYPTO} -DEXPECT=${LCS_LIBCRYPTO_SHA256}
    -P ${CMAKE_CURRENT_LIST_DIR}/verify_sha256.cmake RESULT_VARIABLE _lcs_rc)
if(NOT _lcs_rc EQUAL 0)
    message(FATAL_ERROR "№44: проверка libcrypto.a не пройдена (см. выше)")
endif()
message(STATUS "№44: libcrypto.a sha256 OK (OpenSSL 3.5.9)")

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
    ${LCS_LIBCRYPTO}
    log
    android
)

# №44: повторная сверка перед каждой линковкой (если .a заменили без пересоздания CMake-кэша)
add_custom_command(TARGET cryptosafe PRE_LINK
    COMMAND ${CMAKE_COMMAND} -DLIB=${LCS_LIBCRYPTO} -DEXPECT=${LCS_LIBCRYPTO_SHA256}
            -P ${CMAKE_CURRENT_LIST_DIR}/verify_sha256.cmake)
