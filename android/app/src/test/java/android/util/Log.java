package android.util;

/**
 * Тестовая подмена android.util.Log для JVM-тестов. В android.jar для юнит-тестов
 * все методы — заглушки ("Method ... not mocked"), а SafeLogger в debug зовёт Log.
 * Только для src/test: в APK не попадает.
 */
public final class Log {
    private Log() {}
    public static int v(String tag, String msg) { return 0; }
    public static int d(String tag, String msg) { return 0; }
    public static int i(String tag, String msg) { return 0; }
    public static int w(String tag, String msg) { return 0; }
    public static int e(String tag, String msg) { return 0; }
    public static int e(String tag, String msg, Throwable tr) { return 0; }
    public static int w(String tag, String msg, Throwable tr) { return 0; }
}
