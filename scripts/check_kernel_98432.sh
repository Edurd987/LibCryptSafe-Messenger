#!/usr/bin/env bash
# №39: вышло ли ядро Ubuntu с исправлением CVE-2026-98432? Запуск: scripts/check_kernel_98432.sh
# FIX_AVAILABLE -> ставим (apt-get dist-upgrade) и перезагружаем ВРУЧНУЮ.
RELAY="${RELAY:-root@87.199.204.168}"
ssh "$RELAY" 'bash -s' <<'REMOTE'
apt-get update -qq
C=$(apt-cache policy linux-image-generic | awk '/Candidate/{print $2}')
K="${C%.*}"
N=$(apt-get changelog "linux-image-$K-generic" 2>/dev/null | grep -c "CVE-2026-98432")
echo "running=$(uname -r) candidate=$K-generic cve_in_changelog=$N"
if [ "$(uname -r)" = "$K-generic" ]; then echo "NO_NEW_KERNEL"
elif [ "${N:-0}" -ge 1 ]; then echo "FIX_AVAILABLE"
else echo "NEW_KERNEL_BUT_NO_FIX"; fi
REMOTE
