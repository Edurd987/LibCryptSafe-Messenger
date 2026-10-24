"""Тесты бота (без pytest): python3 bot/test_bot.py   -> ИТОГ: ЗЕЛЁНЫЙ / КРАСНЫЙ"""
import json, sys
from pathlib import Path
import numpy as np
sys.path.insert(0, str(Path(__file__).parent))
import nardi_engine as E
from nardi_engine import S, W, B
from play import final_states, win_type, mirror
from encode import encode, flip, equity, N_IN
from td_net import Net
import train

rows = [json.loads(l) for l in (Path(__file__).parent / "oracle_long.jsonl").read_text().splitlines() if l]
st = lambda r: S(tuple(r["b"]), W if r["t"] == "W" else B, tuple(r["d"]), r["h"], r["rd"], r["ow"], r["ob"])
fails = 0


def check(name, ok, info=""):
    global fails
    print(("PASS " if ok else "FAIL ") + name + (f"  ({info})" if info else ""))
    fails += not ok


# 1. Полный ход: ключи final_states == финалы оракула Kotlin (все 3000)
bad = sum(set(final_states(st(r))) != set(r["finals"]) for r in rows)
check("final_states == финалы Kotlin (3000 позиций)", bad == 0, f"расхождений {bad}")

# 2. Симметрия правил: ходы зеркальной позиции == зеркала ходов
bad = 0
for r in rows[:600]:
    s = st(r)
    a = set(final_states(mirror(s)))
    b = {E.key(mirror(f)) for f in final_states(s).values()}
    bad += a != b
check("правила симметричны цветам (600 позиций)", bad == 0, f"расхождений {bad}")
# отрицательный контроль: зеркало без сдвига на 12 — обязано сломаться
fake = lambda s: S(tuple(-v for v in s.b), -s.turn, s.dice, s.h, s.rd, s.ob, s.ow)
check("контроль: неверное зеркало ловится", any(encode(st(r)).tolist() != encode(fake(st(r))).tolist() for r in rows[:50]))

# 3. Кодирование: размер, диапазон, канонично для обоих цветов
X = [encode(st(r)) for r in rows]
check("кодирование: 196 входов, всё в [0,1]", all(x.shape == (N_IN,) and x.min() >= 0 and x.max() <= 1 for x in X))
check("encode(s) == encode(mirror(s))", all(np.array_equal(encode(st(r)), encode(mirror(st(r)))) for r in rows))
x0 = encode(E.initial())
check("старт: 15 на голове у обоих", x0[0:4].tolist() == [1, 1, 1, 1] and x0[96:100].tolist() == [1, 1, 1, 1] and x0[194] == x0[195] == 1.0)

# 4. Исход партии (как winType в Kotlin)
def end(b_w, ow, ob):
    b = [0] * 24
    for i, n in b_w.items():
        b[i] = n
    return S(tuple(b), W, None, 0, False, ow, ob)
check("win_type: обычная", win_type(end({12: -5}, 15, 10)) == 1)
check("win_type: марс (чёрные ничего не выбросили, есть шашки дома 12..17)", win_type(end({12: -15}, 15, 0)) == 2)
check("win_type: кокс (ни выброса, ни шашек дома)", win_type(end({11: -15}, 15, 0)) == 3)
check("win_type: игра идёт", win_type(E.initial()) == 0)

# 5. Выходы: flip инволюция, эквити антисимметрична
v = np.array([0.6, 0.2, 0.05, 0.1, 0.01])
check("flip(flip(v)) == v", np.allclose(flip(flip(v)), v))
check("equity(flip(v)) == -equity(v)", np.isclose(equity(flip(v)), -equity(v)))

# 6. Градиенты: аналитика == численная производная (float64 -> допуск 1e-7)
net = Net(seed=3); x = X[7]
_, G = net.grads(x)
worst = 0.0
for pi, (p, g) in enumerate(zip(net.params(), G)):
    for idx in [tuple(np.unravel_index(j, p.shape)) for j in (0, p.size // 2, p.size - 1)]:
        old = p[idx]; eps = 1e-6
        p[idx] = old + eps; up = net.forward(x)
        p[idx] = old - eps; dn = net.forward(x)
        p[idx] = old
        worst = max(worst, np.max(np.abs((up - dn) / (2 * eps) - g[(slice(None),) + idx])))
check("градиенты аналитика == численные", worst < 1e-7, f"макс. ошибка {worst:.1e}")

# 7. to_white: перевод градиентов на сторону белых
o, G = net.grads(x)
U, GU = train.to_white(o, G, B)
p = net.params()[3]; old = p[1]; p[1] = old + 1e-6
U2 = flip(net.forward(x)); p[1] = old
check("to_white: градиент совпадает с численным", np.allclose((U2 - U) / 1e-6, GU[3][:, 1], atol=1e-5))

# 8. Детерминизм: два обучения с одним seed -> одинаковые веса бит в бит; другой seed -> другие
def run(seed, n=3):
    net = Net(seed=seed); rng = np.random.default_rng(seed)
    for _ in range(n):
        train.train_game(net, rng, 0.1, 0.7)
    return net.digest()
d1, d2, d3 = run(5), run(5), run(6)
check("детерминизм: тот же seed -> те же веса", d1 == d2, d1)
check("контроль: другой seed -> другие веса", d1 != d3)

# 9. Сохранение/загрузка весов без потерь
net.save("/tmp/_nardi_w.bin")
check("save/load бит в бит", Net.load("/tmp/_nardi_w.bin").digest() == net.digest())

print("ИТОГ:", "ЗЕЛЁНЫЙ" if fails == 0 else f"КРАСНЫЙ ({fails})")
sys.exit(1 if fails else 0)
