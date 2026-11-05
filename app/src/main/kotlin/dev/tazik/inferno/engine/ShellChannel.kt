package dev.tazik.inferno.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.regex.Pattern
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * Шелл гостя, отделённый от болтовни ядра.
 *
 * Одна консоль, общая с ядром, значит, всякий ответ приходит вперемешку с
 * сообщениями драйверов, и никакой подбор по образцам не делает это честным —
 * сообщение незнакомой формы проходит, а сообщение, приземлившееся посреди
 * строки, уносит строку с собой.
 *
 * Выхода два, и канал берёт тот, что доступен.
 *
 * **По сети.** Slirp живёт в самом этом процессе и поворачивает 10.0.2.2 в свой
 * собственный loopback, а bash сам открывает TCP-сокеты через `/dev/tcp`.
 * Консоль используется один раз — попросить гостя позвонить, — и дальше у шелла
 * собственный сокет. Это лучший канал: настоящая интерактивность, никакой цены
 * за строку, и тем же путём уже идут файлы.
 *
 * **По консоли.** Когда у гостя нет работающей связи, консоль — всё, что есть,
 * поэтому шелл отмечает, где его ответ начинается и где кончается. Между этими
 * отметками — вывод команды; за ними не показывается ничего, и бесконечная
 * болтовня ядра до панели не доходит.
 *
 * Помечать каждую строку пробовали первым и забросили как слишком медленное:
 * перед выводом встаёт bash'евский `read`, а `read` берёт по syscall'у на байт,
 * что на эмулируемом процессоре чувствуется. Отметки стоят два `echo` на
 * команду и позволяют выводу идти от команды к консоли напрямик. То, что ядру
 * удастся втиснуть в окно между ними, всё ещё просеивается фильтром по форме —
 * эта часть остаётся догадкой.
 */
