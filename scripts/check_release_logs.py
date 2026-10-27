import re, glob, os, sys
dexdir = sys.argv[1]
files = glob.glob(os.path.join(dexdir, "classes*.dex"))
dex = b"".join(open(f, "rb").read() for f in files)
print(f"DEX files={len(files)} bytes={len(dex)}")
assert len(dex) > 100000, "dex не прочитан - замер недействителен"
pat = re.compile(r'SafeLogger\.[diwe]\("([^"]*)"(?:,\s*|\)\s*\{\s*)"([^"$\\]{6,})')
total = leaked = 0; sample = []
for f in glob.glob("android/app/src/main/**/*.kt", recursive=True):
    if f.endswith("SafeLogger.kt"): continue
    for n, line in enumerate(open(f, encoding="utf-8"), 1):
        for tag, lit in pat.findall(line):
            total += 1
            if lit.encode("utf-8") in dex:
                leaked += 1
                if len(sample) < 8: sample.append(f"{os.path.basename(f)}:{n} {tag}: {lit[:40]}")
print(f"CONTROL bot dice= in dex: {b'bot dice=' in dex}")
assert total >= 90, f"найдено {total} логов - замер недействителен"
print(f"TOTAL={total} LEAKED={leaked}")
print("\n".join(sample))
