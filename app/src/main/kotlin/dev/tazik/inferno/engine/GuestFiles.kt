package dev.tazik.inferno.engine

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.random.Random

/**
 * Число, которое `cksum` печатает в госте: CRC-32/CKSUM с длиной, подмешанной
 * в конце. Ни md5, ни shasum у гостя нет, так что это проверка, которую обе
 * стороны умеют считать.
 */
class PosixChecksum {
    companion object {
        private val table: IntArray = IntArray(256) { index ->
            var crc = index shl 24
            repeat(8) {
                crc = if (crc and 0x8000_0000.toInt() != 0) (crc shl 1) xor 0x04C1_1DB7 else crc shl 1
            }
            crc
        }

        /** Контрольная сумма готового массива, одним звоном. */
        fun of(bytes: ByteArray): Int {
            val sum = PosixChecksum()
            sum.update(bytes)
            return sum.value
        }
    }

    private var crc = 0
    var length: Long = 0
        private set

    fun update(bytes: ByteArray) {
        var value = crc
        for (byte in bytes) {
            value = (value shl 8) xor table[((value ushr 24) xor (byte.toInt() and 0xFF)) and 0xFF]
        }
        crc = value
        length += bytes.size
    }

    val value: Int
        get() {
            var value = crc
            var remaining = length
            while (remaining > 0) {
                value = (value shl 8) xor table[((value ushr 24) xor (remaining and 0xFF).toInt()) and 0xFF]
                remaining = remaining ushr 8
            }
            return value.inv()
        }
}

/** Первое вхождение `needle` в `haystack`, не раньше `from`, либо -1. */
private fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int = 0): Int {
    if (needle.isEmpty()) return from
    var at = from
    while (at + needle.size <= haystack.size) {
        var same = true
        for (i in needle.indices) {
            if (haystack[at + i] != needle[i]) {
                same = false
                break
            }
        }
        if (same) return at
        at += 1
    }
    return -1
}

/** Первое вхождение байта не раньше `from`, либо -1. */
private fun ByteArray.findByte(byte: Byte, from: Int = 0): Int {
    for (i in maxOf(from, 0) until size) if (this[i] == byte) return i
    return -1
}

/**
 * Шелл гостя как канал запрос/ответ: команды уходят через консоль, ответы
 * выбираются из напечатанного по маркеру, который может выдать только гость.
 * Маркер собирается из переменных шелла, поэтому эхо команды показывает `$v$t`,
 * а ответ показывает `VAL1a2b` — их никогда не перепутать.
 */
class GuestShell(private val serial: SerialConsole) {
    private val lock = ReentrantLock()
    private val arrived = lock.newCondition()
    private var buffer = ByteArray(0)

    /** Подписка на консоль. В Swift замыкание держало `[weak self]`, а deinit
     * снимал тап; здесь то же самое делает слабая ссылка — умерший шелл сам
     * выписывается из списка при следующей доставке.
     */
    private val token: UUID

    init {
        val weak = java.lang.ref.WeakReference(this)
        var id: UUID? = null
        id = serial.tap { data ->
            val shell = weak.get()
            if (shell == null) serial.untap(id!!) else shell.append(data)
        }
        token = id!!
    }

    private fun append(data: ByteArray) {
        lock.withLock {
            buffer += data
            if (buffer.size > 1 shl 20) buffer = buffer.copyOfRange(buffer.size - (1 shl 19), buffer.size)
            arrived.signalAll()
        }
    }

    fun send(text: String) = serial.send(text)

