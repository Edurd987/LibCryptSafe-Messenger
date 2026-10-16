#!/usr/bin/env bash
# Все тесты безопасности одной командой. Запуск из корня репозитория:
#   DEVICE=b7549a6f scripts/run_security_tests.sh
# Перед запуском: телефон разблокирован и на связи (adb devices -> device).
set -u
DEVICE="${DEVICE:-b7549a6f}"
# adb: в неинтерактивном скрипте ~/.bashrc не читается -> ищем сами
ADB="${ADB:-$(command -v adb 2>/dev/null || echo /c/Users/79384/AppData/Local/Android/Sdk/platform-tools/adb.exe)}"
[ -x "$ADB" ] || { echo "adb не найден: $ADB (задай ADB=/путь/к/adb.exe)"; exit 2; }
RELAY="${RELAY:-root@87.199.204.168}"
PKG=com.libcryptsafe.messenger
RUNNER="$PKG.test/androidx.test.runner.AndroidJUnitRunner"
declare -A RES; FAIL=0
mark() { RES["$1"]="$2"; [ "$2" = PASS ] || FAIL=1; }

echo "== 0. Версия relay: репозиторий vs сервер =="
L=$(sha256sum relay/server.js | cut -c1-16)
R=$(ssh "$RELAY" "sha256sum /opt/libcryptsafe/server.js" | cut -c1-16)
if [ "$L" = "$R" ]; then mark "relay: развёрнута версия из репо" PASS
else mark "relay: развёрнута версия из репо" "FAIL (локально $L, на сервере $R)"; fi

echo "== 1. Сборка =="
if (cd android && ./gradlew -q assembleDebug assembleDebugAndroidTest); then mark "сборка" PASS
else mark "сборка" FAIL; fi

echo "== 2. Установка на $DEVICE =="
"$ADB" -s "$DEVICE" shell am force-stop "$PKG" >/dev/null 2>&1
if "$ADB" -s "$DEVICE" install -r -t android/app/build/outputs/apk/debug/app-debug.apk >/dev/null &&
   "$ADB" -s "$DEVICE" install -r -t android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk >/dev/null
then mark "установка APK" PASS; else mark "установка APK" FAIL; fi

echo "== 3. Инструментальные тесты =="
for C in X3dhSecurityTest SessionIntegrityTest; do
    OUT=$("$ADB" -s "$DEVICE" shell am instrument -w -e class "com.libcryptsafe.$C" "$RUNNER" 2>&1)
    N=$(echo "$OUT" | grep -oE "OK \([0-9]+ tests?\)")
    if [ -n "$N" ]; then mark "android: $C" "PASS"; echo "  $C: $N"
    else mark "android: $C" FAIL; echo "$OUT" | tail -15; fi
done

echo "== 4. Тесты relay =="
if ssh "$RELAY" "node /opt/libcryptsafe/test/relay_protocol_test.js" | tail -1 | grep -q "ALL PASS"
then mark "relay: протокол" PASS; else mark "relay: протокол" FAIL; fi
if ssh "$RELAY" "cd /opt/libcryptsafe && runuser -u libcrypt -- node test/housekeeping_test.js" | tail -1 | grep -q "ALL PASS"
then mark "relay: гигиена" PASS; else mark "relay: гигиена" FAIL; fi

echo; echo "================ ИТОГ ================"
for k in "${!RES[@]}"; do printf "%-38s %s\n" "$k" "${RES[$k]}"; done | sort
echo "======================================"
[ $FAIL -eq 0 ] && echo "ВСЁ ЗЕЛЁНОЕ" || echo "ЕСТЬ ПАДЕНИЯ"
exit $FAIL
