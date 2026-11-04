#!/usr/bin/env bash
# №44 остаток: воспроизводимая сборка статической libcrypto.a (OpenSSL LTS) для Android.
# Запускается в GitHub Actions (.github/workflows/build-openssl.yml), можно и локально на Linux/Mac.
# Fail-closed: любая несверка хеша/подписи = остановка, ничего не собирается.
set -euo pipefail

OPENSSL_VERSION="${OPENSSL_VERSION:-3.5.9}"
# SHA-256 архива: сверен 2026-11-04 по двум каналам (GitHub Releases + openssl.org/source).
OPENSSL_SHA256="${OPENSSL_SHA256:-603f5602e2eef00d77fbd429d34dcd5822bb301757a1bc9cdb24c670f1eb859a}"
# Отпечаток ОСНОВНОГО ключа подписи релизов (openssl.org/source). Подпись делает его подключ.
OPENSSL_PGP_FPR="${OPENSSL_PGP_FPR:-B146647E45A7B33947AB226B2A2C87D161692D40}"
OPENSSL_PUBKEYS_URL="${OPENSSL_PUBKEYS_URL:-https://www.openssl.org/source/pubkeys.asc}"
ANDROID_API="${ANDROID_API:-26}"                 # = minSdk в app/build.gradle
TARGET="${TARGET:-android-arm64}"               # host-режим (linux-x86_64) — только для проверки детерминизма
SKIP_PGP="${SKIP_PGP:-0}"                        # 1 — только для локальной проверки без доступа к ключу
WORK="${WORK:-$PWD/openssl-build}"
OUT="${OUT:-$PWD/out/openssl-$OPENSSL_VERSION}"

TARBALL="openssl-$OPENSSL_VERSION.tar.gz"
REL="https://github.com/openssl/openssl/releases/download/openssl-$OPENSSL_VERSION"
mkdir -p "$WORK"; cd "$WORK"

echo "== 1. Скачивание"
[ -f "$TARBALL" ] || curl -fsSL -o "$TARBALL" "$REL/$TARBALL"
[ -f "$TARBALL.asc" ] || curl -fsSL -o "$TARBALL.asc" "$REL/$TARBALL.asc"

echo "== 2. SHA-256 (закреплённый)"
echo "$OPENSSL_SHA256  $TARBALL" | sha256sum -c -

echo "== 3. Подпись PGP"
if [ "$SKIP_PGP" = "1" ]; then
  echo "ВНИМАНИЕ: PGP пропущен (SKIP_PGP=1) — только для локальной проверки"
else
  export GNUPGHOME="$WORK/gnupg"; rm -rf "$GNUPGHOME"; mkdir -m 700 "$GNUPGHOME"
  curl -fsSL -o pubkeys.asc "$OPENSSL_PUBKEYS_URL"
  gpg --batch --quiet --import pubkeys.asc
  STATUS=$(gpg --batch --status-fd 1 --verify "$TARBALL.asc" "$TARBALL" 2>/dev/null || true)
  # VALIDSIG <fpr подключа> <дата> ... <fpr ОСНОВНОГО ключа> — последний столбец
  PRIMARY=$(echo "$STATUS" | awk '$2=="VALIDSIG"{print $NF}')
  if [ "$PRIMARY" != "$OPENSSL_PGP_FPR" ]; then
    echo "ПОДПИСЬ НЕ ПРОШЛА: primary='$PRIMARY' ожидали '$OPENSSL_PGP_FPR'"; echo "$STATUS"; exit 1
  fi
  echo "подпись OK, основной ключ $PRIMARY"
fi

echo "== 4. Распаковка в ФИКСИРОВАННУЮ папку"
# OpenSSL вшивает строку флагов компилятора (с путём сборки) в cversion.o.
# Одинаковый путь на любой машине = одинаковый бинарник (проверено: иначе расходится только cversion.o).
BUILD_ROOT="/tmp/lcs-openssl-build"
rm -rf "$BUILD_ROOT"; mkdir -p "$BUILD_ROOT"; tar xzf "$TARBALL" -C "$BUILD_ROOT"
cd "$BUILD_ROOT/openssl-$OPENSSL_VERSION"

echo "== 5. Конфигурация ($TARGET)"
# Детерминизм: фиксированная дата сборки (дата релиза), без путей сборочной папки в бинаре.
export SOURCE_DATE_EPOCH=1790640000             # 2026-09-29 00:00 UTC — дата релиза 3.5.9, фиксированно
PREFIXMAP="-ffile-prefix-map=$PWD=openssl-$OPENSSL_VERSION"
COMMON="no-shared no-tests no-docs no-apps no-module $PREFIXMAP"
if [ "$TARGET" = "android-arm64" ]; then
  : "${ANDROID_NDK_ROOT:?нужен ANDROID_NDK_ROOT}"
  export PATH="$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin:$PATH"
  ./Configure android-arm64 -D__ANDROID_API__="$ANDROID_API" $COMMON
else
  ./Configure "$TARGET" $COMMON
fi

echo "== 6. Сборка libcrypto"
make -s -j"$(nproc)" build_libs >/dev/null

echo "== 7. Выкладка"
rm -rf "$OUT"; mkdir -p "$OUT/include" "$OUT/arm64-v8a"
cp -r include/openssl "$OUT/include/"
cp libcrypto.a "$OUT/arm64-v8a/libcrypto.a"
( cd "$OUT" && find include -type f | LC_ALL=C sort | xargs sha256sum > HEADERS.sha256 )
LIB_SHA=$(sha256sum "$OUT/arm64-v8a/libcrypto.a" | cut -d' ' -f1)
cat > "$OUT/OPENSSL_BUILD.txt" <<INFO
openssl_version=$OPENSSL_VERSION
tarball_sha256=$OPENSSL_SHA256
pgp_primary_fpr=$OPENSSL_PGP_FPR
target=$TARGET
android_api=$ANDROID_API
ndk=$(basename "${ANDROID_NDK_ROOT:-none}")
configure=$COMMON
source_date_epoch=$SOURCE_DATE_EPOCH
libcrypto_a_sha256=$LIB_SHA
INFO
cat "$OUT/OPENSSL_BUILD.txt"
grep -a -o -m1 "OpenSSL [0-9.]* *[0-9]* [A-Za-z]* [0-9]*" "$OUT/arm64-v8a/libcrypto.a" | head -1
