package dev.tazik.inferno

import android.app.Application
import dev.tazik.inferno.data.DeviceStore
import dev.tazik.inferno.data.DeviceProbe
import dev.tazik.inferno.data.SettingsStore
import dev.tazik.inferno.engine.EngineEnv
import dev.tazik.inferno.engine.JIT
import dev.tazik.inferno.engine.LogCapture
import java.io.File
import kotlin.concurrent.thread

class TazikApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DeviceStore.get(this)
        SettingsStore.get(this)
        DeviceProbe.probe(this)
        // Движок: пути, гостевые инструменты, журнал. Дальше всё, что нужно
        // машине, идёт через него.
        EngineEnv.install(this)
        LogCapture.start()
        thread(name = "tazik.jit", isDaemon = true) {
            JIT.prepare()
        }
        TazikEngine.initialize(this)
        extractKeymaps()
    }

    /**
     * QEMU смотрит за keymaps в `-L` каталоге (qemu_find_file). Едут в assets,
     * распаковываются при первом запуске — как в старом слое, каталог тот же,
     * что берёт движок.
     */
    private fun extractKeymaps() {
        val share = EngineEnv.qemuDataDir ?: return
        val target = File(share, "keymaps")
        try {
            val names = assets.list("keymaps") ?: return
            if (names.isEmpty()) return
            target.mkdirs()
            for (name in names) {
                val out = File(target, name)
                if (out.isFile && out.length() > 0) continue
                assets.open("keymaps/$name").use { input ->
                    out.outputStream().use { output -> input.copyTo(output) }
                }
            }
        } catch (_: Exception) {
            // keymaps опциональны; `-L` каталог нужен и без них.
        }
    }
}
