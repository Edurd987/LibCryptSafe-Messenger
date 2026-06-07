# КОНТЕКСТ ПРОЕКТА (для ввода нового ассистента в курс)

## ЧТО ЭТО
LibCryptSafe Messenger — защищённый E2E мессенджер.
Автор: Eduard (GitHub: Edurd987).
Репозиторий: github.com/Edurd987/LibCryptSafe-Messenger

## АРХИТЕКТУРА
- C++ ядро: ECDH (P-256) + AES-256-GCM (OpenSSL), JNI-мост
- Android: Kotlin, View+XML (НЕ Compose), MainActivity
- Хранение: Room + SQLCipher (БД зашифрована, ключ в Keystore)
- Сеть: Node.js WebSocket relay (СЛЕПОЙ — видит байты, не текст)
- Среда: Windows MSYS2 CLANG64, Android Studio, тест на Xiaomi
  по USB, relay на ПК (192.168.1.152:8080)

## ЧТО РАБОТАЕТ (проверено на 2 телефонах)
- E2EE переписка между устройствами
- SQLCipher шифрование БД
- FLAG_SECURE (защита экрана, тумблер в хабе "Ещё")
- Детектор небезопасной среды (root/эмулятор/отладчик)
- APK integrity (проверка подписи)
- i18n (EN дефолт + RU)

## SECURITY СТАТУС: hardening 3 из 4
 done: FLAG_SECURE, детектор среды, APK integrity
осталось: wss:// + cleartext=false (нужен VPS)
Все модели угроз и границы — в SECURITY_HARDENING_ROADMAP.md

## КАК РАБОТАЕМ (важно для ассистента-наставника)
- Eduard ведёт, ассистент даёт ГОТОВЫЕ команды для MSYS2,
  Eduard запускает и показывает вывод
- файлы создаём через: python - << 'PYEOF' (надёжнее sed с $)
- ПРОВЕРЯЕМ факт после каждого шага (grep, wc, сборка)
- КОММИТИМ рабочие вехи СРАЗУ
- бэкап перед правкой: cp file file_BACKUP (в .gitignore)
- скилл Eduard: сильный архитектор/security-мышление,
  но код руками пишет мало (через команды ассистента)
- ЦЕННОСТЬ: честность, не обещать невозможного (ни обход DPI,
  ни защиту от Pegasus/root, ни "сканер шпионов")
- "больше кода = больше дыр", "работает — не трогай"

## ДОКУМЕНТЫ-ПАМЯТЬ (читать для контекста)
- SECURITY_HARDENING_ROADMAP.md — защита, модели угроз, горизонт
- AI_PLAN_V2_QWEN.md — стратегия AI (отложено до V100+чёткой цели)
- RELEASE_CHECKLIST.md — план Google Play
- TODO_MINOR.md — мелкие хвосты (SpannableString warning)
- I18N_ROADMAP.md — локализация

## СЛЕДУЮЩИЙ ПРИОРИТЕТ
1. VPS + wss:// (главное, разблокирует hardening + релиз)
2. фоновые уведомления (нужен foreground service, осторожно)
3. мелочи (Spannable, тумблер)
НЕ раньше: AI (нужен V100 + чёткая цель), Double Ratchet (книги+libsignal)
