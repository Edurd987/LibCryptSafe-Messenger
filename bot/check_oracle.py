"""Сверка Python-движка с оракулом Kotlin. Запуск: python3 bot/check_oracle.py
Выход 0 = все позиции совпали (шаги, максимум заров, финалы полного хода)."""
import json, sys, time
from pathlib import Path
sys.path.insert(0, str(Path(__file__).parent))
import nardi_engine as E
from nardi_engine import S, W, B

path = Path(__file__).parent / "oracle_long.jsonl"
rows = [json.loads(l) for l in path.read_text(encoding="utf-8").splitlines() if l.strip()]


def mismatches(limit=None, verbose=False):
    bad = 0
    for i, r in enumerate(rows[:limit]):
        s = S(tuple(r["b"]), W if r["t"] == "W" else B, tuple(r["d"]), r["h"], r["rd"], r["ow"], r["ob"])
        mx, steps, finals = E.turn_analysis(s)
        exp_steps = {tuple(x) for x in r["steps"]}
        problems = []
        if mx != r["max"]: problems.append(f"max {mx} != {r['max']}")
        if steps != exp_steps: problems.append(f"шаги лишние={sorted(steps - exp_steps)} нет={sorted(exp_steps - steps)}")
        if finals != set(r["finals"]): problems.append(f"финалы: лишних {len(finals - set(r['finals']))}, нет {len(set(r['finals']) - finals)}")
        if problems:
            bad += 1
            if verbose and bad <= 5: print(f"[{i}] d={r['d']} h={r['h']} t={r['t']}: " + "; ".join(problems))
    return bad


# ОТРИЦАТЕЛЬНЫЕ КОНТРОЛИ: сознательно ломаем движок -> оракул ОБЯЗАН это заметить.
# Детектор, который молчит на заведомую ошибку, — не детектор.
def mutant(name, attr, fake):
    orig = getattr(E, attr); setattr(E, attr, fake)
    try:
        n = mismatches()
    finally:
        setattr(E, attr, orig)
    print(f"  контроль '{name}': расхождений {n} -> {'ПОЙМАН' if n else 'НЕ ПОЙМАН!'}")
    return n > 0

t0 = time.time()
bad = mismatches(verbose=True)
n = len(rows)
print("отрицательные контроли (мутации движка):")
orig_ta = E.turn_analysis
def no_r2a(s):
    mx, st, fi = orig_ta(s)
    if mx == 1 and len(s.dice) == 2 and s.dice[0] != s.dice[1]:   # разрешить и меньший зар
        per = [(f, t, d) for f, t, d in E.raw_steps(s)]
        st = st | {(f, t) for f, t, d in per}
    return mx, st, fi
caught = [
    mutant("нет запрета блока из 6", "creates_6_block", lambda b, p: False),
    mutant("нет исключения R1 (одна с головы всегда)", "legal_step",
           (lambda ls: lambda s, f, t: ls(s._replace(rd=False), f, t))(E.legal_step)),
    mutant("выброс без проверки 'все дома'", "all_home", lambda s, p: True),
    mutant("нет правила большего зара R2a", "turn_analysis", no_r2a),
]
cnt = lambda pred: sum(1 for r in rows if pred(r))
print(f"позиций {n}: совпало {n - bad}, расхождений {bad}  ({time.time() - t0:.1f} с)")
print(f"покрытие: дубли {cnt(lambda r: len(r['d']) == 4)}, особый дубль с головы "
      f"{cnt(lambda r: r['rd'] and r['d'][0] in (6,4,3) and abs(r['b'][23 if r['t']=='W' else 11]) + r['h'] == 15)}, "
      f"выброс {cnt(lambda r: any(x[1] == -1 for x in r['steps']))}, сгорание {cnt(lambda r: r['max'] == 0)}, "
      f"один из двух (R2a) {cnt(lambda r: r['max'] == 1 and len(r['d']) == 2)}")
ok = bad == 0 and n > 0 and all(caught)
print("ИТОГ:", "ЗЕЛЁНЫЙ — Python == Kotlin, контроли пойманы" if ok else "КРАСНЫЙ")
sys.exit(0 if ok else 1)
