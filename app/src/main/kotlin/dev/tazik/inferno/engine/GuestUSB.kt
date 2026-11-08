package dev.tazik.inferno.engine

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import kotlin.concurrent.thread

/**
 * USB-хост для собственного порта гостя и usbmux поверх него.
 *
 * Эмулируемое устройство отдаёт свой USB-порт через сокет: оно само
 * подключается и ждёт, что слушающий будет играть роль хоста. Штатная схема
 * отвечает на это Linux-ВМ с usbmuxd — единственное, для чего та ВМ нужна.
 * Здесь этот хост живёт в приложении: перечисляет устройство, находит
 * интерфейс usbmux, ведёт протокол mux и урезанный TCP внутри него и выдаёт
 * каналы к портам, которые слушают службы гостя — `restored` во время
 * восстановления, lockdownd на установленной системе.
 *
 * Это та же роль, что `netlab/muxd.py` играет на стенде, поэтому форматы
 * совпадают: `hw/usb/inferno-proto.h` для сокета и `device.c` самого usbmuxd
 * для заголовка mux (шестнадцать байт начиная со второй версии) и заголовка
 * TCP, окно которого сдвинуто на восемь бит вниз.
 *
 * Всё здесь блокирующее; у канала свой поток.
 */
class GuestUSB(private val socketName: String = VMConfig.usbSocketName) {

    /** Что слушают службы гостя. */
    enum class Port(val value: Int) {
        /** `restored`, который ведёт восстановление и отвечает на простой QueryType. */
        RESTORED(0xF27E),

        /** Куда идёт образ файловой системы, когда restored его просит. */
        ASR(12345),
    }

    enum class FailureKind { USB, REFUSED, GONE, NOT_READY }

    class Failure(
        message: String,
        val kind: FailureKind,
        val port: Int = 0,
    ) : Exception(message)

    // MARK: - Сокет, в который звонит эмулятор

    private var listener: ServerSocket? = null
    @Volatile
    private var link: Socket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    /** Выставляется, когда устройство ответило и связь mux поднялась. */
    @Volatile
    var deviceReady: Boolean = false
        private set

    /**
     * Серийный номер, как его называет само устройство. Только печатные
     * символы: гость добивает строку пробелами и управляющими байтами.
     */
    @Volatile
    var serial: String = ""
        private set

    @Volatile
    var productID: Int = 0
        private set

    /** Зовётся в потоке канала, когда устройство появляется и пропадает. */
    @Volatile
    var onReady: ((Boolean) -> Unit)? = null

