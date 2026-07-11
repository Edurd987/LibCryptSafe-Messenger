#include "MessengerApp/KeyExchange.hpp"
#include <cstdio>
using namespace Crypto;

static std::string hex(const std::vector<uint8_t>& v) {
    std::string s; char b[3];
    for (auto c : v) { snprintf(b, 3, "%02x", c); s += b; }
    return s;
}

int main() {
    // ── ключи сторон (по спеке X3DH_DESIGN.md) ──
    KeyExchange IK_A;    // identity Алисы (DH-компонент)
    KeyExchange EK_A;    // эфемерный Алисы
    KeyExchange IK_B;    // identity Боба
    KeyExchange SPK_B;   // signed prekey Боба
    KeyExchange OPK_B;   // one-time prekey Боба

    // ═══ АЛИСА: своими приватными ↔ публичными Боба ═══
    auto a_dh1 = IK_A.compute_raw_dh(SPK_B.export_public_key());  // IK_A ↔ SPK_B
    auto a_dh2 = EK_A.compute_raw_dh(IK_B.export_public_key());   // EK_A ↔ IK_B
    auto a_dh3 = EK_A.compute_raw_dh(SPK_B.export_public_key());  // EK_A ↔ SPK_B
    auto a_dh4 = EK_A.compute_raw_dh(OPK_B.export_public_key());  // EK_A ↔ OPK_B
    auto alice = KeyExchange::derive_x3dh_session_keys(a_dh1, a_dh2, a_dh3, a_dh4);

    // ═══ БОБ: своими приватными ↔ публичными Алисы (Р6, коммутативность) ═══
    auto b_dh1 = SPK_B.compute_raw_dh(IK_A.export_public_key());  // SPK_B ↔ IK_A
    auto b_dh2 = IK_B.compute_raw_dh(EK_A.export_public_key());   // IK_B ↔ EK_A
    auto b_dh3 = SPK_B.compute_raw_dh(EK_A.export_public_key());  // SPK_B ↔ EK_A
    auto b_dh4 = OPK_B.compute_raw_dh(EK_A.export_public_key());  // OPK_B ↔ EK_A
    auto bob = KeyExchange::derive_x3dh_session_keys(b_dh1, b_dh2, b_dh3, b_dh4);

    printf("=== ТЕСТ 1: полный X3DH (DH1-DH4, с OPK) ===\n");
    printf("Алиса Kenc: %s\n", hex(alice.k_enc).c_str());
    printf("Боб   Kenc: %s\n", hex(bob.k_enc).c_str());
    bool ok1 = (alice.k_enc == bob.k_enc) && (alice.k_auth == bob.k_auth);
    printf("%s\n\n", ok1 ? ">>> СОШЛОСЬ (симметрия есть)" : ">>> ПРОВАЛ!");

    // ═══ ТЕСТ 2: graceful degradation (без OPK, DH1-DH3) ═══
    auto alice3 = KeyExchange::derive_x3dh_session_keys(a_dh1, a_dh2, a_dh3);
    auto bob3   = KeyExchange::derive_x3dh_session_keys(b_dh1, b_dh2, b_dh3);
    printf("=== ТЕСТ 2: graceful degradation (DH1-DH3, opk=null) ===\n");
    bool ok2 = (alice3.k_enc == bob3.k_enc) && (alice3.k_auth == bob3.k_auth);
    printf("%s\n\n", ok2 ? ">>> СОШЛОСЬ (degradation работает)" : ">>> ПРОВАЛ!");

    // ═══ ТЕСТ 3: ключи с OPK и без — РАЗНЫЕ (иначе OPK бесполезен) ═══
    bool ok3 = (alice.k_enc != alice3.k_enc);
    printf("=== ТЕСТ 3: DH4 реально влияет на ключ ===\n");
    printf("%s\n\n", ok3 ? ">>> РАЗНЫЕ (OPK влияет — верно)" : ">>> ПРОВАЛ: OPK не влияет!");

    // ═══ ТЕСТ 4: Kenc != Kauth (разные info-метки) ═══
    bool ok4 = (alice.k_enc != alice.k_auth);
    printf("=== ТЕСТ 4: Kenc и Kauth различны ===\n");
    printf("%s\n\n", ok4 ? ">>> РАЗНЫЕ (info-разделение работает)" : ">>> ПРОВАЛ!");

    // ═══ ТЕСТ 5: чужой ключ -> НЕ сходится (защита от подмены) ═══
    KeyExchange EVE;
    auto e_dh1 = EVE.compute_raw_dh(SPK_B.export_public_key());
    auto eve = KeyExchange::derive_x3dh_session_keys(e_dh1, a_dh2, a_dh3, a_dh4);
    bool ok5 = (eve.k_enc != bob.k_enc);
    printf("=== ТЕСТ 5: подмена IK_A (Ева) -> другой ключ ===\n");
    printf("%s\n\n", ok5 ? ">>> НЕ СОШЛОСЬ (аутентификация работает)" : ">>> ПРОВАЛ: подмена прошла!");

    bool all = ok1 && ok2 && ok3 && ok4 && ok5;
    printf("════════════════════════════════════\n");
    printf("%s\n", all ? "ВСЕ 5 ТЕСТОВ ПРОЙДЕНЫ — X3DH КОРРЕКТЕН" : "ЕСТЬ ПРОВАЛЫ — РАЗБИРАТЬ");
    return all ? 0 : 1;
}
