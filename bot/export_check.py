"""
Эталон для Kotlin-бота: python3 bot/export_check.py [--weights bot/weights/long_v2.bin]
БЕЗ numpy (работает и на Windows): прямой проход считается теми же циклами и в том же
порядке сложения, что NardiNet.kt -> выходы должны совпасть почти бит в бит.
Пишет bot/net_check.tsv: позиция | входы сети | выходы | выбранный полный ход | зазор до 2-го.
NardiNetTest (Kotlin, JVM) обязан получить то же самое на тех же весах из assets.
"""
import argparse, hashlib, json, math, struct, sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).parent))
import nardi_engine as E
from nardi_engine import S, W, B, POS, owner
from play import final_states, win_type

N_IN, N_OUT = 196, 5


def load(path):
    raw = Path(path).read_bytes()
    assert raw[:4] == b"NRD1"
    h = struct.unpack("<i", raw[4:8])[0]
    vals = struct.unpack("<%dd" % ((len(raw) - 8) // 8), raw[8:])
    assert len(vals) == h * N_IN + h + N_OUT * h + N_OUT
    o = 0
    def take(n):
        nonlocal o
        r = vals[o:o + n]; o += n
        return r
    return h, take(h * N_IN), take(h), take(N_OUT * h), take(N_OUT), hashlib.sha256(raw).hexdigest()


def encode(s):
    me = s.turn
    x = [0.0] * N_IN
    pm = po = 0
    for idx, v in enumerate(s.b):
        o = owner(v)
        if o == 0:
            continue
        n, pos = abs(v), POS[o][idx]
        base = (0 if o == me else 96) + pos * 4
        x[base:base + 4] = [1.0 if n >= 1 else 0.0, 1.0 if n >= 2 else 0.0, 1.0 if n >= 3 else 0.0, max(n - 3, 0) / 12.0]
        if o == me: pm += (24 - pos) * n
        else: po += (24 - pos) * n
    om, oo = (s.ow, s.ob) if me == W else (s.ob, s.ow)
    x[192:196] = [om / 15.0, oo / 15.0, pm / 360.0, po / 360.0]
    return x


def forward(net, x):
    h_n, w1, b1, w2, b2, _ = net
    hid = []
    for j in range(h_n):
        z = b1[j]; off = j * N_IN
        for i in range(N_IN):
            z += w1[off + i] * x[i]
        hid.append(1.0 / (1.0 + math.exp(-z)))
    out = []
    for o in range(N_OUT):
        z = b2[o]; off = o * h_n
        for j in range(h_n):
            z += w2[off + j] * hid[j]
        out.append(1.0 / (1.0 + math.exp(-z)))
    return out


def equity(v): return 2 * v[0] - 1 + v[1] + v[2] - v[3] - v[4]


def choose(net, s):
    """-> (ключ лучшей позиции, зазор до второй). Ничьи — первый по ключу (как Kotlin)."""
    fins = final_states(s)
    vals = []
    for k in sorted(fins):
        f = fins[k]
        v = float(win_type(f)) if E.winner(f) is not None else -equity(forward(net, encode(f)))
        vals.append((v, k))
    best_v, best_k = vals[0]
    for v, k in vals[1:]:
        if v > best_v: best_v, best_k = v, k
    others = [v for v, k in vals if k != best_k]
    return best_k, (best_v - max(others)) if others else 1.0


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--weights", default="bot/weights/long_v2.bin")
    ap.add_argument("--n", type=int, default=400)
    ap.add_argument("--out", default="bot/net_check.tsv")
    a = ap.parse_args()
    net = load(a.weights)
    rows = [json.loads(l) for l in (Path(__file__).parent / "oracle_long.jsonl").read_text().splitlines() if l][:a.n]
    with open(a.out, "w", encoding="utf-8", newline="\n") as f:
        f.write(f"#sha256\t{net[5]}\n")
        for r in rows:
            s = S(tuple(r["b"]), W if r["t"] == "W" else B, tuple(r["d"]), r["h"], r["rd"], r["ow"], r["ob"])
            x = encode(s); out = forward(net, x); k, gap = choose(net, s)
            f.write("\t".join([",".join(map(str, r["b"])), r["t"], ",".join(map(str, r["d"])), str(r["h"]),
                               "1" if r["rd"] else "0", str(r["ow"]), str(r["ob"]),
                               ",".join(map(repr, x)), ",".join(map(repr, out)), k, repr(gap)]) + "\n")
    print(f"{a.out}: {len(rows)} позиций, веса sha256 {net[5][:16]}…")
