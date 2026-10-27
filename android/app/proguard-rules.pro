# ============================================================
# LibCryptSafe ProGuard/R8 правила.
# minify ВКЛЮЧЁН в release (build.gradle). №18: строки логов в dex = 0 (ленивый SafeLogger).
# ============================================================

# SafeLogger: при minifyEnabled true R8 ВЫРЕЖЕТ вызовы + строки-аргументы из
# release-байткода (метаданные peerId/пути физически исчезнут даже в дизассемблере).
-assumenosideeffects class com.libcryptsafe.util.SafeLogger {
    public void d(...);
    public void i(...);
    public void w(...);
    public void e(...);
}

# TODO при включении minify — добавить keep-правила:
# - Room entities/DAO (рефлексия)
# - SQLCipher (native)
# - kotlinx.coroutines
# - наши JSON-модели (MediaModels, если через рефлексию)
# Без них release упадёт в рантайме.

# JNI: C++ ищет методы по имени Java_com_libcryptsafe_CryptoManager_*
# Переименование класса/методов = краш крипто-ядра.
-keep class com.libcryptsafe.CryptoManager { *; }

# SQLCipher: нативный код, ищет свои Java-классы по имени.
-keep class net.zetetic.** { *; }
-dontwarn net.zetetic.**
