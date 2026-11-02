# Фаззинг нативного кода (№14)

## fuzz_decrypt (Linux / macOS, clang)
    clang++ -std=c++17 -g -O1 -fsanitize=fuzzer,address -ILibCryptSafe/include \
      tests/fuzz/fuzz_decrypt.cpp LibCryptSafe/src/Cryptor.cpp -lcrypto -o fuzz_decrypt
    mkdir -p corpus && ./fuzz_decrypt -max_total_time=60 -max_len=200 corpus

## Слепое пятно ASan (урок №40, 2026-11-02)
ASan видит только код, собранный с ASan. Системный OpenSSL собран без него, поэтому
чтение за границей ВНУТРИ OpenSSL ASan не ловит: 2.1 млн прогонов fuzz_decrypt -
"чисто", хотя ключ 1 байт заставлял EVP_CipherInit_ex читать 32.
valgrind видит любой код:
    ==532== Invalid read of size 16 ... EVP_CipherInit_ex ... Cryptor::decrypt (Cryptor.cpp:74)
Правило: если данные уходят в OpenSSL - проверять ещё и valgrind
(или собрать OpenSSL с -fsanitize=address).

## Регрессия №40 (Windows, CLANG64)
    /clang64/bin/clang++ -std=c++17 -I. -ILibCryptSafe/include tests/test_crypto_attacks.cpp \
      LibCryptSafe/src/Cryptor.cpp -lcrypto -o ~/tca.exe && PATH=/clang64/bin:$PATH ~/tca.exe
    ожидаем: [ЗАЩИЩЕНО] ключ не 32 байта отвергнут (№40), ПРОБОЕВ: 0
