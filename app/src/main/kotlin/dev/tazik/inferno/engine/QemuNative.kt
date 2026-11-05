package dev.tazik.inferno.engine

import java.io.File
import java.nio.ByteBuffer

/**
 * Нативные вызовы эмулятора — то, что в Swift делали `dlopen`/`dlsym` прямо из
 * `QemuBridge`. Библиотека уже в `jniLibs`, так что её открывает C-мост по
 * абсолютному пути из `nativeLibraryDir`, а все символы `inferno_*` достаются
 * там же.
 *
 * Состояния доставляются колбэком `onState` из потока эмулятора: 1 — машина
 * встала, 2 — закончилась с кодом выхода, 3 — сорвалась (текст — в
 * `lastError`).
 */
object QemuNative {

    init {
        System.loadLibrary("tazikqemu")
    }

    /** Зовётся из нативного потока. Ставится один раз `QemuBridge`. */
    @Volatile
    var stateListener: ((state: Int, status: Int) -> Unit)? = null

    @JvmStatic
    private fun onState(state: Int, status: Int) {
        stateListener?.invoke(state, status)
    }

    private val libPath: File
        get() = File(
            EngineEnv.requireContext().applicationInfo.nativeLibraryDir,
            "libqemu-aarch64-softmmu.so",
        )

    /** Заводит машину. `attachDisplay` — то, чем в Swift был `afterInit`:
     * единственная щель между `qemu_init` и `qemu_main_loop`, когда к дисплею
     * можно прицепиться.
     */
    fun start(
        arguments: List<String>,
        environment: Map<String, String> = emptyMap(),
        attachDisplay: Boolean = false,
        workDir: File? = VMConfig.socketDirectory,
        stderrLog: File? = EngineEnv.qemuStderrLog,
    ): Boolean = nativeStart(
        libPath.absolutePath,
        arguments.toTypedArray(),
        environment.map { (name, value) -> "$name=$value" }.toTypedArray(),
        attachDisplay,
        workDir?.absolutePath,
        stderrLog?.absolutePath,
    )

    val isRunning: Boolean
        get() = nativeIsRunning()

    fun lastError(): String = nativeLastError()

    /** Есть ли в загруженной библиотеке такой экспорт. Доступно лишь после
     * старта машины — то есть тогда, когда оно и нужно.
     */
    fun hasSymbol(name: String): Boolean = nativeHasSymbol(name)

    fun displayRead(buffer: ByteBuffer?, bytes: Int, info: IntArray): Int =
        nativeDisplayRead(buffer, bytes, info)

    /** `presents` и `refreshes` с прошлого чтения; чтение их обнуляет. */
    fun displayStats(): LongArray = nativeDisplayStats()

    fun inputTouch(x: Int, y: Int, pressed: Boolean) = nativeInputTouch(x, y, pressed)

    fun inputFunctionKey(key: Int, pressed: Boolean) = nativeInputFunctionKey(key, pressed)

    fun batterySet(percent: Int, external: Boolean, charging: Boolean) =
        nativeBatterySet(percent, external, charging)

    fun netLinkUp(): Boolean = nativeNetLinkUp()

    /** Кадры вибрации, по два float на кадр (уровень, герцы); ждёт до `waitMs`. */
    fun hapticsRead(frames: FloatArray, capacity: Int, waitMs: Long): Int =
        nativeHapticsRead(frames, capacity, waitMs)

    private external fun nativeStart(
        libPath: String,
        args: Array<String>,
        envPairs: Array<String>,
        attachDisplay: Boolean,
        workDir: String?,
        stderrLog: String?,
    ): Boolean

    private external fun nativeIsRunning(): Boolean
    private external fun nativeLastError(): String
    private external fun nativeHasSymbol(name: String): Boolean
    private external fun nativeDisplayRead(buffer: ByteBuffer?, bytes: Int, info: IntArray): Int
    private external fun nativeDisplayStats(): LongArray
    private external fun nativeInputTouch(x: Int, y: Int, pressed: Boolean)
    private external fun nativeInputFunctionKey(key: Int, pressed: Boolean)
    private external fun nativeBatterySet(percent: Int, external: Boolean, charging: Boolean)
    private external fun nativeNetLinkUp(): Boolean
    private external fun nativeHapticsRead(frames: FloatArray, capacity: Int, waitMs: Long): Int
}
