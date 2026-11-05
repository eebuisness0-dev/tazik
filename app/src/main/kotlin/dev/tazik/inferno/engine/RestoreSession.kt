package dev.tazik.inferno.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.RandomAccessFile

/**
 * То, чем машина управляется извне движка: экран восстановления говорит с
 * машиной только через этот интерфейс, реализуемый моделью интерфейса
 * (`VMModel`).
 */
interface RestoreHost {
    /** Машина сейчас работает? */
    val machineRunning: Boolean

    /** Конфигурация, как её настроил человек. */
    fun currentConfig(): VMConfig

    /** Поднимает машину с этим конфигом. */
    fun startMachine(config: VMConfig)

    /** Гасит машину штатно. */
    fun shutdownMachine()

    /** Спрашивает у машины, жива ли она ещё. */
    fun inspectMachine()
}

/**
 * Восстановление гостя с телефона, без компьютера на другом конце.
 *
 * Штатный способ требует двух машин: одна запускает эмулируемый iPhone, другая
 * держит `usbmuxd` и `idevicerestore` и разговаривает с ним по USB. Здесь оба
 * конца в одном приложении — эмулятор грузит восстановительный рамдиск,
 * `GuestUSB` — USB-хост, а это ведёт то, чем был бы `idevicerestore`.
 *
 * Машина грузится в режим восстановления, USB гостя поднимается, `restored`
 * отвечает, и `RestoreClient` ведёт само восстановление — тикеты, загрузочные
 * объекты, данные NOR и образ файловой системы по ASR. Сверх набора
 * эмулятора нужны архив прошивки, из которого идёт восстановление, и
 * подделанный тикет; `RESTORE.md` говорит, откуда те.
 */
class RestoreSession private constructor() {

    sealed class Stage {
        object Idle : Stage()

        /** Машина поднимается на восстановительном рамдиске. */
        object Booting : Stage()

        /** Ожидание, пока гость поднимет свой USB-порт. */
        object WaitingForDevice : Stage()

        /** `restored` ответил: гость готов к восстановлению. */
        data class Ready(val type: String) : Stage()

        /** Само восстановление, с тем, чем гость занят прямо сейчас. */
        data class Restoring(val what: String, val done: Double) : Stage()

        object Done : Stage()
        data class Failed(val why: String) : Stage()

        val isBusy: Boolean
            get() = this is Booting || this is WaitingForDevice || this is Restoring
    }

    private val _stage = MutableStateFlow<Stage>(Stage.Idle)
    val stage: StateFlow<Stage> = _stage.asStateFlow()

    private var usb: GuestUSB? = null

    /** Модель машины, поднявшая этот рестор. */
    private var host: RestoreHost? = null

    /** Сколько раз сессия сама перезапускала машину целиком. Лимит — два:
     * дальше честный отказ, вечный цикл ребутов жжёт батарею и флеш. */
    private var fullRestarts = 0

    private fun set(stage: Stage) {
        _stage.value = stage
        if (stage is Stage.Failed) {
            // Причину сбоя — в общий лог: на телефоне сессию видно только там.
            LogCapture.note(L("Рестор не удался: ") + stage.why)
        }
        // Восстановление идёт минуты, и экран, погаснув посреди пути,
        // усыпляет приложение без объяснений, почему всё встало. На Android
        // экран держит активность интерфейса своим wake lock'ом.
    }

