package dev.tazik.inferno.engine

import android.graphics.Bitmap
import android.graphics.Color
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Минимальный клиент RFB 3.8, достаточный, чтобы вести собственный VNC-сервер
 * эмулятора.
 *
 * Запрашивается только кодировка Raw. На сети это расточительно, но сервер —
 * в этом же самом процессе, так что пиксели не покидают устройство, а
 * декодер остаётся достаточно простым, чтобы ему можно было верить.
 *
 * Вся сессия идёт на одном потоке с блокирующими чтениями: протокол — строгая
 * последовательность запросов и ответов, и в таком виде она читается сверху
 * вниз.
 */
class VNCClient(private val port: Int = 5900) : GuestDisplay {

    private val _status = MutableStateFlow<GuestDisplayStatus>(GuestDisplayStatus.Disconnected)
    override val status: GuestDisplayStatus get() = _status.value

    override var onStatus: ((GuestDisplayStatus) -> Unit)? = null
    override var onFrame: ((Bitmap) -> Unit)? = null

    private val sock = Sock()
    private var sessionThread: Thread? = null

    @Volatile
    private var running = false

    private var width = 0
    private var height = 0
    private var pixels = IntArray(1)
    /** Два `Bitmap`, поочерёдно отдаваемых наружу: у каждого кадра два кадра
     * жизни.
     */
    private var published: Array<Bitmap> = arrayOf()
    private var publishedIndex = 0

    // MARK: - Сессия

    override fun connect() {
        if (sessionThread != null) return
        report(GuestDisplayStatus.Connecting)
        running = true

        sessionThread = engineThread("inferno.vnc", 512L * 1024) { run() }
    }

    override fun disconnect() {
        running = false
        sock.close()
        sessionThread = null
        report(GuestDisplayStatus.Disconnected)
    }

    private fun report(new: GuestDisplayStatus) {
        when (new) {
            is GuestDisplayStatus.Connected ->
                LogCapture.note(L("VNC: подключено, экран %d×%d", new.width, new.height))
            is GuestDisplayStatus.Failed -> LogCapture.note("VNC: ${new.why}")
            else -> {}
        }
        _status.value = new
        onStatus?.invoke(new)
    }

    /** Сервер начинает слушать, только когда эмулятор поднялся, — поэтому
     * пробуют снова и снова.
     */
    private fun run() {
        var attempt = 0
        while (running) {
            attempt += 1
            val problem = sock.connect(port)
            if (problem != null) {
                if (attempt == 1 || attempt % 10 == 0) {
                    LogCapture.note(L("VNC: %@ — попытка %d", problem, attempt))
                }
                sleepASecond()
                continue
            }
            LogCapture.note(L("VNC: сокет открыт, рукопожатие"))
            if (session()) return    // кончилось чисто
            sock.close()
            sleepASecond()
        }
    }

    private fun sleepASecond() {
        try {
            Thread.sleep(1000)
        } catch (_: InterruptedException) {
        }
    }

