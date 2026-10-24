"""
Ход целиком + исход партии поверх сверенного движка (nardi_engine.py не трогаем).
final_states(s) -> {key: состояние после ПОЛНОГО хода}; множество ключей обязано
совпадать с turn_analysis (тот сверен с Kotlin) — это проверяет test_bot.py.
"""
import nardi_engine as E
from nardi_engine import S, W, B, owner


def final_states(s):
    """Все позиции после полного хода по правилам R2 (ключ -> состояние)."""
    memo = {}

    def best(st):                       # (макс. заров, {key: state}) из середины хода
        if st in memo:
            return memo[st]
        b = E.burn(st)
        res = (0, {E.key(b): b})
        for f, t, d in E.raw_steps(st):
            n = E.do_step(st, f, t, d)
            l, fs = (1, {E.key(n): n}) if n.dice is None else (lambda r: (1 + r[0], r[1]))(best(n))
            if l > res[0]:
                res = (l, dict(fs))
            elif l == res[0] and l > 0:
                res[1].update(fs)
        memo[st] = res
        return res

    per = []
    for f, t, d in E.raw_steps(s):
        n = E.do_step(s, f, t, d)
        l, fs = (1, {E.key(n): n}) if n.dice is None else (lambda r: (1 + r[0], r[1]))(best(n))
        per.append((d, l, fs))
    if not per:
        b = E.burn(s)
        return {E.key(b): b}
    mx = max(x[1] for x in per)
    keep = [x for x in per if x[1] == mx]
    if mx == 1 and len(s.dice) == 2 and s.dice[0] != s.dice[1]:
        big = max(s.dice)
        if any(x[0] == big for x in per):
            keep = [x for x in keep if x[0] == big]
    out = {}
    for x in keep:
        out.update(x[2])
    return out


def win_type(s):
    """Как winType в Kotlin: 0 игра идёт, 1 обычная, 2 марс, 3 кокс."""
    w = E.winner(s)
    if w is None:
        return 0
    loser = -w
    if (s.ow if loser == W else s.ob) > 0:
        return 1
    in_home = sum(abs(s.b[i]) for i in E.HOME[loser] if owner(s.b[i]) == loser)
    return 3 if in_home == 0 else 2


def mirror(s):
    """Смена цветов: доска сдвигается на 12 пунктов (маршруты B = W + 12), знаки меняются."""
    b = [0] * 24
    for i, v in enumerate(s.b):
        b[(i + 12) % 24] = -v
    return S(tuple(b), -s.turn, s.dice, s.h, s.rd, s.ob, s.ow)


def opening(a, c):
    """Розыгрыш как в APK: кости разные, у кого больше — ходит ими."""
    assert a != c
    return E.initial()._replace(dice=(a, c), turn=W if a > c else B, rd=False)
