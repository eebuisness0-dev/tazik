package dev.tazik.inferno.engine

import android.system.Os
import android.system.OsConstants
import java.io.File
import kotlin.concurrent.thread

/**
 * Порт `Sampler.swift`: находит поток, который жжёт процессор, и говорит, где
 * он исполняется.
 *
 * На iOS регистры вытаскивались через `thread_get_state`, а адрес назывался
 * через `dladdr`. Android не даёт чужому потоку прочитать PC без `ptrace`,
 * поэтому здесь тот же вопрос решается по `/proc`: имя потока, состояние и
 * сколько времени он сжёг. Этого хватает, чтобы отличить «крутится транслятор»
 * от «эмулятор встал в цикле».
 */
object Sampler {

    private data class Sample(val tid: Int, val name: String, val state: String, val cpu: Double)

    private fun clockTicks(): Long = try {
        Os.sysconf(OsConstants._SC_CLK_TCK)
    } catch (_: Throwable) {
        100L
    }

    private fun sample(tid: Int, hz: Double): Sample? {
        val stat = try {
            File("/proc/self/task/$tid/stat").readText()
        } catch (_: Throwable) {
            return null
        }
        val close = stat.lastIndexOf(')')
        if (close < 0 || close + 2 >= stat.length) return null
        val rest = stat.substring(close + 2).split(' ')
        if (rest.size < 13) return null
        val comm = stat.substring(stat.indexOf('(') + 1, close)
        val utime = rest[11].toLongOrNull() ?: 0L
        val stime = rest[12].toLongOrNull() ?: 0L
        return Sample(tid, comm, rest[0], (utime + stime).toDouble() / hz)
    }

    private fun samples(): List<Sample> {
        val hz = clockTicks().toDouble()
        return File("/proc/self/task").list()?.mapNotNull { it.toIntOrNull() }?.mapNotNull { sample(it, hz) }
            ?: emptyList()
    }

    fun report(completion: (String) -> Unit) {
        thread(name = "inferno.sampler", isDaemon = true) {
            val before = samples().associateBy { it.tid }
            Thread.sleep(1000)
            val after = samples()

            val lines = mutableListOf(
                L("Где крутится поток (топ-5, замер 1 с):"),
            )
            val ranked = after.mapNotNull { now ->
                val was = before[now.tid] ?: return@mapNotNull null
                Pair(now, now.cpu - was.cpu)
            }.sortedByDescending { it.second }.take(5)
            for ((now, burned) in ranked) {
                lines += L("  tid %d «%@» — %@, +%.2f с за замер", now.tid, now.name, now.state, burned)
            }
            completion(lines.joinToString("\n"))
        }
    }
}
