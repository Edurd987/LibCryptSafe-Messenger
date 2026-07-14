#include "MessengerApp/KeyExchange.hpp"
#include <cstdio>
using namespace Crypto;

static std::string hex(const std::vector<uint8_t>& v) {
    std::string s; char b[3];
    for (auto c : v) { snprintf(b, 3, "%02x", c); s += b; }
    return s;
}

int main() {
    // ── ключи Боба (получатель) ──
    KeyExchange IK_B;    // identity-DH Боба
    KeyExchange SPK_B;
    KeyExchange OPK_B;

    // ── ключи Алисы (инициатор) ──
    KeyExchange IK_A;    // identity-DH Алисы

    // ═══ АЛИСА через новый x3dh_initiator ═══
    auto alice = KeyExchange::x3dh_initiator(
        IK_A.export_private_key(),   // наш identity-DH priv
        IK_B.export_public_key(),    // peer IK_DH
        SPK_B.export_public_key(),   // peer SPK
        OPK_B.export_public_key()    // peer OPK
    );
    printf("Алиса Kenc: %s\n", hex(alice.k_enc).c_str());
    printf("EK_A_pub:   %zu байт\n", alice.ek_pub.size());

    // ═══ БОБ вручную (повторяет DH, используя EK_A_pub от Алисы) ═══
    // восстанавливаем ключи Боба из их приватных (как будто из БД)
    KeyExchange ek_a_pub_holder(IK_B.export_private_key()); // заглушка, нужен только pub Алисы
    // Боб считает по формулам b_dh (из теста симметрии):
    auto b_dh1 = SPK_B.compute_raw_dh(IK_A.export_public_key());   // SPK_B ↔ IK_A
    auto b_dh2 = IK_B.compute_raw_dh(alice.ek_pub);                // IK_B ↔ EK_A
    auto b_dh3 = SPK_B.compute_raw_dh(alice.ek_pub);               // SPK_B ↔ EK_A
    auto b_dh4 = OPK_B.compute_raw_dh(alice.ek_pub);               // OPK_B ↔ EK_A
    auto bob = KeyExchange::derive_x3dh_session_keys(b_dh1, b_dh2, b_dh3, b_dh4);
    printf("Боб   Kenc: %s\n\n", hex(bob.k_enc).c_str());

    bool ok1 = (alice.k_enc == bob.k_enc) && (alice.k_auth == bob.k_auth);
    printf("ТЕСТ 1 (Алиса-инициатор == Боб): %s\n", ok1 ? ">>> СОШЛОСЬ" : ">>> ПРОВАЛ!");

    // ═══ ТЕСТ 2: graceful degradation (без OPK) ═══
    auto alice3 = KeyExchange::x3dh_initiator(
        IK_A.export_private_key(),
        IK_B.export_public_key(),
        SPK_B.export_public_key()
        // без OPK
    );
    auto b3_dh1 = SPK_B.compute_raw_dh(IK_A.export_public_key());
    auto b3_dh2 = IK_B.compute_raw_dh(alice3.ek_pub);
    auto b3_dh3 = SPK_B.compute_raw_dh(alice3.ek_pub);
    auto bob3 = KeyExchange::derive_x3dh_session_keys(b3_dh1, b3_dh2, b3_dh3);
    bool ok2 = (alice3.k_enc == bob3.k_enc);
    printf("ТЕСТ 2 (без OPK, DH1-DH3): %s\n", ok2 ? ">>> СОШЛОСЬ" : ">>> ПРОВАЛ!");

    // ═══ ТЕСТ 3: каждый вызов даёт новый EK (уникальность) ═══
    auto a1 = KeyExchange::x3dh_initiator(IK_A.export_private_key(),
        IK_B.export_public_key(), SPK_B.export_public_key(), OPK_B.export_public_key());
    auto a2 = KeyExchange::x3dh_initiator(IK_A.export_private_key(),
        IK_B.export_public_key(), SPK_B.export_public_key(), OPK_B.export_public_key());
    bool ok3 = (a1.ek_pub != a2.ek_pub) && (a1.k_enc != a2.k_enc);
    printf("ТЕСТ 3 (новый EK каждый раз): %s\n", ok3 ? ">>> РАЗНЫЕ (верно)" : ">>> ПРОВАЛ!");

    bool all = ok1 && ok2 && ok3;
    printf("\n====================================\n");
    printf("%s\n", all ? "ВСЕ ПРОЙДЕНЫ - x3dh_initiator корректен" : "ЕСТЬ ПРОВАЛЫ");
    return all ? 0 : 1;
}
