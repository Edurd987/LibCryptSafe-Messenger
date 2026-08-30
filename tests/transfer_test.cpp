// ============================================================================
//  LibCryptSafe — Media Transfer, BRICK 1: pure chunk-assembly logic
// ----------------------------------------------------------------------------
//  Proves ONLY the assembly math. NO crypto, NO socket, NO real file, NO UI.
//  We simulate chunks arriving in CHAOTIC order WITH gaps, detect the gaps,
//  "re-send" the missing ones, reassemble, and verify the SHA-256 matches
//  the original. If this passes offline, the Kotlin TransferManager is a
//  direct port of this exact logic.
//
//  Build (MSYS, same toolchain as the crypto tests):
//     g++ -std=c++17 -I/mingw64/include transfer_test.cpp \
//         -L/mingw64/lib -lssl -lcrypto -o transfer_test.exe
//     PATH="/mingw64/bin:$PATH" ./transfer_test.exe
//
//  OpenSSL is used ONLY for the verification SHA-256 (not the thing under
//  test) — same reason the crypto tests link it.
// ============================================================================

#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>
#include <map>
#include <random>
#include <algorithm>
#include <openssl/sha.h>

// ---- Blueprint constants (mirror the paper design) -------------------------
static const uint32_t CHUNK_SIZE = 64 * 1024;   // 64 KB
static const uint64_t MAX_TRANSFER = 100ull * 1024 * 1024; // 100 MB cap

// ---- The three media control structs (INIT / CHUNK / DONE) -----------------
// In production these live INSIDE the ciphertext; here we model them as plain
// structs to prove the assembly logic. transferId is 16 random bytes.

struct TransferId {
    uint8_t b[16];
    bool operator<(const TransferId& o) const {
        return std::memcmp(b, o.b, 16) < 0;
    }
    bool operator==(const TransferId& o) const {
        return std::memcmp(b, o.b, 16) == 0;
    }
};

struct MediaInit {
    TransferId transferId;
    uint8_t   mediaKind;      // 0=PHOTO 1=VIDEO 2=VOICE 3=FILE (opaque here)
    uint64_t  totalBytes;
    uint32_t  totalChunks;
    uint32_t  chunkSize;
    uint8_t   sha256_full[32];
    // ephemeralKey(32B) would live here too — omitted: no crypto in brick 1
};

struct MediaChunk {
    TransferId transferId;
    uint32_t   seq;
    std::vector<uint8_t> bytes;
};

struct MediaDone {
    TransferId transferId;
};

// ---- Receiver state: one bucket per in-flight transfer ---------------------
struct TransferState {
    MediaInit init;
    bool      haveInit = false;
    // UNORDERED assembly: sparse map seq -> chunk bytes.
    // Chosen over an ordered apply because file chunks are INDEPENDENT
    // (unlike nardi seq, where a gap must block because moves are causal).
    std::map<uint32_t, std::vector<uint8_t>> chunks;
};

// ---- TransferManager: the unit under test ----------------------------------
class TransferManager {
public:
    // Returns true if this INIT was accepted (passes the size cap).
    bool onInit(const MediaInit& init) {
        if (init.totalBytes > MAX_TRANSFER) {
            std::printf("  [REJECT] transfer %llu bytes exceeds 100MB cap\n",
                        (unsigned long long)init.totalBytes);
            return false;
        }
        TransferState st;
        st.init = init;
        st.haveInit = true;
        states_[init.transferId] = std::move(st);
        return true;
    }

    // Drop a chunk into its bucket. Idempotent: a duplicate seq is ignored
    // (re-sends must not corrupt state).
    void onChunk(const MediaChunk& c) {
        auto it = states_.find(c.transferId);
        if (it == states_.end()) return;          // chunk before INIT: ignore
        auto& st = it->second;
        if (st.chunks.count(c.seq)) return;        // duplicate: ignore
        st.chunks[c.seq] = c.bytes;
    }