    companion object {
        val shared = RestoreSession()

        /** Может ли восстановление начаться вовсе: рамдиск и всё, что нужно
         * машине, должны быть на месте, и ничего другого не должно работать.
         */
        val ramdisk: File? get() = VMConfig.restoreRamdisk

        /**
         * Рамдиск, который машина действительно грузит: стоковый с патчером и
         * его демоном внутри, чтобы патчи файловой системы случились в той же
         * загрузке, что и восстановление, — последний шаг, которому раньше был
         * нужен компьютер.
         *
         * Собирается один раз и хранится рядом с набором; собирается заново,
         * когда приложение новее образа — так новый патчер доходит до старого
         * набора.
         */
        fun bootRamdisk(stock: File): File {
            val ours = File(VMConfig.dataDirectory, "Restore-inferno.dmg")
            val stamp = { file: File ->
                if (file.isFile) file.lastModified() else Long.MIN_VALUE
            }
            val files = RestoreRamdisk.programs
            if (ours.isFile && files != null &&
                stamp(ours) > stamp(files.first) && stamp(ours) > stamp(stock)
            ) {
                // Новее и стокового: набор, пересобранный под другую прошивку,
                // распаковывает свежий стоковый рамдиск, и старая копия не
                // должна его пережить.
                return ours
            }
            return try {
                /* «stack size 1035KB» — это StackOverflowError на дефолтном
                 * стеке ~1 МБ, и три повтора подряд (run53) доказывают, что
                 * дело не в прижатой памяти, а в глубине вызовов. Сборке
                 * выделен собственный поток с широким стеком: глубина больше
                 * не зависит от того, кто её позвал. */
                var built: File? = null
                for (attempt in 1..3) {
                    try {
                        onWideStack("inferno.ramdisk", 64L shl 20) {
                            RestoreRamdisk.build(stock, into = ours) { LogCapture.note(it) }
                        }
                        built = ours
                        break
                    } catch (e: Throwable) {
                        // Сборка, оборвавшаяся на середине, оставляет
                        // полупатченную копию, и она прошла бы проверку выше
                        // в следующий раз. След пишем, чтобы увидеть место.
                        try {
                            ours.delete()
                        } catch (_: Throwable) {
                        }
                        LogCapture.note(e.stackTraceToString())
                        if (attempt >= 3) throw e
                        LogCapture.note(L("Рестор: сборка рамдиска сорвалась (%@), пробую заново", e.message ?: L("ошибка")))
                        Thread.sleep(5000)
                    }
                }
                built ?: stock
            } catch (e: Throwable) {
                // Стоит сказать вслух, а не ронять восстановление: стоковый
                // рамдиск восстанавливает прекрасно, а теряются патчи после,
                // которые человек может применить и с компьютера.
                LogCapture.note(L("Рестор: патчер в RAM-диск не попал — %@", e.message ?: L("ошибка")))
                stock
            }
        }
    }

