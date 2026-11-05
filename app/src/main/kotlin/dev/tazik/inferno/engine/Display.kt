package dev.tazik.inferno.engine

import android.graphics.Bitmap
import android.graphics.Color
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Откуда приходит картинка гостя и куда уходят его касания.
 *
 * Две реализации: VNC-сервер эмулятора по loopback и framebuffer эмулятора,
 * читаемый прямо из этого процесса. Они взаимозаменяемы, так что выбор остаётся
 * переключателем в настройках, а не пересборкой.
 */
interface GuestDisplay {
    var onFrame: ((Bitmap) -> Unit)?
    var onStatus: ((GuestDisplayStatus) -> Unit)?
    val status: GuestDisplayStatus

    fun connect()
    fun disconnect()

    /** Абсолютная позиция в пикселях framebuffer'а: касание ложится там, где
     * палец, — этого ждёт эмулируемая панель мультитача.
     */
    fun send(touchX: Float, touchY: Float, pressed: Boolean)

    /** Машина ведёт кнопки устройства на F1..F10. */
    fun send(functionKey: Int, pressed: Boolean)
}

sealed class GuestDisplayStatus {
    object Disconnected : GuestDisplayStatus()
    object Connecting : GuestDisplayStatus()
    data class Connected(val width: Int, val height: Int) : GuestDisplayStatus()
    data class Failed(val why: String) : GuestDisplayStatus()

    val size: Pair<Int, Int>?
        get() = (this as? Connected)?.let { it.width to it.height }
}

/**
 * Framebuffer гостя, читаемый там, где он уже лежит.
 *
 * Эмулятор — библиотека внутри этого процесса, поэтому его display surface —
 * попросту память, на которую приложение может смотреть. Библиотека следит,
 * какие строки гость перерисовал, и отдаёт только их; ничего не сравнивается,
 * не кодируется и никуда не отправляется. Остаётся сама копия и кадр, в
 * который она публикуется.
 *
 * Кадры вращаются по трём буферам. Байты, отданные наружу, не должны
 * переписываться, пока ещё на экране, и вращение даёт каждому опубликованному
 * кадру ещё два кадра жизни — достаточно, чтобы вид успел уйти дальше. В
 * Swift кадр публиковался без копии через `CFDataCreateWithBytesNoCopy`; на
 * Android `Bitmap` хранит пиксели в другом порядке (R,G,B,A в памяти против
 * B,G,R,A у буфера машины), поэтому каждый кадр проходит через четвёртый
 * буфер — RGBA, — куда повреждённый прямоугольник переносится со сменой
 * порядка байтов попиксельно, а `Bitmap` забирает его целиком одной копией.
 * Три буфера BGRA всё так же вращаются, и `Bitmap`'ы чередуются, так что ни
 * один опубликованный кадр не переписывается на глазах у вида.
 */
class EmbeddedDisplay : GuestDisplay {

    /** Совпадает с InfernoFrameResult в ui/inferno-embed.h. */
    private enum class Result(val raw: Int) {
        NONE(0), OK(1), RESIZE(2);

        companion object {
            fun of(raw: Int): Result? = entries.firstOrNull { it.raw == raw }
        }
    }

    /** Блок информации — восемь полей uint32; читается по индексам, чтобы не
     * предполагать раскладку структуры через границу языка.
     */
    private object Field {
        const val WIDTH = 0
        const val HEIGHT = 1
        const val STRIDE = 2
        const val X = 3
        const val Y = 4
        const val W = 5
        const val H = 6
        const val COUNT = 8
    }

    private val _status = MutableStateFlow<GuestDisplayStatus>(GuestDisplayStatus.Disconnected)
    override val status: GuestDisplayStatus get() = _status.value

    override var onFrame: ((Bitmap) -> Unit)? = null
    override var onStatus: ((GuestDisplayStatus) -> Unit)? = null

    /** Доступно лишь когда библиотека эмулятора загружена и её машина стоит.
     * Открытое для `VMModel`: Swift узнавал это через failable init.
     */
    val isAvailable: Boolean =
        QemuNative.hasSymbol("inferno_display_read") &&
            QemuNative.hasSymbol("inferno_input_touch") &&
            QemuNative.hasSymbol("inferno_input_function_key")

    private val available: Boolean get() = isAvailable

    private var pumpThread: Thread? = null

    @Volatile
    private var running = false

    /** Буферы-кадры в порядке байтов B,G,R,A — как их пишет машина. */
    private var buffers: List<ByteBuffer> = emptyList()
    private var bufferBytes = 0
    private var current = 0
    private var width = 0
    private var height = 0

    /** Собранный кадр для `Bitmap`: R,G,B,A в памяти. */
    private var front: ByteBuffer? = null

