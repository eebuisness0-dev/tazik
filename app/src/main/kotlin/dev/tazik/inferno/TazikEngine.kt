package dev.tazik.inferno

import android.content.Context
import dev.tazik.inferno.engine.LogCapture
import dev.tazik.inferno.engine.QemuBridge
import dev.tazik.inferno.engine.RestoreSession
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