    /**
     * Грузит восстановительный рамдиск и ждёт `restored`. Безопасно звать с
     * главного потока; всё медленное происходит в других местах.
     */
    fun start(model: RestoreHost) {
        if (stage.value.isBusy) return
        if (model.machineRunning) {
            set(Stage.Failed(L("Сначала выключите машину: рестор поднимает её по-своему")))
            return
        }
        val ramdisk = RestoreSession.ramdisk ?: run {
            set(Stage.Failed(L("Нет RAM-диска: подготовьте набор из .ipsw")))
            return
        }
        // Машина не может стартовать с неполной папкой — QEMU зовёт exit()
        // внутри нашего же процесса, когда не хватает диска. С нуля это то,
        // что делает «Подготовить набор», поэтому так и сказать, вместо
        // перечисления файлов, которых у человека никогда и не было.
        val missing = VMConfig.missingFiles()
        if (missing.isNotEmpty()) {
            set(Stage.Failed(L("Сначала подготовьте набор — не хватает: %@",
                missing.joinToString(", "))))
            return
        }

        set(Stage.Booting)
        LogCapture.note(L("Рестор: RAM-диск %@", ramdisk.name))

        // Прошлая попытка оставляет записанное в файле диска, а восстановление
        // всё равно перезапишет. Отдать блоки сначала — то, что освобождает
        // место для этого.
        emptyTheDisk()
        val free = EngineEnv.freeSpace()
        if (free != null) {
            LogCapture.note(L("Рестор: свободно %.1f ГБ", free.toDouble() / 1_073_741_824))
            if (free < 9L * 1_073_741_824) {
                set(Stage.Failed(
                    L("Мало места: нужно около 9 ГБ, свободно %.1f", free.toDouble() / 1_073_741_824),
                ))
                return
            }
        }

        // USB-хост должен слушать до старта машины: эмулятор звонит на сокет
        // один раз, поднимаясь, и больше никогда.
        val usb = GuestUSB()
        this.usb = usb
        usb.onReady = { ready ->
            if (ready) talkToRestored(usb)
        }
        try {
            usb.listen()
        } catch (e: Throwable) {
            set(Stage.Failed(e.message ?: L("ошибка USB")))
            return
        }

        val config = model.currentConfig()
        // Не стоковый рамдиск: тот, что несёт патчер, сделанный здесь, если
        // ещё не сделан.
        config.restoreRamdiskPath = bootRamdisk(ramdisk).absolutePath
        // Память и буфер трансляции оставлены как настроил человек. Гигабайта,
        // кстати, мало — пробовали на стенде, и сторожевой пёс гостя сбрасывает
        // машину прежде, чем ASR вообще начнёт. Сетевое устройство заняло бы
        // USB-сокет, который нужен этому хосту, а у восстановления нет никаких
        // дел по сети. Раздача порта другой машине увела бы его ещё дальше —
        // сокета здесь не было бы вовсе.
        config.network = false
        config.usbExport = null
        host = model
        model.startMachine(config)
        set(Stage.WaitingForDevice)

        /* Сторож бута. Холодный первый бут нового процесса иногда паникует
         * в госте («wdog panic», «Halt/Restart Timed Out»): загрузка на
         * холодных страницах и свопе идёт медленно, сторожевой пёс гостя
         * стреляет, а сам гость перезагрузиться не может — путь ресета
         * SMC/PMU в форке не дописан. Тёплый бут после такого всегда
         * чистый, так что не ждём, когда машина «вернётся», — гасим и
         * поднимаем заново сами. Живому буту хватает двух минут, Ready и
         * Restoring снимают сторож. */
        Thread {
            val deadline = System.nanoTime() / 1_000_000_000 + 300
            while (System.nanoTime() / 1_000_000_000 < deadline) {
                when (stage.value) {
                    is Stage.Booting, is Stage.WaitingForDevice -> {}
                    else -> return@Thread
                }
                Thread.sleep(5000)
            }
            val toRestart = host ?: return@Thread
            when (stage.value) {
                is Stage.Booting, is Stage.WaitingForDevice -> {}
                else -> return@Thread
            }
            if (fullRestarts >= 2) return@Thread
            fullRestarts++
            LogCapture.note(
                L("Рестор: гость не поднялся — перезапускаю машину (%d/2)", fullRestarts),
            )
            restartMachineWhole(toRestart)
        }.apply { isDaemon = true; start() }
    }

    fun stop() {
        usb?.stop()
        usb = null
        host = null
        set(Stage.Idle)
    }

    /** Полный перезапуск машины в ходе рестора: гасим, ждём смерти,
     * поднимаем заново. Единственное лекарство от трупа гостя (restored
     * уже ответил своей ошибкой) и от паники холодного бута. */
    private fun restartMachineWhole(model: RestoreHost) {
        model.shutdownMachine()
        var waited = 0
        while (model.machineRunning && waited < 20) {
            Thread.sleep(1000)
            waited++
        }
        stop()
        Thread.sleep(5000)
        start(model)
    }

