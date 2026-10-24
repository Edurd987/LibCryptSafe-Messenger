"""Сверка лёгкого бота Python с Kotlin: python3 bot/check_easy.py  (выход 0 = совпало)."""
import json, sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).parent))
import nardi_engine as E
from nardi_engine import S, W, B
import easy_bot

rows = [json.loads(l) for l in (Path(__file__).parent / "oracle_easy.jsonl").read_text().splitlines() if l]
st = lambda r: S(tuple(r["b"]), W if r["t"] == "W" else B, tuple(r["d"]), r["h"], r["rd"], r["ow"], r["ob"])


def mism():
    bad_ev = bad_fin = 0
    for r in rows:
        s = st(r)
        if (easy_bot.evaluate_state(s, s.turn), easy_bot.evaluate_state(s, -s.turn)) != (r["ev"], r["evo"]):
            bad_ev += 1
        if E.key(easy_bot.easy_turn(s)) != r["fin"]:
            bad_fin += 1
    return bad_ev, bad_fin


ev, fin = mism()
print(f"позиций {len(rows)}: оценка расходится {ev}, ход расходится {fin}")
# отрицательный контроль: другой вес ширины (60 -> 61) обязан изменить выбор хода
orig = easy_bot.evaluate_state
easy_bot.evaluate_state = lambda s, p: orig(s, p) + sum(1 for i in E.ROUTE[p] if E.owner(s.b[i]) == p and abs(s.b[i]) >= 2) * 15
cev, cfin = mism()
easy_bot.evaluate_state = orig
print(f"контроль (вес ширины изменён): расходится оценок {cev}, ходов {cfin} -> {'ПОЙМАН' if cev and cfin else 'НЕ ПОЙМАН!'}")
ok = len(rows) > 0 and ev == 0 and fin == 0 and cev > 0 and cfin > 0
print("ИТОГ:", "ЗЕЛЁНЫЙ — лёгкий бот Python == Kotlin" if ok else "КРАСНЫЙ")
sys.exit(0 if ok else 1)
