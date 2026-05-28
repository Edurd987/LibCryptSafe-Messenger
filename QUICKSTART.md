# LibCryptSafe Messenger — Quick Start

## Требования
- Windows + MSYS2 CLANG64
- Android Studio (AGP 8.3.2, Gradle 8.4, CMake 3.22.1)
- Node.js (через /mingw64/bin/)
- 2 Android устройства (ARM64) в одной WiFi сети

## 1. Запуск relay сервера
```bash
cd ~/LibCryptSafe-Messenger/relay
node server.js
# Слушает на 0.0.0.0:8080
```

## 2. Сборка APK
- Открыть `android/` в Android Studio
- Build → Build APK
- abiFilters: только arm64-v8a
- Установить на оба телефона через USB

## 3. Настройка Xiaomi
- Настройки → Для разработчиков → Установка через USB → ВКЛ
- USB отладка → ВКЛ

## 4. Тест E2EE
- Запустить relay: `node server.js`
- Открыть приложение на обоих телефонах
- Дождаться "ECDH handshake выполнен!"
- Отправить сообщение — должно расшифроваться на втором

## IP адреса (домашняя сеть)
- Relay сервер: 192.168.1.152:8080
- Xiaomi Gen S3: 192.168.1.76
- Xiaomi Elite: 192.168.1.90

## Ключевые файлы
- `LibCryptSafe/src/Cryptor.cpp` — AES-256-GCM
- `MessengerApp/KeyExchange.hpp` — ECDH P-256
- `android/app/src/main/cpp/jni_bridge.cpp` — JNI мост
- `android/app/src/main/kotlin/com/libcryptsafe/MainActivity.kt` — UI
- `relay/server.js` — WebSocket relay

## Если порт 8080 занят
```bash
netstat -ano | grep 8080
taskkill /PID <номер> /F
```
