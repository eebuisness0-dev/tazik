package dev.tazik.inferno.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import kotlin.concurrent.thread
import kotlin.concurrent.withLock

/**
 * Консоль гостя — загрузочный журнал и первое место, куда смотреть, когда экран
 * остался чёрным.
 *
 * Эмулятор пишет её в файл, а не только в сокет: сокет выбрасывает всё
 * напечатанное до подключения клиента, а гость начинает говорить задолго до
 * того, как интерфейс успеет подключиться. Чтение из файла значит, что журнал
 * всегда начинается с первого байта и переживает переподключения.
 *
 * В SwiftUI это был `ObservableObject` с `@Published`-свойствами; здесь те же
 * свойства — `StateFlow`, которые Compose-экран собирает так же, как собирал бы
 * любые другие.
 */
class SerialConsole {

    private val _text = MutableStateFlow("")
    val text: StateFlow<String> = _text.asStateFlow()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    /** Байты, пришедшие последними, и число, которое их считает.
     *
     * Терминал кормится по мере того, как говорит консоль, а не пересобирается
     * из целого журнала каждые полсекунды: escape-коды значат что-то только по
     * порядку, а проигрывать четверть мегабайта четыре раза в секунду, чтобы
     * это узнать, было бы глупо. Счётчик позволяет экрану отличить кусок, уже
     * виденный, от нового.
     */
    private val _chunk = MutableStateFlow("")
    val chunk: StateFlow<String> = _chunk.asStateFlow()

    private val _sequence = MutableStateFlow(0)
    val sequence: StateFlow<Int> = _sequence.asStateFlow()

    private val _interactive = MutableStateFlow(false)
    val interactive: StateFlow<Boolean> = _interactive.asStateFlow()

    /** Консоль уже доложила о молчании — сказано один раз, а не дважды в секунду. */
    private var quiet = false

    /** Байт в секунду, которые гость льёт в консоль, — для счётчика под экраном. */
    private val _consoleRate = MutableStateFlow(0.0)
    val consoleRate: StateFlow<Double> = _consoleRate.asStateFlow()
    private val rateLock = Any()
    private var rateBytes = 0
    private var rateSince = System.nanoTime() / 1_000_000_000.0

    private var handle: RandomAccessFile? = null
    private var poller: Thread? = null
    @Volatile private var stopping = false

    /** Длинная загрузка выдаёт много; хвоста хватает. */
    private val limit = 256 * 1024

    /** Сколько журнала эмулятора позволено держать на диске. */
    private val logLimit: Long = 64L * 1024 * 1024
    private var lastTrim = Double.NEGATIVE_INFINITY

    private val log: File get() = VMConfig.guestConsoleLog

    /** Консоль — сокет так же, как файл: файл держит историю с первого байта,
     * сокет несёт то, что мы печатаем. С бутстрапом джейлбрейка на
     * /dev/console сидит демон bash, так что это настоящий шелл, а не журнал.
     */
    private var input: Sock? = null
    @Volatile private var attached = false
    private val sendLock = Any()

    /** Держится на целый разговор, а не на одну запись.
     *
     * Блокировать каждую запись было недостаточно. Передача файлов —
     * последовательность: заведи переменную, создай каталог, спроси, есть ли он, —
     * а сетевой сторож ставил `ipconfig set en0 DHCP` между двумя её строками.
     * Ничего не перемешалось, и всё же последовательность развалилась, потому что
     * гость выполнил чужую команду посреди нашей.
     */
    private val conversation = java.util.concurrent.locks.ReentrantLock()

    /** Проводит последовательность команд, владея консолью целиком. Блокирует;
     * никогда не звать с главного потока.
     */
    fun <T> exclusive(body: () -> T): T = conversation.withLock { body() }

    /** То же — для звонящего, которому нечего сказать, когда консоль занята:
     * сторож скорее пропустит пинок, чем сломает передачу.
     */
    fun ifFree(body: () -> Unit): Boolean =
        if (conversation.tryLock()) {
            try {
                body()
                true
            } finally {
                conversation.unlock()
            }
        } else {
            false
        }

    /** Сколько раз гость падал с тех пор, как приложение стартовало.
     *
     * Паника ядра кончает всякий разговор на этой консоли разом, и тот, кто
     * ждал, чем закончится установка пакета, иначе просидел бы весь таймаут —
     * полчаса — прежде чем узнал, что отвечать уже некому. Гость прощается
     * внятно, на пути вниз; здесь это считается, а ждущие сверяют счёт с тем,
     * с которого начинали.
     */
    private val deathLock = Any()
    private var deaths = 0
    private var deathWhy = ""
    private var lastDeath = Double.NEGATIVE_INFINITY

