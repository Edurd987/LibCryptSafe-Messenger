// ============================================================================
//  LibCryptSafe — Media Transfer, BRICK 1 (Kotlin port of the proven C++)
// ----------------------------------------------------------------------------
//  Direct port of transfer_test.cpp: SAME assembly logic, SAME test scenario
//  (chaotic order + gaps + duplicates -> reassemble -> SHA-256). Proves the
//  Kotlin TransferManager behaves identically to the C++ original before it
//  ever goes near the Android project.
//
//  This is a STANDALONE kotlinc main — NO Android, NO gradle. Build/run:
//     kotlinc TransferTest.kt -include-runtime -d transfer.jar
//     kotlin transfer.jar        (or: java -jar transfer.jar)
//
//  Verification SHA-256 uses java.security.MessageDigest (built into the JDK,
//  nothing to install) — it is the CHECK, not the thing under test, same role
//  OpenSSL played in the C++ brick.
//
//  Scope: PURE assembly logic. NO crypto, NO socket, NO UI. The production
//  class in android/app/src/main/kotlin/com/libcryptsafe/ is a copy of the
//  TransferManager class below (minus the test harness).
// ============================================================================

import java.security.MessageDigest

// ---- Blueprint constants (mirror the paper design) -------------------------
const val CHUNK_SIZE = 64 * 1024                 // 64 KB
const val MAX_TRANSFER = 100L * 1024 * 1024      // 100 MB cap

// ---- ContentType taxonomy (lives INSIDE the ciphertext, client-only) -------
enum class ContentType(val id: Int) {
    TEXT(0),
    MEDIA_INIT(1),
    MEDIA_CHUNK(2),
    MEDIA_DONE(3),
    CONTROL(4),
    CALL_OFFER(10),   // beacon — not implemented
    CALL_ANSWER(11),  // beacon
    CALL_END(12)      // beacon
}

// ---- The three media control structs (INIT / CHUNK / DONE) -----------------
// transferId is 16 random bytes. We wrap it so it can be a Map key with
// value-equality (ByteArray uses identity equality in Kotlin, which would
// break the map — this is the one real gotcha porting from C++'s memcmp).
class TransferId(val bytes: ByteArray) {
    init { require(bytes.size == 16) { "transferId must be 16 bytes" } }
    override fun equals(other: Any?): Boolean =
        other is TransferId && bytes.contentEquals(other.bytes)
    override fun hashCode(): Int = bytes.contentHashCode()
}

class MediaInit(
    val transferId: TransferId,
    val mediaKind: Int,        // 0=PHOTO 1=VIDEO 2=VOICE 3=FILE (opaque here)
    val totalBytes: Long,
    val totalChunks: Int,
    val chunkSize: Int,
    val sha256Full: ByteArray  // hash of the WHOLE file
    // ephemeralKey(32B) would live here too — omitted: no crypto in brick 1
)

class MediaChunk(
    val transferId: TransferId,
    val seq: Int,
    val bytes: ByteArray
)

class MediaDone(val transferId: TransferId)

// ---- Receiver state: one bucket per in-flight transfer ---------------------
class TransferState(val init: MediaInit) {
    // UNORDERED assembly: sorted map seq -> chunk bytes. Chosen over an
    // ordered apply because file chunks are INDEPENDENT (unlike nardi seq,
    // where a gap must block because moves are causal).
    val chunks = sortedMapOf<Int, ByteArray>()
}

// ---- TransferManager: the unit under test ----------------------------------
class TransferManager {
    private val states = HashMap<TransferId, TransferState>()

    // Returns true if this INIT was accepted (passes the size cap).
    fun onInit(init: MediaInit): Boolean {
        if (init.totalBytes > MAX_TRANSFER) {
            println("  [REJECT] transfer ${init.totalBytes} bytes exceeds 100MB cap")
            return false
        }
        states[init.transferId] = TransferState(init)
        return true
    }

    // Drop a chunk into its bucket. Idempotent: a duplicate seq is ignored
    // (re-sends must not corrupt state).
    fun onChunk(c: MediaChunk) {
        val st = states[c.transferId] ?: return       // chunk before INIT: ignore
        if (st.chunks.containsKey(c.seq)) return       // duplicate: ignore
        st.chunks[c.seq] = c.bytes
    }

    // Which seqs are still missing? Empty list = complete.
    fun missing(id: TransferId): List<Int> {
        val st = states[id] ?: return emptyList()
        val miss = ArrayList<Int>()
        for (s in 0 until st.init.totalChunks)
            if (!st.chunks.containsKey(s)) miss.add(s)
        return miss
    }

    // Called on MEDIA_DONE. Returns the spliced file, or null if incomplete.
    fun onDone(id: TransferId): ByteArray? {
        val st = states[id] ?: return null
        if (missing(id).isNotEmpty()) return null      // gaps remain -> not done
        val out = java.io.ByteArrayOutputStream(st.init.totalBytes.toInt())
        for (s in 0 until st.init.totalChunks)
            out.write(st.chunks[s]!!)
        val file = out.toByteArray()
        return if (file.size.toLong() == st.init.totalBytes) file else null
    }
}

