#include "MessengerApp/KeyExchange.hpp"
#include "LibCryptSafe/include/Cryptor.h"
#include <cstdio>
using namespace Crypto;

static std::string hex(const std::vector<uint8_t>& v){std::string s;char b[3];for(auto c:v){snprintf(b,3,"%02x",c);s+=b;}return s;}

int main() {
    int passed = 0, failed = 0;
    auto check = [&](bool ok, const char* name){
        printf("%s %s\n", ok?"[ЗАЩИЩЕНО]":"[ПРОБОЙ!]", name);
        if(ok) passed++; else failed++;
    };

    // готовим честную сессию Алиса->Боб
    KeyExchange IK_B, SPK_B, OPK_B, IK_A;
    auto alice = KeyExchange::x3dh_initiator(IK_A.export_private_key(),
        IK_B.export_public_key(), SPK_B.export_public_key(), OPK_B.export_public_key());
    auto bob = KeyExchange::x3dh_responder(IK_B.export_private_key(),
        SPK_B.export_private_key(), IK_A.export_public_key(), alice.ek_pub, OPK_B.export_private_key());

    printf("=== БАЗА: честная сессия ===\n");
    check(alice.k_enc == bob.k_enc, "Алиса и Боб получили общий ключ");

    // === АТАКА 1: подмена EK злоумышленником ===
    printf("\n=== АТАКА 1: MITM подменяет эфемерный ключ ===\n");
    KeyExchange EVE;
    auto bob_mitm = KeyExchange::x3dh_responder(IK_B.export_private_key(),
        SPK_B.export_private_key(), IK_A.export_public_key(), EVE.export_public_key(), OPK_B.export_private_key());
    check(bob_mitm.k_enc != alice.k_enc, "подменённый EK -> ключи расходятся (атака видна)");

    // === АТАКА 2: tamper шифртекста (порча одного байта) ===
    printf("\n=== АТАКА 2: искажение шифртекста (bit-flip) ===\n");
    LibCryptSafe::Cryptor cA(alice.k_enc);
    auto plaintext = std::vector<uint8_t>{'S','e','c','r','e','t',' ','m','s','g'};
    auto cipher = cA.encrypt(plaintext);
    // портим байт в середине шифртекста
    auto tampered = cipher;
    tampered[cipher.size()/2] ^= 0x01;
    LibCryptSafe::Cryptor cB(bob.k_enc);
    bool rejected = false;
    try { cB.decrypt(tampered); } catch(...) { rejected = true; }
    check(rejected, "искажённый шифр отвергнут GCM-tag (целостность)");

    // === АТАКА 3: подмена tag ===
    printf("\n=== АТАКА 3: подмена GCM-tag ===\n");
    auto badtag = cipher;
    badtag[cipher.size()-1] ^= 0xFF;  // портим последний байт (tag)
    bool tagreject = false;
    try { cB.decrypt(badtag); } catch(...) { tagreject = true; }
    check(tagreject, "подделка tag отвергнута");

    // === АТАКА 4: чужой ключ не расшифровывает ===
    printf("\n=== АТАКА 4: расшифровка чужим ключом ===\n");
    KeyExchange RANDOM;
    LibCryptSafe::Cryptor cEve(RANDOM.export_public_key());  // случайный ключ (91б обрежется? нет - нужен 32)
    // берём честный 32-байтный чужой ключ
    std::vector<uint8_t> wrongKey(32, 0xAB);
    LibCryptSafe::Cryptor cWrong(wrongKey);
    bool wrongreject = false;
    try { cWrong.decrypt(cipher); } catch(...) { wrongreject = true; }
    check(wrongreject, "чужой ключ не расшифровывает (конфиденциальность)");

    // === АТАКА 5: уникальность эфемерных ключей (энтропия) ===
    printf("\n=== АТАКА 5: предсказуемость EK (1000 генераций) ===\n");
    std::vector<std::vector<uint8_t>> eks;
    bool anydup = false;
    for(int i=0;i<1000;i++){
        auto a = KeyExchange::x3dh_initiator(IK_A.export_private_key(),
            IK_B.export_public_key(), SPK_B.export_public_key(), OPK_B.export_public_key());
        for(auto& e : eks) if(e == a.ek_pub){ anydup = true; break; }
        eks.push_back(a.ek_pub);
        if(anydup) break;
    }
    check(!anydup, "1000 EK уникальны (нет предсказуемости/повторов)");

    // === АТАКА 6: nonce не повторяется (AES-GCM) ===
    printf("\n=== АТАКА 6: повтор nonce в шифровании ===\n");
    auto c1 = cA.encrypt(plaintext);
    auto c2 = cA.encrypt(plaintext);
    // nonce - первые 12 байт
    bool noncediff = false;
    for(int i=0;i<12;i++) if(c1[i]!=c2[i]){noncediff=true;break;}
    check(noncediff, "nonce случайный каждый раз (нет reuse -> нет утечки)");

    printf("\n========================================\n");
    printf("ЗАЩИЩЕНО: %d | ПРОБОЕВ: %d\n", passed, failed);
    printf("%s\n", failed==0 ? "КРИПТОМОДУЛЬ УСТОЯЛ ПРОТИВ ВСЕХ АТАК" : "!!! ЕСТЬ ПРОБОИ - РАЗБИРАТЬ !!!");
    return failed==0 ? 0 : 1;
}