    /** Хвост прошлого чтения, чтобы строка, разрезанная между двумя чтениями,
     * всё равно узналась. Ничто из искомого здесь не длиннее одной строки.
     */
    private var deathTail = ""

    val guestDeaths: Int get() = synchronized(deathLock) { deaths }
    val guestDeathReason: String get() = synchronized(deathLock) { deathWhy }

    /** Зовётся, когда гость падает, — тому, кому надо перестроиться, когда он
     * вернётся. В Swift уходило в главный поток; здесь зовётся с читающего
     * потока, и получателю виднее, куда перепрыгивать.
     */
    var onGuestDeath: (() -> Unit)? = null

    /** Что пишет умирающий гость. `initproc exited` — launchd, который не смог
     * дальше, — что и делает установка подменяющей среды с этим образом.
     */
    private val deathMarks = listOf("panic(cpu ", "wdog panic", "initproc exited")

    private fun watchForDeath(piece: String) {
        val text = deathTail + piece
        deathTail = text.takeLast(120)
        val line = text.split("\n").firstOrNull { line -> deathMarks.any { it in line } } ?: return

        // Паника — страница текста, и гость печатает её целиком снова при
        // следующей загрузке. Один доклад на падение — то, чего хватает ждущему.
        val now = System.nanoTime() / 1_000_000_000.0
        if (now - lastDeath < 60) return
        lastDeath = now

        synchronized(deathLock) {
            deaths += 1
            deathWhy = line.trim().take(160)
        }
        onGuestDeath?.invoke()
    }

    /** Все, кому нужен вывод консоли по мере прихода. Передача файлов выбирает
     * из него ответы.
     */
    private val tapsLock = Any()
    private val taps = HashMap<UUID, (ByteArray) -> Unit>()

    fun tap(handler: (ByteArray) -> Unit): UUID {
        val id = UUID.randomUUID()
        synchronized(tapsLock) { taps[id] = handler }
        return id
    }

    fun untap(id: UUID) {
        synchronized(tapsLock) { taps.remove(id) }
    }

    private fun deliver(data: ByteArray) {
        count(data.size)
        val handlers = synchronized(tapsLock) { taps.values.toList() }
        for (handler in handlers) handler(data)
    }

    /** Держит скорость потока консоли, публикуя её раз в секунду.
     *
     * Рядом со счётчиком кадров: гость, живущий давно, льёт в эту трубу десятки
     * мегабайт ядрового журнала в секунду — каждая его строка отформатирована
     * эмулируемыми ядрами, теми же, что должны рисовать экран. Когда это число
     * велико, больше ничего о частоте кадров читать не стоит.
     */
    private fun count(bytes: Int) {
        val rate: Double
        synchronized(rateLock) {
            rateBytes += bytes
            val elapsed = System.nanoTime() / 1_000_000_000.0 - rateSince
            if (elapsed < 1) return
            rate = rateBytes / elapsed
            rateBytes = 0
            rateSince = System.nanoTime() / 1_000_000_000.0
        }
        _consoleRate.value = rate
    }

    /** Держит читателя на сокете консоли, пока машина работает.
     *
     * Переподключение важнее, чем кажется. Кто-то должен брать то, что пишет
     * эмулятор; когда этот цикл кончался — ошибка чтения, таймаут сокета, —
     * не делал этого никто, буфер эмулятора наполнялся, и всё за ним
     * останавливалось вместе с ним. Поэтому цикл — цикл: если чтение кончилось,
     * а машину не просили остановить, он подключается снова.
     */
    fun attachInput(port: Int) {
        if (attached) return
        attached = true
        thread(name = "inferno.serial-input", isDaemon = true) {
            while (!stopping) drain(port)
        }
    }

    private fun drain(port: Int) {
        val sock = Sock()
        val why = sock.connect(port)
        if (why != null) {
            Thread.sleep(2000)
            return
        }
        // Консоль может молчать минутами. С обычным двадцатисекундным таймаутом
        // чтения первая же тихая пауза была принята за конец потока, и всё
        // останавливалось насовсем — после чего никто не видел ответов гостя,
        // а передача файлов докладывала о мёртвом шелле, пока шелл отвечал
        // на экране.
        sock.waitIndefinitely()
        input = sock
        _interactive.value = true
        // Выкачанное идёт всем, кто ждёт ответа.
        while (input === sock) {
            val data = sock.readSome() ?: break
            deliver(data)
        }

        sock.close()
        if (input === sock) input = null
        _interactive.value = false
        if (!stopping) Thread.sleep(1000)
    }