    /** Два `Bitmap`, поочерёдно отдаваемых наружу. */
    private var published: Array<Bitmap> = arrayOf()
    private var publishedIndex = 0

    override fun connect() {
        if (!available) {
            report(GuestDisplayStatus.Failed(L("в этой сборке библиотеки нет встроенного вывода")))
            return
        }
        if (pumpThread != null) return
        report(GuestDisplayStatus.Connecting)
        running = true

        pumpThread = engineThread("inferno.display", 256L * 1024) { pump() }
    }

    override fun disconnect() {
        running = false
        pumpThread = null
        report(GuestDisplayStatus.Disconnected)
    }

    private fun report(new: GuestDisplayStatus) {
        (new as? GuestDisplayStatus.Connected)?.let {
            LogCapture.note(L("Экран: встроенный вывод, %d×%d", it.width, it.height))
        }
        _status.value = new
        onStatus?.invoke(new)
    }

    /** Читает в том темпе, с каким перерисовывает эмулятор. Проход без новостей
     * стоит одного замка и одного сравнения, так что опрос дешевле
     * устройства пробуждений.
     */
    private fun pump() {
        val info = IntArray(Field.COUNT)
        val tally = Tally()

        while (running) {
            val beforeRead = System.nanoTime()
            val outcome = Result.of(
                QemuNative.displayRead(buffers.getOrNull(current), bufferBytes, info),
            ) ?: Result.NONE
            val afterRead = System.nanoTime()
            tally.readNanos += afterRead - beforeRead

            when (outcome) {
                Result.RESIZE -> resize(info[Field.WIDTH], info[Field.HEIGHT])
                Result.OK -> {
                    mirror(info[Field.X], info[Field.Y], info[Field.W], info[Field.H], info[Field.STRIDE])
                    publish()
                    current = (current + 1) % BUFFER_COUNT
                    tally.delivered += 1
                }
                Result.NONE -> tally.idle += 1
            }
            tally.handNanos += System.nanoTime() - afterRead
            closeSecond(tally)
            report(tally)
            try {
                Thread.sleep((1000 / 60).toLong())
            } catch (_: InterruptedException) {
            }
        }
    }

    /** Что выглядел последний четверть-минутный отрезок цикла. */
    private class Tally {
        var delivered = 0
        var idle = 0
        var readNanos = 0L
        var handNanos = 0L
        var since = System.nanoTime()
        /** Кадры, которые показала машина, — по записи на каждую закрытую
         * секунду.
         */
        val presentsList = ArrayList<Int>()
        var refreshes = 0L
        /** Когда открылась ещё не закрытая секунда. */
        var secondSince = System.nanoTime()
    }

    /** Спрашивает у машины, на каждом закрытии секунды, сколько кадров она
     * показала в ней.
     *
     * Среднее за четверть минуты не отличит свайп от неподвижного экрана:
     * две секунды по тридцать кадров среди тринадцати пустых читаются как
     * четыре. Со счётом по секундам свайп читается как тридцать. Счётчики
     * библиотеки обнуляются при каждом чтении, поэтому это единственное
     * место, где их читают.
     */
    private fun closeSecond(tally: Tally) {
        val now = System.nanoTime()
        if (now - tally.secondSince < 1_000_000_000L) return
        val counters = QemuNative.displayStats()
        tally.presentsList.add(counters.getOrNull(0)?.toInt() ?: 0)
        tally.refreshes += counters.getOrNull(1) ?: 0L
        tally.secondSince = now
    }

    /** Раз в четверть минуты говорит, куда ушли кадры.
     *
     * Счётчик кадров под экраном знал только о кадрах, которые дошли; он не
     * отличал гостя, рисующего десять раз в секунду, от гостя, рисующего
     * сорок и теряющего тридцать по дороге. Эти числа отличают: что показала
     * машина, что дошло до экрана и как часто главный цикл QEMU — тот, кто
     * соревнуется с vCPU за большой замок, — вообще успевал просить
     * перерисовку. Секунды, в которых гость что-то рисовал, суммируются
     * отдельно, потому что именно их кто-то смотрел.
     */
    private fun report(tally: Tally) {
        val now = System.nanoTime()
        val elapsed = (now - tally.since) / 1e9
        // Раз в пятнадцать секунд, усреднённое по окну. Строка в секунду
        // выталкивала из журнала всё остальное, а числа несла те же самые
        // пятнадцать раз подряд.
        if (elapsed < 15) return

        // Считанное до обнуления; открытая секунда переезжает: её кадры всё
        // ещё в счётчиках машины, и часы, заведённые заново, сложили бы их со
        // следующей секундой.
        val shown = tally.presentsList.sum()
        val drawing = tally.presentsList.filter { it > 0 }.sorted()
        val delivered = tally.delivered
        val idle = tally.idle
        val refreshes = tally.refreshes
        val readNanos = tally.readNanos
        val handNanos = tally.handNanos
        val open = tally.secondSince
        tally.delivered = 0
        tally.idle = 0
        tally.readNanos = 0
        tally.handNanos = 0
        tally.refreshes = 0
        tally.presentsList.clear()
        tally.since = now
        tally.secondSince = open

        if (!Settings.showFPS || !QemuNative.hasSymbol("inferno_display_stats")) return
        // Окно, где машина не показала ничего и ничего не дошло, говорит лишь
        // о том, что гость молчал, так что его пропускают.
        if (shown <= 0 && delivered <= 0) return
        fun millis(nanos: Long): Double = nanos / 1_000_000.0 / elapsed
        LogCapture.note(
            L(
                L("Кадры: гость показал %.0f/с (в секунды, когда рисовал: медиана %d, лучшая %d, от 30 и выше — %d из %d); дошло %.0f/с, вхолостую %.0f/с; главный цикл %.0f/с; чтение %.0f мс/с, выдача %.0f мс/с"),
                shown / elapsed,
                drawing.getOrNull(drawing.size / 2) ?: 0,
                drawing.lastOrNull() ?: 0,
                drawing.count { it >= 30 },
                drawing.size,
                delivered / elapsed,
                idle / elapsed,
                refreshes / elapsed,
                millis(readNanos),
                millis(handNanos),
            ),
        )
    }