// ---- Helpers (test harness, NOT under test) --------------------------------
fun sha256(data: ByteArray): ByteArray =
    MessageDigest.getInstance("SHA-256").digest(data)

fun hex(b: ByteArray): String =
    b.joinToString("") { "%02x".format(it) }

// Sender side: split a whole file into 64KB chunks (last chunk short).
fun splitIntoChunks(file: ByteArray, id: TransferId): List<MediaChunk> {
    val out = ArrayList<MediaChunk>()
    var seq = 0
    var off = 0
    while (off < file.size) {
        val end = minOf(off + CHUNK_SIZE, file.size)
        out.add(MediaChunk(id, seq++, file.copyOfRange(off, end)))
        off += CHUNK_SIZE
    }
    return out
}

// ============================================================================
//  TEST
// ============================================================================
fun main() {
    println("=== BRICK 1 (Kotlin): chunk assembly (chaotic order + gaps) ===\n")

    val rng = java.util.Random(0xC0FFEE)  // fixed seed = reproducible

    // 1) Build a fake "photo": 300000 deterministic bytes.
    val FILE_SIZE = 300000
    val original = ByteArray(FILE_SIZE)
    rng.nextBytes(original)
    val origHash = sha256(original)
    println("Original: $FILE_SIZE bytes, sha256=${hex(origHash)}\n")

    // 2) Sender splits into chunks.
    val idBytes = ByteArray(16); rng.nextBytes(idBytes)
    val id = TransferId(idBytes)
    val allChunks = splitIntoChunks(original, id)
    println("Split into ${allChunks.size} chunks of $CHUNK_SIZE bytes (last short)")

    // 3) Build INIT.
    val init = MediaInit(id, 0, FILE_SIZE.toLong(), allChunks.size, CHUNK_SIZE, origHash)

    // 4) Receiver gets INIT.
    val mgr = TransferManager()
    if (!mgr.onInit(init)) { println("FAIL: INIT rejected"); kotlin.system.exitProcess(1) }

    // 5) Deliver chunks in CHAOTIC order, DROP ~40% on the first pass; force
    //    chunk 0 and the last chunk to be dropped to stress edge positions.
    val firstPass = allChunks.shuffled(rng)
    println("\n-- First pass: shuffled, ~40% dropped --")
    var delivered = 0
    for (c in firstPass) {
        var drop = (rng.nextInt(5) < 2)               // ~40% loss
        if (c.seq == 0 || c.seq == init.totalChunks - 1) drop = true
        if (drop) { println("  drop seq=${c.seq}"); continue }
        mgr.onChunk(c); delivered++
    }
    println("  delivered $delivered of ${init.totalChunks} on pass 1")

    // 6) DONE arrives early -> must report NOT complete + list gaps.
    val partial = mgr.onDone(id)
    val miss = mgr.missing(id)
    println("\n-- DONE #1 -> complete=${partial != null}, missing ${miss.size} chunks: ${miss.joinToString(" ")}")
    if (partial != null) { println("FAIL: returned bytes while incomplete!"); kotlin.system.exitProcess(1) }

    // 7) Re-send ONLY the missing chunks (the CONTROL{missing:[...]} refill),
    //    plus one DUPLICATE to prove idempotency doesn't corrupt.
    println("\n-- Refill: re-send missing (+ a duplicate to test idempotency) --")
    for (c in allChunks) if (c.seq in miss) { mgr.onChunk(c); println("  refill seq=${c.seq}") }
    allChunks.firstOrNull { it.seq == 1 }?.let { mgr.onChunk(it); println("  dup seq=1 (should be ignored)") }

    // 8) DONE again -> must be complete, and SHA-256 must match.
    val rebuilt = mgr.onDone(id)
    println("\n-- DONE #2 -> complete=${rebuilt != null}, rebuilt ${rebuilt?.size ?: 0} bytes --")
    if (rebuilt == null) { println("FAIL: still incomplete after refill"); kotlin.system.exitProcess(1) }

    val reHash = sha256(rebuilt)
    println("Rebuilt  sha256=${hex(reHash)}")
    println("Original sha256=${hex(origHash)}")

    if (!reHash.contentEquals(origHash)) {
        println("\n*** FAIL: SHA-256 MISMATCH — assembly is broken ***"); kotlin.system.exitProcess(1)
    }
    if (!rebuilt.contentEquals(original)) {
        println("\n*** FAIL: byte-for-byte mismatch despite hash?! ***"); kotlin.system.exitProcess(1)
    }
    println("\n=== PASS: chaotic+lossy chunks reassembled byte-for-byte, SHA-256 matches ===")

    // 9) Bonus guard: 100MB cap rejects an oversized INIT.
    val big = MediaInit(id, 0, MAX_TRANSFER + 1, 1, CHUNK_SIZE, origHash)
    if (TransferManager().onInit(big)) { println("FAIL: cap did not reject oversized"); kotlin.system.exitProcess(1) }
    println("Cap guard: oversized INIT correctly rejected.")
}
