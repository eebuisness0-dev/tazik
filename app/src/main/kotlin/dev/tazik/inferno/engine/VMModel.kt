package dev.tazik.inferno.engine

import android.graphics.Bitmap
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Картинка гостя, держимая отдельно от всего остального.
 *
 * Она приходит так же часто, как гость рисует, и всякий, кто следит за
 * объектом-владельцем, перестраивался бы так же часто. Это было меню
 * управления: открытое, его отбрасывало к первой строке десятки раз в
 * секунду, и прокрутить его было нельзя. Кадры нужны только экрану, поэтому
 * за ними следит только экран.
 */
class GuestFrame {
    private val _image = MutableStateFlow<Bitmap?>(null)
    val image: StateFlow<Bitmap?> = _image.asStateFlow()

    /** Сколько таких пришло за последнюю секунду. */
    private val _fps = MutableStateFlow(0.0)
    val fps: StateFlow<Double> = _fps.asStateFlow()

    private var counted = 0
    private var since = System.nanoTime() / 1_000_000_000.0

    fun deliver(frame: Bitmap) {
        _image.value = frame
        counted += 1
        // Раз в секунду, а не раз в кадр: число — для глаз, и перепубликовать
        // его тридцать раз в секунду значило перерисовывать надпись так же
        // часто, как картинку.
        val elapsed = System.nanoTime() / 1_000_000_000.0 - since
        if (elapsed >= 1) {
            _fps.value = counted / elapsed
            counted = 0
            since = System.nanoTime() / 1_000_000_000.0
        }
    }
}

/** Состояние переноса файла — для полосы внизу экрана. */
sealed class TransferState {
    data class Running(val title: String, val done: Long, val total: Long) : TransferState()
    data class Finished(val text: String) : TransferState()
    data class Failed(val why: String) : TransferState()

    val isRunning: Boolean
        get() = this is Running

    companion object {
        fun size(bytes: Long): String =
            if (bytes < 1 shl 20) {
                L("%.0f КБ", bytes / 1024.0)
            } else {
                L("%.1f МБ", bytes / 1_048_576.0)
            }

        /** «5,0 МБ за 10,1 с · 507 КБ/с» — по скорости и видно, взят ли быстрый
         * путь.
         */
        fun summary(file: File, seconds: Double): String {
            val bytes = try {
                file.length()
            } catch (_: Throwable) {
                0L
            }
            val rate = if (seconds > 0) bytes / seconds / 1024 else 0.0
            return L("%@ за %.1f с · %.0f КБ/с", size(bytes), seconds, rate)
        }
    }
}

/**
 * Вся модель машины. Порт `VMModel` из `InfernoApp.swift` без view-тел:
 * состояние здесь — `StateFlow`, а вместо `DispatchQueue` — главный поток
 * Android (`Handler`) и собственный пул для фоновой работы. Всё, что Swift
 * делал на main-потоке, делается здесь на нём же, так что порядок перемен
 * тот же.
 *
 * В Swift рестор звал модель напрямую; здесь то же самое выражено
 * интерфейсом `RestoreHost`, чтобы движок не зависел от приложения.
 */
class VMModel : RestoreHost {

    val picture = GuestFrame()

    private val _displayStatus =
        MutableStateFlow<GuestDisplayStatus>(GuestDisplayStatus.Disconnected)
    val displayStatus: StateFlow<GuestDisplayStatus> = _displayStatus.asStateFlow()

    /** Перемены жизненного цикла эмулятора — проброс `QemuBridge`. */
    val qemuState: StateFlow<QemuBridge.State> get() = QemuBridge.shared.state

    /** Поднимал ли гость когда-нибудь адрес по USB-связи. */
    private val _networkUp = MutableStateFlow(false)
    val networkUp: StateFlow<Boolean> = _networkUp.asStateFlow()

    /** Чем занят перенос файлов, — для полосы внизу. */
    private val _transfer = MutableStateFlow<TransferState?>(null)
    val transfer: StateFlow<TransferState?> = _transfer.asStateFlow()

    private val _missing = MutableStateFlow(VMConfig.missingFiles())
    val missing: StateFlow<List<String>> = _missing.asStateFlow()

    /** Ставится пятью касаниями заголовка экрана настройки: показывает главный
     * экран, когда файлов всё ещё нет. Только на этот запуск — запуск машины
     * всё равно отказан, пока файлов нет.
     */
    private val _setupSkipped = MutableStateFlow(false)
    val setupSkipped: StateFlow<Boolean> = _setupSkipped.asStateFlow()

    fun skipSetup() {
        _setupSkipped.value = true
    }

    private val _jit = MutableStateFlow(JIT.status)
    val jit: StateFlow<JIT.Availability> = _jit.asStateFlow()

    /** QEMU не реентерабелен и живёт внутри этого процесса. Раз машина была
     * заведена, вторая в том же процессе утащила бы приложение за собой, так
     * что запускать снова — значит перезапускать приложение.
     */
    private val _hasRun = MutableStateFlow(false)
    val hasRun: StateFlow<Boolean> = _hasRun.asStateFlow()

    /** Всё на месте, но на диске ещё нет системы. */
    private val _needsRestore =
        MutableStateFlow(VMConfig.missingFiles().isEmpty() && !VMConfig.systemInstalled)
    val needsRestore: StateFlow<Boolean> = _needsRestore.asStateFlow()

    val serial = SerialConsole()

    /** Шелл на собственном сокете, вдали от болтовни ядра. Ему нужен тот же
     * работающий линк, что и переносу файлов, и просит он его тем же способом.
     */
    val shell = ShellChannel(serial = serial, linkUp = { linkIsUp })

    /** Агент внутри гостя, когда он найден или поставлен, — путь приложения
     * мимо единственной консоли. null, пока поднимается, и навсегда на сборке
     * или эмуляторе, где его быть не может, — тогда всё откатывается на
     * консоль, как раньше.
     */
    @Volatile
    var guestAgent: GuestAgent? = null
        private set

