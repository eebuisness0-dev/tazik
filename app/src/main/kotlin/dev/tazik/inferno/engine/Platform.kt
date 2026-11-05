package dev.tazik.inferno.engine

import android.app.ActivityManager
import android.os.Build
import java.io.File

/**
 * Порт `Platform.swift`.
 *
 * В SwiftUI-версии здесь было то, что iOS и macOS пишут по-разному. На Android
 * платформа одна, поэтому остаются только сведения о хосте, которые движок
 * пишет в лог, и структура отступов экрана (её использует Compose-дизайн Tazik).
 */

/** Насколько мебель самого устройства заходит в экран. */
data class ScreenInsets(
    var top: Float = 0f,
    var bottom: Float = 0f,
)

object Platform {
    val isDebugBuild: Boolean
        get() = (EngineEnv.appContext?.applicationInfo?.flags ?: 0) and
            android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0

    /** Аналог `hw.machine`: модель устройства. */
    val machine: String
        get() = "${Build.MANUFACTURER} ${Build.MODEL}"

    val systemName: String get() = "Android"

    val systemRelease: String
        get() = "${Build.VERSION.RELEASE} (${Build.ID})"

    val physicalMemoryBytes: Long
        get() {
            val ctx = EngineEnv.appContext ?: return 0
            val am = ctx.getSystemService(ActivityManager::class.java) ?: return 0
            val info = ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            return info.totalMem
        }

    /** Строка для шапки лога — тот же смысл, что `noteDevice` в Swift. */
    fun deviceLine(): String = L(
        L("Устройство: %@, %@ %@ (%@), память %.1f ГБ"),
        machine,
        systemName,
        systemRelease,
        Build.ID,
        physicalMemoryBytes.toDouble() / 1_073_741_824.0,
    )
}

/** Каталог, куда QEMU должен сделать chdir (в Swift — `socketDirectory`). */
internal fun socketDirectory(): File = EngineEnv.requireContext().cacheDir
