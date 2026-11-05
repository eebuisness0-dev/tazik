package dev.tazik.inferno.engine

import android.os.Debug
import android.system.Os
import android.system.OsConstants
import java.io.File
import kotlin.concurrent.thread

/** Поток с явным размером стека — то, чего у `kotlin.concurrent.thread` нет,
 * а в Swift задавался через `Thread.stackSize`. Читатели дисплея, VNC и
 * вибрации работают на своих небольших стеках, как в оригинале.
 */
internal fun engineThread(
    name: String,
    stackSize: Long,
    block: () -> Unit,
): Thread = Thread(null, block, name, stackSize).apply { isDaemon = true; start() }

/** Блок — синхронно, но на выделенном потоке с широким стеком: смысл тот же,
 * что у прямого вызова, только стек не дефолтные ~1 МБ. Исключение блока
 * перебрасывается как собственное.
 */
internal fun <T> onWideStack(name: String, stackSize: Long, block: () -> T): T {
    val future = java.util.concurrent.FutureTask(java.util.concurrent.Callable { block() })
    Thread(null, future, name, stackSize).apply { isDaemon = true; start() }
    return try {
        future.get()
    } catch (e: java.util.concurrent.ExecutionException) {
        throw e.cause ?: e
    }
}

/**
 * Порт `Threads.swift`: что делает каждый поток процесса.
 *
 * На iOS это `task_threads` + `thread_basic_info`. На Android те же числа
 * лежат в `/proc/self/task/<tid>/stat`; `running` — состояние `R`, время —
 * utime+stime в тиках.
 */
object Threads {
    data class Snapshot(
        val total: Int,
        val running: Int,
        val cpuSeconds: Double,
    )

    private fun clockTicks(): Long = try {
        Os.sysconf(OsConstants._SC_CLK_TCK)
    } catch (_: Throwable) {
        100L
    }

    private fun taskIds(): List<Int> {
        val dir = File("/proc/self/task")
        return try {
            dir.list()?.mapNotNull { it.toIntOrNull() } ?: emptyList()
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun readStat(tid: Int): String? = try {
        File("/proc/self/task/$tid/stat").readText()
    } catch (_: Throwable) {
        null
    }

    fun snapshot(): Snapshot {
        val ids = taskIds()
        val hz = clockTicks().toDouble()
        var running = 0
        var seconds = 0.0
        for (tid in ids) {
            val stat = readStat(tid) ?: continue
            // Формат: pid (comm) state ppid ... Поля после закрывающей скобки.
            val close = stat.lastIndexOf(')')
            if (close < 0 || close + 2 >= stat.length) continue
            val rest = stat.substring(close + 2).split(' ')
            if (rest.size < 13) continue
            if (rest[0] == "R") running++
            val utime = rest[11].toLongOrNull() ?: 0L
            val stime = rest[12].toLongOrNull() ?: 0L
            seconds += (utime + stime).toDouble() / hz
        }
        return Snapshot(total = ids.size, running = running, cpuSeconds = seconds)
    }

    /** Два замера с интервалом: важна разница. */
    fun report(interval: Double = 3.0, completion: (String) -> Unit) {
        thread(name = "inferno.threads", isDaemon = true) {
            val first = snapshot()
            Thread.sleep((interval * 1000).toLong())
            val second = snapshot()

            val burned = second.cpuSeconds - first.cpuSeconds
            val cores = burned / interval
            val verdict = if (cores < 0.05) {
                L("процессор простаивает — гость не исполняется")
            } else {
                L("загружено ~%.1f ядра — гость исполняется", cores)
            }

            val threadsLine = L("Потоки: %d, из них выполняются %d", second.total, second.running)
            val spent = L(
                L("за %d с сожжено %@ с процессорного времени"),
                interval.toInt(),
                "%.2f".format(burned),
            )
            completion(
                buildString {
                    appendLine(threadsLine)
                    appendLine("  $spent")
                    appendLine("  $verdict")
                    appendLine("  " + L("память приложения: %@", footprint()))
                    append("guest-console.log: " + consoleSize())
                },
            )
        }
    }

    /** Сколько система считает за этим приложением. */
    fun footprint(): String {
        val bytes = try {
            val statm = File("/proc/self/statm").readText().split(' ')
            val pages = statm.getOrNull(1)?.toLongOrNull() ?: 0L
            pages * Os.sysconf(OsConstants._SC_PAGESIZE)
        } catch (_: Throwable) {
            0L
        }
        if (bytes <= 0) {
            val native = Debug.getNativeHeapAllocatedSize()
            return L("%.0f МБ").format(native.toDouble() / 1_048_576.0)
        }
        return L("%.0f МБ").format(bytes.toDouble() / 1_048_576.0)
    }

    private fun consoleSize(): String {
        val file = EngineEnv.guestConsoleLog()
        return if (!file.isFile) {
            L("файла нет")
        } else {
            L("%d байт", file.length())
        }
    }
}
