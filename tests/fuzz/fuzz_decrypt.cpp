// №14/№40: libFuzzer для Cryptor::decrypt(key, blob) - как CryptoManager.decryptWithKey,
// куда ключ (ephKey из чужого INIT) и блоб приходят с недоверенной стороны.
#include "Cryptor.h"
#include <cstdint>
#include <vector>
#include <stdexcept>
extern "C" int LLVMFuzzerTestOneInput(const uint8_t* d, size_t n) {
    if (n < 1) return 0;
    size_t klen = d[0] % 64;                 // длина ключа 0..63 (ожидается 32)
    if (n < 1 + klen) return 0;
    std::vector<uint8_t> key(d + 1, d + 1 + klen), blob(d + 1 + klen, d + n);
    try { LibCryptSafe::Cryptor c(key); auto p = c.decrypt(blob); (void)p; } catch (const std::exception&) {}
    return 0;
}
