package dev.tazik.inferno

import android.content.Context
import dev.tazik.inferno.data.BuildManifestInfo
import dev.tazik.inferno.data.DeviceStore
import dev.tazik.inferno.data.PlistParser
import dev.tazik.inferno.engine.L
import dev.tazik.inferno.engine.LogCapture
import dev.tazik.inferno.engine.QemuBridge
import dev.tazik.inferno.engine.RestoreSession
import dev.tazik.inferno.engine.VMConfig
import dev.tazik.inferno.engine.VMModel
import dev.tazik.inferno.service.MachineService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Клей между приложением и движком: одна модель машины на весь процесс.
 *
 * QEMU не реентерабелен и живёт в этом процессе — второй запуск утащил бы
 * приложение за собой, так что модель здесь ровно одна, как в Inferno.
 */
object TazikEngine {

    val model: VMModel by lazy { VMModel() }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Служба переднего плана заведена, пока машина работает. */
    @Volatile
    private var serviceUp = false

    fun initialize(context: Context) {
        val app = context.applicationContext
        scope.launch {
            model.qemuState.collect { state ->
                when (state) {
                    is QemuBridge.State.Running -> {
                        if (!serviceUp) {
                            serviceUp = true
                            MachineService.ensureForeground(app)
                        }
                    }
                    is QemuBridge.State.Stopped, is QemuBridge.State.Failed -> {
                        if (serviceUp) {
                            serviceUp = false
                            MachineService.stopForegroundQuiet(app)
                        }
                    }
                    QemuBridge.State.Idle -> {}
                }
            }
        }
        scope.launch(Dispatchers.IO) { backfillFirmware(app) }
    }

    /** Карточка устройства может не знать о уже готовом наборе: прежние
     * сборки не записывали версию после «Установить iOS», и кнопка запуска
     * оставалась серой, хотя файлы лежали в InfernoData. Лечится по
     * BuildManifest набора, который подготовка распаковала на место.
     */
    private fun backfillFirmware(context: Context) {
        val manifest = java.io.File(VMConfig.dataDirectory, "Restore/BuildManifest.plist")
        if (!manifest.isFile) return
        val info = runCatching {
            manifest.inputStream().use { PlistParser.parse(it) }
        }.getOrNull()?.let { BuildManifestInfo.from(it) } ?: return
        if (info.buildId.isBlank()) return

        val store = DeviceStore.get(context)
        val localIpsw = VMConfig.dataDirectory
            .listFiles { f: java.io.File -> f.isFile && f.name.endsWith(".ipsw") }
            ?.maxByOrNull { it.length() }?.name
        var changed = false
        for (device in store.devices.value) {
            if (device.firmwareBuild.isNotBlank()) continue
            store.put(
                device.copy(
                    firmwareVersion = info.version,
                    firmwareBuild = info.buildId,
                    firmwareFile = device.firmwareFile.ifBlank { localIpsw ?: "" },
                )
            )
            changed = true
        }
        if (changed) LogCapture.note(L("Карточка: iOS %@ (%@) дописана по набору", info.version, info.buildId))
    }

    /** Обычный запуск: установленная система с диска. */
    fun startMachine(context: Context): Boolean {
        val app = context.applicationContext
        MachineService.ensureForeground(app)
        model.start()
        return true
    }

    /** Восстановление iOS: рестор сам поднимает машину на рамдиске. */
    fun startRestore(context: Context) {
        val app = context.applicationContext
        MachineService.ensureForeground(app)
        RestoreSession.shared.start(model)
    }
}
