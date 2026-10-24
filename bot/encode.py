"""
Кодирование позиции для сети — ОТ ЛИЦА ТОГО, ЧЕЙ ХОД (канонически): свои шашки по
своему маршруту (0 = голова, 23 = последний пункт дома), чужие — по чужому.
Поэтому encode(s) == encode(mirror(s)): сеть одна на оба цвета.

На пункт 4 входа (как TD-Gammon): n>=1, n>=2, n>=3, (n-3)/12.  24*4*2 = 192
+ выброшено своих/чужих /15 + pip своих/чужих /360 = 196 входов.
"""
import numpy as np
from nardi_engine import POS, owner

N_IN = 196


def _unary(n):
    return (n >= 1, n >= 2, n >= 3, max(n - 3, 0) / 12.0)


def encode(s):
    me, op = s.turn, -s.turn
    x = np.zeros(N_IN, dtype=np.float64)
    pip_me = pip_op = 0
    for idx, v in enumerate(s.b):
        o = owner(v)
        if o == 0:
            continue
        n = abs(v)
        pos = POS[o][idx]
        base = (0 if o == me else 96) + pos * 4
        x[base:base + 4] = _unary(n)
        if o == me:
            pip_me += (24 - pos) * n
        else:
            pip_op += (24 - pos) * n
    off_me, off_op = (s.ow, s.ob) if me == 1 else (s.ob, s.ow)
    x[192:196] = (off_me / 15.0, off_op / 15.0, pip_me / 360.0, pip_op / 360.0)
    return x


# Выходы сети (от лица того, чей ход): победа, победа марсом+, победа коксом,
# поражение марсом+, поражение коксом. Очки: обычная 1, марс 2, кокс 3.
def equity(v):
    return 2 * v[..., 0] - 1 + v[..., 1] + v[..., 2] - v[..., 3] - v[..., 4]


def flip(v):
    """Тот же прогноз от лица соперника. flip(flip(v)) == v."""
    v = np.asarray(v)
    return np.stack([1 - v[..., 0], v[..., 3], v[..., 4], v[..., 1], v[..., 2]], axis=-1)


def outcome_for(winner, wtype, persp):
    """Точный исход партии как вектор выходов от лица persp."""
    g, k = float(wtype >= 2), float(wtype == 3)
    return np.array([1, g, k, 0, 0.]) if winner == persp else np.array([0, 0, 0, g, k], dtype=np.float64)
