package dev.tazik.inferno.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Загружает библиотеку эмулятора Inferno и ведёт её жизненный цикл.
 *
 * Нативная половина — в `qemu_bridge.c` и `QemuNative`: там dlopen, поток и
 * три точки входа. Этот фасад держит состояние, окружение и флаг дисплея.
 *
 * QEMU не реентерабелен и живёт внутри этого процесса. Раз машина заведена,
 * вторая в том же процессе утащила бы приложение за собой, так что снова
 * запускать — значит перезапускать приложение.
 */
class QemuBridge private constructor() {

    sealed class State {
        object Idle : State()
        object Running : State()
        data class Stopped(val status: Int) : State()
        data class Failed(val why: String) : State()
    }

    companion object {
        val shared = QemuBridge()
    }

    private val _state = MutableStateFlow<State>(State.Idle)

    /** Перемены жизненного цикла эмулятора. */
    val state: StateFlow<State> = _state.asStateFlow()

    /** Зовётся при каждой перемене состояния, с того же нативного потока —
     * то, чем в Swift был `onStateChange`. Ставящий отвечает за замену своего
     * прежнего замыкания.
     */
    @Volatile
    var onStateChange: ((State) -> Unit)? = null

    init {
        QemuNative.stateListener = { native, status ->
            val next = when (native) {
                1 -> State.Running
                2 -> State.Stopped(status)
                else -> State.Failed(QemuNative.lastError())
            }
            _state.value = next
            onStateChange?.invoke(next)
        }
    }

    /** Правды дисплея здесь нет — Swift ставил `afterInit`, чтобы прицепиться
     * к дисплею в единственной безопасной щели после `qemu_init`. На Android
     * эта щель внутри C-моста, поэтому наружу выходит один флаг.
     */
    @Volatile
    var attachDisplay: Boolean = false

    /** Окружение, которое эмулятор читает вместо командной строки. Это не
     * свойства машины — они выбирают между двумя способами сделать одно и то
     * же, и существуют, чтобы оба можно было сравнить на устройстве, где
     * честная мера — частота кадров. Ставится до `start`; читается один раз,
     * на подъёме машины.
     */
    @Volatile
    var environment: Map<String, String> = emptyMap()

    fun start(arguments: List<String>) {
        if (_state.value is State.Running) return

        LogCapture.note(L("Аргументы:\n  ") + arguments.joinToString(" "))
        for ((name, value) in environment) {
            LogCapture.note("$name=$value")
        }

        val ok = QemuNative.start(
            arguments = arguments,
            environment = environment,
            attachDisplay = attachDisplay,
            workDir = VMConfig.socketDirectory,
            stderrLog = EngineEnv.qemuStderrLog,
        )
        if (!ok) {
            _state.value = State.Failed(QemuNative.lastError())
        }
    }
}