    /** Открывает канал к `restored` и спрашивает, кто он, — тот же QueryType,
     * с которого начинается всякий инструмент libimobiledevice.
     */
    private fun talkToRestored(usb: GuestUSB) {
        // Стек задан широко: беседа гнала StackOverflowError («stack size
        // 1035KB», run52 [1284,33]) на дефолтных ~1 МБ — прямо перед финишем
        // заливки.
        engineThread("inferno.restore", 64L shl 20) {
            try {
                val channel = usb.connect(GuestUSB.Port.RESTORED, timeout = 120.0)
                try {
                    val answer = RestoreClient.request(
                        channel,
                        mapOf("Request" to "QueryType", "Label" to "Inferno"),
                    )
                    val type = answer.str("Type") ?: "?"
                    val version = answer.long("RestoreProtocolVersion") ?: 0
                    LogCapture.note(L("Рестор: гость отвечает — %@, протокол %d", type, version))
                    set(Stage.Ready(type))
                    restore(channel, usb, version.toInt())
                } finally {
                    channel.close()
                }
            } catch (e: Throwable) {
                LogCapture.note(L("Рестор: %@", e.message ?: L("ошибка")))
                LogCapture.note(e.stackTraceToString())
                // Жива ли машина ещё — то, что отличает мёртвый канал от
                // зависшей машины.
                host?.inspectMachine()
                /* Эскалация. Ретраи беседы лечат короткий клин, когда гость
                 * ещё жив (утренний прогон отошёл на попытке 2), но не труп:
                 * если гость успел ответить своей ошибкой (14, 6 — run60,
                 * run62), новая беседа бьётся в мёртвого и сессия падает.
                 * Перезапуск машины целиком даёт чистого гостя и чистую
                 * заливку. Два перезапуска на процесс — дальше честный
                 * отказ: вечный цикл ребутов жжёт батарею и флеш. */
                val model = host
                if (model != null && fullRestarts < 2) {
                    fullRestarts++
                    LogCapture.note(
                        L("Рестор: беседа кончилась нештатно — перезапускаю машину целиком (%d/2)", fullRestarts),
                    )
                    restartMachineWhole(model)
                } else {
                    set(Stage.Failed(e.message ?: L("ошибка")))
                }
            }
        }
    }

    /** Передаёт разговор `RestoreClient`, когда прошивка и тикет оба на месте. */
    private fun restore(channel: GuestUSB.Channel, usb: GuestUSB, protocolVersion: Int) {
        val firmware = firmware ?: run {
            val why = L("Рестор: положите .ipsw рядом с InfernoData, см. RESTORE.md")
            LogCapture.note(why)
            set(Stage.Failed(why))
            return
        }
        val ticketBytes = try {
            ticket.readBytes()
        } catch (_: Throwable) {
            null
        } ?: run {
            val why = L("Рестор: нет тикета %@, см. RESTORE.md", ticket.name)
            LogCapture.note(why)
            set(Stage.Failed(why))
            return
        }

        val ipsw = IPSW(firmware)
        val identity = ipsw.buildIdentity()
        LogCapture.note(L("Рестор: прошивка %@", firmware.name))

        val cryptexTemplate = try {
            VMConfig.cryptexTemplate.readBytes()
        } catch (_: Throwable) {
            null
        }
        /* NAK-сбой посередине разговора не должен ронять сессию: пробуем
         * снова, с чистыми каналами. Гость к этому моменту жив (NAK — «ещё
         * не готово», а не паника), но разговор у него начинается заново,
         * так что и клиент строим новый. Хватит трёх подступов. */
        var attempt = 1
        while (true) {
            val client = RestoreClient(
                usb = usb,
                ipsw = ipsw,
                identity = identity,
                ticket = ticketBytes,
                cryptexTemplate = cryptexTemplate,
                note = { LogCapture.note(it) },
                progress = { what, done ->
                    set(Stage.Restoring(what, done))
                    noteMemory(what, done)
                },
            )
            try {
                client.run(channel, protocolVersion)
                break
            } catch (e: GuestUSB.Failure) {
                /* НЕ_READY — гость не берёт данные; USB — гость замолчал
                 * целиком (клин или потерянный mux-пакет). Оба лечатся
                 * свежей беседой: гость заново выпрашивает образ. Всё
                 * остальное — честный фейл. */
                val retryable = e.kind == GuestUSB.FailureKind.NOT_READY ||
                    e.kind == GuestUSB.FailureKind.USB
                if (!retryable || attempt >= 3) throw e
                LogCapture.note(L("Рестор: гость замолчал, всё с начала (попытка %d/3)", attempt))
                attempt++
                Thread.sleep(5000)
            } catch (e: VirtualMachineError) {
                /* OOM и StackOverflowError — родня: первый — «поток не
                 * создать», вторая — «stack size 1035KB» (run52 [1284,33] —
                 * сессия умерла на полном образе). Свежая беседа — дешёвый
                 * повтор; след пишем, чтобы узнать точное место. */
                if (attempt >= 3) throw e
                LogCapture.note(L("Рестор: сбой памяти (%@), всё с начала (попытка %d/3)", e.message ?: "OOM", attempt))
                LogCapture.note(e.stackTraceToString())
                attempt++
                System.gc()
                Thread.sleep(10000)
            }
        }

        // Восстановление записано, и машина всё ещё работает, потому что
        // эмулятор держит сброс, о котором попросил гость. Это то окно, в
        // котором патчит наш демон.
        waitForPatcher()
        host?.shutdownMachine()
        set(Stage.Done)
    }

