# MITM-проверка клиента (аудит №32)

Цель: доказать, что клиент не подключается к relay через прокси-перехватчик,
даже если корень перехвата установлен на телефоне.
- **A** — корень в пользовательском хранилище → отказ на доверии (`Trust anchor not found`).
- **B** — корень в системном хранилище («телефон от государства») → отказ на пинах
  (`SSLPeerUnverifiedException: Certificate pinning failure`).

Контроль в обоих случаях — Chrome эмулятора: `https://example.com` должен открыться
без предупреждения, а mitmproxy — показать `GET https://example.com/`. Без этого
отказы нашего клиента ничего не доказывают.

## Стенд
- Windows, MSYS2; mitmproxy (установщик с mitmproxy.org).
- Эмулятор: **Pixel 5, API 30, x86_64, Google APIs** (не Play Store, не x86 —
  наша нативная библиотека только arm64, x86_64-образ её транслирует).
- Переменные (задавать заново в каждом новом окне MSYS!):
```bash
  ADB=/c/Users/79384/AppData/Local/Android/Sdk/platform-tools/adb.exe; E=emulator-5554
  EMU=/c/Users/79384/AppData/Local/Android/Sdk/emulator/emulator.exe
```

## 1. Прокси и эмулятор
```bash
taskkill //F //IM mitmdump.exe 2>/dev/null          # порт 8080 должен быть свободен
"/c/Program Files/mitmproxy/bin/mitmdump.exe" --listen-port 8080 > /c/Users/79384/mitm.log 2>&1 &
$EMU -avd Pixel_5 > /dev/null 2>&1 &                 # без -writable-system!
$ADB -s $E wait-for-device
MSYS_NO_PATHCONV=1 $ADB -s $E shell settings put global http_proxy 10.0.2.2:8080
```

## 2A. Пользовательский корень
```bash
MSYS_NO_PATHCONV=1 $ADB -s $E push "C:/Users/79384/.mitmproxy/mitmproxy-ca-cert.cer" /sdcard/Download/
```
Эмулятор: Settings → поиск «certificate» → CA certificate → Install anyway → Download →
файл. Потребует PIN блокировки экрана.

## 2B. Системный корень (tmpfs, без remount)
```bash
H=$(openssl x509 -inform PEM -subject_hash_old -in /c/Users/79384/.mitmproxy/mitmproxy-ca-cert.pem -noout)
echo "H=[$H]"                                        # 8 символов, иначе стоп
cp /c/Users/79384/.mitmproxy/mitmproxy-ca-cert.pem /c/Users/79384/$H.0
MSYS_NO_PATHCONV=1 $ADB -s $E root
MSYS_NO_PATHCONV=1 $ADB -s $E push "C:/Users/79384/$H.0" /data/local/tmp/
MSYS_NO_PATHCONV=1 $ADB -s $E shell "rm -rf /data/local/tmp/certs; mkdir /data/local/tmp/certs; cp /system/etc/security/cacerts/* /data/local/tmp/certs/; cp /data/local/tmp/$H.0 /data/local/tmp/certs/; mount -t tmpfs tmpfs /system/etc/security/cacerts; cp /data/local/tmp/certs/* /system/etc/security/cacerts/; chmod 644 /system/etc/security/cacerts/*; chcon u:object_r:system_file:s0 /system/etc/security/cacerts/*; ls /system/etc/security/cacerts | wc -l"
MSYS_NO_PATHCONV=1 $ADB -s $E shell am force-stop com.android.chrome
```
Ждём 139 (138 системных + 1). Снять: `umount /system/etc/security/cacerts` или перезагрузка.

## 3. Прогон
```bash
cd android
$ADB -s $E install -r -t app/build/outputs/apk/debug/app-debug.apk
$ADB -s $E install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
$ADB -s $E shell am instrument -w -e class com.libcryptsafe.PinningTest com.libcryptsafe.messenger.test/androidx.test.runner.AndroidJUnitRunner > /tmp/mitm.txt 2>&1
grep -E "^Error in|Exception|^Tests run" /tmp/mitm.txt | cut -c1-160
grep -i cryptsafe /c/Users/79384/mitm.log | grep -iE "GET|POST" || echo "к relay ни одного запроса"
```
Ожидаемо (это успех): тесты подключения к relay падают — A: `Trust anchor`,
B: `Certificate pinning failure`. В B также «падают» badssl/IP с `получено: null` —
там пинов нет, перехват удаётся; это иллюстрация, а не дыра.

## 4. Уборка
```bash
MSYS_NO_PATHCONV=1 $ADB -s $E shell settings put global http_proxy :0
$ADB -s $E reboot
taskkill //F //IM mitmdump.exe
```

## Ловушки (каждая стоила нам времени)
- **MSYS переписывает пути с `/`** для Windows-программ (`/sdcard` → `C:/msys64/sdcard`):
  `MSYS_NO_PATHCONV=1`, а пути на компьютере — в виде `C:/...`.
- **Порт 8080 занят** прошлым mitmdump: `kill %1` не убивает Windows-процесс — `taskkill //F //IM mitmdump.exe`.
- **Многострочные блоки при вставке склеиваются** — переменные теряются (`-s: command not found`).
  Проверять `echo "H=[$H]"` перед использованием.
- **tmpfs прячет исходные корни** — сначала копировать их, потом монтировать.
- **Без метки SELinux `system_file`** система корень игнорирует.
- **`remount` системного раздела** повесил эмулятор на Android 11 x86_64 — не использовать.
- **Проверять в Chrome эмулятора**, а не на компьютере.
- `emulator -wipe-data` возвращает эмулятор к заводскому состоянию, если всё сломалось.