    // Which seqs are still missing? Empty vector = complete.
    std::vector<uint32_t> missing(const TransferId& id) const {
        std::vector<uint32_t> miss;
        auto it = states_.find(id);
        if (it == states_.end() || !it->second.haveInit) return miss;
        const auto& st = it->second;
        for (uint32_t s = 0; s < st.init.totalChunks; ++s)
            if (!st.chunks.count(s)) miss.push_back(s);
        return miss;
    }

    // Called on MEDIA_DONE. If complete, splice the file and return it.
    // outOk reports completeness; caller checks SHA-256 separately.
    std::vector<uint8_t> onDone(const TransferId& id, bool& outOk) {
        outOk = false;
        std::vector<uint8_t> file;
        auto it = states_.find(id);
        if (it == states_.end() || !it->second.haveInit) return file;
        const auto& st = it->second;
        if (!missing(id).empty()) return file;     // gaps remain -> not done
        file.reserve(st.init.totalBytes);
        for (uint32_t s = 0; s < st.init.totalChunks; ++s) {
            const auto& part = st.chunks.at(s);
            file.insert(file.end(), part.begin(), part.end());
        }
        outOk = (file.size() == st.init.totalBytes);
        return file;
    }

private:
    std::map<TransferId, TransferState> states_;
};

// ---- Helpers (test harness, NOT under test) --------------------------------
static void sha256(const std::vector<uint8_t>& data, uint8_t out[32]) {
    SHA256(data.data(), data.size(), out);
}

static std::string hex(const uint8_t* p, size_t n) {
    static const char* h = "0123456789abcdef";
    std::string s;
    for (size_t i = 0; i < n; ++i) { s += h[p[i] >> 4]; s += h[p[i] & 15]; }
    return s;
}

// Split a whole "file" into MediaChunk list per the blueprint (64KB chunks,
// last chunk short). This is the SENDER side, also just harness.
static std::vector<MediaChunk> splitIntoChunks(const std::vector<uint8_t>& file,
                                               const TransferId& id) {
    std::vector<MediaChunk> out;
    uint32_t seq = 0;
    for (size_t off = 0; off < file.size(); off += CHUNK_SIZE) {
        MediaChunk c;
        c.transferId = id;
        c.seq = seq++;
        size_t end = std::min(off + CHUNK_SIZE, file.size());
        c.bytes.assign(file.begin() + off, file.begin() + end);
        out.push_back(std::move(c));
    }
    if (out.empty()) {  // zero-byte file edge case: one empty chunk? -> none
        // keep it simple: a 0-byte file has 0 chunks; onDone must handle it.
    }
    return out;
}

