# Сверка зависимостей — 2026-10-30 (доказательство к №19)

Инструменты: devops-saas (парсер gradle.lockfile с разделением "в приложении /
только сборка", коммиты devops-saas 0fae1b2, b75b847, тесты 1d33c0a) и
osv-scanner (scripts/scan_deps.sh) — независимо друг от друга.

## Охват
- android/app/gradle.lockfile — 134 пакета (Maven)
- relay/package.json + package-lock.json — 2 прямых + 37 транзитивных = 39 (npm)
- Оба инструмента: одинаковые объёмы, 134 и 39.

## Найдено (до исправления)
- APK: 0 уязвимых пакетов; relay: 0 уязвимых пакетов.
- Только сборка: com.google.guava:guava 31.1-jre — исключительно в ksp*-конфигурациях
  (процессор Room), в APK не попадает. Advisory (OSV):
  - GHSA-5mg8-w23w-74h3 / CVE-2020-8908 — был принят в №19
  - GHSA-7g45-4rm6-3mm3 / CVE-2023-2976 — был принят в №19
  - GHSA-xxph-c9ww-hj94 / CVE-2026-102554 — НОВЫЙ: неконтролируемое выделение памяти
    при Java-десериализации CompactHashMap/CompactHashSet/MapMakerInternalMap (DoS).
    Опубликован 2026-10-27, после закрытия №19. Исправлен в 33.7.2.
    Оценка: сканеры показывают 8.2 / HIGH; в самой записи CVSS 3.1 = 5.9.
- osv-scanner: два известных advisory отфильтрованы (osv-scanner.toml), новый — нет:
  exit=1, гейт красный. devops-saas: уязвимых в приложении 0, в среде сборки 1.

## Применимость к проекту
Условие атаки — ObjectInputStream.readObject() на данных атакующего при guava в classpath.
В android/app/src и relay вхождений ObjectInputStream/readObject нет (проверено поиском).
guava работает только в KSP на машине сборки. Практического риска не было, но
advisory был вне списка принятых рисков — статус №19 "ЗАКРЫТО" перестал соответствовать факту.

## После исправления (2026-10-31, коммит 0a63609)
- guava поднята до 33.7.2-jre только в ksp*-конфигурациях (build.gradle, resolutionStrategy.force).
- Блокировка версий сработала: со старым lock-файлом сборка упала, lock перегенерирован.
- osv-scanner.toml удалён: принятых рисков 0. scripts/scan_deps.sh: No issues found, exit=0.
- Debug-сборка и unit-тесты: зелёные.

## Вне рамок отчёта
devops-saas внёс ssl_1.patch, ssl_3.patch и root_ye.pem в список просканированных
файлов (зависимостей в них нет) — долг devops-saas, к аудиту LibCryptSafe не относится.
