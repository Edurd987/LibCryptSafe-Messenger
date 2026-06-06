package com.libcryptsafe

import android.content.Context
import android.os.Build
import android.os.Debug
import java.io.File

/**
 * Детектор небезопасной СРЕДЫ (не "сканер шпионов").
 * Проверяет КОСВЕННЫЕ признаки компрометации устройства.
 * Честная граница: ловит обычный root/эмулятор/отладку,
 * но продвинутое сокрытие (Zygisk DenyList) может обойти.
 * НЕ блокирует — только информирует.
 */
object EnvironmentSecurity {

    data class Report(
        val rootDetected: Boolean,
        val isEmulator: Boolean,
        val debuggerAttached: Boolean
    ) {
        val isClean: Boolean
            get() = !rootDetected && !isEmulator && !debuggerAttached
    }

    fun analyze(context: Context): Report {
        return Report(
            rootDetected = checkRoot(),
            isEmulator = checkEmulator(),
            debuggerAttached = checkDebugger()
        )
    }

    // ── ROOT: ищем бинарник su в типичных путях ──
    private fun checkRoot(): Boolean {
        val suPaths = arrayOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/system/su",
            "/system/bin/.ext/.su",
            "/system/usr/we-need-root/su",
            "/data/local/xbin/su",
            "/data/local/bin/su"
        )
        for (path in suPaths) {
            if (File(path).exists()) return true
        }
        // test-keys в сборке = кастомная/неофициальная прошивка
        val tags = Build.TAGS
        if (tags != null && tags.contains("test-keys")) return true
        // признаки Magisk
        val magiskPaths = arrayOf(
            "/sbin/.magisk",
            "/data/adb/magisk",
            "/data/adb/modules"
        )
        for (path in magiskPaths) {
            if (File(path).exists()) return true
        }
        return false
    }

    // ── ЭМУЛЯТОР: маркеры QEMU/Goldfish/generic ──
    private fun checkEmulator(): Boolean {
        val fp = Build.FINGERPRINT ?: ""
        val model = Build.MODEL ?: ""
        val product = Build.PRODUCT ?: ""
        val hardware = Build.HARDWARE ?: ""
        return (fp.startsWith("generic")
                || fp.contains("emulator")
                || fp.contains("sdk_gphone")
                || model.contains("Emulator")
                || model.contains("Android SDK")
                || product.contains("sdk")
                || hardware.contains("goldfish")
                || hardware.contains("ranchu"))
    }

    // ── ОТЛАДЧИК подключён ──
    private fun checkDebugger(): Boolean {
        return Debug.isDebuggerConnected() || Debug.waitingForDebugger()
    }
}