    @Volatile
    private var agentBringUpStarted = false

    private val qmp: QMPClient by lazy { QMPClient(config.qmpPort) }

    /** Чем следующий запуск подменяет настройки. Восстановление заводит ту же
     * машину с рамдиском и без сетевого устройства, и это свойство прогона, а
     * не то, что стоит сохранять.
     */
    var configOverride: VMConfig? = null

    val config: VMConfig get() = configOverride ?: Settings.config

    /** Машина стоит на рамдиске восстановления. В её консоль ничего нельзя
     * вводить: шелл, агент, строка состояния и часовой пояс начинают с
     * очистки строки Ctrl-C, а на рамдиске тот SIGINT достаётся самому
     * восстановлению — ASR замолкал через две секунды, и перенос умирал
     * с NAK.
     */
    val isRestoring: Boolean get() = config.restoreRamdiskPath != null

    /** Выбран при запуске машины и держится весь её срок: встроенному пути
     * нужна уже загруженная библиотека эмулятора.
     */
    @Volatile
    private var display: GuestDisplay? = null

    val framebufferSize: Pair<Int, Int>? get() = _displayStatus.value.size

    val isRunning: Boolean get() = QemuBridge.shared.state.value == QemuBridge.State.Running

    /** Как в Swift-диспетчерах: всё, что там шло на главный поток, идёт на
     * главный поток Android; фон — в собственный пул.
     */
    private val main = Handler(Looper.getMainLooper())
    private val pool: ExecutorService = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "inferno.model").apply { isDaemon = true }
    }

    private fun onMain(block: () -> Unit) {
        main.post(block)
    }

    private fun after(seconds: Double, block: () -> Unit) {
        main.postDelayed(block, (seconds * 1000).toLong())
    }

    private fun background(block: () -> Unit) {
        pool.execute(block)
    }

    private fun now(): Double = System.nanoTime() / 1_000_000_000.0

    /** Читаемый текст ошибки вместо Swift-описания исключения. */
    private fun text(error: Throwable): String =
        error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName

    // MARK: Экран

    /** null, когда просили встроенный путь, а в библиотеке его нет. Откат на
     * VNC был бы хуже, чем сказать прямо: машина запущена без VNC-сервера, и
     * клиент сидел бы в попытках на порту, где никто не слушает.
     */
    private fun makeDisplay(): GuestDisplay? {
        val chosen: GuestDisplay = if (config.builtInDisplay) {
            val embedded = EmbeddedDisplay()
            if (!embedded.isAvailable) return null
            embedded
        } else {
            VNCClient(config.vncPort)
        }
        chosen.onFrame = { image -> picture.deliver(image) }
        chosen.onStatus = { status -> _displayStatus.value = status }
        return chosen
    }

    // MARK: Файлы и JIT

    fun refreshFiles() {
        _missing.value = VMConfig.missingFiles()
        _needsRestore.value = _missing.value.isEmpty() && !VMConfig.systemInstalled
    }

    /** StikDebug цепляется после запуска, так что ответ меняется со временем. */
    fun refreshJIT() {
        _jit.value = JIT.prepare()
        if (!_jit.value.isAvailable) {
            // Записать, какие стратегии выделения ядро всё же позволяет, чтобы
            // причина лежала в журнале, а не в догадках.
            JIT.diagnose()
        }
    }

    // MARK: Запуск и останов

    fun start() {
        refreshFiles()
        refreshJIT()
        // Сказано вслух и в журнал. Кнопка в этом случае недоступна, так что
        // снаружи это «жму «Запустить», и ничего» — и в журнале из такого
        // отчёта нет ничего вовсе.
        val stillMissing = _missing.value
        if (stillMissing.isNotEmpty()) {
            LogCapture.note(L("Запуск отменён: не хватает файлов — %@", stillMissing.joinToString(", ")))
            return
        }
        // Пустой диск грузится в ничто и выглядит как повисание. Восстановление
        // заводится наоборот — оно приносит собственный рамдиск, — так что
        // отказ здесь касается только простого запуска.
        if (config.restoreRamdiskPath == null && !VMConfig.systemInstalled) {
            LogCapture.note(L("Запуск отменён: на диске ещё нет системы — сначала «Восстановление»"))
            return
        }
        // Какой ускоритель — сказано раньше всего: под HVF транслятора нет, и
        // отчёт, не называющий свой, — половина отчёта. На Android ускорителя
        // нет вовсе, так что писать нечего.
        val virtualized = config.virtualization
        // Запуск без исполняемой памяти не проваливается — он вешает vCPU на
        // первой же сгенерированной инструкции, что куда труднее прочесть, чем
        // отказ. HVF кода не генерирует, так что память ему не нужна.
        if (!virtualized && !_jit.value.isAvailable) {
            LogCapture.note(L("Запуск отменён: JIT недоступен."))
            return
        }

        // Встроенный вывод регистрируется в машине в единственный безопасный
        // момент: после `qemu_init`, до главного цикла. На Android эта щель
        // внутри C-моста, и наружу выходит один флаг.
        if (config.builtInDisplay && !config.headless) {
            QemuBridge.shared.attachDisplay = true
        }

        QemuBridge.shared.onStateChange = { state -> onQemuState(state) }

        _hasRun.value = true
        // Эмулятор дописывает в свой консольный журнал, а не начинает заново
        // (см. VMConfig), так что прошлый прогон стирается здесь.
        try {
            VMConfig.guestConsoleLog.outputStream().use { }
        } catch (_: Throwable) {
        }
        QemuBridge.shared.environment = Settings.emulatorEnvironment
        QemuBridge.shared.start(config.arguments())
    }

    private fun onQemuState(state: QemuBridge.State) {
        if (state != QemuBridge.State.Running) HostHaptics.shared.stop()
        if (state == QemuBridge.State.Running) {
            // Дать `qemu_init` время открыть свои сокеты.
            after(1.5) {
                if (!config.headless) {
                    val made = makeDisplay()
                    if (made != null) {
                        display = made
                        made.connect()
                    } else {
                        val why = L("в этой сборке библиотеки нет встроенного вывода")
                        LogCapture.note(L("Экран: %@. Переключитесь на VNC в параметрах.", why))
                        _displayStatus.value = GuestDisplayStatus.Failed(why)
                    }
                }
                serial.follow()
                serial.attachInput(config.serialPort)
                // Не на рамдиске восстановления: см. `isRestoring`.
                if (!isRestoring) {
                    // Шелл открывается, не дожидаясь, пока на его панель кто-то
                    // посмотрит. Он сам ждёт bash'а бутстрапа, и иметь его с
                    // самого начала — то, что держит работающим остальное:
                    // консольному каналу линк не нужен, а гость свою сторону
                    // USB-сети гасит, как только загрузился, и шелл, попрошенный
                    // позже, ждал бы уже на погасшем.
                    shell.connect()
                }
            }
            // Библиотека уже загружена, так что батарею можно передать прежде,
            // чем батарейный драйвер гостя спросит её в первый раз.
            HostBattery.shared.start()
            // Ничего не приходит, пока гость не завибрирует, и ничего вовсе —
            // от гостя, запущенного без звука.
            HostHaptics.shared.start()
            // Строка состояния: когда SpringBoard поднялся, и снова при каждой
            // перемене связи телефона, пока гость следует за ней.
            PhoneNetwork.shared.onChange = {
                if (Settings.statusBarMode == GuestStatusBar.Mode.PHONE.raw) paintStatusBar()
            }
            PhoneNetwork.shared.start()
            // Агент поднимается в фоне: поставить, если гость свежий, найти,
            // если уже там. Он перенимает строку состояния и служебные команды
            // у консоли. С задержкой, чтобы свежая загрузка дошла до шелла.
            after(20.0) { bringUpAgent() }
            paintStatusBarWhenReady(30.0)
            if (config.network) watchNetwork()
            if (Settings.autoRepairPackages) preparePackages()
            serial.onGuestDeath = { guestDied() }
            // Доложить, что делает машина, когда у неё было время загрузиться.
            after(15.0) { inspectMachine() }
            // И где на самом деле занят поток — решающая подробность.
            after(25.0) { Sampler.report { LogCapture.note(it) } }
            after(60.0) { Sampler.report { LogCapture.note(it) } }
        }
    }

    /** Останавливает машину единственным безопасным способом.
     *
     * Смахивание приложения прочь убивает процесс посреди записи; QMP `quit`
     * даёт QEMU развернуть главный цикл и сначала сбросить диски.
     */
    fun shutdown() {
        if (!isRunning) return
        LogCapture.note(L("Выключение: отправляю QMP quit…"))
        qmp.quit { report -> LogCapture.note(report) }
        display?.disconnect()
        shell.disconnect()
        serial.stop()
    }

    /** QMP stop/cont. */
    fun freeze(on: Boolean) {
        if (!isRunning) return
        LogCapture.note(if (on) L("Заморозка: отправляю QMP stop…") else L("Продолжение: отправляю QMP cont…"))
        qmp.pause(on) { report -> LogCapture.note(report) }
    }

    // MARK: Сеть

    /** Спрашивает у эмулятора, настроил ли гость когда-нибудь свою сторону связи. */
    val linkIsUp: Boolean
        get() = QemuNative.netLinkUp()

    /** Просит гостя настроить USB-интерфейс самому.
     *
     * Эмулятор умеет переподключать устройство, сбрасывать шину и
     * переопросить её, и на образе, пожившем немного, ничто из этого не
     * помогает: iOS докладывает, что связь есть, начинает DHCP-запрос, а
     * затем объявляет `NETWORK_CONNECTION 0` и выключает контроллер. Что
     * помогает — сказать об этом изнутри, чему стоковый гид с самого начала
     * учил людей делать руками.
     */
    fun fixNetwork() {
        if (isRestoring) return
        // Через агента, когда он есть: он достаёт гостя в обход консоли, так
        // что это работает, даже пока консоль занята — а занята она чаще
        // всего как раз тогда, когда сеть просит пинка.
        val agent = guestAgent
        if (agent != null) {
            background {
                val job = agent.run("/usr/sbin/ipconfig set en0 DHCP", timeout = 90.0)
                onMain {
                    if (job != null) {
                        LogCapture.note(L("Сеть (агент): попросил гостя поднять en0."))
                    } else if (!agent.isAlive()) {
                        guestAgent = null
                        LogCapture.note(L("Агент пропал — сеть подниму через консоль."))
                        fixNetwork()
                    }
                }
            }
            return
        }
        // Никогда посреди чужого разговора с консолью: команда, приземлившаяся
        // между двумя строками переноса файла, ломает перенос, и пинок может
        // подождать следующего круга.
        val sent = serial.ifFree {
            LogCapture.note(L("Сеть: прошу гостя поднять en0…"))
            serial.send("/usr/sbin/ipconfig set en0 DHCP\n")
        }
        if (!sent) LogCapture.note(L("Сеть: консоль занята, попрошу позже."))
    }

    // MARK: Пакеты

    /** Скачивает пакет и ставит его, показывая оба в той же полосе, что и всякий
     * другой перенос, — так менеджер можно закрыть сразу после запуска и
     * смотреть на экран гостя.
     */
    fun installFromRepo(name: String, id: String, version: String, url: String, size: Long) {
        if (_transfer.value?.isRunning == true) return
        transferBlocker()?.let { _transfer.value = TransferState.Failed(it); return }
        _transfer.value = TransferState.Running(L("↓ %@", name), 0, size)
        LogCapture.note(L("Пакеты: качаю %@", name))
        background {
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Telesphoreo APT-HTTP/1.0.592")
                    .header("X-Machine", "iPhone12,1")
                    .header("X-Firmware", "14.0")
                    .build()
                http.newCall(request).execute().use { response ->
                    if (response.code != 200) {
                        throw GuestFiles.Failure(
                            L("репозиторий ответил %d", response.code),
                            GuestFiles.FailureKind.IO,
                        )
                    }
                    val body = response.body ?: throw GuestFiles.Failure(
                        L("репозиторий ответил без тела"),
                        GuestFiles.FailureKind.IO,
                    )
                    val total = maxOf(body.contentLength(), size)
                    val file = File(EngineEnv.requireContext().cacheDir, "${id}_${version}.deb")
                    file.delete()
                    var last = 0.0
                    var read = 0L
                    body.byteStream().use { input ->
                        file.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val piece = input.read(buffer)
                                if (piece < 0) break
                                output.write(buffer, 0, piece)
                                read += piece
                                val moment = now()
                                if (moment - last > 0.1) {
                                    last = moment
                                    onMain {
                                        _transfer.value =
                                            TransferState.Running(L("↓ %@", name), read, total)
                                    }
                                }
                            }
                        }
                    }
                    _transfer.value = null
                    installDEB(file)
                }
            } catch (error: Throwable) {
                onMain { _transfer.value = TransferState.Failed(text(error)) }
                LogCapture.note(L("Пакеты: %@ — %@", name, text(error)))
            }
        }
    }

    /** Берёт приложение из каталога: скачанное здесь, где сеть настоящая, и
     * занесённое в гостя тем же установщиком, каким идёт кнопка `.ipa`, — так
     * что достаются тот же канал, та же проверка и та же полоса.
     */
    fun installCatalogApp(name: String, url: String, size: Long) {
        if (_transfer.value?.isRunning == true) return
        transferBlocker()?.let { _transfer.value = TransferState.Failed(it); return }
        _transfer.value = TransferState.Running(L("↓ %@", name), 0, size)
        LogCapture.note(L("Каталог: качаю %@", name))
        background {
            try {
                val request = Request.Builder().url(url).build()
                http.newCall(request).execute().use { response ->
                    if (response.code != 200) {
                        throw GuestFiles.Failure(
                            L("источник ответил %d", response.code),
                            GuestFiles.FailureKind.IO,
                        )
                    }
                    val body = response.body ?: throw GuestFiles.Failure(
                        L("источник ответил без тела"),
                        GuestFiles.FailureKind.IO,
                    )
                    val total = maxOf(body.contentLength(), size)
                    val file = File(
                        EngineEnv.requireContext().cacheDir,
                        GuestFiles.safeName(name) + ".ipa",
                    )
                    file.delete()
                    var last = 0.0
                    var read = 0L
                    body.byteStream().use { input ->
                        file.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val piece = input.read(buffer)
                                if (piece < 0) break
                                output.write(buffer, 0, piece)
                                read += piece
                                val moment = now()
                                if (moment - last > 0.1) {
                                    last = moment
                                    onMain {
                                        _transfer.value =
                                            TransferState.Running(L("↓ %@", name), read, total)
                                    }
                                }
                            }
                        }
                    }
                    _transfer.value = null
                    installIPA(file)
                }
            } catch (error: Throwable) {
                onMain { _transfer.value = TransferState.Failed(text(error)) }
                LogCapture.note(L("Каталог: %@ — %@", name, text(error)))
            }
        }
    }

    /** Медленный OkHttp-клиент для загрузок: Swift задавал таймаут на каждый
     * запрос (60 с на пакеты, 120 с на каталог), здесь — один общий.
     */
    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    /** Что у гостя установлено, как имя пакета к версии. Блокирует; звать с
     * фонового потока.
     */
    fun installedPackages(): Map<String, String> =
        try {
            GuestPackages.installed(serial, files)
        } catch (_: Throwable) {
            emptyMap()
        }

    /** Убирает пакет, который есть у гостя. */
    fun removePackage(packageName: String) {
        if (!isRunning || _transfer.value?.isRunning == true) return
        _transfer.value = TransferState.Running(L("Удаляю %@…", packageName), 0, 0)
        background {
            try {
                val said = GuestPackages.remove(packageName, serial)
                onMain {
                    _transfer.value = TransferState.Finished(
                        L("Пакет удалён.") + (if (said.isEmpty()) "" else "\n$said"),
                    )
                    LogCapture.note(L("Пакеты: %@ удалён.", packageName))
                }
            } catch (error: Throwable) {
                onMain {
                    _transfer.value = TransferState.Failed(text(error))
                    LogCapture.note(L("Пакеты: %@ — %@", packageName, text(error)))
                }
            }
        }
    }

    /** Перезапускает SpringBoard гостя — так свежепоставленный твик и
     * загружается.
     */
    fun respring() {
        if (!isRunning) return
        LogCapture.note(L("Перезапускаю SpringBoard…"))
        background {
            try {
                GuestPackages.respring(serial)
            } catch (error: Throwable) {
                LogCapture.note(L("SpringBoard: %@", text(error)))
            }
        }
        // Новый SpringBoard ничего не знает о подмене строки состояния.
        paintedStatusBar = null
        paintStatusBarWhenReady(20.0)
    }

    // MARK: Агент

    /** Сколько раз временный отказ поднять агента уже пережит, чтобы гостя,
     * который просто медленно доходит до шелла, выждать, а постоянный отказ
     * не долбить.
     */
    private var agentAttempts = 0

    /** Находит или ставит агента гостя, в стороне от главного потока, и когда
     * он поднят, передаёт ему строку состояния, чтобы консоль вышла из того
     * круга. Звать снова безопасно — пока подъём в ходу или уже кончился,
     * ничего не делает.
     */
    fun bringUpAgent(force: Boolean = false) {
        if (isRestoring) return
        if (!isRunning) return
        if (force) {
            agentBringUpStarted = false
            guestAgent = null
            agentAttempts = 0
        }
        if (agentBringUpStarted || guestAgent != null) return
        agentBringUpStarted = true
        background {
            val outcome = GuestAgentSetup.bringUp(serial) { line ->
                LogCapture.note(L("Агент: %@", line))
            }
            onMain {
                when (outcome) {
                    is GuestAgentSetup.Outcome.Installed -> {
                        guestAgent = outcome.agent
                        agentAttempts = 0
                        LogCapture.note(L("Агент готов: команды и строка состояния идут мимо консоли."))
                        // Отдать строку состояния сразу же и оставить её ему.
                        paintStatusBar(force = true)
                        syncTimeZone()
                    }
                    is GuestAgentSetup.Outcome.Unavailable -> {
                        // Не ошибка: консольный путь остаётся. Гость, ещё не
                        // дошедший до шелла, стоит новой попытки; сборка или
                        // эмулятор, где агента быть не может, — нет.
                        agentBringUpStarted = false
                        if (outcome.retry && agentAttempts < 8 && isRunning) {
                            agentAttempts += 1
                            after(30.0) { bringUpAgent() }
                        } else {
                            LogCapture.note(L("Агент не поднят (%@) — работаю через консоль.", outcome.why))
                        }
                    }
                }
            }
        }
    }

    // MARK: Строка состояния гостя

    /** Аргументы помощника, нарисованные в последний раз, — чтобы не рисовать
     * дважды одно и то же.
     */
    @Volatile
    private var paintedStatusBar: String? = null

    @Volatile
    private var paintQueued = false

    /** Рисует то, чего просит настройка строки состояния.
     *
     * Собирается на секунду: текстовое поле или счётчик меняются много раз
     * подряд, и каждая перемена была бы отдельным разговором с консолью.
     */
    fun paintStatusBar(force: Boolean = false) {
        if (isRestoring) return
        if (!isRunning) return
        if (force) paintedStatusBar = null
        if (paintQueued) return
        paintQueued = true
        after(1.0) {
            paintQueued = false
            val look = GuestStatusBar.requested()
            val arguments = look?.arguments ?: "-z"
            // Ничего не нарисовано и ничего не хочется: помощника можно даже не
            // заносить.
            if (arguments == paintedStatusBar && !(look != null || paintedStatusBar != null)) return@after

            // Путь агента: вручить ему вид, и он сам держит его применённым —
            // через перезапуски SpringBoard и занятую консоль. Шлётся один раз,
            // а не по таймеру.
            val agent = guestAgent
            if (agent != null) {
                background {
                    val ok = agent.setStatusBar(look?.json)
                    onMain {
                        if (ok) {
                            paintedStatusBar = if (look == null) null else arguments
                            LogCapture.note(L("Строка состояния гостя (агент): %@", arguments))
                        } else if (agent.isAlive()) {
                            LogCapture.note(L("Строка состояния гостя: агент не принял."))
                        } else {
                            // Агент ушёл; возвращаемся на консоль.
                            guestAgent = null
                            LogCapture.note(L("Агент пропал — возвращаюсь на консоль для строки состояния."))
                            paintStatusBar(force = true)
                        }
                    }
                }
                return@after
            }

            // Сказано вслух: перемена, которая молча ничего не сделала, — то,
            // из-за чего это выглядело сломанным на телефоне.
            if (!serial.interactive.value) {
                LogCapture.note(L("Строка состояния гостя: консоль гостя не готова, попробую позже."))
                return@after
            }
            background {
                val said: String? = try {
                    serial.exclusive { GuestStatusBar.apply(look, GuestShell(serial)) }
                    null
                } catch (error: Throwable) {
                    text(error)
                }
                onMain {
                    if (said == null) {
                        paintedStatusBar = if (look == null) null else arguments
                        LogCapture.note(L("Строка состояния гостя: %@", arguments))
                    } else {
                        LogCapture.note(L("Строка состояния гостя: не вышло — %@", said))
                    }
                }
            }
        }
    }

    /** Какой круг перерисовки сейчас идёт; более старый останавливается, увидев,
     * что начался более новый.
     */
    @Volatile
    private var statusBarRounds = 0

    /** Держит строку состояния нарисованной, пока гость поднимается, и потом.
     *
     * SpringBoard заводит свой сервер строки состояния далеко в загрузке, и
     * подмена, посланная раньше, теряется. Спрашивать гостя, стоит ли там
     * SpringBoard, пробовали — это стоило консоли: `ps -A` вис на занятом
     * госте и утащил bash за собой, а панель шелла, пакеты и это всё печатали
     * в консоль, которую никто не читал. Подмена безопасна при повторной
     * отправке, так что её просто шлют снова — каждые полминуты, пока гость
     * грузится, потом раз в минуту, что возвращает её и вскоре после
     * самовольного перезапуска SpringBoard. Каждый раз только когда консоль
     * свободна: никто ради этого не ждёт.
     */
    private fun paintStatusBarWhenReady(delay: Double) {
        if (isRestoring) return
        statusBarRounds += 1
        val round = statusBarRounds
        val pauses = doubleArrayOf(delay, 30.0, 30.0, 30.0, 30.0)

        fun tick(step: Int) {
            if (!isRunning || round != statusBarRounds) return
            repaintStatusBarQuietly()
            // Часовой пояс едет теми же кругами. Когда гость уже получил пояс
            // телефона, это ничего не делает, а пояс, на который телефон
            // сменился, доходит до гостя за минуту.
            syncTimeZone()
            val next = if (step + 1 < pauses.size) pauses[step + 1] else 60.0
            after(next) { tick(step + 1) }
        }

        after(delay) { tick(0) }
    }

    /** Одна перерисовка, которая уступает всем остальным на консоли и молчит,
     * пока не провалится.
     */
    private fun repaintStatusBarQuietly() {
        if (isRestoring) return
        // Когда агент поднят, делать тут нечего: он reapplies подмену сам, в
        // том числе после respring, без консоли.
        if (guestAgent != null) return
        if (!serial.interactive.value) return
        val look = GuestStatusBar.requested() ?: return
        background {
            var said: String? = null
            val ran = serial.ifFree {
                said = try {
                    GuestStatusBar.apply(look, GuestShell(serial))
                    ""
                } catch (error: Throwable) {
                    text(error)
                }
            }
            if (!ran) return@background
            onMain {
                if (said == "") {
                    if (paintedStatusBar != look.arguments) {
                        LogCapture.note(L("Строка состояния гостя: %@", look.arguments))
                    }
                    paintedStatusBar = look.arguments
                } else {
                    LogCapture.note(L("Строка состояния гостя: не вышло — %@", said ?: ""))
                }
            }
        }
    }

    // MARK: Часовой пояс гостя

    /** Пояс, который гость получил последним, — чтобы не слать тот же снова. */
    @Volatile
    private var guestTimeZone: String? = null

    @Volatile
    private var timeZoneInFlight = false

    /** Провал, уже записанный в журнал, — чтобы попытка каждую минуту не
     * писала его снова.
     */
    @Volatile
    private var timeZoneComplaint: String? = null

    /** Даёт гостю часовой пояс телефона, когда настройка того просит. Как —
     * см. `GuestTimeZone`. Через агента, когда он есть, иначе по консоли,
     * только когда её никто не занял; молча, пока пояс не сменился или гость
     * не сказал неожиданного.
     */
    fun syncTimeZone(force: Boolean = false) {
        if (isRestoring) return
        if (!isRunning || !Settings.guestTimeZone) return
        val zone = GuestTimeZone.phone ?: return
        if (force) guestTimeZone = null
        if (zone == guestTimeZone || timeZoneInFlight) return
        val agent = guestAgent
        if (agent == null && !serial.interactive.value) return
        timeZoneInFlight = true
        val command = GuestTimeZone.command(zone)
        background {
            val answer: String? = if (agent != null) {
                agent.run(command, timeout = 30.0)?.output
            } else {
                var result: String? = null
                serial.ifFree { result = GuestShell(serial).text(command, timeout = 30.0) }
                result
            }
            onMain {
                timeZoneInFlight = false
                // Ответа нет: консоль была занята или гость медлен. Следующий
                // круг спросит снова.
                if (answer == null) return@onMain
                when (GuestTimeZone.outcome(answer, zone)) {
                    GuestTimeZone.Outcome.SET -> {
                        LogCapture.note(L("Часовой пояс гостя: %@", zone))
                        guestTimeZone = zone
                    }
                    GuestTimeZone.Outcome.MISSING -> {
                        // Спрашивать снова — тот же ответ.
                        LogCapture.note(L("Часовой пояс гостя: в образе нет пояса %@.", zone))
                        guestTimeZone = zone
                    }
                    GuestTimeZone.Outcome.UNEXPECTED -> {
                        val said = answer.take(120)
                        if (said != timeZoneComplaint) {
                            timeZoneComplaint = said
                            LogCapture.note(L("Часовой пояс гостя: не вышло — %@", said))
                        }
                    }
                }
            }
        }
    }

    /** Ставит `.deb` без Cydia: гость слишком медлителен, чтобы Cydia пережила
     * собственную упаковку, а у этого пути нет никого, кто смотрел бы на часы.
     */
    fun installDEB(file: File) {
        if (_transfer.value?.isRunning == true) return
        transferBlocker()?.let { _transfer.value = TransferState.Failed(it); return }
        val name = file.name
        _transfer.value = TransferState.Running(L("→ пакет %@", name), 0, 0)
        LogCapture.note(L("Пакеты: ставлю %@", name))
        background {
            var phase = L("→ пакет %@", name)
            try {
                var last = 0.0
                val said = GuestPackages.installDeb(
                    file,
                    serial,
                    files,
                    progress = { done, total ->
                        val moment = now()
                        if (moment - last > 0.1 || done == total) {
                            last = moment
                            onMain { _transfer.value = TransferState.Running(phase, done, total) }
                        }
                    },
                    note = { line ->
                        phase = line
                        onMain { _transfer.value = TransferState.Running(line, 0, 0) }
                    },
                )
                onMain {
                    _transfer.value = TransferState.Finished(
                        L("Пакет установлен.") + (if (said.isEmpty()) "" else "\n$said"),
                    )
                    LogCapture.note(L("Пакеты: %@ установлен.", name))
                }
            } catch (error: Throwable) {
                onMain {
                    _transfer.value = TransferState.Failed(text(error))
                    LogCapture.note(L("Пакеты: %@ — %@", name, text(error)))
                }
            }
        }
    }

    /** Переделывает часть починки пакетов, которую отменяет перезагрузка гостя.
     *
     * Перемонтирование и корневой помощник не переживают перезапуск, а без
     * них Cydia падает с `cydo returned an error code (2)` — ошибкой, которая
     * ничего не говорит о причине. Здесь идёт только быстрая половина;
     * медленная нужна раз на образ и остаётся на кнопке.
     */
    private fun preparePackages(delay: Double = 45.0) {
        if (isRestoring) return
        var attempts = 0

        fun attempt() {
            if (!isRunning) return
            if (!serial.interactive.value) {
                attempts += 1
                if (attempts >= 150) return
                after(4.0) { attempt() }
                return
            }
            background {
                try {
                    val complaints = GuestPackages.prepare(serial)
                    val tail = if (complaints.isEmpty()) "" else " " + complaints.joinToString(" ")
                    LogCapture.note(L("Пакеты: гость подготовлен.") + tail)
                } catch (error: Throwable) {
                    LogCapture.note(L("Пакеты: подготовить не вышло — %@", text(error)))
                }
            }
        }

        // Не на десятой секунде: гость ещё грузится, и он возвращает корень на
        // только-чтение на своём пути вверх — перемонтирование ответило 0 и не
        // значило ничего.
        after(delay) { attempt() }
    }

    /** Гость запаниковал, и сторожевой таймер перезапускает его.
     *
     * Всё, что нужно менеджеру пакетов — пишущий корень, папки, корневой
     * помощник, — отменено этим, и никакого другого знака гость не даёт:
     * приложение работает дальше, консоль печатает дальше, а Cydia снова
     * тихо сломана. Так что та же подготовка, что шла при старте, идёт
     * снова, когда у машины будет время подняться.
     */
    private fun guestDied() {
        LogCapture.note(L("Гость упал: %@", serial.guestDeathReason))
        // Подмена строки состояния жила в SpringBoard, который только что ушёл.
        paintedStatusBar = null
        // Экземпляр агента умер вместе с гостем. С пути обратно он заводит себя
        // сам из кэша launchd, так что клиент отбрасывается и находится заново,
        // когда у машины будет время загрузиться; строку состояния тем временем
        // держит консоль.
        guestAgent = null
        agentBringUpStarted = false
        after(120.0) { bringUpAgent() }
        paintStatusBarWhenReady(150.0)
        if (!Settings.autoRepairPackages || !isRunning) return
        LogCapture.note(L("Гость упал в панику — поднимаю менеджер пакетов заново, когда вернётся."))
        preparePackages(150.0)
    }

    /** Собирает менеджер пакетов гостя заново — починка для
     * `cydo returned an error code (2)` у Cydia.
     *
     * Предложена кнопкой, а не делается при каждой загрузке, потому что
     * пишет в собственный системный том гостя; та часть, что не переживает
     * перезагрузку, — перемонтирование, — так что жать её снова после одной
     * нормально.
     */
    fun repairPackages() {
        if (!isRunning || _transfer.value?.isRunning == true) return
        _transfer.value = TransferState.Running(L("Чиню менеджер пакетов…"), 0, 0)
        LogCapture.note(L("Пакеты: чиню dpkg в госте…"))
        background {
            try {
                val complaints = GuestPackages.repair(serial, note = { line ->
                    onMain { _transfer.value = TransferState.Running(line, 0, 0) }
                })
                val tail = if (complaints.isEmpty()) "" else "\n" + complaints.joinToString("\n")
                onMain {
                    _transfer.value = TransferState.Finished(
                        L("Менеджер пакетов починен. Попробуйте Cydia снова.") + tail,
                    )
                    LogCapture.note(L("Пакеты: готово.") + tail)
                }
            } catch (error: Throwable) {
                onMain {
                    _transfer.value = TransferState.Failed(text(error))
                    LogCapture.note(L("Пакеты: не вышло — %@", text(error)))
                }
            }
        }
    }

    /** Следит за связью и, если она так и не поднялась, пользуется собственным
     * шеллом гостя.
     *
     * Разбросано нарочно: на телефоне гостю может быть нужно четыре минуты от
     * включения до шелла, и команда, посланная раньше этого шелла, не
     * приземляется нигде. Пять попыток покрывают медленный случай; если после
     * них связи всё ещё нет, сама она уже не поднимется.
     */
    private fun watchNetwork() {
        val tries = 5
        var attempts = 0

        fun check() {
            if (!isRunning) return
            if (linkIsUp) {
                if (!_networkUp.value) {
                    _networkUp.value = true
                    LogCapture.note(L("Сеть: гость получил адрес."))
                }
                // Следить дальше. iOS берёт адрес, пользуется им, а затем
                // объявляет связь погашенной и перестаёт принимать, — и
                // наблюдатель, остановившийся на хорошей новости, этого
                // никогда не видел.
                after(20.0) { check() }
                return
            }
            if (_networkUp.value) {
                _networkUp.value = false
                attempts = 0
                LogCapture.note(L("Сеть: гость погасил связь."))
            }
            if (Settings.netAutoFix && attempts < tries) {
                attempts += 1
                LogCapture.note(L("Сеть: адреса всё ещё нет, попытка %d из %d.", attempts, tries))
                fixNetwork()
                after(45.0) { check() }
                return
            }
            // Смотреть дальше, молча: гость может ещё сам собраться.
            after(60.0) { check() }
        }

        // Достаточно долго, чтобы неторопливая загрузка успела сама.
        after(90.0) { check() }
    }

    // MARK: Файлы

    private val files: GuestFiles by lazy {
        GuestFiles(
            serial = serial,
            linkUp = { linkIsUp },
            bringNetworkUp = { onMain { fixNetwork() } },
            agent = { guestAgent },
        )
    }

    /** Проверяет то, что проверяется на месте, — так перенос, которому не быть,
     * говорит почему сразу, а не после минуты ожидания.
     */
    private fun transferBlocker(): String? = when {
        !config.network -> GuestFiles.networkOffMessage()
        !serial.interactive.value -> GuestFiles.noShellMessage()
        else -> null
    }

    fun sendToGuest(file: File) {
        if (_transfer.value?.isRunning == true) return
        transferBlocker()?.let { _transfer.value = TransferState.Failed(it); return }
        val name = file.name
        _transfer.value = TransferState.Running("→ $name", 0, 0)
        LogCapture.note(L("Файлы: отправляю %@ в гостя", name))
        val fs = files
        background {
            val started = now()
            try {
                var last = 0.0
                val remote = fs.send(file) { done, total ->
                    val moment = now()
                    if (moment - last > 0.1 || done == total) {
                        last = moment
                        onMain { _transfer.value = TransferState.Running("→ $name", done, total) }
                    }
                }
                val summary = TransferState.summary(file, now() - started)
                onMain {
                    _transfer.value = TransferState.Finished("$name → $remote\n$summary")
                    LogCapture.note(L("Файлы: %@ → %@, %@", name, remote, summary))
                }
            } catch (error: Throwable) {
                onMain {
                    _transfer.value = TransferState.Failed(text(error))
                    LogCapture.note(L("Файлы: %@ не отправлен — %@", name, text(error)))
                }
            }
        }
    }

    /** Одна кнопка: распаковать `.ipa` здесь, занести его тем каналом, какой
     * есть, и положить в `/Applications`. Первый прогон оставляет в госте и
     * помощника, так что каждая следующая установка находит его уже там.
     */
    fun installIPA(file: File) {
        if (_transfer.value?.isRunning == true) return
        transferBlocker()?.let { _transfer.value = TransferState.Failed(it); return }
        if (!file.extension.lowercase().contentEquals("ipa")) {
            _transfer.value = TransferState.Failed(L("Нужен файл .ipa."))
            return
        }
        val name = file.name
        _transfer.value = TransferState.Running(L("→ установка %@", name), 0, 0)
        LogCapture.note(L("Установка: %@", name))
        val installer = GuestInstaller(serial, files)
        background {
            val started = now()
            // Какой шаг идёт, чтобы полоса продолжала говорить это, пока байты
            // едут. Оба замыкания зовутся с этого потока, один за другим, так
            // что простой переменной достаточно.
            var phase = L("→ установка %@", name)
            try {
                var last = 0.0
                val target = installer.install(
                    file,
                    progress = { done, total ->
                        val moment = now()
                        if (moment - last > 0.1 || done == total) {
                            last = moment
                            onMain { _transfer.value = TransferState.Running(phase, done, total) }
                        }
                    },
                    note = { line ->
                        phase = line
                        onMain { _transfer.value = TransferState.Running(line, 0, 0) }
                    },
                )
                val summary = TransferState.summary(file, now() - started)
                // Предупреждение — не провал: приложение стоит в любом случае,
                // и сказать, почему оно не заведётся, лучше, чем дать ему
                // выглядеть сломанным.
                val caveat = installer.warning?.let { "\n$it" } ?: ""
                onMain {
                    _transfer.value =
                        TransferState.Finished(L("Установлено: %@", target) + "\n" + summary + caveat)
                    LogCapture.note(L("Установка: %@ → %@, %@", name, target, summary) + caveat)
                }
            } catch (error: Throwable) {
                onMain {
                    _transfer.value = TransferState.Failed(text(error))
                    LogCapture.note(L("Установка: %@ — %@", name, text(error)))
                }
            }
        }
    }

    fun receiveFromGuest(path: String) {
        val remote = path.trim().trim('"')
        if (remote.isEmpty() || _transfer.value?.isRunning == true) return
        transferBlocker()?.let { _transfer.value = TransferState.Failed(it); return }
        val name = remote.substringAfterLast('/')
        _transfer.value = TransferState.Running("← $name", 0, 0)
        LogCapture.note(L("Файлы: забираю %@ из гостя", remote))
        val fs = files
        background {
            val started = now()
            try {
                var last = 0.0
                val saved = fs.receive(remote) { done, total ->
                    val moment = now()
                    if (moment - last > 0.1 || done == total) {
                        last = moment
                        onMain { _transfer.value = TransferState.Running("← $name", done, total) }
                    }
                }
                val summary = TransferState.summary(saved, now() - started)
                onMain {
                    _transfer.value = TransferState.Finished("$remote → Guest/${saved.name}\n$summary")
                    LogCapture.note(L("Файлы: %@ → %@, %@", remote, saved.path, summary))
                }
            } catch (error: Throwable) {
                onMain {
                    _transfer.value = TransferState.Failed(text(error))
                    LogCapture.note(L("Файлы: %@ не получен — %@", remote, text(error)))
                }
            }
        }
    }

    override fun inspectMachine() {
        Threads.report { report -> LogCapture.note(report) }
        qmp.inspect { report -> LogCapture.note(report) }
    }

    // MARK: RestoreHost — то, через что рестор управляет машиной

    override val machineRunning: Boolean
        get() = isRunning

    override fun currentConfig(): VMConfig = config

    /** Рестор поднимает машину по-своему: с рамдиском и без сети, что уже
     * записано в переданном конфиге.
     */
    override fun startMachine(config: VMConfig) {
        configOverride = config
        start()
    }

    override fun shutdownMachine() {
        shutdown()
    }

    // MARK: Ввод

    /** Снимает леттербокс вписанной картинки, так что палец на экране ложится
     * на тот пиксель, что под ним, каково бы ни было разрешение гостя.
     *
     * Берёт прямоугольник, который картинка занимает на самом деле, а не весь
     * вид: она стоит в центре с полями вокруг, и это уже не одна и та же
     * коробка.
     */
    private fun guestPoint(x: Float, y: Float, box: RectF): Pair<Float, Float>? {
        val framebuffer = framebufferSize ?: return null
        if (box.width() <= 0 || box.height() <= 0) return null
        val scale = framebuffer.first.toFloat() / box.width()
        return (x - box.left) * scale to (y - box.top) * scale
    }

    fun tap(x: Float, y: Float, box: RectF) {
        val framebuffer = framebufferSize ?: return
        val point = guestPoint(x, y, box) ?: return
        if (point.first < 0 || point.second < 0) return
        if (point.first.toInt() >= framebuffer.first || point.second.toInt() >= framebuffer.second) return
        display?.send(point.first, point.second, true)
    }

    fun release(x: Float, y: Float, box: RectF) {
        val point = guestPoint(x, y, box) ?: return
        display?.send(point.first, point.second, false)
    }

    /** Кнопки устройства ведутся машиной на F1..F10.
     *
     * `hold` — как долго гость должен видеть кнопку нажатой: боковая открывает
     * выключатель только после долгого удержания, и десятая секунды в нём не
     * делает ничего.
     */
    enum class HardwareButton(val title: String, val functionKey: Int, val hold: Double) {
        POWER(L("Питание"), 5, 2.5),
        VOLUME_UP(L("Громче"), 4, 0.2),
        VOLUME_DOWN(L("Тише"), 3, 0.2),
        HOME("Home", 6, 0.2),
    }

    /** Держит кнопку устройства нажатой, сколько ей нужно. */
    fun press(button: HardwareButton) {
        val target = display
        if (target == null) {
            LogCapture.note(L("Кнопки: экран не подключён, нажатие некуда отправить."))
            return
        }
        LogCapture.note(L("Кнопка %@ (F%d) на %.1f с", L(button.title), button.functionKey, button.hold))
        target.send(button.functionKey, true)
        after(button.hold) { target.send(button.functionKey, false) }
    }
}