    /** Ждёт патчера в госте, читая консоль, которую машина и так пишет.
     *
     * Больше спросить не у кого: у гостя к тому моменту не осталось USB —
     * восстановление кончается уходом `restored`, — а трогать NVRAM или тома,
     * пока восстановление заканчивается, — то, что сломало два прогона на
     * стенде. Демон говорит о своих делах на консоли, и этого хватает.
     *
     * Час — щедро нарочно. Патчер проходит разделяемый кэш на два гигабайта, а
     * телефон ведёт машину, переводя каждую инструкцию.
     */
    private fun waitForPatcher() {
        set(Stage.Restoring(L("Патчи файловой системы"), 0.99))
        val console = VMConfig.guestConsoleLog
        if (!console.isFile) return

        val handle = try {
            RandomAccessFile(console, "r")
        } catch (_: Throwable) {
            return
        }
        handle.use { reader ->
            // Только с этого места. Консоль — один файл на все прогоны, и
            // «done», оставленный там восстановлением до этого, кончил бы
            // ожидание прежде, чем этот патчер успел начаться.
            try {
                reader.seek(reader.length())
            } catch (_: Throwable) {
            }

            var text = ""
            val seen = mutableSetOf<String>()
            val startedAt = System.nanoTime() / 1_000_000_000
            var lastPulse = startedAt
            var lastPulseSize = reader.length()
            val deadline = System.nanoTime() / 1_000_000_000 + 3600
            while (System.nanoTime() / 1_000_000_000 < deadline) {
                try {
                    val left = reader.length() - reader.filePointer
                    if (left > 0) {
                        val chunk = ByteArray(left.toInt())
                        reader.readFully(chunk)
                        text += String(chunk, Charsets.UTF_8)
                    }
                } catch (_: Throwable) {
                }
                for (line in text.split("\n")) {
                    if (!line.contains("*** PATCHER:")) continue
                    val said = line.trim()
                    if (seen.add(said)) {
                        LogCapture.note(said.replace("*** PATCHER: ", L("Патчи: ")))
                    }
                }
                if (text.contains("PATCHER: done") || text.contains("PATCHER: no shared cache")) return
                /* Пульс ожидания: раз в минуту — сколько ждём и пишет ли гость
                 * вообще. Рост консоли (ретраи монтирования демона) — живой
                 * пульс launchd; тишина консоли после «patching» — норма:
                 * fs-патчер пишет в свой лог, демо вывалит его по завершении. */
                val nowTick = System.nanoTime() / 1_000_000_000
                if (nowTick - lastPulse >= 60) {
                    val grown = ((reader.length() - lastPulseSize) / 1024).toInt()
                    lastPulse = nowTick
                    lastPulseSize = reader.length()
                    if (grown > 0) {
                        LogCapture.note(L("Патчи: жду %d с, гость пишет (+%d КБ)", (nowTick - startedAt).toInt(), grown))
                    } else {
                        LogCapture.note(L("Патчи: жду %d с, консоль тиха — патчер пишет в свой лог, придёт по завершении", (nowTick - startedAt).toInt()))
                    }
                }
                Thread.sleep(2000)
            }
            LogCapture.note(L("Патчи: не дождались — машина останавливается как есть"))
        }
    }

