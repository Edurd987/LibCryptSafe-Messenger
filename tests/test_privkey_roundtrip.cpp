#include "MessengerApp/KeyExchange.hpp"
#include <cstdio>
using namespace Crypto;

static std::string hex(const std::vector<uint8_t>& v) {
    std::string s; char b[3];
    for (auto c : v) { snprintf(b, 3, "%02x", c); s += b; }
    return s;
}

int main() {
    // 1) генерим пару, запоминаем её публичный ключ
    KeyExchange orig;
    auto orig_pub = orig.export_public_key();
    auto priv_der = orig.export_private_key();
    printf("приватный DER: %zu байт\n", priv_der.size());
    printf("публичный DER: %zu байт\n\n", orig_pub.size());

    // 2) восстанавливаем пару из приватного DER
    KeyExchange restored(priv_der);
    auto restored_pub = restored.export_public_key();

    // ТЕСТ 1: публичный ключ восстановленной пары == оригинал
    bool ok1 = (orig_pub == restored_pub);
    printf("ТЕСТ 1 (публичный ключ сохранился): %s\n",
           ok1 ? ">>> СОВПАЛ" : ">>> ПРОВАЛ!");

    // ТЕСТ 2: round-trip DH — оригинал и восстановленный дают ОДИН ключ
    // партнёр (Алиса), считаем DH с обеих версий Боба
    KeyExchange peer;
    auto peer_pub = peer.export_public_key();
    auto dh_orig     = orig.compute_raw_dh(peer_pub);
    auto dh_restored = restored.compute_raw_dh(peer_pub);
    bool ok2 = (dh_orig == dh_restored) && !dh_orig.empty();
    printf("ТЕСТ 2 (DH round-trip совпал): %s\n",
           ok2 ? ">>> СОВПАЛ (ключ восстановлен верно)" : ">>> ПРОВАЛ!");

    // ТЕСТ 3: битый DER -> исключение, не краш
    bool ok3 = false;
    try {
        std::vector<uint8_t> junk = {0x01, 0x02, 0x03};
        KeyExchange bad(junk);
    } catch (const std::exception&) { ok3 = true; }
    printf("ТЕСТ 3 (битый DER -> исключение): %s\n",
           ok3 ? ">>> ПОЙМАНО (без краха)" : ">>> ПРОВАЛ!");

    bool all = ok1 && ok2 && ok3;
    printf("\n====================================\n");
    printf("%s\n", all ? "ВСЕ ПРОЙДЕНЫ - экспорт/импорт приватного ключа корректен"
                       : "ЕСТЬ ПРОВАЛЫ - РАЗБИРАТЬ");
    return all ? 0 : 1;
}