// ============================================================================
//  TEST
// ============================================================================
int main() {
    std::printf("=== BRICK 1: chunk assembly (chaotic order + gaps) ===\n\n");

    std::mt19937 rng(0xC0FFEE);  // fixed seed = reproducible test

    // 1) Build a fake "photo": 300000 bytes of deterministic pseudo-random.
    const size_t FILE_SIZE = 300000;   // ~293 KB -> 5 chunks (4x64KB + tail)
    std::vector<uint8_t> original(FILE_SIZE);
    for (size_t i = 0; i < FILE_SIZE; ++i)
        original[i] = (uint8_t)(rng() & 0xFF);

    uint8_t origHash[32];
    sha256(original, origHash);
    std::printf("Original: %zu bytes, sha256=%s\n\n",
                FILE_SIZE, hex(origHash, 32).c_str());

    // 2) Sender splits into chunks.
    TransferId id;
    for (int i = 0; i < 16; ++i) id.b[i] = (uint8_t)(rng() & 0xFF);
    auto allChunks = splitIntoChunks(original, id);
    std::printf("Split into %zu chunks of %u bytes (last short)\n",
                allChunks.size(), CHUNK_SIZE);

    // 3) Build INIT.
    MediaInit init;
    init.transferId = id;
    init.mediaKind = 0;                 // PHOTO
    init.totalBytes = FILE_SIZE;
    init.totalChunks = (uint32_t)allChunks.size();
    init.chunkSize = CHUNK_SIZE;
    std::memcpy(init.sha256_full, origHash, 32);

    // 4) Receiver gets INIT.
    TransferManager mgr;
    if (!mgr.onInit(init)) { std::printf("FAIL: INIT rejected\n"); return 1; }

    // 5) THE HARD PART: deliver chunks in CHAOTIC order, and DROP some on the
    //    first pass to simulate packet loss. Shuffle, then skip ~40%.
    std::vector<MediaChunk> firstPass = allChunks;
    std::shuffle(firstPass.begin(), firstPass.end(), rng);

    std::printf("\n-- First pass: shuffled, ~40%% dropped --\n");
    int delivered = 0;
    for (auto& c : firstPass) {
        // Deterministic "loss": drop if low bit pattern hits. Force at least
        // chunk 0 and the last chunk to be among the dropped on pass 1 to
        // stress edge positions.
        bool drop = (rng() % 5) < 2;               // ~40% loss
        if (c.seq == 0 || c.seq == init.totalChunks - 1) drop = true;
        if (drop) { std::printf("  drop seq=%u\n", c.seq); continue; }
        mgr.onChunk(c);
        delivered++;
    }
    std::printf("  delivered %d of %u on pass 1\n", delivered, init.totalChunks);

    // 6) DONE arrives early -> manager must report NOT complete + list gaps.
    bool ok = false;
    auto partial = mgr.onDone(id, ok);
    auto miss = mgr.missing(id);
    std::printf("\n-- DONE #1 -> ok=%s, missing %zu chunks: ",
                ok ? "true" : "false", miss.size());
    for (auto s : miss) std::printf("%u ", s);
    std::printf("\n");
    if (ok) { std::printf("FAIL: reported complete while gaps remain!\n"); return 1; }
    if (!partial.empty()) { std::printf("FAIL: returned bytes while incomplete!\n"); return 1; }

    // 7) Re-send ONLY the missing chunks (this is the CONTROL{missing:[...]}
    //    gap-refill path). Also re-send a couple as DUPLICATES to prove
    //    idempotency doesn't corrupt.
    std::printf("\n-- Refill: re-send missing (+ a duplicate to test idempotency) --\n");
    // duplicate an already-delivered chunk if any exists
    for (auto& c : allChunks) {
        bool isMissing = std::find(miss.begin(), miss.end(), c.seq) != miss.end();
        if (isMissing) { mgr.onChunk(c); std::printf("  refill seq=%u\n", c.seq); }
    }
    // fire one duplicate of seq that already arrived (pick seq=1 if present)
    for (auto& c : allChunks) if (c.seq == 1) { mgr.onChunk(c); std::printf("  dup seq=1 (should be ignored)\n"); break; }

    // 8) DONE again -> must now be complete, and SHA-256 must match.
    ok = false;
    auto rebuilt = mgr.onDone(id, ok);
    std::printf("\n-- DONE #2 -> ok=%s, rebuilt %zu bytes --\n",
                ok ? "true" : "false", rebuilt.size());
    if (!ok) { std::printf("FAIL: still incomplete after refill\n"); return 1; }

    uint8_t reHash[32];
    sha256(rebuilt, reHash);
    std::printf("Rebuilt sha256 =%s\n", hex(reHash, 32).c_str());
    std::printf("Original sha256=%s\n", hex(origHash, 32).c_str());

    if (std::memcmp(reHash, origHash, 32) != 0) {
        std::printf("\n*** FAIL: SHA-256 MISMATCH — assembly is broken ***\n");
        return 1;
    }
    if (rebuilt != original) {
        std::printf("\n*** FAIL: byte-for-byte mismatch despite hash?! ***\n");
        return 1;
    }

    std::printf("\n=== PASS: chaotic+lossy chunks reassembled byte-for-byte, SHA-256 matches ===\n");

    // 9) Bonus guard: 100MB cap rejects an oversized INIT.
    MediaInit big = init;
    big.totalBytes = MAX_TRANSFER + 1;
    TransferManager mgr2;
    if (mgr2.onInit(big)) { std::printf("FAIL: cap did not reject oversized transfer\n"); return 1; }
    std::printf("Cap guard: oversized INIT correctly rejected.\n");

    return 0;
}
