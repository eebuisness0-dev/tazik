package dev.tazik.inferno.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Порт `LogCapture.swift`: журнал эмулятора.
 *
 * QEMU пишет фатальные ошибки в stderr и вызывает `exit()`, что внутри
 * приложения выглядит как крэш без объяснения. JNI-мост уже перенаправляет
 * stdout/stderr в файл (`freopen`), поэтому здесь остаётся тот же файл плюс
 * буфер для интерфейса: режем на строки, ставим время, считаем повторы.
 */
object LogCapture {
    private val _text = MutableStateFlow("")
    val text: StateFlow<String> = _text.asStateFlow()

    private val _lastLine = MutableStateFlow("")
    val lastLine: StateFlow<String> = _lastLine.asStateFlow()

    private const val LIMIT = 128 * 1024

    private val lock = Any()
    private var started = false
    private var file: File? = null
    private var out: FileOutputStream? = null
    private var startTime = System.nanoTime()
    private var partial = ""
    private var repeatLine: String? = null
    private var repeats = 0
    private var flushGeneration = 0
    private var schedule: ScheduledExecutorService? = null

    /**
     * Ротация — раз на процесс. Второй вызов `start` бывает у каждой машины:
     * приложение стартует процесс, машина стартует второй вызов. Если на нём
     * переименовать журнал, открытая труба остаётся на старом inode —
     * переименованный файл живёт сам по себе, а свежий `emulator.log`
     * молчит, и самое свежее оказывается в «прошлом» журнале. */
    private var rotated = false

    private val noise = listOf("=== AttributeGraph: cycle detected")

    /** Журнал этого запуска. */
    val logFile: File
        get() = file ?: File(EngineEnv.documents, "emulator.log")

    /** Журнал прошлого запуска — интересен почти всегда именно он. */
    val previousLogFile: File
        get() = File(EngineEnv.documents, "emulator.prev.log")

    fun start() {
        synchronized(lock) {
            if (started) return
            started = true
            startTime = System.nanoTime()

            val log = File(EngineEnv.documents, "emulator.log")
            val prev = previousLogFile
            if (!rotated) {
                rotated = true
                try {
                    if (prev.exists()) prev.delete()
                } catch (_: Throwable) {
                }
                try {
                    if (log.exists()) log.renameTo(prev)
                } catch (_: Throwable) {
                }
            }
            try {
                log.parentFile?.mkdirs()
                file = log
                out = FileOutputStream(log, true)
            } catch (_: Throwable) {
                out = null
            }
            EngineEnv.qemuStderrLog = log

            schedule = Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "inferno.log").apply { isDaemon = true }
            }
        }
    }

    fun stop() {
        synchronized(lock) {
            out?.flush()
            out?.close()
            out = null
            schedule?.shutdownNow()
            schedule = null
            started = false
        }
    }

    /** Заметка — это целые строки, и они идут сразу, минуя буфер трубы. */
    fun note(line: String) {
        synchronized(lock) {
            for (piece in line.split('\n', '\r')) {
                if (line.isEmpty()) continue
                emit(piece)
            }
            if (line.isEmpty()) emit("")
        }
    }

    fun noteDevice() {
        note(Platform.deviceLine())
    }

    /** Отдаёт в журнал всё, что QEMU уже успел записать в файл. */
    fun ingestExternal() {
        val log = file ?: return
        if (!log.isFile) return
        synchronized(lock) {
            try {
                val text = log.readText()
                if (text.isNotEmpty()) take(text)
            } catch (_: Throwable) {
            }
        }
    }

    private fun take(chunk: String) {
        partial += chunk
        while (true) {
            val newline = partial.indexOfFirst { it == '\n' || it == '\r' }
            if (newline < 0) break
            val line = partial.substring(0, newline)
            partial = partial.substring(newline + 1)
            emit(line)
        }
    }

    private fun emit(line: String) {
        if (noise.any { line.startsWith(it) }) return
        if (line == repeatLine) {
            if (line.isEmpty()) return
            repeats += 1
            if (repeats >= 100) flushRepeats() else scheduleFlush()
            return
        }
        flushRepeats()
        repeatLine = line
        write(line)
    }

    private fun scheduleFlush() {
        flushGeneration += 1
        val generation = flushGeneration
        val exec = schedule ?: return
        exec.schedule({
            synchronized(lock) {
                if (flushGeneration == generation) flushRepeats()
            }
        }, 2, TimeUnit.SECONDS)
    }

    private fun flushRepeats() {
        if (repeats <= 0) return
        val line = repeatLine ?: return
        if (repeats == 1) write(line) else write(L("(повторилось ещё: %d)", repeats))
        repeats = 0
    }

    private fun write(line: String) {
        val seconds = (System.nanoTime() - startTime) / 1_000_000_000.0
        val stamped = "[%7.2f] ".format(seconds) + line + "\n"
        try {
            out?.write(stamped.toByteArray())
            out?.flush()
        } catch (_: Throwable) {
        }
        _lastLine.value = line
        val next = _text.value + stamped
        _text.value = if (next.length > LIMIT) next.substring(next.length - LIMIT) else next
    }
}