    private fun resize(newWidth: Int, newHeight: Int) {
        if (newWidth <= 0 || newHeight <= 0) return
        bufferBytes = newWidth * newHeight * 4
        val blank = ByteArray(bufferBytes)
        buffers = (0 until BUFFER_COUNT).map { _ ->
            ByteBuffer.allocateDirect(bufferBytes).order(ByteOrder.nativeOrder()).apply {
                put(blank)
                rewind()
            }
        }
        current = 0
        width = newWidth
        height = newHeight
        front = ByteBuffer.allocateDirect(bufferBytes).order(ByteOrder.nativeOrder()).apply {
            put(blank)
            rewind()
        }
        published = Array(BUFFER_COUNT) {
            Bitmap.createBitmap(newWidth, newHeight, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.BLACK)
            }
        }
        publishedIndex = 0
        report(GuestDisplayStatus.Connected(newWidth, newHeight))
    }

    /** Переносит свежепрочитанный прямоугольник в остальные буферы — так что
     * каждый из них остаётся полным кадром, хотя трогаются только сменившиеся
     * строки, — и в RGBA-буфер для `Bitmap`.
     */
    private fun mirror(x: Int, y: Int, w: Int, h: Int, stride: Int) {
        if (w <= 0 || h <= 0 || buffers.isEmpty()) return
        val source = buffers[current]
        val rgba = front ?: return
        val span = w * 4
        // Копия строк — та же последовательность байтов, так что это
        // блочные `memcpy`: `put` между прямыми буферами.
        for (index in buffers.indices) {
            if (index == current) continue
            val destination = buffers[index]
            for (row in y until y + h) {
                val offset = row * stride + x * 4
                source.position(offset).limit(offset + span)
                destination.position(offset).limit(offset + span)
                destination.put(source)
            }
            destination.clear()
        }
        source.clear()
        // B,G,R,A → R,G,B,A. Строка кадра лежит по `stride`, а не по ширине.
        for (row in y until y + h) {
            var sourceAt = row * stride + x * 4
            var targetAt = (row * width + x) * 4
            repeat(w) {
                val b = source.get(sourceAt).toInt() and 0xFF
                val g = source.get(sourceAt + 1).toInt() and 0xFF
                val r = source.get(sourceAt + 2).toInt() and 0xFF
                val a = source.get(sourceAt + 3).toInt() and 0xFF
                rgba.put(targetAt, r.toByte())
                rgba.put(targetAt + 1, g.toByte())
                rgba.put(targetAt + 2, b.toByte())
                rgba.put(targetAt + 3, a.toByte())
                sourceAt += 4
                targetAt += 4
            }
        }
    }

    private fun publish() {
        if (width <= 0 || bufferBytes <= 0) return
        val bitmap = published.getOrNull(publishedIndex) ?: return
        val rgba = front ?: return
        rgba.rewind()
        bitmap.copyPixelsFromBuffer(rgba)
        onFrame?.invoke(bitmap)
        publishedIndex = (publishedIndex + 1) % published.size
    }

    // MARK: - Ввод

    override fun send(touchX: Float, touchY: Float, pressed: Boolean) {
        if (width <= 0) return
        QemuNative.inputTouch(touchX.toInt(), touchY.toInt(), pressed)
    }

    override fun send(functionKey: Int, pressed: Boolean) {
        QemuNative.inputFunctionKey(functionKey, pressed)
    }

    companion object {
        private const val BUFFER_COUNT = 3
    }
}
