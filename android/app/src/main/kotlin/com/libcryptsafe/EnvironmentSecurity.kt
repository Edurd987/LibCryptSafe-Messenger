package com.libcryptsafe

import android.content.Context
import android.content.pm.PackageManager
import java.security.MessageDigest
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
    // Эталон debug-подписи. На release-этапе заменить через BuildConfig
    // (архитектура двух ключей: debug-хеш НЕ должен попасть в release).
    private const val DEBUG_SIGNATURE_SHA256 =
        "4ee3f65da019d7ed59c7ca23ad50d95d067d89214ede8077f27aaae129007a88"

    /**
     * Проверка целостности: совпадает ли подпись с эталоном.
     * true = подпись наша, false = подмена/переподписан.
     * ЧЕСТНАЯ ГРАНИЦА: на root обходится, реверсер выпилит проверку.
     * Отсекает ленивую переподписку, не абсолют.
     */
    fun isIntegrityOk(context: Context): Boolean {
        val current = getSignatureSha256(context)
        return current.equals(DEBUG_SIGNATURE_SHA256, ignoreCase = true)
    }

    /**
     * SHA-256 хеш подписи приложения (HEX).
     * Современный API: GET_SIGNING_CERTIFICATES (API 28+).
     * Для APK integrity: сравнить с эталоном -> подмена детектируется.
     * ЧЕСТНАЯ ГРАНИЦА: на root можно обойти, реверсер выпилит проверку.
     * Поднимает планку против ленивой переподписи, не абсолют.
     */
    fun getSignatureSha256(context: Context): String {
        return try {
            val pm = context.packageManager
            val pkg = context.packageName
            val signatures = if (android.os.Build.VERSION.SDK_INT >= 28) {
                val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
                info.signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES)
                @Suppress("DEPRECATION")
                info.signatures
            }
            if (signatures.isNullOrEmpty()) return "unknown"
            val md = MessageDigest.getInstance("SHA-256")
            val digest = md.digest(signatures[0].toByteArray())
            digest.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            "error: ${e.message}"
        }
    }
}
