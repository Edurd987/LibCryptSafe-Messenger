"""
Самоигра TD(lambda) для длинных нард (как TD-Gammon). Детерминированно: один seed ->
одни и те же кости -> одни и те же веса бит в бит (отпечаток печатается).

  python3 bot/train.py --bench                       # скорость, ничего не сохраняет
  python3 bot/train.py --games 20000 --out bot/weights/long_v1.bin
"""
import argparse, csv, os, sys, time
from pathlib import Path
import numpy as np
sys.path.insert(0, str(Path(__file__).parent))
import nardi_engine as E
from nardi_engine import W, B
from play import final_states, win_type, opening
from encode import encode, equity, flip, outcome_for
from td_net import Net

PERM = [0, 3, 4, 1, 2]                 # flip: выход k <- выход PERM[k] соперника


def to_white(o, G, turn):
    """Прогноз и градиенты от лица белых (фиксированная точка зрения для TD)."""
    if turn == W:
        return o, G
    U = flip(o)
    GU = []
    for g in G:
        h = g[PERM].copy()
        h[0] = -h[0]                   # 1 - win: знак градиента меняется
        GU.append(h)
    return U, GU


def choose(net, s, rng=None):
    """Лучший полный ход: минимум эквити соперника. rng -> случайный ход (контрольный игрок)."""
    fins = final_states(s)
    keys = sorted(fins)                # детерминированный порядок и разрешение ничьих
    if rng is not None:
        return fins[keys[rng.integers(len(keys))]]
    best, best_v = None, None
    live = [k for k in keys if E.winner(fins[k]) is None]
    vals = {}
    if live:
        X = np.stack([encode(fins[k]) for k in live])
        for k, v in zip(live, equity(net.forward(X))):
            vals[k] = -v               # эквити того, кто ходит сейчас
    for k in keys:
        f = fins[k]
        v = float(win_type(f)) if E.winner(f) is not None else vals[k]
        if best_v is None or v > best_v:
            best, best_v = f, v
    return best


def roll(rng, s):
    a, c = rng.integers(1, 7, 2)
    return E.roll(s, int(a), int(c))


def start(rng):
    while True:
        a, c = rng.integers(1, 7, 2)
        if a != c:
            return opening(int(a), int(c))


def train_game(net, rng, alpha, lam, stats=None):
    s = start(rng)
    e = None
    U_prev = None
    while True:
        t0 = time.perf_counter()
        o, G = net.grads(encode(s))
        U, GU = to_white(o, G, s.turn)
        if U_prev is not None:
            delta = U - U_prev
            for p, ep in zip(net.params(), e):
                p += alpha * np.tensordot(delta, ep, axes=1)
        e = GU if e is None else [lam * ep + g for ep, g in zip(e, GU)]
        U_prev = U
        t1 = time.perf_counter()
        nxt = choose(net, s)
        if stats is not None:
            stats["net"] += t1 - t0
            stats["move"] += time.perf_counter() - t1
            stats["pos"] += 1
        w = E.winner(nxt)
        if w is not None:
            delta = outcome_for(w, win_type(nxt), W) - U_prev
            for p, ep in zip(net.params(), e):
                p += alpha * np.tensordot(delta, ep, axes=1)
            return w, win_type(nxt)
        s = roll(rng, nxt)


def play_game(players, rng):
    """players = {W: f(s)->state, B: f(s)->state}. -> (победитель, тип)."""
    s = start(rng)
    while True:
        s = players[s.turn](s)
        w = E.winner(s)
        if w is not None:
            return w, win_type(s)
        s = roll(rng, s)


def evaluate(net, n, seed):
    """Сеть против СЛУЧАЙНОГО игрока, цвета по очереди. -> (% побед, очков за партию)."""
    rng = np.random.default_rng(seed)
    wins = pts = 0
    for i in range(n):
        me = W if i % 2 == 0 else B
        rnd = np.random.default_rng(seed * 1000 + i)
        players = {me: lambda s: choose(net, s), -me: lambda s: choose(net, s, rnd)}
        w, t = play_game(players, rng)
        wins += w == me
        pts += t if w == me else -t
    return 100.0 * wins / n, pts / n


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--games", type=int, default=1000)
    ap.add_argument("--seed", type=int, default=1)
    ap.add_argument("--alpha", type=float, default=0.1)
    ap.add_argument("--lam", type=float, default=0.7)
    ap.add_argument("--eval-every", type=int, default=500)
    ap.add_argument("--eval-games", type=int, default=100)
    ap.add_argument("--out", default="bot/weights/long_v1.bin")
    ap.add_argument("--bench", action="store_true")
    a = ap.parse_args()

    net = Net(seed=a.seed)
    rng = np.random.default_rng(a.seed)
    if a.bench:
        stats = {"net": 0.0, "move": 0.0, "pos": 0}
        t0 = time.time(); n = 20
        for _ in range(n):
            train_game(net, rng, a.alpha, a.lam, stats)
        dt = time.time() - t0
        print(f"обучение: {n / dt:.2f} партий/с, {stats['pos'] / n:.0f} ходов/партию; "
              f"время: правила+выбор {stats['move']:.1f} с, сеть+TD {stats['net']:.1f} с")
        print(f"прогноз на 1 ядро: 10 тыс. партий = {10000 / (n / dt) / 3600:.1f} ч")
        print(f"отпечаток весов: {net.digest()}")
        return

    Path(a.out).parent.mkdir(parents=True, exist_ok=True)
    log = Path(a.out).with_suffix(".csv")
    with open(log, "w", newline="") as f:
        csv.writer(f).writerow(["games", "sec", "win_pct_vs_random", "pts_per_game", "digest"])
    t0 = time.time()
    for g in range(1, a.games + 1):
        train_game(net, rng, a.alpha, a.lam)
        if g % a.eval_every == 0 or g == a.games:
            wp, ppg = evaluate(net, a.eval_games, seed=12345)
            net.save(a.out)
            net.save(str(Path(a.out).with_name(f"{Path(a.out).stem}_g{g}.bin")))   # история точек
            row = [g, round(time.time() - t0), round(wp, 1), round(ppg, 3), net.digest()]
            with open(log, "a", newline="") as f:
                csv.writer(f).writerow(row)
            print(f"[{g}] {row[1]} с: против случайного {wp:.1f}% побед, {ppg:+.2f} очк/партию, веса {row[4]}", flush=True)


if __name__ == "__main__":
    main()
