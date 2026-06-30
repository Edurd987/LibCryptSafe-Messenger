# Инфраструктура транспорта — VPS relay (РАЗВЁРНУТО)

## СТАТУС: РАБОТАЕТ
Транспорт wss:// поднят и подтверждён. Главный блокер проекта СНЯТ.
- VPS: Ubuntu 24.04, IP 87.199.204.168 (провайдер vdsina)
- Домен: cryptsafe-relay.duckdns.org (DuckDNS, бесплатный, -> VPS IP)
- TLS: Let's Encrypt, TLS 1.3, постквантовый X25519MLKEM768 на канале
- Relay: Node.js 22, systemd-сервис, wss:// порт 8080
- Связь в одной сети подтверждена. Между сетями — тест предстоит.

## ВАЖНО: что постквантовое, а что НЕТ
- ТРАНСПОРТ (телефон<->relay): TLS 1.3 уже постквантовый (X25519MLKEM768).
- E2EE-ЯДРО (контент между собеседниками): пока классический ECDH P-256.
  Постквантовый апгрейд ядра (ECDH+Kyber) — см. PQC_FUTURE.md, НЕ сделан.

## HARDENING СЕРВЕРА (сделано)
- apt update/upgrade, новое ядро 6.8.0-124
- SSH: вход только по ключу ed25519, PasswordAuthentication no,
  PermitRootLogin prohibit-password
- ufw: deny incoming, разрешены только 22 (SSH) и 8080 (relay)
- Node.js 22 via NodeSource

## RELAY КАК СЕРВИС
Файл: /etc/systemd/system/libcryptsafe-relay.service
- ExecStart=/usr/bin/node /root/server.js
- Restart=always (автоперезапуск), enabled (автозапуск при ребуте)
- server.js: HTTPS+WebSocket, читает сертификаты Let's Encrypt,
  слепая пересылка байтов (E2EE контент не виден серверу)

## TLS / СЕРТИФИКАТ
- certbot + плагин certbot-dns-duckdns (DNS-проверка по токену)
- сертификат: /etc/letsencrypt/live/cryptsafe-relay.duckdns.org/
- автопродление настроено certbot'ом (90-дневный цикл)
- токен DuckDNS: /root/.secrets/duckdns.ini (chmod 600, НЕ в git!)

## КЛИЕНТ (Android)
- SERVER_URL = wss://cryptsafe-relay.duckdns.org:8080
- usesCleartextTraffic = false (только TLS)

## ПОЧЕМУ DuckDNS+LetsEncrypt, а не РФ-домен/Cloudflare
- РФ-регистраторы могут вшить шпионский сертификат -> MITM на TLS. Отказ.
- Cloudflare Tunnel терминирует TLS у себя (US) -> промежуточная расшифровка. Отказ.
- DuckDNS+LetsEncrypt: TLS терминируется НА СВОЁМ сервере, свой сертификат,
  публичный доверенный CA с Certificate Transparency. Без посредников.

## ПРЕДСТОИТ
- тест связи между разными сетями (мобильный интернет + Wi-Fi)
- надёжный домен 2-го уровня (DuckDNS — временное решение для теста)
- разблокировано: онлайн-нарды (GAME_MOVE), дениабельность, PQC-ядро
