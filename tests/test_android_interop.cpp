#include "MessengerApp/KeyExchange.hpp"
#include <cstdio>
#include <string>
using namespace Crypto;

static std::vector<uint8_t> unhex(const std::string& h) {
    std::vector<uint8_t> v;
    for (size_t i = 0; i + 1 < h.size(); i += 2)
        v.push_back(static_cast<uint8_t>(std::stoi(h.substr(i, 2), nullptr, 16)));
    return v;
}

int main(int argc, char** argv) {
    if (argc != 4) { printf("usage: %s <pub_hex> <data_hex> <sig_hex>\n", argv[0]); return 2; }
    auto pub  = unhex(argv[1]);
    auto data = unhex(argv[2]);
    auto sig  = unhex(argv[3]);

    printf("pubkey: %zu байт | data: %zu байт | sig: %zu байт\n\n",
           pub.size(), data.size(), sig.size());

    bool ok = KeyExchange::verify_signature(pub, data, sig);

    printf("═══════════════════════════════════════════\n");
    if (ok) {
        printf(">>> ПОДПИСЬ С ANDROID KEYSTORE (TEE) ПРИНЯТА OpenSSL\n");
        printf(">>> Развилка 1 подтверждена ФАКТОМ: форматы совместимы\n");
    } else {
        printf(">>> ОТВЕРГНУТА — форматы НЕ совместимы, разбирать\n");
    }
    return ok ? 0 : 1;
}