    /** Архив прошивки, из которого идёт восстановление.
     *
     * `RestorePrep` запоминает собственную копию, как только сделал её, — см.
     * `localCopy`. До того, или если запоминание не успело, это откатывается к
     * любому `.ipsw`, лежащему рядом с файлами эмулятора.
     */
    val firmware: File?
        get() {
            synchronized(firmwareLock) {
                resolvedFirmware?.let { return it }
            }
            for (directory in listOf(VMConfig.dataDirectory, VMConfig.documents)) {
                val found = directory.listFiles()
                    ?.firstOrNull { it.isFile && it.extension.lowercase() == "ipsw" }
                if (found != null) {
                    synchronized(firmwareLock) { resolvedFirmware = found }
                    return found
                }
            }
            return null
        }

    /** Раз в сколько-то говорит, сколько памяти держит приложение, пока идёт
     * образ. Догадка об этой цене стоила трёх прогонов; число стоит одной
     * строки.
     */
    private var lastMemoryNote = 0.0
    private fun noteMemory(what: String, done: Double) {
        val now = System.nanoTime() / 1_000_000_000.0
        if (now - lastMemoryNote < 15) return
        lastMemoryNote = now
        LogCapture.note(
            L("Рестор: %@ %.1f %%, память %@, свободно %.1f ГБ",
                what, done * 100, Threads.footprint(),
                (EngineEnv.freeSpace() ?: 0).toDouble() / 1_073_741_824),
        )
    }

    /** Что осталось у телефона, как iOS считает это для чего-то стоящего. */
    val freeSpace: Long? get() = EngineEnv.freeSpace()

    /** Возвращает блоки записанного раньше, держа файл полного размера.
     * Восстановление стирает диск в любом случае.
     */
    private fun emptyTheDisk() {
        VMConfig.rootImage?.let { image ->
            if (image.format == "raw") blank(File(image.path), null)
        }
        // Четыре маленьких диска состояния тоже возвращаются к пустым, потому
        // что восстановление стирает устройство, а руководство делает их
        // пустыми для одного.
        //
        // Это не аккуратность. Сборки раньше засеивали их с настоящего
        // устройства, и набор, сделанный такой сборкой, держит те копии —
        // ничто никогда не перезаписывает файл, который уже есть. Восстановление
        // на них закончится и затем не сможет открыть собственный том данных:
        // нет ключа, нет экрана, и в журнале ничего, что объясняло бы почему.
        for (name in listOf("nvram", "effaceable", "sep_nvram", "sep_ssc")) {
            blank(
                File(VMConfig.dataDirectory, name),
                RestorePrep.disks.firstOrNull { it.first == name }?.second,
            )
        }
    }

    /** Отдаёт блоки диска, держа файл прежнего размера. */
    private fun blank(url: File, size: Long?) {
        if (!url.isFile) return
        val was = try {
            url.length()
        } catch (_: Throwable) {
            return
        }
        try {
            RandomAccessFile(url, "rw").use { handle ->
                handle.setLength(0)
                handle.setLength(size ?: was)
            }
        } catch (_: Throwable) {
        }
    }

    private val firmwareLock = Any()

    @Volatile
    private var resolvedFirmware: File? = null

    /** Держит выбранный файл доступным после перезапуска приложения. */
    fun remember(firmware: File) {
        synchronized(firmwareLock) { resolvedFirmware = firmware }
    }

    /** Подделанный тикет AP — тот самый файл, что нужен каждой загрузке потом. */
    val ticket: File get() = File(VMConfig.dataDirectory, "root_ticket.der")
}
