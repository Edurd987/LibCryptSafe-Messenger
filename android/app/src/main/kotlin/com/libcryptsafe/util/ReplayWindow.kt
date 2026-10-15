package com.libcryptsafe.util

// Анти-replay окно чата. ИНВАРИАНТ: MAX_AGE < RETENTION, иначе после очистки nonce
// старый пакет можно повторить. X3DH-записи (x3dh:...) не удаляются никогда.
object ReplayWindow {
    const val RETENTION_MS = 30L * 24 * 3600 * 1000   // хранение nonce чата
    const val MAX_AGE_MS   = 28L * 24 * 3600 * 1000   // старше -> отвергаем по ts
}
