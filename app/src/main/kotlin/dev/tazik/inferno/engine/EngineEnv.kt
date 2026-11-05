package dev.tazik.inferno.engine

import android.content.Context
import java.io.File

/**
 * Всё, что движку нужно знать о хосте.
 *
 * Swift-версия брала пути из `FileManager` и бандла приложения. На Android
 * эквивалент — `Context.filesDir` и распакованные ресурсы; этот объект
 * заполняется один раз в `TazikApp`, а движок дальше работает только с ним.
 */
object EngineEnv {
    @Volatile
    var appContext: Context? = null

    /** Куда QEMU смотрит за keymaps и прочими data-файлами (`-L`). */
    @Volatile
    var qemuDataDir: File? = null

    /** Каталог с гостевыми программами (`guest-tools`, `guest`). */
    @Volatile
    var guestToolsDir: File? = null

    /** Порт, на котором `GuestUSB` слушает USB-хост (TCP loopback). */
    @Volatile
    var usbPort: Int = 8030

    /** Файл, куда JNI перенаправляет stdout/stderr QEMU. */
    @Volatile
    var qemuStderrLog: File? = null

    /** Каталог документов приложения — аналог `VMConfig.documents`. */
    val documents: File
        get() = requireContext().filesDir

    fun requireContext(): Context =
        appContext ?: error(L("EngineEnv.appContext не задан: вызовите EngineEnv.install(context)"))

    fun install(context: Context) {
        appContext = context.applicationContext
        val share = File(context.filesDir, "inferno/share")
        if (!share.isDirectory) share.mkdirs()
        if (qemuDataDir == null) qemuDataDir = share
        val guest = File(context.filesDir, "inferno/guest")
        if (!guest.isDirectory) guest.mkdirs()
        // Гостевые инструменты едут в assets (в Swift они лежали в бандле
        // приложения). Упаковщик снимает суффикс «.gz», так что пробуем оба
        // имени. Распаковка каждый запуск — дешевле одного сравнения размеров,
        // чем контроль версий. Патчер с демоном (`RestoreRamdisk.programs`)
        // едет с ними же: без них рамдиска с патчем не собрать.
        for (name in listOf("nsio", "agent.gz", "sbnet.gz", "inferno_patcher", "inferno_fs_patcher")) {
            val out = File(guest, name)
            if (out.isFile && out.length() > 0) continue
            val input = try {
                context.assets.open("guest-tools/$name")
            } catch (_: Throwable) {
                try {
                    context.assets.open("guest-tools/${name.removeSuffix(".gz")}")
                } catch (_: Throwable) {
                    null
                }
            } ?: continue
            try {
                input.use {
                    out.outputStream().use { output -> it.copyTo(output) }
                }
            } catch (_: Throwable) {
            }
        }
        if (guestToolsDir == null) guestToolsDir = guest
        if (qemuStderrLog == null) qemuStderrLog = File(context.filesDir, "inferno/qemu-stderr.log")
    }

    fun dataDirectory(): File = File(documents, "InfernoData")

    fun transferImage(): File = File(dataDirectory(), "xfer")

    fun guestConsoleLog(): File = File(documents, "guest-console.log")

    fun sepRom(): File = File(documents, "AppleSEPROM-Cebu-B1")

    fun absoluteOf(relative: String): File =
        if (relative.startsWith("/")) File(relative) else File(documents, relative)

    fun freeSpace(): Long? = try {
        documents.usableSpace.takeIf { it > 0 }
    } catch (_: Throwable) {
        null
    }
}
