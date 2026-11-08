package com.libcryptsafe

import java.security.MessageDigest
import java.security.SecureRandom

// №48 шаг 3: честный бросок — случайность из ДВУХ половин (Блюм, «монета по телефону»).
//   A (бросает): sA (32 Б), шлёт ROLL_COMMIT{h = SHA256(sA)}
//   B (соперник): sB (32 Б), шлёт ROLL_SEED{sB} открыто
//   A: шлёт ROLL_REVEAL{sA}; B проверяет SHA256(sA) == h
//   оба: кости = derive(sA, sB, gameId, rollSeq)
// A зафиксировал sA до того, как увидел sB; B выбрал sB, не зная sA -> кости не выбирает никто.
// gameId + rollSeq в хеше -> старый бросок нельзя подсунуть повторно.
// Ограничение: A узнаёт результат раньше B и может не раскрыть — это ловит таймаут (UI).
object DiceProtocol {
    const val VERSION = 2                        // версия протокола броска; едет в INVITE/ACCEPT
    const val SECRET_BYTES = 32
    const val REVEAL_TIMEOUT_MS = 60_000L
    private val rnd = SecureRandom()
    private val HEX64 = Regex("^[0-9a-f]{64}$")

    fun newSecret(): ByteArray = ByteArray(SECRET_BYTES).also { rnd.nextBytes(it) }
    fun commit(secret: ByteArray): String = sha256(secret).toHex()
    fun isHex64(s: String) = HEX64.matches(s)
    fun verify(secret: ByteArray, commitHex: String): Boolean =
        secret.size == SECRET_BYTES && isHex64(commitHex) &&
            MessageDigest.isEqual(sha256(secret), commitHex.fromHex())

    /** Две кости 1..6 из общей случайности. Байты >= 252 отбрасываются (252 = 42*6) -> равномерно. */
    fun derive(sA: ByteArray, sB: ByteArray, gameId: String, rollSeq: Int): Pair<Int, Int> {
        require(sA.size == SECRET_BYTES && sB.size == SECRET_BYTES)
        val base = sA + sB + gameId.toByteArray(Charsets.UTF_8) +
            byteArrayOf((rollSeq ushr 24).toByte(), (rollSeq ushr 16).toByte(), (rollSeq ushr 8).toByte(), rollSeq.toByte())
        val out = ArrayList<Int>(2)
        var counter = 0
        while (out.size < 2) {                       // вероятность нужды во втором блоке ~ 0
            val block = sha256(base + byteArrayOf(counter.toByte()))
            for (bt in block) {
                val v = bt.toInt() and 0xFF
                if (v < 252) { out += v % 6 + 1; if (out.size == 2) break }
            }
            counter++
        }
        return out[0] to out[1]
    }

    fun fromHexOrNull(s: String): ByteArray? = if (isHex64(s)) s.fromHex() else null
    fun hex(b: ByteArray): String = b.toHex()

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)
    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
    private fun String.fromHex() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
