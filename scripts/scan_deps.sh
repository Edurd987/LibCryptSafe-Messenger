#!/usr/bin/env bash
# №19: скан зависимостей перед релизом (Android lock-файл + relay). Запуск из любого места.
# --write-locks: сначала перегенерировать android/app/gradle.lockfile (после смены зависимостей).
# Код выхода != 0 = найдена уязвимость, не принятая в android/app/osv-scanner.toml.
set -euo pipefail
cd "$(dirname "$0")/.."
OSV="${OSV:-$(command -v osv-scanner || echo "$HOME/tools/osv/osv-scanner_windows_amd64.exe")}"
if [ "${1:-}" = "--write-locks" ]; then
    (cd android && ./gradlew -q :app:dependencies --write-locks > /dev/null)
fi
"$OSV" scan source -L android/app/gradle.lockfile -L relay/package-lock.json
