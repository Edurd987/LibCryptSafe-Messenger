#include "MessengerApp/KeyExchange.hpp"
#include <cstdio>
using namespace Crypto;

static std::string hex(const std::vector<uint8_t>& v) {
    std::string s; char b[3];
    for (auto c : v) { snprintf(b, 3, "%02x", c); s += b; }
    return s;
}

int main() {
    // ── ключи Боба (в его SQLCipher) ──
    KeyExchange IK_B, SPK_B, OPK_B;
    // ── ключи Алисы ──
    KeyExchange IK_A;

    printf("=== ТЕСТ 1: полный X3DH (с OPK) ===\n");
    // АЛИСА: строит сессию из публичных ключей Боба (полученных с relay)
    auto alice = KeyExchange::x3dh_initiator(
        IK_A.export_private_key(),
        IK_B.export_public_key(),
        SPK_B.export_public_key(),
        OPK_B.export_public_key());

    // БОБ: получил первое сообщение (EK_A_pub, IK_A_pub), достал свои приватные
    auto bob = KeyExchange::x3dh_responder(
        IK_B.export_private_key(),    // свой IK_DH
        SPK_B.export_private_key(),   // свой SPK
        IK_A.export_public_key(),     // IK_A из сообщения
        alice.ek_pub,                 // EK_A из сообщения
        OPK_B.export_private_key());  // свой OPK (по opk_id)

    printf("Алиса Kenc:  %s\n", hex(alice.k_enc).c_str());
    printf("Боб   Kenc:  %s\n", hex(bob.k_enc).c_str());
    bool ok1 = (alice.k_enc == bob.k_enc) && (alice.k_auth == bob.k_auth);
    printf("%s\n\n", ok1 ? ">>> КЛЮЧИ СОШЛИСЬ (X3DH замкнут!)" : ">>> ПРОВАЛ!");

    printf("=== ТЕСТ 2: graceful degradation (без OPK) ===\n");
    auto alice3 = KeyExchange::x3dh_initiator(
        IK_A.export_private_key(),
        IK_B.export_public_key(),
        SPK_B.export_public_key());   // без OPK
    auto bob3 = KeyExchange::x3dh_responder(
        IK_B.export_private_key(),
        SPK_B.export_private_key(),
        IK_A.export_public_key(),
        alice3.ek_pub);               // без OPK
    bool ok2 = (alice3.k_enc == bob3.k_enc);
    printf("%s\n\n", ok2 ? ">>> СОШЛИСЬ (degradation работает)" : ">>> ПРОВАЛ!");

    printf("=== ТЕСТ 3: чужой OPK -> ключи НЕ сходятся ===\n");
    KeyExchange WRONG_OPK;
    auto bob_wrong = KeyExchange::x3dh_responder(
        IK_B.export_private_key(),
        SPK_B.export_private_key(),
        IK_A.export_public_key(),
        alice.ek_pub,
        WRONG_OPK.export_private_key());   // НЕ тот OPK
    bool ok3 = (bob_wrong.k_enc != alice.k_enc);
    printf("%s\n\n", ok3 ? ">>> НЕ СОШЛИСЬ (верно: важен правильный OPK)" : ">>> ПРОВАЛ!");

    printf("=== ТЕСТ 4: подмена EK (атака) -> НЕ сходятся ===\n");
    KeyExchange EVE_EK;
    auto bob_eve = KeyExchange::x3dh_responder(
        IK_B.export_private_key(),
        SPK_B.export_private_key(),
        IK_A.export_public_key(),
        EVE_EK.export_public_key(),        // подменённый EK
        OPK_B.export_private_key());
    bool ok4 = (bob_eve.k_enc != alice.k_enc);
    printf("%s\n\n", ok4 ? ">>> НЕ СОШЛИСЬ (подмена EK отсекается)" : ">>> ПРОВАЛ!");

    bool all = ok1 && ok2 && ok3 && ok4;
    printf("====================================\n");
    printf("%s\n", all ? "X3DH ЗАМКНУТ: initiator + responder согласованы"
                       : "ЕСТЬ ПРОВАЛЫ - РАЗБИРАТЬ");
    return all ? 0 : 1;
}
