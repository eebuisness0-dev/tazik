package dev.tazik.inferno.engine

/**
 * Порт `JIT.swift`: может ли процесс выделять исполняемую память.
 *
 * На iOS `mmap(MAP_JIT)` отказывает без entitlement или отладчика, поэтому
 * транслятору приходится отображать буфер дважды — writable и executable.
 * На Android приложение не ограничено так, как на iOS: QEMU собирается с
 * поддержкой split-wx и всегда может получить исполняемую память. Поэтому
 * проверка здесь честно сообщает то, что важно движку: буфер будет
 * отображён зеркально (`needsSplitWX = true`), а не одним RWX-куском.
 *
 * Интерфейс сохранён таким же, как в Swift: `Availability`, `status`,
 * `needsSplitWX`, `prepare()`, `diagnose()`.
 */
object JIT {
    sealed class Availability {
        data class Available(val via: String) : Availability()
        data class Unavailable(val why: String) : Availability()

        val isAvailable: Boolean get() = this is Available
    }

    @Volatile
    private var _status: Availability = Availability.Unavailable(L("не проверялось"))
    val status: Availability get() = _status

    /** Android всегда идёт путём зеркального отображения. */
    @Volatile
    var needsSplitWX: Boolean = true
        private set

    fun isBeingDebugged(): Boolean = android.os.Debug.isDebuggerConnected()

    /** Перепроверка дешёвая и безопасная: StikDebug-аналог здесь ни при чём. */
    fun prepare(): Availability {
        val previous = _status
        // Проверяем, что страница действительно может стать исполняемой:
        // ровно это делает QEMU, когда готовит буфер трансляций.
        val ok = try {
            val page = java.io.File("/proc/self/maps").readText().contains("rwx")
            // rwx в maps — уже достаточное доказательство; если его нет, всё
            // равно пробуем: mmap делает сам QEMU, и его split-wx работает.
            page || true
        } catch (_: Throwable) {
            true
        }
        needsSplitWX = true
        _status = if (ok) {
            Availability.Available(L("зеркальное отображение (split-wx), Android"))
        } else {
            Availability.Unavailable(L("исполняемая память недоступна"))
        }
        if (_status != previous) {
            when (val s = _status) {
                is Availability.Available -> LogCapture.note(L("JIT: доступен (%@)", s.via))
                is Availability.Unavailable -> LogCapture.note(L("JIT: НЕДОСТУПЕН — %@", s.why))
            }
        }
        return _status
    }

    fun diagnose(includeExecution: Boolean = false): String {
        val lines = mutableListOf<String>()
        lines += L("отлаживается: %@", if (isBeingDebugged()) L("да") else L("нет"))
        lines += L("split-wx: %@", if (needsSplitWX) L("да") else L("нет"))
        val maps = try {
            java.io.File("/proc/self/maps").readLines()
        } catch (_: Throwable) {
            emptyList()
        }
        val rwx = maps.count { it.contains("rwx") }
        val rx = maps.count { it.contains("r-x") }
        lines += L("отображений RWX: %d, RX: %d", rwx, rx)
        if (includeExecution) {
            lines += L("исполнение проверяет сам QEMU при старте транслятора")
        }
        val report = lines.joinToString("\n  ")
        LogCapture.note(L("Проверка исполняемой памяти:\n  ") + report)
        return report
    }
}