    /**
     * Начинает слушать. Эмулятор звонит на этот сокет один раз, при старте,
     * поэтому слушатель должен быть на месте до машины — и остаётся на всё
     * время работы, потому что второй попытки нет.
     */
    fun listen() {
        stop()
        val server = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), EngineEnv.usbPort))
            }
        } catch (e: IOException) {
            throw Failure("listen(127.0.0.1:${EngineEnv.usbPort}): ${e.message}", FailureKind.USB)
        }
        listener = server

        thread(name = "inferno.usb-host", isDaemon = true) { serve(server) }
    }

    fun stop() {
        deviceReady = false
        try {
            link?.close()
        } catch (_: IOException) {
        }
        link = null
        input = null
        output = null
        try {
            listener?.close()
        } catch (_: IOException) {
        }
        listener = null
    }

    /** Ждёт эмулятор, затем поднимает устройство и качает канал. */
    private fun serve(server: ServerSocket) {
        val accepted = try {
            server.accept()
        } catch (_: IOException) {
            return
        }
        accepted.tcpNoDelay = true
        try {
            accepted.soTimeout = 0
        } catch (_: IOException) {
        }
        link = accepted
        input = try {
            accepted.getInputStream()
        } catch (_: IOException) {
            null
        }
        output = try {
            accepted.getOutputStream()
        } catch (_: IOException) {
            null
        }
        LogCapture.note(L("USB гостя: эмулятор подключился"))

        // USB-стек гостя отвечает много позже, чем звонит эмулятор, а второй
        // раз эмулятор не звонит, поэтому подъём повторяется на этом же канале.
        var lastNote = Double.NEGATIVE_INFINITY
        var attempts = 0
        while (link != null && !deviceReady) {
            attempts += 1
            Thread.sleep(3000)
            try {
                bringUp()
                deviceReady = true
                LogCapture.note(
                    L("USB гостя: устройство %04x, серийный %@", productID, serial),
                )
                onReady?.invoke(true)
            } catch (e: Failure) {
                if (e.kind == FailureKind.GONE) break
                // Молчание здесь стоит недели: причины повторов подъёма
                // (вечный NAK, «нет интерфейса», сбой пакета) выглядят снаружи
                // как зависший рестор. Говорим раз в десять секунд — рамдиск
                // поднимает USB десятки секунд, между повторами всё ещё раз
                // пройдётся.
                if (now() - lastNote > 10) {
                    lastNote = now()
                    LogCapture.note(
                        L("USB гостя: попытка %d не удалась — %@", attempts, e.message ?: L("ошибка")),
                    )
                }
                continue
            } catch (t: Throwable) {
                if (now() - lastNote > 10) {
                    lastNote = now()
                    LogCapture.note(
                        L("USB гостя: попытка %d не удалась — %@", attempts, t.message ?: L("ошибка")),
                    )
                }
                continue
            }
        }

        pump()
        deviceReady = false
        onReady?.invoke(false)
    }

    // MARK: - Перечисление

    private var endpointIn: Int = 0
    private var endpointOut: Int = 0
    private var maxPacket: Int = 512

    private fun bringUp() {
        reset()
        val device = controlIn(request = 6, value = 0x0100, index = 0, length = 18)
        productID = (device[10].toInt() and 0xFF) or ((device[11].toInt() and 0xFF) shl 8)
        serial = stringAt(device[16])
        val configurations = device[17].toInt() and 0xFF

        // Сначала самая новая конфигурация, как делает usbmuxd: у устройства
        // с несколькими интерфейс mux лежит в последней.
        for (index in configurations - 1 downTo 0) {
            val head = controlIn(request = 6, value = 0x0200 or index, index = 0, length = 9)
            val total = (head[2].toInt() and 0xFF) or ((head[3].toInt() and 0xFF) shl 8)
            val raw = controlIn(request = 6, value = 0x0200 or index, index = 0, length = total)
            val found = findMux(raw) ?: continue
            endpointIn = found.first
            endpointOut = found.second
            maxPacket = found.third
            controlOut(request = 9, value = raw[5].toInt() and 0xFF, index = 0)
            handshake()
            return
        }
        throw Failure(L("у гостя нет интерфейса usbmux"), FailureKind.USB)
    }

    /**
     * Интерфейс usbmux по классу 255 / подклассу 254 / протоколу 2 и его два
     * bulk-эндпоинта.
     */
    private fun findMux(raw: ByteArray): Triple<Int, Int, Int>? {
        var at = 0
        var inMux = false
        var epIn = 0
        var epOut = 0
        var packet = 0
        while (at + 2 <= raw.size) {
            val length = raw[at].toInt() and 0xFF
            val kind = raw[at + 1].toInt() and 0xFF
            if (length == 0) break
            if (kind == 4 && at + 8 < raw.size) {
                inMux = (raw[at + 5].toInt() and 0xFF) == 255 &&
                    (raw[at + 6].toInt() and 0xFF) == 254 &&
                    (raw[at + 7].toInt() and 0xFF) == 2
            } else if (kind == 5 && inMux && at + 6 < raw.size &&
                (raw[at + 3].toInt() and 0x03) == 2
            ) {
                val address = raw[at + 2].toInt() and 0xFF
                packet = (raw[at + 4].toInt() and 0xFF) or ((raw[at + 5].toInt() and 0xFF) shl 8)
                if (address and 0x80 != 0) epIn = address and 0x0F else epOut = address and 0x0F
            }
            at += length
        }
        if (epIn == 0 || epOut == 0) return null
        return Triple(epIn, epOut, if (packet == 0) 512 else packet)
    }

    // MARK: - Своя рамка сокета

    private enum class Packet(val value: Int) { REQUEST(1), RESPONSE(2), RESET(3), CANCEL(4) }

    private enum class Token(val value: Int) { SETUP(0x2D), TOKEN_IN(0x69), TOKEN_OUT(0xE1) }

    private var nextTransfer: Long = 1
    private val linkLock = Any()

    /** Громкость отладки передачи: первые пакеты подъёма — целиком,
     * дальше редкая выборка, чтобы следить за каналом, не заливая журнал. */
    private var debugSent = 0
    private var debugRecv = 0

    private fun debugNote(kind: String, detail: String) {
        val n = if (kind == ">") debugSent else debugRecv
        if (n < 6 || n % 50 == 0) {
            val side = if (kind == ">") L("передача") else L("ответ")
            LogCapture.note(L("USB гостя: %@ %d: %@", side, n, detail))
        }
    }

    private fun send(bytes: ByteArray) {
        val out = output ?: throw Failure(L("Гость отключился"), FailureKind.GONE)
        var sent = 0
        while (sent < bytes.size) {
            val n = try {
                out.write(bytes, sent, bytes.size - sent)
                out.flush()
                bytes.size - sent
            } catch (_: IOException) {
                -1
            }
            if (n <= 0) throw Failure(L("Гость отключился"), FailureKind.GONE)
            sent += n
        }
    }

    private fun receive(count: Int): ByteArray {
        val inp = input ?: throw Failure(L("Гость отключился"), FailureKind.GONE)
        val out = ByteArray(count)
        var filled = 0
        while (filled < count) {
            val n = try {
                inp.read(out, filled, count - filled)
            } catch (_: IOException) {
                -1
            }
            if (n <= 0) throw Failure(L("Гость отключился"), FailureKind.GONE)
            filled += n
        }
        return out
    }

    private fun reset() {
        synchronized(linkLock) {
            send(byteArrayOf(Packet.RESET.value.toByte()))
            Thread.sleep(300)
        }
    }

    /**
     * Один USB-пакет, с повтором пока устройство отвечает NAK — это не
     * ошибка, а «ещё не готово», ровно как видит настоящий хост-контроллер.
     */
    private fun transfer(
        token: Token,
        endpoint: Int,
        data: ByteArray = ByteArray(0),
        length: Int? = null,
        /* 200 повторов (~2 с) не переживают волну записи гостя: ASR то и
         * дело NAK'ает дольше двух секунд на давке диска — и лев ронял
         * всю заливку. Настоящий хост-контроллер ждёт вечно; даём ~2 мин
         * (NAK — «ещё не готово», GONE ловится отдельно и мгновенно). */
        retries: Int = 12000,
        delay: Double = 0.01,
    ): ByteArray {
        synchronized(linkLock) {
            val want = length ?: data.size
            for (attempt in 0 until maxOf(1, retries)) {
                val id = nextTransfer
                nextTransfer += 1

                val request = java.io.ByteArrayOutputStream()
                request.write(Packet.REQUEST.value)
                request.write(0) // addr
                request.write(le32(token.value))
                request.write(endpoint)
                request.write(le64(id))
                request.write(le32(0)) // stream
                request.write(0) // short_not_ok
                request.write(0) // int_req
                request.write(le16(want))
                if (token != Token.TOKEN_IN) request.write(data)
                send(request.toByteArray())
                debugSent += 1
                debugNote(">", "pid=%02x ep=%d id=%d want=%d".format(
                    token.value, endpoint, id, want,
                ))

                while (true) {
                    val kind = receive(1)[0].toInt() and 0xFF
                    if (kind != Packet.RESPONSE.value) {
                        throw Failure(L("USB гостя: %@", "packet type $kind"), FailureKind.USB)
                    }
                    val header = receive(20)
                    val replyID = u64(header, 6)
                    val status = u32(header, 14).toInt()
                    val length = u16(header, 18)
                    val pid = u32(header, 1).toInt()
                    var body = ByteArray(0)
                    if (length > 0 && status != -6 && pid == Token.TOKEN_IN.value) {
                        body = receive(length)
                    }
                    debugRecv += 1
                    debugNote("<", "pid=%02x id=%d status=%d len=%d".format(
                        pid, replyID, status, length,
                    ))
                    // ASYNC-ответ — это обещание; настоящий придёт с тем же id.
                    if (replyID != id || status == -6) continue
                    if (status == -2) break // NAK: спросить снова
                    if (status != 0) {
                        throw Failure(L("USB гостя: %@", "status $status"), FailureKind.USB)
                    }
                    return body
                }
                Thread.sleep((delay * 1000).toLong())
            }
            throw Failure(L("устройство не отвечает (NAK)"), FailureKind.NOT_READY)
        }
    }

    private fun control(
        type: Int,
        request: Int,
        value: Int,
        index: Int,
        length: Int = 0,
        data: ByteArray = ByteArray(0),
    ): ByteArray {
        var setup = byteArrayOf(type.toByte(), request.toByte())
        setup += le16(value)
        setup += le16(index)
        setup += le16(length)
        transfer(Token.SETUP, endpoint = 0, data = setup)

        val incoming = type and 0x80 != 0
        var out = ByteArray(0)
        if (length > 0) {
            if (incoming) {
                out = transfer(Token.TOKEN_IN, endpoint = 0, length = length)
            } else {
                transfer(Token.TOKEN_OUT, endpoint = 0, data = data.copyOfRange(0, minOf(data.size, length)))
            }
        }
        // Стадия статуса идёт в обратную сторону и ничего не несёт.
        transfer(if (incoming) Token.TOKEN_OUT else Token.TOKEN_IN, endpoint = 0, length = 0)
        return out
    }

    private fun controlIn(request: Int, value: Int, index: Int, length: Int): ByteArray =
        control(0x80, request = request, value = value, index = index, length = length)

    private fun controlOut(request: Int, value: Int, index: Int) {
        control(0x00, request = request, value = value, index = index)
    }

    private fun stringAt(index: Byte): String {
        val at = index.toInt() and 0xFF
        if (at == 0) return ""
        val raw = controlIn(request = 6, value = 0x0300 or at, index = 0x0409, length = 255)
        // Строковый дескриптор — UTF-16 little-endian за байтом длины.
        val end = minOf(raw[0].toInt() and 0xFF, raw.size)
        if (end <= 2) return ""
        val units = ArrayList<Char>()
        var pos = 2
        while (pos + 1 < end) {
            units.add((((raw[pos + 1].toInt() and 0xFF) shl 8) or (raw[pos].toInt() and 0xFF)).toChar())
            pos += 2
        }
        return String(units.toCharArray())
            .filter { ch -> ch.code >= 0x20 }
            .trim()
    }

    // MARK: - Протокол mux

    /**
     * Самый крупный пакет, который устройство примет за раз.
     *
     * `muxd.py` шлёт до 48 КБ и сходит с этим на малом трафике, но под
     * восстановлением гость перестаёт их брать: его драйвер mux выставляет
     * 16-килобайтные буферы приёма — это то, что usbmuxd зовёт MRU. Всё, что
     * больше, устройство просто никогда не принимает.
     */
    private val usbMTU = 16384

    private enum class Proto(val value: Long) { VERSION(0), CONTROL(1), SETUP(2), TCP(6) }

    private var muxVersion: Long = 0
    private var txSeq: Int = 0
    private var rxSeq: Int = 0xFFFF
    private var inbound = ByteArray(0)

    /** Номер нашего последнего пакета, принятого гостем (из его заголовков); -1 — ещё не слышали. */
    @Volatile
    private var peerRxSeq: Int = -1

    /** Когда эхо последний раз сдвинулось: застывшее эхо — признак стоящего чтения. */
    @Volatile
    private var peerRxChangedAt: Double = 0.0

    private fun handshake() {
        sendMux(Proto.VERSION, header = be32(2) + be32(0) + be32(0))
        val deadline = now() + 10
        while (now() < deadline) {
            val packet = try {
                readMux()
            } catch (e: Failure) {
                if (e.kind == FailureKind.NOT_READY) null else throw e
            }
            if (packet == null) {
                Thread.sleep(10)
                continue
            }
            val (proto, body) = packet
            if (proto == Proto.VERSION.value && body.size >= 12) {
                muxVersion = minOf(u32be(body, 0), 2)
                if (muxVersion >= 2) sendMux(Proto.SETUP, payload = byteArrayOf(0x07))
                return
            }
        }
        throw Failure(L("mux не ответил на VERSION"), FailureKind.USB)
    }

    /**
     * Держится на весь исходящий пакет.
     *
     * Mux нумерует каждый пакет, который шлёт, и устройство отбрасывает всё
     * не по порядку. Сюда пишут два потока — тот, что ведёт передачу, и тот,
     * что отвечает устройству, — поэтому номер и байты, к которым он
     * относится, должны уходить вместе. Блокировка рекурсивная: `sendTcp`
     * берёт её и затем вызывает сюда. `muxd.py` получает это даром: каналом
     * владеет один поток.
     */
    private val sendLock = java.util.concurrent.locks.ReentrantLock()

    private fun sendMux(proto: Proto, header: ByteArray = ByteArray(0), payload: ByteArray = ByteArray(0)) {
        sendLock.lock()
        try {
            val size = (if (muxVersion < 2) 8 else 16) + header.size + payload.size
            var head = be32(proto.value.toInt()) + be32(size)
            if (muxVersion >= 2) {
                if (proto == Proto.SETUP) {
                    txSeq = 0
                    rxSeq = 0xFFFF
                }
                head += be32(MUX_MAGIC) + be16(txSeq) + be16(rxSeq)
                txSeq = (txSeq + 1) and 0xFFFF
            }
            val packet = head + header + payload
            try {
                bulkOut(packet)
            } catch (e: Throwable) {
                // Номер возвращается: устройство считает каждый пакет и после
                // пропуска отбрасывает всё навсегда — «detected duplicate
                // packet. Expected 1790 received 1791», — поэтому пакет,
                // который так и не ушёл, не должен уносить свой номер.
                if (muxVersion >= 2) txSeq = (txSeq - 1) and 0xFFFF
                LogCapture.note(L("USB гостя: пакет не ушёл — %@", e.message ?: L("ошибка")))
                throw e
            }
            // Передача, кончающаяся на границе пакета, требует нулевого пакета,
            // иначе устройство ждёт её продолжения.
            if (packet.size % maxPacket == 0) bulkOut(ByteArray(0))
            if (muxVersion >= 2) {
                synchronized(sendLock) {
                    recent.add(packet)
                    // Подтверждённое гостем спереди больше не нужно: окно
                    // держит только то, что ещё может понадобиться повторить.
                    val heard = peerRxSeq
                    if (heard >= 0) {
                        while (recent.isNotEmpty()) {
                            val diff = (heard - u16be(recent[0], 12)) and 0xFFFF
                            if (diff !in 1..0x7FFF) break
                            recent.removeAt(0)
                        }
                    }
                    if (recent.size > recentKept) {
                        repeat(recent.size - recentKept) { recent.removeAt(0) }
                    }
                }
            }
        } finally {
            sendLock.unlock()
        }
    }

    /**
     * Последние отправленные пакеты, целиком и под своими номерами, для
     * `resendRecent`. Под `sendLock`.
     */
    private val recent = ArrayList<ByteArray>()

    /**
     * Куда больше, чем вмещает окно устройства: какой бы пакет оно ни
     * потеряло, он точно ещё здесь, когда видно, что канал встал.
     *
     * Окно гостя — 16 МБ ≈ 1024 mux-пакета: встал он не на наш пакет, так на
     * следующий после дыры, и flood убегает вперёд, пока буфер не переполнится.
     * Ретрансмита у самого mux'а нет — недостающий пакет обязан оставаться
     * здесь, пока не придёт его подтверждение; 2048 покрывает всё окно с
     * запасом. Подтверждённое подрезается на каждом добавлении.
     */
    private val recentKept = 2048

    /**
     * Время от времени эмулируемый контроллер отдаёт устройству mux-пакет
     * искажённым, устройство его отбрасывает и с этого момента отказывает
     * всем следующим как нарушающим порядок — канал мёртв, хотя на этой
     * стороне ничего не потеряно. Повторная отправка последних пакетов с
     * теми же номерами лечит это: устройство берёт тот, который ждёт, и всё
     * после, а уже имеющиеся отбрасывает по той же причине. Безвредно, когда
     * ничего не потеряно, — тогда все они повторы.
     *
     * Быстро, когда у канала есть неподтверждённые байты: явный признак
     * потерянного пакета. Медленно иначе: потерянным мог быть голый
     * acknowledgement, после которого здесь ничего не висит, но занятый гость
     * тоже молчит.
     */
    private fun resendIfStuck(channel: Channel) {
        val patience: Double
        val stuck: Boolean
        synchronized(muxLock) {
            patience = if (channel.txSeq != channel.txAcked) 3.0 else 15.0
            stuck = now() - channel.lastProgress > patience && now() - channel.lastResend > patience
            if (stuck) channel.lastResend = now()
        }
        if (!stuck) return

        sendLock.lock()
        try {
            resendPending("гость молчит")
        } finally {
            sendLock.unlock()
        }
    }

    /**
     * Сколько устройство может отказывать пакету, прежде чем канал считается
     * мёртвым. Гость, проверяя принятый образ (checksumChunks) под TCG,
     * перестаёт читать сокет на десятки минут, оставаясь живым; заблокированный
     * его mux не шлёт и keepalive'ов. Перезапуск беседы в этом состоянии
     * бесполезен — новый никто не прочтёт, — поэтому терпим дольше самой
     * паузы: пятнадцать минут.
     */
    private val bulkOutPatience = 900.0

    /**
     * Один OUT-пакет с повтором на NAK — и между попытками с устройства
     * снимается то, что у него есть для нас.
     *
     * Повтор, держа канал, морил другое направление голодом: устройство не
     * берёт больше, пока не прочитано то, что оно шлёт, поток, который бы это
     * читал, ждал этой самой отправки, и оба стояли — «a packet waited 89.1 s»
     * здесь, «IO timeout for endpoint 81» у гостя. Прочитанное только
     * складывается в `inbound`: действовать по нему — значит слать
     * подтверждение, а оно ушло бы впереди этого пакета под следующим номером.
     */
    private fun bulkOut(packet: ByteArray) {
        val started = now()
        currentOut = packet
        try {
            val deadline = started + bulkOutPatience
            while (true) {
                try {
                    transfer(Token.TOKEN_OUT, endpoint = endpointOut, data = packet, retries = 1, delay = 0.0)
                    currentOut = null
                    return
                } catch (e: Failure) {
                    if (e.kind != FailureKind.NOT_READY) throw e
                    if (now() > deadline) throw e
                    drainIn()
                    // Здесь сидит тот, кто держит sendLock, — вызовы ретрая
                    // из write()/pump() до него не доходят. Повторяем сами:
                    // NAK дольше 3 с при живом канале — обычно затор на
                    // потерянном пакете. Повтор сам может встать в NAK —
                    // не вкручиваемся.
                    if (!resending && now() - lastNaKResend > 3.0) {
                        lastNaKResend = now()
                        resending = true
                        try {
                            resendUnacked()
                        } finally {
                            resending = false
                        }
                    }
                    Thread.sleep(1)
                }
            }
        } finally {
            currentOut = null
            // Сказано вслух: долгий отказ — то, что предшествует мёртвому
            // восстановлению, и вопрос в том, гость медленный или ушёл.
            val took = now() - started
            if (took > 2) LogCapture.note(L("USB гостя: пакет ждал приёма %.1f с", took))
        }
    }

    /** Последний повтор неподтверждённого из NAK-петли bulkOut. */
    private var lastNaKResend = Double.NEGATIVE_INFINITY

    /** Внутри повтора: его bulkOut сам может встать в NAK — не вкручиваться. */
    private var resending = false

    /** Повторяет пакеты, которых гость ещё не принял (см. `resendIfStuck`).
     * Зовётся только из-под уже взятого `sendLock` — блокировка реентерабельная. */
    private fun resendUnacked() {
        resendPending("передача стоит")
    }

    /** Пакет, который прямо сейчас крутится в NAK-пете bulkOut; под sendLock. */
    private var currentOut: ByteArray? = null

    /**
     * Повторяет пакеты, которые гость мог не получить.
     *
     * Пока эхо движется, повторяется эхо-фильтрованное подмножество: гость
     * признался, что всё до него принял. Стоит эху замереть на минуту —
     * повторяется всё окно целиком: счётчик, которым гость ждёт пакеты, не
     * обязан совпадать с эхом, и отфильтрованное подмножество может не
     * содержать тот самый недостающий пакет. Дубли безвредны — гость
     * отбрасывает их как повторы.
     *
     * Проход неблокирующий: стоящий гость отказывает всему, и застревать в
     * NAK-петле на первом же пакете — значит заморозить сам повтор. Первый
     * отказ кончает проход; зовущий повторит его через свои три секунды,
     * когда и эскалация переоценится.
     */
    private fun resendPending(what: String) {
        val heard = peerRxSeq
        val escalate = heard >= 0 && now() - peerRxChangedAt > 60.0
        val pending = if (heard < 0 || escalate) recent.toList() else recent.filter { p ->
            val diff = (u16be(p, 12) - heard) and 0xFFFF
            diff in 1..0x7FFF
        }
        // Хвост (то, чего гость ещё не получил) — вперёд: при эскалации
        // дубликаты из начала окна съедают живое чтение гостя, и недостающий
        // кадр всегда остаётся последним — наедине с опустевшим переносом
        // (дедлок на одном кадре: гость ждёт N, а доезжают только N-5..N-1).
        val ordered = if (heard >= 0) {
            val (tail, old) = pending.partition { p ->
                ((u16be(p, 12) - heard) and 0xFFFF) in 1..0x7FFF
            }
            (tail + old).toMutableList()
        } else {
            pending.toMutableList()
        }
        // Кадр, крутящийся в NAK-пете bulkOut, в recent не попадает (он ещё
        // не ушёл), а гость может ждать только его — «дедлок на одном
        // кадре»: ждут N, доезжают N-5..N-1.
        val cur = currentOut
        if (cur != null && ordered.none { it.contentEquals(cur) }) {
            val alive = heard < 0 || ((u16be(cur, 12) - heard) and 0xFFFF) in 1..0x7FFF
            if (alive) ordered.add(cur)
        }
        if (ordered.isEmpty()) return
        LogCapture.note(
            L(
                "USB гостя: %@ — повторяю %d из %d пакетов (эхо %d, передано %d%@)",
                what, ordered.size, recent.size, heard, txSeq,
                if (escalate) ", всё окно: эхо стоит" else "",
            ),
        )
        // Первый NAK — не конец прохода: недостающий кадр обычно в хвосте,
        // и NAK одного пакета не значит NAK остальных. Гасим проход двойным
        // NAK подряд: кольцо пусто и дальше будет так же.
        var nakStreak = 0
        for (p in ordered) {
            val ok = tryOnce(p)
            LogCapture.note("USB гостя: повтор ${u16be(p, 12)} → ${if (ok) "ушёл" else "NAK"}")
            nakStreak = if (ok) 0 else nakStreak + 1
            if (nakStreak >= 2) break
            if (ok && p.size % maxPacket == 0) tryOnce(ByteArray(0))
        }
    }

    /** Одна попытка OUT без ожиданий: истина — устройство взяло пакет. */
    private fun tryOnce(packet: ByteArray): Boolean {
        return try {
            transfer(Token.TOKEN_OUT, endpoint = endpointOut, data = packet, retries = 1, delay = 0.0)
            true
        } catch (e: Failure) {
            if (e.kind != FailureKind.NOT_READY) throw e
            false
        }
    }

    /**
     * Снимает с устройства одну IN-передачу, если у него что-то есть, и
     * складывает не читая — `readMux` разберёт позже.
     */
    private fun drainIn() {
        synchronized(recvLock) {
            try {
                val chunk = transfer(
                    Token.TOKEN_IN,
                    endpoint = endpointIn,
                    length = usbMTU,
                    retries = 1,
                    delay = 0.0,
                )
                if (chunk.isNotEmpty()) inbound += chunk
            } catch (_: Failure) {
            }
        }
    }

    /**
     * Держится на весь входящий пакет, по той же причине, что `sendLock`.
     *
     * Читают два потока: тот, что качает канал, и тот, что ждёт на канале.
     * Байты приходят кусками и собираются в `inbound`, поэтому без этого они
     * перемешиваются и буфер перестаёт иметь смысл — подтверждения устройства
     * теряются, окно никогда не открывается, и долгая передача встаёт
     * намертво, пока оба потока всё ещё заняты.
     */
    private val recvLock = java.util.concurrent.locks.ReentrantLock()

    /** Читает всё, что есть у устройства, и отдаёт один целый mux-пакет. */
    private fun readMux(): Pair<Long, ByteArray>? {
        recvLock.lock()
        try {
            takeMux()?.let { return it }
            // NAK здесь — это «для тебя ничего нет», обычный ответ на
            // бездействующем канале, а не сбой. Принять его за сбой было
            // достаточно, чтобы mux вообще никогда не поднялся.
            val chunk = try {
                transfer(Token.TOKEN_IN, endpoint = endpointIn, length = usbMTU, retries = 1, delay = 0.0)
            } catch (e: Failure) {
                if (e.kind == FailureKind.NOT_READY) return null
                throw e
            }
            if (chunk.isEmpty()) return null
            inbound += chunk
            return takeMux()
        } finally {
            recvLock.unlock()
        }
    }

    private fun takeMux(): Pair<Long, ByteArray>? {
        if (inbound.size < 8) return null
        val proto = u32be(inbound, 0)
        val total = u32be(inbound, 4).toInt()
        if (total < 8 || inbound.size < total) return null
        val raw = inbound.copyOfRange(0, total)
        inbound = inbound.copyOfRange(total, inbound.size)
        var headerSize = 8
        if (muxVersion >= 2 && raw.size >= 16) {
            // Эхо собственного номера устройства — то, что держит сеанс живым.
            rxSeq = u16be(raw, 12)
            // Последний наш пакет, который гость принял по порядку.
            val heard = u16be(raw, 14)
            if (heard != peerRxSeq) {
                peerRxSeq = heard
                peerRxChangedAt = now()
            }
            headerSize = 16
        }
        return proto to raw.copyOfRange(headerSize, raw.size)
    }

    // MARK: - TCP внутри mux

    /** Одно соединение с портом на госте. */
    class Channel internal constructor(
        internal val host: GuestUSB,
        internal val localPort: Int,
        val remotePort: Int,
    ) {
        internal var txSeq: Long = 0
        internal var txAck: Long = 0

        /**
         * Сколько нашего устройство подтвердило и сколько ещё готово держать.
         * Без обоих долгая передача переполняет его.
         */
        internal var txAcked: Long = 0
        internal var peerWindow: Int = 0
        internal var inbox = ByteArray(0)
        internal var closed = false

        /* Прогресс и ретрансмит — ПЕРСЕССНЫЕ. Глобальный штамп убивал ASR:
         * пока живой контрольный канал тикал сердцебиениями, застрявший
         * канал данных никогда не считался «застрявшим», повторная отправка
         * не взлетала, и обе стороны висли до взаимных таймаутов (гость —
         * «failed to restore APFS image», мы — «не ответил вовремя»). */
        internal var lastProgress = host.now()
        internal var lastResend = Double.NEGATIVE_INFINITY

        fun write(bytes: ByteArray) = host.write(this, bytes)

        fun read(count: Int, timeout: Double = 20.0): ByteArray =
            host.read(this, count = count, timeout = timeout)

        /**
         * Всё, что пришло, как только что-то пришло. Плисты ASR идут без
         * длины впереди, поэтому их читатель не может просить число.
         */
        fun readAvailable(timeout: Double = 20.0): ByteArray =
            host.readAvailable(this, timeout = timeout)

        fun close() = host.close(this)
    }

    private val channels = HashMap<Int, Channel>()
    private var nextLocalPort: Int = 0xF001
    private val muxLock = Any()

    /**
     * Снимок состояния mux'а: кто сколько подтвердил, что висит непрочитанным.
     * Рестор зовёт его в стоянии — там видно, на чьей стороне застряло.
     */
    fun describe(): String {
        synchronized(muxLock) {
            val lines = mutableListOf(
                L(
                    "Состояние: эхо %d (стоит %.0f с), передано %d, не по порядку %d",
                    peerRxSeq, now() - peerRxChangedAt, txSeq, seqAnomalies.get(),
                ),
            )
            for (c in channels.values.sortedBy { it.localPort }) {
                lines += L(
                    "  канал %d→%d%s: передано %d, потверждено %d, окно %d, непрочитано %d б, прогресс %.0f с назад",
                    c.localPort, c.remotePort, if (c.closed) " закрыт" else "",
                    c.txSeq, c.txAcked, c.peerWindow, c.inbox.size, now() - c.lastProgress,
                )
            }
            return lines.joinToString("\n")
        }
    }

    /**
     * Открывает соединение с портом на госте. Блокируется, пока гость не
     * ответит, — во время восстановления это может быть долго.
     */
    fun connect(port: Port, timeout: Double = 30.0): Channel =
        connect(port.value, timeout)

    fun connect(port: Int, timeout: Double = 30.0): Channel {
        val channel = synchronized(muxLock) {
            val c = Channel(this, nextLocalPort, port)
            nextLocalPort = if (nextLocalPort == 0xFFFE) 0xF001 else nextLocalPort + 1
            channels[c.localPort] = c
            c
        }

        sendTcp(channel, flags = 0x02) // SYN
        val deadline = now() + timeout
        while (now() < deadline) {
            // SYN может потеряться, как любой другой пакет, и тогда на него
            // никто не ответит; если оставить как есть, гость брал его поздно,
            // после того как от стука отказались, и ASR умирал на пустом
            // соединении.
            resendIfStuck(channel)
            serviceMux(timeout = 0.2)
            val (established, dead) = synchronized(muxLock) {
                (channel.txAck != 0L) to channel.closed
            }
            // Убирается из таблицы на выходе: в отказанный порт стучатся снова,
            // иначе тридцать мёртвых каналов копились бы.
            if (dead) {
                close(channel)
                throw Failure(L("Гость отказал в порту %d", port), FailureKind.REFUSED, port)
            }
            if (established) return channel
        }
        close(channel)
        throw Failure(L("Гость отказал в порту %d", port), FailureKind.REFUSED, port)
    }

    private fun sendTcp(channel: Channel, flags: Int, payload: ByteArray = ByteArray(0)) {
        sendLock.lock()
        try {
            var header = be16(channel.localPort) + be16(channel.remotePort)
            header += be32(channel.txSeq.toInt()) + be32(channel.txAck.toInt())
            header += byteArrayOf((5 shl 4).toByte(), flags.toByte())
            header += be16((256 * 1024) shr 8) + be16(0) + be16(0)
            sendMux(Proto.TCP, header = header, payload = payload)
            channel.txSeq = (channel.txSeq + payload.size + (if (flags and 0x02 != 0) 1 else 0)) and 0xFFFF_FFFFL
        } finally {
            sendLock.unlock()
        }
    }

    /** Сколько раз входящий payload пришёл не по порядку (в лог идут первые 20). */
    private val seqAnomalies = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * Читает с устройства и раскладывает пришедшее, возвращаясь сразу после
     * обработки одного пакета.
     *
     * Ранний возврат — весь смысл. Ожидающему места в окне устройства нужно
     * взглянуть снова в момент, когда приходит подтверждение, а ожидание
     * остатка таймаута стоило восстановлению больше всего остального: окно —
     * 128 КБ, и ожидание 200 мс, чтобы заметить его открытие, держало
     * передачу на половине мегабайта в секунду.
     */
    private fun serviceMux(timeout: Double) {
        val deadline = now() + timeout
        do {
            // Приём и разбор одного mux-пакета — под единым recvLock, разбор
            // внутри него. Раньше второй поток (писатель канала, pump)
            // успевал вклиниться между пакетами одной пары гостя и обрабатывать
            // их в обратном порядке: средний кадр терялся, наш открытый ack
            // уезжал за потерянные байты, и гость навсегда ждал ответа
            // («ждали 10746, пришло 10750» у copy_restore_sep — тихий
            // дедлок restaura).
            var empty = true
            var handled = false
            var replyFlags = -1                    // -1: нечего слать; ≥0: флаги ответа
            var replyChannel: Channel? = null
            recvLock.lock()
            try {
                var t = takeMux()
                if (t == null) {
                    val chunk = try {
                        transfer(Token.TOKEN_IN, endpoint = endpointIn, length = usbMTU, retries = 1, delay = 0.0)
                    } catch (e: Failure) {
                        if (e.kind != FailureKind.NOT_READY) throw e
                        null
                    }
                    if (chunk != null && chunk.isNotEmpty()) inbound += chunk
                    t = takeMux()
                }
                if (t != null) {
                    empty = false
                    val (proto, body) = t
                    if (proto == Proto.TCP.value && body.size >= 20) {
                        val destination = u16be(body, 2)
                        val sequence = u32be(body, 4)
                        val acknowledged = u32be(body, 8)
                        val flags = body[13].toInt() and 0xFF
                        val payload = body.copyOfRange(20, body.size)
                        val channel = synchronized(muxLock) { channels[destination] } ?: continue
                        handled = true
                        // Все решения в одном lock: сдвиг txAck, приём
                        // payload'а и журнал атомарны относительно потока
                        // соседа — иначе он вклинивается между приёмом и
                        // обработкой, и кадр теряется.
                        synchronized(muxLock) {
                            val before = channel.txAcked
                            channel.peerWindow = u16be(body, 14) shl 8
                            if (flags and 0x10 != 0) {
                                if (acknowledged != channel.txAcked) channel.lastProgress = now()
                                channel.txAcked = acknowledged
                            }
                            LogCapture.note(
                                "USB гостя: in ch$destination txn=$sequence ack=$acknowledged (было $before) окно=${channel.peerWindow} " +
                                    "флаги=$flags байт=${payload.size} эхо=$peerRxSeq",
                            )
                            if (flags and 0x04 != 0) { // RST
                                channel.closed = true
                                channels.remove(destination)
                                replyChannel = null
                            }
                            if (flags and 0x02 != 0 && flags and 0x10 != 0) { // SYN|ACK
                                channel.txAck = (sequence + 1) and 0xFFFF_FFFFL
                                replyFlags = 0x10
                            } else {
                                if (payload.isNotEmpty()) {
                                    // Принимаем только то, что продолжает поток. Повтор уже
                                    // принятого (гость переотправляет по таймеру) раньше
                                    // дописывался в inbox ещё раз и сдвигал рамку plist'ов;
                                    // перекрытие обрезаем, дыру не принимаем — подтверждение
                                    // с прежним номером заставит гостя повторить.
                                    val expected = channel.txAck
                                    val behind = (expected - sequence) and 0xFFFF_FFFFL
                                    if (behind != 0L && seqAnomalies.incrementAndGet() <= 20) {
                                        LogCapture.note("USB гостя: данные не по порядку — ждали $expected, пришло $sequence, байт ${payload.size}")
                                    }
                                    if (behind == 0L) {
                                        channel.txAck = (sequence + payload.size) and 0xFFFF_FFFFL
                                        channel.inbox += payload
                                        channel.lastProgress = now()
                                    } else if (behind < 0x8000_0000L && behind < payload.size) {
                                        val fresh = payload.copyOfRange(behind.toInt(), payload.size)
                                        channel.txAck = (expected + fresh.size) and 0xFFFF_FFFFL
                                        channel.inbox += fresh
                                        channel.lastProgress = now()
                                    }
                                }
                                if (payload.isNotEmpty()) replyFlags = 0x10
                                if (flags and 0x01 != 0) { // FIN
                                    channel.closed = true
                                }
                            }
                            replyChannel = channel
                        }
                    }
                }
            } finally {
                recvLock.unlock()
            }
            if (!handled) {
                // Пустое чтение — обычный ответ на тихом канале, и немедленный
                // повтор превращает это в верчение, отбирающее канал у того,
                // кто действительно передаёт. Миллисекунда терпения — то, что
                // ждёт `muxd.py`, и здесь она ничего не стоит.
                Thread.sleep(1)
                continue
            }
            if (replyFlags >= 0) {
                val out = replyChannel
                if (out != null) sendTcp(out, flags = 0x10)
            }
            return
        } while (now() < deadline)
    }

    internal fun write(channel: Channel, bytes: ByteArray) {
        // Mux устройства наотрез отказывает PSH, поэтому данные едут на простом
        // ACK.
        //
        // И шлются только настолько, насколько устройство говорит, что может
        // держать: здесь ничего не переотправляется, поэтому всё записанное
        // сверх его окна теряется без слова. Горсть управляющих плистов
        // переживает наивный способ; гигабайты восстановления — нет.
        var at = 0
        val mtu = usbMTU - 16 - 20
        while (at < bytes.size) {
            /* Гость, потеряв один наш пакет, отбрасывает всё после него и
             * молчит, продолжая забирать OUT — NAK не будет, окно не
             * кончится, и до ветки room<=0 дело не дойдёт. Поэтому ретрай
             * стража зовётся на каждом шаге: его внутренняя отсрочка (3 с)
             * делает вызов бесплатным, пока подтверждения идут. */
            resendIfStuck(channel)
            val (inFlight, room, dead) = synchronized(muxLock) {
                val inflightNow = (channel.txSeq - channel.txAcked) and 0xFFFF_FFFFL
                Triple(inflightNow.toInt(), channel.peerWindow - inflightNow.toInt(), channel.closed)
            }
            if (dead) throw Failure(L("Гость отключился"), FailureKind.GONE)
            if (room <= 0) {
                resendIfStuck(channel)
                serviceMux(timeout = 0.005)
                if (now() > writeDeadline(channel)) {
                    throw Failure(L("USB гостя: %@", L("гость не разбирает присланное")), FailureKind.USB)
                }
                continue
            }
            var end = minOf(at + minOf(mtu, room), bytes.size)
            // Никогда не целое число USB-пакетов на проводе. Такая передача
            // требует нулевого пакета после себя, а эмулируемый контроллер
            // иногда обращается с ними неправильно: гость читает mux-пакет
            // короче, отбрасывает его и навсегда отказывается от всего после
            // пропуска («detected duplicate packet. Expected 749 received
            // 750»). На байт меньше — и каждый пакет данных заканчивает себя.
            if ((end - at + (if (muxVersion < 2) 8 else 16) + 20) % 512 == 0 && end - at > 1) end -= 1
            sendTcp(channel, flags = 0x10, payload = bytes.copyOfRange(at, end))
            at = end
            synchronized(waitedLock) { waited.remove(channel.localPort) }
        }
        synchronized(waitedLock) { waited.remove(channel.localPort) }
    }

    /**
     * Когда сдаваться, ожидая места у устройства. Сбрасывается каждым
     * ушедшим байтом, поэтому медленный гость — это нормально, а застрявший
     * нет.
     */
    private val waited = HashMap<Int, Double>()
    private val waitedLock = Any()

    private fun writeDeadline(channel: Channel): Double {
        synchronized(waitedLock) {
            waited[channel.localPort]?.let { return it }
            val deadline = now() + 600
            waited[channel.localPort] = deadline
            return deadline
        }
    }

    internal fun read(channel: Channel, count: Int, timeout: Double): ByteArray {
        val deadline = now() + timeout
        while (now() < deadline) {
            val (have, dead) = synchronized(muxLock) { channel.inbox.size to channel.closed }
            if (have >= count) {
                return synchronized(muxLock) {
                    val out = channel.inbox.copyOfRange(0, count)
                    channel.inbox = channel.inbox.copyOfRange(count, channel.inbox.size)
                    out
                }
            }
            if (dead && have == 0) throw Failure(L("Гость отключился"), FailureKind.GONE)
            resendIfStuck(channel)
            serviceMux(timeout = 0.2)
        }
        throw Failure(L("USB гостя: %@", L("гость не ответил вовремя")), FailureKind.USB)
    }

    internal fun readAvailable(channel: Channel, timeout: Double): ByteArray {
        val deadline = now() + timeout
        while (now() < deadline) {
            val (out, dead) = synchronized(muxLock) {
                val piece = channel.inbox
                channel.inbox = ByteArray(0)
                piece to channel.closed
            }
            if (out.isNotEmpty()) return out
            if (dead) throw Failure(L("Гость отключился"), FailureKind.GONE)
            // Читатель тоже лечит десинк: гость, потеряв наш mux-пакет,
            // навсегда отказывается от всего после пропуска и молчит — без
            // повтора оба замирают до таймаута (run51: «Validating source»).
            resendIfStuck(channel)
            serviceMux(timeout = 0.2)
        }
        throw Failure(L("USB гостя: %@", L("гость не ответил вовремя")), FailureKind.USB)
    }

    internal fun close(channel: Channel) {
        val alreadyClosed = synchronized(muxLock) {
            channels.remove(channel.localPort)
            val was = channel.closed
            channel.closed = true
            was
        }
        if (!alreadyClosed) {
            try {
                sendTcp(channel, flags = 0x04)
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * Обслуживает канал, пока никто не читает ни одного канала, чтобы
     * устройство видело свои подтверждения и сеанс оставался живым.
     */
    private fun pump() {
        var lastNote = Double.NEGATIVE_INFINITY
        while (link != null) {
            try {
                serviceMux(timeout = 0.2)
            } catch (e: Failure) {
                if (e.kind == FailureKind.GONE) break
                // Всё, кроме закрытия канала, переживается, а тихий конец этого
                // потока — нет: именно он подтверждает устройству, пока никто
                // не читает канал.
                if (now() - lastNote > 5) {
                    lastNote = now()
                    LogCapture.note(e.message ?: L("USB гостя: %@", L("сбой")))
                }
                Thread.sleep(50)
            } catch (_: Throwable) {
                Thread.sleep(50)
            }
        }
    }

    // MARK: - Мелкие помощники

    private fun now(): Double = System.nanoTime() / 1_000_000_000.0

    private companion object {
        /** `0xFEEDFACE` в виде знакового Int для `be32`. */
        const val MUX_MAGIC: Int = 0xFEED_FACE.toInt()
    }
}