    /** Ждёт маркер и newline после него; возвращает то, что за ним следует. */
    fun wait(marker: String, timeout: Double): String? {
        val needle = marker.toByteArray(Charsets.UTF_8)
        val deadline = System.nanoTime() / 1_000_000_000.0 + timeout
        while (true) {
            var answer: String? = null
            lock.withLock {
                val found = indexOf(buffer, needle)
                if (found >= 0) {
                    val newline = buffer.findByte(0x0A, found + needle.size)
                    if (newline >= 0) {
                        answer = String(
                            buffer, found + needle.size, newline - found - needle.size, Charsets.UTF_8,
                        ).trim()
                        buffer = buffer.copyOfRange(newline + 1, buffer.size)
                    }
                }
            }
            answer?.let { return it }
            val left = deadline - System.nanoTime() / 1_000_000_000.0
            if (left <= 0) return null
            lock.withLock {
                // Выход по таймауту, даже когда сигнала не было: полсекунды —
                // чтобы кусок, пришедший между проверками, не проспать навсегда.
                try {
                    arrived.awaitNanos((minOf(left, 0.5) * 1e9).toLong())
                } catch (_: InterruptedException) {
                }
            }
        }
    }

    /** Строка, напечатанная гостем, снятая с линии самого маркера. */
    fun text(expression: String, timeout: Double = 20.0): String? {
        val tag = "%04x".format(Random.nextInt(0, 0x10000))
        send("v=VAL; t=$tag; echo \"\$v\$t \$(($expression))\"\n")
        return wait("VAL$tag", timeout)
    }

    /** Число, вычисленное в госте, снятое с линии самого маркера — никогда не
     * из свободного вывода, где с приглашением и строками ядра идут числа своих.
     */
    fun number(expression: String, timeout: Double = 30.0): Long? {
        val tag = "%04x".format(Random.nextInt(0, 0x10000))
        send("v=VAL; t=$tag; echo \"\$v\$t \$(($expression))\"\n")
        val rest = wait("VAL$tag", timeout) ?: return null
        return rest.split(" ").firstOrNull()?.toLongOrNull()
    }

    /** Запускает команду и отдаёт её код выхода; вывод выбрасывается. Команда
     * не должна кончаться трубой: статус был бы последней ступени, а `head`
     * удаётся, как ни плохо то, что перед ним.
     */
    fun run(command: String, timeout: Double = 120.0): Long? =
        number("{ $command ;} >/dev/null 2>&1; echo \$?", timeout)

    /** Проводит строку в самом шелле и отдаёт её код выхода.
     *
     * `run` на это не способен: его подстановка команд — дочерний шелл, и
     * переменная, заведённая там, умирает с ним. Здесь маркер едет на той же
     * линии, что и работа, — именно это делает ожидание безопасным: наше ничего
     * не остаётся сидеть в терминале, пока гость занят, а байты консоль теряет
     * только из того, что там сидит.
     */
    fun line(command: String, timeout: Double = 120.0): Long? {
        val tag = "%04x".format(Random.nextInt(0, 0x10000))
        // Статус ловится прежде, чем побежит что-то ещё, иначе это был бы статус
        // присваивания сразу за ним — а оно всегда успешно.
        send("$command; s=\$?; v=VAL; t=$tag; echo \"\$v\$t \$s\"\n")
        val rest = wait("VAL$tag", timeout) ?: return null
        return rest.split(" ").firstOrNull()?.toLongOrNull()
    }

    /** Проверка, обязанная ответить одним из `accepting`; одна попытка снова
     * после очистки строки, потерявшей байты на входе.
     */
    fun requireAnswer(expression: String, accepting: Set<Long> = setOf(1)): Long {
        for (attempt in 0 until 2) {
            val answer = number(expression)
            if (answer != null && answer in accepting) return answer
            if (attempt == 0) reset()
        }
        throw GuestFiles.Failure(
            L("Шелл гостя не отвечает. Передача файлов работает только с бутстрапом, где на консоли сидит bash."),
            GuestFiles.FailureKind.NO_SHELL,
        )
    }

    /** Чистит строку, измельчённую потерянными байтами, или незакрытую кавычку,
     * оставленную ими. Ctrl-C, никогда Ctrl-D: последний кончил бы шелл, а у
     * демона bash на старых образах нет KeepAlive, чтобы вернуть его.
     */
    fun reset() {
        send("\u0003")
        Thread.sleep(500)
        send("\n")
        Thread.sleep(500)
    }
}

