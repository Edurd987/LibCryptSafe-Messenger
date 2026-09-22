# ============================================================
# LibCryptSafe ProGuard/R8 правила.
# СЕЙЧАС minify ВЫКЛЮЧЕН (этап A) — эти правила лежат для будущего включения.
# ============================================================

# SafeLogger: при minifyEnabled true R8 ВЫРЕЖЕТ вызовы + строки-аргументы из
# release-байткода (метаданные peerId/пути физически исчезнут даже в дизассемблере).
-assumenosideeffects class com.libcryptsafe.util.SafeLogger {
    public static void d(...);
    public static void i(...);
    public static void w(...);
    public static void e(...);
}

# TODO при включении minify — добавить keep-правила:
# - Room entities/DAO (рефлексия)
# - SQLCipher (native)
# - kotlinx.coroutines
# - наши JSON-модели (MediaModels, если через рефлексию)
# Без них release упадёт в рантайме.