class ShellChannel(
    private val serial: SerialConsole,
    private val linkUp: () -> Boolean,
) {

    enum class Transport { NETWORK, CONSOLE }

    sealed class State {
        object Idle : State()
        object Connecting : State()
        data class Up(val transport: Transport) : State()
        data class Failed(val why: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Терминал, в который канал рисует. Compose-экран следит за ним напрямую. */
    val screen = GuestScreen()

    @Volatile private var sock: Sock? = null

    /** Говорит читателю, чей канал сменился, замолчать. */
    @Volatile private var generation = 0

    // Состояние консольной перевозки.
    private val stateLock = Any()
    private var ear: UUID? = null
    private var marker = ""
    private var inside = false
    private var pending = ByteArray(0)
    private val filter = KernelFilter()

    /** Имя этого приложения для гостя, как его подаёт slirp. */
    private val host = "10.0.2.2"

    private val valEcho = Pattern.compile("VAL[0-9a-f]{4}")

    val isUp: Boolean get() = _state.value is State.Up

    val transport: Transport? get() = (_state.value as? State.Up)?.transport

    /** Открывает лучший канал, который гость может прямо сейчас.
     *
     * Сеть не ждут и гостя о ней не просят: за линком уже следит кто-то другой в
     * приложении, и второй голос давал только экран строк `ipconfig`. Всегда по
     * консоли.
     *
     * Сетевой путь здесь и работает, но полагаться на него нельзя: ему нужен у
     * гостя адрес и обратный звонок, а когда не случается ни того, ни другого,
     * нет никакого шелла — ровно так это выглядело снаружи, как канал, который
     * просто не поднимается никогда. Консоль есть с того момента, как на ней
     * появляется bash бутстрапа, и это стоит больше, чем делить её с журналом
     * ядра.
     */
    fun connect() {
        if (_state.value == State.Connecting || isUp) return

        _state.value = State.Connecting
        generation += 1
        val mine = generation
        release()
        screen.reset()
        waitForConsole(mine, attempt = 0)
    }

    /** Ждёт bash на консоли вместо того, чтобы сдаться с ней.
     *
     * На телефоне гость может идти от включения до шелла минуты, и прежнее
     * поведение — один взгляд, потом ошибка — оставляло панель пустой до конца
     * сессии, если никто не нажимал кнопку.
     */
    private fun waitForConsole(mine: Int, attempt: Int) {
        if (mine != generation) return
        if (serial.interactive.value) {
            openConsole(mine, null)
            return
        }
        if (attempt >= 150) {
            _state.value = State.Failed(
                L("Шелл гостя не отвечает: на консоли должен сидеть bash из бутстрапа."),
            )
            return
        }
        thread(name = "inferno.shell-wait", isDaemon = true) {
            try {
                Thread.sleep(2000)
            } catch (_: InterruptedException) {
            }
            waitForConsole(mine, attempt + 1)
        }
    }

    /** По сети вместо консоли: никакого дележа с журналом ядра, ценой того, что
     * гостю нужен адрес и обратный звонок.
     */
    fun connectOverNetwork() {
        if (!serial.interactive.value) {
            _state.value = State.Failed(
                L("Шелл гостя не отвечает: на консоли должен сидеть bash из бутстрапа."),
            )
            return
        }
        _state.value = State.Connecting
        generation += 1
        val mine = generation
        release()
        screen.reset()
        thread(name = "inferno.shell-net", isDaemon = true) { openNetwork(mine) }
    }

    // MARK: - По сети

    private fun openNetwork(mine: Int) {
        val listener = try {
            LoopbackListener()
        } catch (e: Throwable) {
            report(mine, e.message ?: L("ошибка сокета"))
            return
        }

        try {
            // Что консоль говорит, пока ждём, стоит слушать: bash сразу
            // докладывает об отказе, и услышать это лучше, чем просиживать
            // весь таймаут.
            val complaint = Complaint()
            val listening = serial.tap { data -> complaint.consider(data) }

            // `exec` во внутреннем bash, чтобы подоболочка не оставалась; всё
            // вместе в фоне, чтобы консоль получила своё приглашение обратно.
            // Коротко нарочно: у консоли нет управления потоком, и занятый гость
            // теряет байты внутри одной строки.
            serial.send("(exec 3<>/dev/tcp/$host/${listener.port};exec bash -i<&3>&3 2>&3)&\n")

            var accepted: java.net.Socket? = null
            val deadline = System.nanoTime() / 1_000_000_000.0 + 30
            while (System.nanoTime() / 1_000_000_000.0 < deadline) {
                val conn = listener.accept(0.5)
                if (conn != null) {
                    accepted = conn
                    break
                }
                if (complaint.heard != null) break
                if (generation != mine) {
                    serial.untap(listening)
                    return
                }
            }
            serial.untap(listening)

            val descriptor = accepted
            if (descriptor == null) {
                // Консоль на месте, и она тоже может нести шелл.
                val why = complaint.heard ?: L("Гость не позвонил обратно.")
                if (generation == mine) {
                    openConsole(mine, why + " " + L("Перехожу на консоль."))
                }
                return
            }

            val connection = Sock(descriptor)
            // Шелл может молчать минутами; таймаут чтения принял бы первую тихую
            // паузу за конец потока.
            connection.waitIndefinitely()

            if (generation != mine) {
                connection.close()
                return
            }
            sock = connection
            _state.value = State.Up(Transport.NETWORK)

            while (true) {
                val data = connection.readSome() ?: break
                if (generation != mine) return
                screen.append(String(data, Charsets.UTF_8))
            }

            if (generation == mine) {
                sock = null
                _state.value = State.Failed(L("Гость закрыл канал."))
            }
        } finally {
            listener.close()
        }
    }

    // MARK: - По консоли

    /** Учит гостя двум отметкам, затем проверяет, что он их выучил.
     *
     * Отметки собираются из переменных шелла нарочно. Консольный шелл повторяет
     * эхом всё, что в него напечатано, поэтому команда с отметкой в чистом виде
     * объявила бы её дважды — раз эхом, раз по правде, — и эхо открыло бы окно,
     * которое никто не имел в виду. Написанные как `$s` и `$e`, эхо показывает
     * имена, и только собственный вывод шелла показывает значения.
     */
    private fun openConsole(mine: Int, note: String?) {
        val tag = "%06x".format(Random.nextInt(0, 0x1000000))
        synchronized(stateLock) {
            marker = "Q$tag"
            inside = false
            pending = ByteArray(0)
        }
        filter.reset()

        ear = serial.tap { data ->
            if (generation == mine) absorb(data)
        }

        // Один разговор: две строки значат что-то только вместе.
        val mark = synchronized(stateLock) { marker }
        thread(name = "inferno.shell-probe", isDaemon = true) {
            // Одна строка, и она несёт собственную отметку. Две строки могло бы
            // разлучить письмо в консоль кем-то ещё между ними, а отметка,
            // живущая в переменной, теряется, когда bash гостя перезапускается,
            // — после чего всякая команда печатает ничего, и панель выглядит
            // мёртвой.
            serial.exclusive {
                serial.send("m=$mark; echo \"S\$m\"; echo ok; echo \"E\$m\"\n")
            }
            // Часы стартуют, когда проверка действительно отослана, а не когда
            // она встала в очередь. Консоль может держать кто-то другой минуту —
            // починка при старте так делает, — и таймаут, отмеренный отсюда,
            // раньше истекал прежде, чем этот шелл сказал хоть слово.
            try {
                Thread.sleep(20_000)
            } catch (_: InterruptedException) {
            }
            if (generation == mine && _state.value == State.Connecting) {
                release()
                _state.value = State.Failed(
                    L("Шелл не отозвался на проверку. Похоже, на консоли не bash."),
                )
            }
        }

        note?.let { LogCapture.note(L("Шелл: %@", it)) }
    }

    /** Показывает то, что лежит между отметками, и ничего больше. */
    private fun absorb(data: ByteArray) {
        synchronized(stateLock) {
            pending += data
            if (pending.size > 1 shl 20) pending = pending.copyOfRange(pending.size - (1 shl 19), pending.size)

            while (true) {
                val end = pending.indexOfByte(0x0A)
                if (end < 0) break
                val line = String(pending, 0, end, Charsets.UTF_8)
                pending = pending.copyOfRange(end + 1, pending.size)

                if (line.contains("S$marker")) {
                    inside = true
                    continue
                }
                val mark = line.indexOf("E$marker")
                if (mark >= 0) {
                    // Статус едет на линии конечной отметки — единственное место,
                    // где его прочесть без приглашения.
                    val tail = line.substring(mark + "E$marker".length).trim()
                    inside = false
                    finished(tail.toIntOrNull())
                    continue
                }
                if (!inside) continue
                // Собственная механика приложения делит эту консоль — починка
                // пакетов, установка агента, — и tty повторяет эхом всё, что она
                // пишет, в самый момент записи, даже когда команда этой панели
                // ещё работает. Те эхо приземляются между нашими отметками и не
                // то, что просил пользователь. Всё, что та механика шлёт, несёт
                // ту же сборку маркера, а её ответы несут сам маркер, так что
                // обе узнаваемы и ни одной здесь не место.
                if (line.contains("v=VAL; t=")) continue
                if (valEcho.matcher(line).find()) continue
                // Ядро всё ещё может вписаться в окно; это фильтруется старым
                // путём, по форме написанного.
                screen.append(filter.process(line + "\r\n"))
            }
        }
    }

    private fun finished(code: Int?) {
        if (_state.value == State.Connecting) {
            _state.value = State.Up(Transport.CONSOLE)
            screen.append(
                "\u001B[32m" + L("Канал по консоли открыт: показывается только вывод команд.") +
                    "\u001B[0m\r\n",
            )
            return
        }
        // Без собственного приглашения панель не подавала виду, что команда
        // кончилась, — а на таком медленном госте «ещё работает» и «кончилось,
        // не сказав ничего» выглядели одинаково.
        code ?: return
        screen.append(
            "\u001B[${if (code == 0) "90" else "31"}m" + L("— готово, код %d", code) + "\u001B[0m\r\n",
        )
    }

    // MARK: - Разговор

    /** Посылает команду и показывает её.
     *
     * По сети ничего не возвращается само: bash выключает строчное
     * редактирование, когда его ввод не терминал, и вместе с ним эхо. По консоли
     * эхо шелла не отмечено, так что выбрасывается вместе со строками ядра. В
     * обоих случаях напечатанное должен показать сам.
     */
    fun send(command: String) {
        when (transport) {
            Transport.NETWORK -> {
                val connection = sock ?: return
                screen.append(command + "\r\n")
                thread(name = "inferno.shell-send", isDaemon = true) {
                    connection.write((command + "\n").toByteArray(Charsets.UTF_8))
                }
            }
            Transport.CONSOLE -> {
                screen.append("\u001B[36m# \u001B[0m" + command + "\r\n")
                // Отметка ставится и на этой строке, по той же причине: между
                // командами ничего не помнится.
                serial.send("m=$marker; echo \"S\$m\"; { $command ;} 2>&1; r=\$?; echo \"E\$m \$r\"\n")
            }
            null -> {}
        }
    }

    /** Ctrl-C — по консоли он идёт тому, чем консольный шелл занят, а занят он
     * той же командой.
     */
    fun sendControl(byte: Int) {
        when (transport) {
            Transport.NETWORK -> {
                val connection = sock ?: return
                thread(name = "inferno.shell-send", isDaemon = true) {
                    connection.write(byteArrayOf(byte.toByte()))
                }
            }
            Transport.CONSOLE -> serial.send(String(Character.toChars(byte)))
            null -> {}
        }
    }

    fun disconnect() {
        generation += 1
        release()
        _state.value = State.Idle
    }

    private fun release() {
        sock?.close()
        sock = null
        ear?.let { serial.untap(it) }
        ear = null
        synchronized(stateLock) { pending = ByteArray(0) }
    }

    private fun report(mine: Int, reason: String) {
        if (generation == mine) _state.value = State.Failed(reason)
    }
}

/** Слушает консоль на предмет причин, по которым звонок обратно не состоялся. */
private class Complaint {
    private val lock = Any()
    private var found: String? = null

    /** Что сказал bash, переведённое, или null, пока ничего не сказал. */
    val heard: String? get() = synchronized(lock) { found }

    fun consider(data: ByteArray) {
        val text = String(data, Charsets.UTF_8)
        val reason = when {
            text.contains("Network is unreachable") || text.contains("No route to host") ->
                L("Сети в госте нет: bash ответил «Network is unreachable».")
            text.contains("Connection refused") ->
                L("Гость дозвонился, но соединение отвергнуто.")
            text.contains("/dev/tcp") && text.contains("No such file") ->
                L("В этом bash нет поддержки /dev/tcp.")
            else -> return
        }
        synchronized(lock) {
            if (found == null) found = reason
        }
    }
}

/** Первое вхождение байта, либо -1. */
private fun ByteArray.indexOfByte(byte: Byte): Int {
    for (i in indices) if (this[i] == byte) return i
    return -1
}