/**
 * Перекладывает файлы между телефоном и гостем — быстрым путём, по USB-сети,
 * причём консоль гостя несёт только команду.
 *
 * Гость достаёт это приложение по адресу 10.0.2.2: slirp, живущий в самом этом
 * процессе, поворачивает адрес в 127.0.0.1. Поэтому приложение слушает
 * loopback, просит шелл гостя позвонить туда через bash'евский `/dev/tcp`, и
 * `cat` на том конце копирует байты, как они есть. На стенде пять мегабайт
 * прошли за десять секунд. Одна консоль управляет лишь несколькими килобайтами
 * в секунду: управления потоком у неё нет, поэтому каждые два килобайта требуют
 * круга, чтобы убедиться, что ни один не потерян.
 *
 * Команды нарочно коротки. Когда гость занят, консоль теряет байты даже внутри
 * одной строки, и длинная команда приходит изувеченной — bash остаётся внутри
 * незакрытой кавычки и глотает всё, что дальше.
 *
 * Всё здесь блокирует; звать не с главного потока.
 */
class GuestFiles(
    private val serial: SerialConsole,
    private val linkUp: () -> Boolean,
    private val bringNetworkUp: () -> Unit,
    /** Агент гостя, когда он есть.
     *
     * Он владеет черновым namespace'ом — непрерывно опрашивает устройство, —
     * и помощник `nsio` не может читать и писать то же устройство одновременно,
     * не испортив управляющих областей агента. Это не значит потерять быстрый
     * канал: агент носит файлы сам, через область данных, свободную от его
     * заголовков, и когда адресат — обычный путь, идёт именно этот канал.
     * Сеть остаётся на то, что не удалось ни тому, ни другому.
     */
    private val agent: () -> GuestAgent? = { null },
) {

    enum class FailureKind { NETWORK_OFF, NETWORK_DOWN, NO_SHELL, DESTINATION, NOT_FOUND, NO_CONNECTION, MISMATCH, IO }

    class Failure(message: String, val kind: FailureKind) : Exception(message)

    /** Куда падают полученные файлы: Documents приложения, чтобы их было видно
     * в «Файлах».
     */
    val inbox: File get() = File(VMConfig.documents, "Guest")

    /** Имя этого приложения для гостя, как его подаёт slirp. */
    private val hostAddress = "10.0.2.2"

    // MARK: - В гостя

    /** Возвращает путь, где файл оказался внутри гостя.
     *
     * Сеть наперёд больше не требуется: с помощником, уже сидящим в госте,
     * байты едут по namespace, и это работает, поднят линк или нет. Просят её
     * только тогда, когда остался медленный путь.
     */
    fun send(file: File, progress: (Long, Long) -> Unit): String =
        serial.exclusive { sendExclusively(file, progress) }

    /** Несёт локальный файл на точный путь в госте самым быстрым из имеющихся
     * каналов, с возвратом к сети. Общее для меню файлов и установщика `.ipa`,
     * чтобы обоим достались одна скорость и одна проверка. `remote` — выражение
     * шелла: кавыченный путь или построенный вокруг переменной, которую гость
     * держит. `plain` — тот же адресат обычным путём файловой системы, когда
     * звонящий его знает: агент говорит с гостем в обход консоли и не имеет
     * шелла, который разложил бы что-то за него.
     */
    fun carry(
        file: File,
        remote: String,
        plain: String?,
        shell: GuestShell,
        progress: (Long, Long) -> Unit,
        note: (String) -> Unit,
    ) {
        // Каталог должен существовать прежде, чем в него что-то льётся. Когда
        // его не было, `cat` гостя падал на открытии файла и закрывал сокет, и
        // этот конец видел только «Broken pipe» — правду о сокете, которая
        // ничего не говорит о причине. Одна короткая команда не стоит ничего
        // и снимает весь класс недоумений.
        shell.run("mkdir -p \"\$(dirname $remote)\"")

        // Собственный канал агента: тот же namespace, без консоли.
        val agentInstance = agent()
        if (agentInstance != null && plain != null) {
            note(L("Канал: агент, NVMe."))
            agentInstance.send(file, plain, progress)
            return
        }

        val fast = fastChannel(shell, note)
        if (fast != null) {
            note(L("Канал: NVMe, %@.", fast.device))
            fast.send(file, remote, shell, progress)
            return
        }
        note(L("Канал: USB-сеть."))
        requireNetwork()
        stream(file, remote, shell, progress)
    }

    /** Быстрый канал или null, когда эмулятор его не даёт. Ищется раз за
     * передачу: найти его стоит пары коротких команд.
     */
    private fun fastChannel(shell: GuestShell, note: (String) -> Unit): TransferNamespace? {
        // Агент, когда он поднят, — тот, кто читает и пишет это устройство.
        // Его помощник разделить его не может — что агент не унесёт, идёт
        // по сети.
        if (agent() != null) return null
        return TransferNamespace.discover(shell, deliver = { local, path ->
            // Помощник сам может приехать только медленным путём — он и есть
            // то, что делает возможным быстрый.
            requireNetwork()
            stream(local, quote(path), shell) { _, _ -> }
        }, note = note)
    }

    private fun sendExclusively(file: File, progress: (Long, Long) -> Unit): String {
        val shell = GuestShell(serial)
        openDestination(shell)

        val name = safeName(file.name)
        // Построено вокруг переменной самого гостя, а не выписано: в имени
        // папки, на которую она указывает, есть пробелы, а путь с пробелами —
        // ещё одна вещь, которую можно испортить на консоли, теряющей байты.
        val remote = "\"\$F/Inferno/\"" + quote(name)
        // Агенту адресат нужен выписанный, поэтому `$F` спрашивают один раз
        // и раскрывают здесь. В нём пробелы, что безразлично в обход консоли.
        val plain = shell.text("echo \"\$F/Inferno\"")?.let { "$it/$name" }
        carry(file, remote, plain, shell, progress) { }
        // Писал root; открыть файл должен и пользователь телефона.
        shell.line("chown -R mobile:mobile \"\$F/Inferno\" 2>/dev/null", timeout = 60.0)
        return shell.text("echo \"\$F/Inferno\"")?.let { "$it/$name" } ?: name
    }

    /** Сама передача. `remote` — уже выражение шелла: кавыченный путь или
     * построенный вокруг переменной, которую держит гость.
     */
    private fun stream(file: File, remote: String, shell: GuestShell, progress: (Long, Long) -> Unit) {
        val source = try {
            FileInputStream(file)
        } catch (e: Throwable) {
            throw Failure(L("Ошибка ввода-вывода: %@", e.message ?: "?"), FailureKind.IO)
        }

        val listener = LoopbackListener()
        try {
            // `remote` — уже выражение шелла, кавычки и все. Кавычить его второй
            // раз — значит получить имя файла с кавычками внутри, и файл вообще
            // не был бы записан.
            shell.send("exec 3<>/dev/tcp/$hostAddress/${listener.port};cat<&3>$remote;exec 3<&-\n")
            val conn = listener.accept(60.0)
            if (conn == null) {
                shell.reset()
                throw Failure(
                    L("Гость не подключился к приложению: команда не дошла или сеть не работает."),
                    FailureKind.NO_CONNECTION,
                )
            }

            try {
                conn.soTimeout = 120_000
                val out = conn.getOutputStream()

                val sum = PosixChecksum()
                var sent = 0L
                val chunk = ByteArray(64 * 1024)
                while (true) {
                    val n = try {
                        source.read(chunk)
                    } catch (e: Throwable) {
                        throw Failure(L("Ошибка ввода-вывода: %@", e.message ?: "?"), FailureKind.IO)
                    }
                    if (n <= 0) break
                    sum.update(chunk.copyOf(n))
                    try {
                        out.write(chunk, 0, n)
                    } catch (e: Throwable) {
                        throw Failure(L("Ошибка ввода-вывода: %@", e.message ?: "?"), FailureKind.IO)
                    }
                    sent += n
                    progress(sent, maxOf(file.length(), sent))
                }

                // Гость закрывает свой конец, когда `cat` прочитал всё; только
                // тогда файл полон и стоит проверки.
                try {
                    conn.shutdownOutput()
                    val scratch = ByteArray(4096)
                    while (conn.getInputStream().read(scratch) > 0) {
                    }
                } catch (_: Throwable) {
                }

                verify(shell, remote, sum)
            } finally {
                try {
                    conn.close()
                } catch (_: Throwable) {
                }
            }
        } finally {
            listener.close()
            try {
                source.close()
            } catch (_: Throwable) {
            }
        }
    }

    /** Направляет `$F` гостя туда, куда заглянет его собственный Files.
     *
     * Файл, положенный в `/var/mobile/Inferno`, изнутри гостя невидим: Files
     * показывает «На моём iPhone» из контейнера локального провайдера, который
     * живёт под app group, чьё имя — UUID, другой на каждом устройстве, так что
     * его надо спрашивать, а не знать.
     *
     * Путь остаётся в переменной шелла вместо того, чтобы таскаться туда и
     * обратно. В нём пробелы, а консоль — не место для них. Если шаблон не
     * совпал ни с чем, переменная держит путь, которого нет, — это и ловит
     * проверка ниже.
     */
    private fun openDestination(shell: GuestShell) {
        var heard = false
        for (attempt in 0 until 2) {
            if (prepareDestination(shell) { heard = it }) return
            if (attempt == 0) shell.reset()
        }
        // Тишина и неверный ответ — разные неисправности, и докладывались они
        // раньше как одна. Совсем ничего — значит, на консоли нет шелла; ответ,
        // который мы не хотели, значит, гость на месте, а папки нет.
        throw if (heard) {
            Failure(
                L("Не удалось подготовить папку для файлов в госте: он слишком занят. Попробуйте ещё раз."),
                FailureKind.DESTINATION,
            )
        } else {
            Failure(
                L("Шелл гостя не отвечает. Передача файлов работает только с бутстрапом, где на консоли сидит bash."),
                FailureKind.NO_SHELL,
            )
        }
    }

    /** Один проход подготовки, true, когда папка в конце есть.
     *
     * Каждая строка ждёт предыдущую. `grep` обходит каждую app group телефона,
     * и на загруженном госте это долго; всё, что послано в это время, лишь
     * сидит в терминале, откуда консоль теряет байты, — и Ctrl-C, посланный
     * очистить одну изувеченную строку, выбрасывает и остальные. Так `$F`
     * оказывался незаведённым, и папка не создавалась, а повтор спрашивал про
     * `/Inferno` и слышал честное «нет».
     *
     * Поэтому неудавшийся повторяет всю подготовку, а не только проверку:
     * после сброса переменные с равной вероятностью отсутствуют или ошибочны.
     */
    private fun prepareDestination(shell: GuestShell, heard: (Boolean) -> Unit): Boolean {
        // Сама папка хранилища может ещё не существовать — Files создаёт её,
        // когда что-то впервые сохраняют локально, а на свежем госте этого
        // никогда не случалось. Контейнер группы есть всегда, и сам говорит,
        // чем владеет, — его и ищут. Совсем безуспешно — старое место, которое
        // по крайней мере работает.
        //
        // `grep` обходит каждую app group телефона — это медленный; остальные
        // мгновенны, и им дан запас на случай, когда гость занят, когда они
        // приходят.
        val steps = listOf(
            "A=/var/mobile/Containers/Shared/AppGroup" to 30.0,
            "G=\$(grep -l LocalStorage \$A/*/.com.apple*.plist 2>/dev/null|head -1)" to 180.0,
            "[ -n \"\$G\" ] && F=\"\${G%/*}/File Provider Storage\" || F=/var/mobile" to 30.0,
            "mkdir -p \"\$F/Inferno\"" to 60.0,
        )
        for ((command, timeout) in steps) {
            if (shell.line(command, timeout) == null) return false
            heard(true)
        }
        val answer = shell.number("test -d \"\$F/Inferno\" && echo 1 || echo 0") ?: return false
        heard(true)
        return answer == 1L
    }

    // MARK: - Из гостя

    /** Возвращает, где файл сохранён на телефоне. */
    fun receive(remote: String, progress: (Long, Long) -> Unit): File =
        serial.exclusive { receiveExclusively(remote, progress) }

    private fun receiveExclusively(remote: String, progress: (Long, Long) -> Unit): File {
        val shell = GuestShell(serial)
        val exists = shell.requireAnswer("test -f ${quote(remote)} && echo 1 || echo 0", setOf(0, 1))
        if (exists != 1L) throw Failure(L("Нет такого файла в госте: %@", remote), FailureKind.NOT_FOUND)
        val total = shell.number("wc -c < ${quote(remote)}") ?: 0

        inbox.mkdirs()
        val destination = freeName(remote.substringAfterLast('/'), inbox)
        val partial = File(inbox, destination.name + ".part")

        // Агент читает файл и сам укладывает его в область данных.
        val agentInstance = agent()
        if (agentInstance != null) {
            try {
                agentInstance.receive(remote, partial, progress)
                if (!partial.renameTo(destination)) {
                    throw Failure(L("Ошибка ввода-вывода: %@", "rename"), FailureKind.IO)
                }
                return destination
            } catch (e: Throwable) {
                partial.delete()
                throw e
            }
        }

        // Namespace несёт его целиком и проверяет, без замешанных в байты
        // консоли и сети.
        val fast = fastChannel(shell) { }
        if (fast != null) {
            try {
                fast.receive(quote(remote), partial, shell, progress)
                if (!partial.renameTo(destination)) {
                    throw Failure(L("Ошибка ввода-вывода: %@", "rename"), FailureKind.IO)
                }
                return destination
            } catch (e: Throwable) {
                partial.delete()
                throw e
            }
        }

        requireNetwork()
        val listener = LoopbackListener()
        try {
            shell.send("cat ${quote(remote)}>/dev/tcp/$hostAddress/${listener.port}\n")
            val conn = listener.accept(60.0)
            if (conn == null) {
                partial.delete()
                shell.reset()
                throw Failure(
                    L("Гость не подключился к приложению: команда не дошла или сеть не работает."),
                    FailureKind.NO_CONNECTION,
                )
            }

            try {
                conn.soTimeout = 120_000
                val sum = PosixChecksum()
                var got = 0L
                val buffer = ByteArray(64 * 1024)
                FileOutputStream(partial).use { out ->
                    while (true) {
                        val n = try {
                            conn.getInputStream().read(buffer)
                        } catch (e: Throwable) {
                            throw Failure(L("Ошибка ввода-вывода: %@", e.message ?: "?"), FailureKind.IO)
                        }
                        if (n < 0) break
                        if (n == 0) continue
                        val piece = buffer.copyOf(n)
                        sum.update(piece)
                        out.write(piece)
                        got += n
                        progress(got, maxOf(total, got))
                    }
                }
                verify(shell, quote(remote), sum)

                if (!partial.renameTo(destination)) {
                    throw Failure(L("Ошибка ввода-вывода: %@", "rename"), FailureKind.IO)
                }
                return destination
            } catch (e: Throwable) {
                partial.delete()
                throw e
            } finally {
                try {
                    conn.close()
                } catch (_: Throwable) {
                }
            }
        } finally {
            listener.close()
        }
    }

    // MARK: - Помощники

    private fun requireNetwork() {
        if (linkUp()) return
        // Эмулятор уже попробовал всё, что позволяет USB-сторона; осталось
        // попросить гостя настроить его интерфейс самому.
        bringNetworkUp()
        val deadline = System.nanoTime() / 1_000_000_000.0 + 25
        while (System.nanoTime() / 1_000_000_000.0 < deadline) {
            if (linkUp()) return
            Thread.sleep(1000)
        }
        throw Failure(
            L("Сеть в госте не поднялась. Нажмите «Поднять сеть в госте» и попробуйте снова."),
            FailureKind.NETWORK_DOWN,
        )
    }

    /** `path` уже закавычен для шелла гостя — это либо простая строка в кавычках,
     * либо построенное вокруг переменной, которую держит гость.
     */
    private fun verify(shell: GuestShell, path: String, sum: PosixChecksum) {
        // cksum обходит весь файл, а гость не быстрый.
        val slack = 60.0 + sum.length / 200_000.0
        val size = shell.number("wc -c < $path", timeout = slack)
        val crc = shell.number("cksum < $path | cut -d' ' -f1", timeout = slack)
        if (size != sum.length || crc != (sum.value.toLong() and 0xFFFF_FFFFL)) {
            throw Failure(
                L("Файл не сошёлся по контрольной сумме (%@).",
                    L("в госте %@ Б, у нас %d Б", size?.toString() ?: "?", sum.length)),
                FailureKind.MISMATCH,
            )
        }
    }

    companion object {
        /** Сообщение запрета «сеть машины выключена» — то, что в Swift давал
         * `errorDescription` перечисления отказов.
         */
        fun networkOffMessage(): String =
            L("Включите «Интернет через USB» в параметрах: файлы идут по той же сети.")

        /** Сообщение запрета «шелл гостя не отвечает». */
        fun noShellMessage(): String =
            L("Шелл гостя не отвечает. Передача файлов работает только с бутстрапом, где на консоли сидит bash.")

        /** Кавычит путь для шелла гостя, не ставя на командную строку ни одного
         * не-ASCII байта.
         *
         * Консоль отдаёт bash'у байты выше 0x7F так, что ломает строку: команда
         * с кириллическим именем файла никогда не завершается, и bash остаётся
         * ждать внутри неё. `$'…'` с восьмеричными заменами выговаривает те же
         * байты чистым ASCII, так что имя переживает именно, пока строка
         * остаётся безопасной.
         */
        fun quote(path: String): String {
            val bytes = path.toByteArray(Charsets.UTF_8)
            if (bytes.all { it >= 0x20 && it < 0x7F }) {
                return "'" + path.replace("'", "'\\''") + "'"
            }
            val out = StringBuilder("$'")
            for (byte in bytes) {
                val v = byte.toInt() and 0xFF
                if (v >= 0x20 && v < 0x7F && v != 0x27 && v != 0x5C) {
                    out.append(v.toChar())
                } else {
                    out.append("\\%03o".format(v))
                }
            }
            return out.append("'").toString()
        }

        /** Имя, которое не сломает командную строку: без слэшей, без того, на что
         * терминал реагировал бы.
         */
        fun safeName(name: String): String {
            val cleaned = name.filter { it.code >= 0x20 && it != '/' && it.code != 0x7F }
            return cleaned.ifEmpty { "file" }
        }

        /** Сохраняет прежние файлы: `a.txt`, затем `a 2.txt`, как делает Files. */
        fun freeName(name: String, directory: File): File {
            val safe = safeName(name)
            val dot = safe.lastIndexOf('.')
            val base = if (dot > 0) safe.substring(0, dot) else safe
            val ext = if (dot > 0) safe.substring(dot) else ""
            var candidate = File(directory, safe)
            var index = 2
            while (candidate.exists()) {
                candidate = File(directory, if (ext.isEmpty()) "$base $index" else "$base $index$ext")
                index += 1
            }
            return candidate
        }
    }
}
