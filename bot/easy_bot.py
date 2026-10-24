"""
«Лёгкий» бот — копия NardiBot.kt (evaluateState + жадный выбор по ОДНОМУ шагу),
как он играет в APK сейчас. Сверка с Kotlin: bot/check_easy.py по bot/oracle_easy.jsonl.
Нужен как честный соперник: обученная сеть обязана обыгрывать именно его.
"""
import nardi_engine as E
from nardi_engine import W, ROUTE, POS, HOME, owner


def evaluate_state(s, p):
    score = (s.ow if p == W else s.ob) * 1000
    pip = chain = occupied = overstack = in_home = 0
    for idx in ROUTE[p]:
        v = s.b[idx]
        if owner(v) == p:
            n = abs(v)
            pip += (24 - POS[p][idx]) * n
            if idx in HOME[p]:
                in_home += n
            if n >= 2:
                occupied += 1
                overstack += n - 2
                chain += 1
                if chain >= 2:
                    score += chain * chain * 20
            else:
                chain = 0
        else:
            chain = 0
    return score - pip * 3 + occupied * 60 - overstack * 35 + in_home * 15


def _ordered_steps(s):
    """Легальные шаги в порядке botCollectMoves: from 0..23, внутри to 0..23, затем выброс."""
    _, steps, _ = E.turn_analysis(s)
    return sorted(steps, key=lambda ft: (ft[0], ft[1] if ft[1] >= 0 else 24))


def easy_turn(s):
    """Полный ход лёгкого бота (как botStep): шаг с лучшей оценкой, при равенстве — первый."""
    me = s.turn
    while s.turn == me and s.dice is not None:
        steps = _ordered_steps(s)
        if not steps:
            return E.burn(s)
        best, best_sc = None, None
        for f, t in steps:
            d = E.move_distance(me, f, t) if t >= 0 else E.bear_die(s, f)
            n = E.do_step(s, f, t, d)
            sc = evaluate_state(n, me)
            if best_sc is None or sc > best_sc:
                best, best_sc = n, sc
        s = best
        if s.dice is not None and not _ordered_steps(s):
            s = E.burn(s)
    return s
