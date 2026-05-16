#ifndef LIBCRYPTSAFE_H
#define LIBCRYPTSAFE_H

#include <vector>
#include <cstdint>

namespace LibCryptSafe {

class Cryptor {
public:
    // Конструктор принимает вектор ключа (прямой доступ)
    Cryptor(const std::vector<uint8_t>& key);  // УБИРАЕМ параметр size_t
    ~Cryptor();
    std::vector<uint8_t> encrypt(const std::vector<uint8_t>& data) const;
    std::vector<uint8_t> decrypt(const std::vector<uint8_t>& data) const;

private:
    mutable std::vector<uint8_t> key_;
};

} // namespace LibCryptSafe

#endif // LIBCRYPTSAFE_H
