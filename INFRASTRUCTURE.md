# Инфраструктура транспорта — VPS relay (РАЗВЁРНУТО)

## СТАТУС: РАБОТАЕТ
Транспорт wss:// поднят и подтверждён. Главный блокер проекта СНЯТ.
- VPS: Ubuntu 24.04, IP 87.199.204.168 (провайдер vdsina)
- Домен: cryptsafe-relay.duckdns.org (DuckDNS, бесплатный, -> VPS IP)
- TLS: Let's Encrypt, TLS 1.3, постквантовый X25519MLKEM768 на канале
- Relay: Node.js 22 (22.23.4 с 26.10.2026), systemd-сервис libcryptsafe-relay-443, wss:// порт 443
- Связь ПОДТВЕРЖДЕНА между разными сетями, вне белых списков. Устойчива.

## ВАЖНО: что постквантовое, а что НЕТ
- ТРАНСПОРТ (телефон<->relay): TLS 1.3 уже постквантовый (X25519MLKEM768).
- E2EE-ЯДРО (контент между собеседниками): пока классический ECDH P-256.
  Постквантовый апгрейд ядра (ECDH+Kyber) — см. PQC_FUTURE.md, НЕ сделан.

## HARDENING СЕРВЕРА (сделано)
- apt update/upgrade; ядро 6.8.0-147 (26.10.2026; ядро перезагружаем ВРУЧНУЮ)
- SSH: вход только по ключу ed25519, PasswordAuthentication no,
  PermitRootLogin prohibit-password
- ufw: deny incoming, разрешены только 22 (SSH) и 8080 (relay) [УСТАРЕЛО? relay теперь на 443 - перепроверить ufw status]
- Node.js 22 via NodeSource
- unattended-upgrades (с 26.10.2026): только security, Automatic-Reboot выкл.
  Таймеры apt-daily + apt-daily-upgrade: были INACTIVE до 29.10.2026 (патчи копились),
  включены enable --now; контроль: scripts/run_security_tests.sh (VPS: таймеры)

## RELAY КАК СЕРВИС
Сервис: libcryptsafe-relay-443 (User=libcrypt, non-root)
- ExecStart=/usr/bin/node /opt/libcryptsafe/server.js (WorkingDirectory=/opt/libcryptsafe)
- Restart=always (автоперезапуск), enabled (автозапуск при ребуте)
- server.js: HTTPS+WebSocket, читает сертификаты Let's Encrypt,
  слепая пересылка байтов (E2EE контент не виден серверу)

## TLS / СЕРТИФИКАТ
- certbot + плагин certbot-dns-duckdns (DNS-проверка по токену)
- сертификат: /etc/letsencrypt/live/cryptsafe-relay.duckdns.org/
- автопродление настроено certbot'ом (90-дневный цикл); certbot.timer active,
  renew --dry-run успешен 29.10.2026; контроль срока и таймера - run_security_tests.sh
- токен DuckDNS: /root/.secrets/duckdns.ini (chmod 600, НЕ в git!)

## КЛИЕНТ (Android)
- SERVER_URL = wss://cryptsafe-relay.duckdns.org/api/v1/sync (порт 443)
- usesCleartextTraffic = false (только TLS)

## ПОЧЕМУ DuckDNS+LetsEncrypt, а не РФ-домен/Cloudflare
- РФ-регистраторы могут вшить шпионский сертификат -> MITM на TLS. Отказ.
- Cloudflare Tunnel терминирует TLS у себя (US) -> промежуточная расшифровка. Отказ.
- DuckDNS+LetsEncrypt: TLS терминируется НА СВОЁМ сервере, свой сертификат,
  публичный доверенный CA с Certificate Transparency. Без посредников.

## ТРАНСПОРТ ЗАВЕРШЁН
Связь между разными сетями подтверждена — транспорт работает полностью.

## ПРЕДСТОИТ (не транспорт)
- надёжный домен 2-го уровня (DuckDNS — временное решение)
- ОФЛАЙН-ДОСТАВКА (relay копит сообщения для отключённых) — следующий приоритет
- разблокировано: онлайн-нарды (GAME_MOVE), дениабельность, PQC-ядро