    /** true — когда зовущему пора перестать пробовать. */
    private fun session(): Boolean {
        if (sock.readExactly(12) == null) {
            report(GuestDisplayStatus.Failed(L("сервер не прислал версию протокола")))
            return false
        }
        sock.write("RFB 003.008\n".toByteArray(Charsets.US_ASCII))

        val count = sock.readExactly(1)
        val types = count?.takeIf { it[0] > 0 }?.let { sock.readExactly(it[0].toInt()) }
        if (types == null) {
            report(GuestDisplayStatus.Failed(L("сервер отказал в соединении")))
            return false
        }
        if (!types.contains(1)) {
            report(GuestDisplayStatus.Failed(L("сервер требует пароль VNC")))
            return true
        }
        sock.write(byteArrayOf(1))                    // тип защиты: None

        val result = sock.readExactly(4)
        if (result == null || result.be32(0) != 0L) {
            report(GuestDisplayStatus.Failed(L("сервер отклонил рукопожатие")))
            return false
        }

        sock.write(byteArrayOf(1))                    // общая сессия
        val header = sock.readExactly(24)
        if (header == null) {
            report(GuestDisplayStatus.Failed(L("нет параметров экрана")))
            return false
        }
        val w = header.be16(0).toInt()
        val h = header.be16(2).toInt()
        val nameLen = header.be32(20).toInt()
        if (nameLen > 0 && sock.readExactly(nameLen) == null) {
            report(GuestDisplayStatus.Failed(L("оборвано имя сервера")))
            return false
        }

        width = w
        height = h
        pixels = IntArray(maxOf(w * h, 1)) { 0xFF000000.toInt() }
        published = Array(2) {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.BLACK)
            }
        }
        publishedIndex = 0
        setPixelFormat()
        setEncodings()
        report(GuestDisplayStatus.Connected(w, h))
        requestUpdate(incremental = false)

        while (running) {
            val type = sock.readExactly(1)
            if (type == null) {
                report(GuestDisplayStatus.Failed(L("соединение закрыто")))
                return false
            }
            when (type[0].toInt()) {
                0 -> if (!readFramebufferUpdate()) return false
                1 -> {                                          // SetColourMapEntries
                    val body = sock.readExactly(5)
                    if (body == null || sock.readExactly(body.be16(3).toInt() * 6) == null) return false
                }
                2 -> {}                                         // Bell
                3 -> {                                          // ServerCutText
                    val body = sock.readExactly(7)
                    if (body == null || sock.readExactly(body.be32(3).toInt()) == null) return false
                }
                else -> {
                    report(GuestDisplayStatus.Failed(L("непонятное сообщение сервера")))
                    return false
                }
            }
        }
        return true
    }

    /** 32 bpp, true colour, разложенные так, чтобы буфер можно было отдать
     * дальше не трогая (little-endian 0xAARRGGBB).
     */
    private fun setPixelFormat() {
        sock.write(
            byteArrayOf(0, 0, 0, 0,
                32, 24, 0, 1,
                0, 255.toByte(), 0, 255.toByte(), 0, 255.toByte(),
                16, 8, 0,
                0, 0, 0),
        )
    }

    private fun setEncodings() {
        sock.write(byteArrayOf(2, 0, 0, 1, 0, 0, 0, 0))   // Raw
    }

    private fun requestUpdate(incremental: Boolean) {
        val w = width.toUShort()
        val h = height.toUShort()
        sock.write(
            byteArrayOf(3, if (incremental) 1 else 0,
                0, 0, 0, 0,
                (w.toInt() shr 8).toByte(), (w.toInt() and 0xFF).toByte(),
                (h.toInt() shr 8).toByte(), (h.toInt() and 0xFF).toByte()),
        )
    }

    private fun readFramebufferUpdate(): Boolean {
        val header = sock.readExactly(3)
        if (header == null) return false
        var remaining = header.be16(1).toInt()

        while (remaining > 0) {
            val rect = sock.readExactly(12)
            if (rect == null) return false
            val x = rect.be16(0).toInt()
            val y = rect.be16(2).toInt()
            val w = rect.be16(4).toInt()
            val h = rect.be16(6).toInt()
            val encoding = rect.be32(8).toInt()

            if (encoding != 0) {
                report(GuestDisplayStatus.Failed(L("неподдерживаемая кодировка %d", encoding)))
                return false
            }
            if (w > 0 && h > 0) {
                val body = sock.readExactly(w * h * 4)
                if (body == null) return false
                blit(body, x, y, w, h)
            }
            remaining -= 1
        }

        publish()
        requestUpdate(incremental = true)
        return true
    }

    private fun blit(body: ByteArray, x: Int, y: Int, w: Int, h: Int) {
        if (width <= 0) return
        // Сервер шлёт слова little-endian 0xAARRGGBB; на байтовом уровне это
        // B,G,R,A, и собираются они в int уже в виде 0xFFRRGGBB. Альфа
        // выставляется насильно: сервер оставляет её набивкой.
        for (row in 0 until h) {
            val dstStart = (y + row) * width + x
            if (dstStart < 0 || dstStart + w > pixels.size) continue
            var srcAt = row * w * 4
            for (col in 0 until w) {
                val b = body[srcAt].toInt() and 0xFF
                val g = body[srcAt + 1].toInt() and 0xFF
                val r = body[srcAt + 2].toInt() and 0xFF
                pixels[dstStart + col] = 0xFF shl 24 or (r shl 16) or (g shl 8) or b
                srcAt += 4
            }
        }
    }

    private fun publish() {
        if (width <= 0 || height <= 0) return
        val bitmap = published.getOrNull(publishedIndex) ?: return
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        onFrame?.invoke(bitmap)
        publishedIndex = (publishedIndex + 1) % published.size
    }

    // MARK: - Ввод

    /** Абсолютная позиция указателя в координатах framebuffer'а: касание
     * ложится ровно там, где палец, — этого ждёт эмулируемая панель
     * мультитача.
     */
    override fun send(touchX: Float, touchY: Float, pressed: Boolean) {
        if (_status.value !is GuestDisplayStatus.Connected) return
        val cx = touchX.toInt().coerceIn(0, width - 1).toUShort()
        val cy = touchY.toInt().coerceIn(0, height - 1).toUShort()
        sock.write(
            byteArrayOf(5, if (pressed) 1 else 0,
                (cx.toInt() shr 8).toByte(), (cx.toInt() and 0xFF).toByte(),
                (cy.toInt() shr 8).toByte(), (cy.toInt() and 0xFF).toByte()),
        )
    }

    override fun send(functionKey: Int, pressed: Boolean) {
        if (functionKey < 1 || functionKey > 12) return
        sendKey(0xFFBEL + functionKey - 1, pressed)   // XK_F1 и выше
    }

    private fun sendKey(keysym: Long, pressed: Boolean) {
        if (_status.value !is GuestDisplayStatus.Connected) return
        sock.write(
            byteArrayOf(4, if (pressed) 1 else 0, 0, 0,
                ((keysym shr 24) and 0xFF).toByte(), ((keysym shr 16) and 0xFF).toByte(),
                ((keysym shr 8) and 0xFF).toByte(), (keysym and 0xFF).toByte()),
        )
    }
}

/** Первые `count` байтов как большое конечное число. */
private fun ByteArray.be16(at: Int): Long =
    ((this[at].toLong() and 0xFF) shl 8) or (this[at + 1].toLong() and 0xFF)

private fun ByteArray.be32(at: Int): Long {
    var value = 0L
    for (k in 0 until 4) value = (value shl 8) or (this[at + k].toLong() and 0xFF)
    return value
}
