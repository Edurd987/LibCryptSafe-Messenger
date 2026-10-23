"""
Движок ДЛИННЫХ нард для обучения бота — точная копия правил NardiModel.kt.
Истина — Kotlin; совпадение доказывает bot/check_oracle.py по оракулу
(bot/oracle_long.jsonl, генерирует NardiOracleTest). Меняешь правила в Kotlin ->
меняй здесь -> перегенерируй оракул -> check_oracle обязан быть зелёным.

Доска: кортеж из 24 чисел, +n = n белых, -n = n чёрных. Игрок: +1 белые, -1 чёрные.
Полный ход ищется НЕЗАВИСИМЫМ от Kotlin способом (максимальные цепочки шагов),
поэтому совпадение с оракулом — настоящая проверка, а не копия ошибки.
"""
from typing import NamedTuple, Optional, Tuple

W, B = 1, -1
ROUTE = {W: [23, 22, 21, 20, 19, 18, 17, 16, 15, 14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1, 0],
         B: [11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1, 0, 23, 22, 21, 20, 19, 18, 17, 16, 15, 14, 13, 12]}
POS = {p: {idx: i for i, idx in enumerate(r)} for p, r in ROUTE.items()}
HEAD = {W: 23, B: 11}
HOME = {W: range(0, 6), B: range(12, 18)}


class S(NamedTuple):
    b: Tuple[int, ...]
    turn: int
    dice: Optional[Tuple[int, ...]]
    h: int = 0          # снято с головы в этом ходу
    rd: bool = False    # текущий бросок — дубль
    ow: int = 0
    ob: int = 0


def owner(v): return (v > 0) - (v < 0)


def initial():
    b = [0] * 24
    b[23], b[11] = 15, -15
    return S(tuple(b), W, None)


def roll(s, a, c):
    return s._replace(dice=(a,) * 4 if a == c else (a, c), rd=(a == c))


def move_distance(p, f, t): return POS[p][t] - POS[p][f]


def apply_move(s, f, t):
    b = list(s.b); m = owner(b[f])
    b[f] -= m
    b[t] = (abs(b[t]) + 1) * m
    return s._replace(b=tuple(b), h=s.h + (1 if f == HEAD[m] else 0))


def consume_die(s, d):
    dice = list(s.dice); dice.remove(d)
    if not dice:
        return s._replace(dice=None, turn=-s.turn, h=0)
    return s._replace(dice=tuple(dice))


def burn(s): return s._replace(dice=None, turn=-s.turn, h=0)


def creates_6_block(b, p):
    opp = -p; route = ROUTE[p]; chain = 0
    for pos, idx in enumerate(route):
        if owner(b[idx]) == p:
            chain += 1
            if chain >= 6:
                wall = {route[i] for i in range(pos - chain + 1, pos + 1)}
                start = min(POS[opp][i] for i in wall)
                for i in range(24):
                    if owner(b[i]) == opp and i not in wall and POS[opp][i] < start:
                        return True
        else:
            chain = 0
    return False


def legal_step(s, f, t):
    if s.dice is None or owner(s.b[f]) != s.turn or not (0 <= t <= 23):
        return False
    p = s.turn
    if f == HEAD[p]:
        special = s.rd and s.dice[0] in (6, 4, 3)
        first = abs(s.b[f]) + s.h == 15
        if s.h >= (2 if first and special else 1):
            return False
    if owner(s.b[t]) == -p:
        return False
    d = move_distance(p, f, t)
    if d <= 0 or d not in s.dice:
        return False
    return not creates_6_block(apply_move(s, f, t).b, p)


def all_home(s, p):
    return all(i in HOME[p] for i in range(24) if owner(s.b[i]) == p)


def bear_die(s, f):
    """Кость для выброса с f, или None, если выброс по правилу шага невозможен."""
    p = s.turn
    if s.dice is None or owner(s.b[f]) != p or not all_home(s, p):
        return None
    need = 24 - POS[p][f]
    if need in s.dice:
        return need
    bigger = [d for d in s.dice if d > need]
    if not bigger:
        return None
    if any(owner(s.b[i]) == p and POS[p][i] < POS[p][f] for i in range(24)):
        return None
    return min(bigger)


def bear_off(s, f, d):
    b = list(s.b); b[f] -= s.turn
    s = s._replace(b=tuple(b), ow=s.ow + (s.turn == W), ob=s.ob + (s.turn == B))
    return consume_die(s, d)


def raw_steps(s):
    """[(f, t, die)] по правилам ОДНОГО шага; t = -1 выброс."""
    out = []
    if s.dice is None:
        return out
    for f in range(24):
        if owner(s.b[f]) != s.turn:
            continue
        for t in range(24):
            if legal_step(s, f, t):
                out.append((f, t, move_distance(s.turn, f, t)))
        d = bear_die(s, f)
        if d is not None:
            out.append((f, -1, d))
    return out


def do_step(s, f, t, d):
    return bear_off(s, f, d) if t < 0 else consume_die(apply_move(s, f, t), d)


def key(s):
    return ",".join(map(str, s.b)) + f"|{s.ow}|{s.ob}|{'W' if s.turn == W else 'B'}"


def _best(s, memo):
    """(макс. число заров, множество финалов цепочек этой длины) из середины хода."""
    if s in memo:
        return memo[s]
    best, fins = 0, {key(burn(s))}
    for f, t, d in raw_steps(s):
        n = do_step(s, f, t, d)
        l, fs = (1, {key(n)}) if n.dice is None else (lambda r: (1 + r[0], r[1]))(_best(n, memo))
        if l > best:
            best, fins = l, set(fs)
        elif l == best and best > 0:
            fins |= fs
    memo[s] = (best, fins)
    return memo[s]


def turn_analysis(s):
    """-> (max заров, легальные шаги {(f,t)}, финалы полного хода {key}). Правило R2:
    максимум заров; если играется только один из двух разных — больший."""
    memo = {}
    per = []
    for f, t, d in raw_steps(s):
        n = do_step(s, f, t, d)
        l, fs = (1, {key(n)}) if n.dice is None else (lambda r: (1 + r[0], r[1]))(_best(n, memo))
        per.append((f, t, d, l, fs))
    if not per:
        return 0, set(), {key(burn(s))}
    mx = max(x[3] for x in per)
    keep = [x for x in per if x[3] == mx]
    if mx == 1 and len(s.dice) == 2 and s.dice[0] != s.dice[1]:
        big = max(s.dice)
        if any(x[2] == big for x in per):
            keep = [x for x in keep if x[2] == big]
    return mx, {(x[0], x[1]) for x in keep}, set().union(*(x[4] for x in keep))


def winner(s):
    return W if s.ow >= 15 else B if s.ob >= 15 else None
