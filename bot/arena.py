"""
Стенд силы: сеть против «лёгкого» бота из APK (или случайного), цвета по очереди.
  python3 bot/arena.py bot/weights/long_v1.bin --games 400
Печатает % побед с 95%-интервалом и очки за партию. Кости детерминированы (--seed).
"""
import argparse, math, sys, time
from pathlib import Path
import numpy as np
sys.path.insert(0, str(Path(__file__).parent))
from nardi_engine import W, B
from td_net import Net
from easy_bot import easy_turn
import train


def match(net, games, seed, opponent="easy"):
    rng = np.random.default_rng(seed)
    wins = pts = 0
    types = {1: 0, 2: 0, 3: 0}
    for i in range(games):
        me = W if i % 2 == 0 else B
        rnd = np.random.default_rng(seed * 1000 + i)
        opp = easy_turn if opponent == "easy" else (lambda s: train.choose(net, s, rnd))
        w, t = train.play_game({me: lambda s: train.choose(net, s), -me: opp}, rng)
        wins += w == me
        pts += t if w == me else -t
        if w == me:
            types[t] += 1
    p = wins / games
    ci = 1.96 * math.sqrt(p * (1 - p) / games) * 100
    return 100 * p, ci, pts / games, types


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("weights")
    ap.add_argument("--games", type=int, default=400)
    ap.add_argument("--seed", type=int, default=777)
    ap.add_argument("--opponent", choices=["easy", "random"], default="easy")
    a = ap.parse_args()
    net = Net.load(a.weights)
    t0 = time.time()
    wp, ci, ppg, types = match(net, a.games, a.seed, a.opponent)
    print(f"{a.weights} (веса {net.digest()}) против '{a.opponent}': {wp:.1f}% ± {ci:.1f}% побед, "
          f"{ppg:+.2f} очк/партию; победы: обычных {types[1]}, марсов {types[2]}, коксов {types[3]}  "
          f"[{a.games} партий, {time.time() - t0:.0f} с]")