    fun send(text: String) {
        synchronized(sendLock) {
            val sock = input ?: return
            sock.write(text.toByteArray(Charsets.UTF_8))
        }
    }

    /** Начинает следить за журналом, дождавшись, когда файл появится. */
    fun follow() {
        if (poller != null) return
        stopping = false
        poller = thread(name = "inferno.serial-poll", isDaemon = true) {
            // Достаточно часто, чтобы вывод не приходил видимыми ступенями.
            // Чтение, не нашедшее ничего, стоит одного syscall'а, а работа за
            // чтением, нашедшим что-то, та же, как его ни делить.
            while (!stopping) {
                try {
                    poll()
                    /* Sleep под общим catch'ем: stop() прерывает эту нить,
                     * и вылезшее из sleep'а InterruptedException раньше
                     * убивало весь процесс — оба сегодняшних «вылетело»
                     * (16:39, 16:52) именно это. Прерывание — штатный
                     * сигнал: останавливаемся тихо по проверке !stopping. */
                    Thread.sleep(120)
                } catch (_: Throwable) {
                }
            }
        }
    }

    fun stop() {
        stopping = true
        input?.close()
        input = null
        _interactive.value = false
        poller?.interrupt()
        poller = null
        try {
            handle?.close()
        } catch (_: Throwable) {
        }
        handle = null
        _connected.value = false
    }

    fun clear() {
        _text.value = ""
    }

    /** Не даёт журналу эмулятора съесть телефон.
     *
     * Журнал — `-chardev …,logfile=`: его пишет эмулятор, и никто его не
     * вращает. Гость в первые десять минут льёт по нему десятки мегабайт в
     * секунду: на стенде — четыре гигабайта прежде, чем уляжется, и всё это
     * попадает в Documents приложения.
     *
     * Обрезать файл в ноль достаточно: эмулятор дописывает, так что то, что он
     * напишет дальше, ляжет в начало короткого файла, и `poll` увидит, что файл
     * стал короче места, где он остановился. Эмулятор раньше писал дальше со
     * старого смещения и оставлял впереди дыру из нулей, и присылаемый журнал
     * был сотнями мегабайт ничего.
     */
    private fun trimIfHuge() {
        val now = System.nanoTime() / 1_000_000_000.0
        if (now - lastTrim < 5) return
        lastTrim = now
        val file = log
        if (file.isFile && file.length() > logLimit) {
            try {
                java.io.RandomAccessFile(file, "rw").use { it.setLength(0) }
            } catch (_: Throwable) {
            }
        }
    }

    private fun poll() {
        trimIfHuge()
        if (handle == null) {
            val file = log
            if (!file.isFile) return
            handle = try {
                RandomAccessFile(file, "r")
            } catch (_: Throwable) {
                return
            }
            _connected.value = true
        }

        val reader = handle ?: return
        // Обрезан trimIfHuge или опустошён заново: читать продолжают с начала,
        // вместо того чтобы ждать за концом файла, до которого он больше не
        // дорастёт.
        try {
            if (reader.length() < reader.filePointer) reader.seek(0)
        } catch (_: Throwable) {
        }
        val bytes = try {
            readAll(reader)
        } catch (_: Throwable) {
            ByteArray(0)
        }
        if (bytes.isEmpty()) {
            // Строка приглашения не несёт newline после себя, поэтому читающий
            // не может отличить недописанную строку от законченной, пока
            // консоль не замолчит. Сказать это один раз, когда замолчала, —
            // то, что заставляет приглашение появиться.
            if (!quiet) {
                quiet = true
                _chunk.value = ""
                _sequence.value += 1
            }
            return
        }
        quiet = false

        val piece = String(bytes, Charsets.UTF_8)
        watchForDeath(piece)
        _text.value = _text.value.let { old ->
            (old + piece).takeLast(limit)
        }
        _chunk.value = piece
        _sequence.value += 1
    }

    /** Хвост файла от текущего места, не держа в памяти больше, чем пришло. */
    private fun readAll(reader: RandomAccessFile): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = reader.read(buffer)
            if (n <= 0) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }
}
