package dev.tazik.inferno.engine

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/**
 * Вибрация гостя, которую чувствуешь на телефоне.
 *
 * Гость ведёт свой таптический движок так же, как играет звук: его сервер
 * звука рисует волну для актуатора, и ядро шлёт её в порт i2s, за которым
 * следит AOP. Библиотека эмулятора читает этот порт и сжимает её в кадры по
 * десять миллисекунд — насколько актуатор был ведом и на какой частоте, —
 * которые отдаёт `inferno_haptics_read`.
 *
 * В Swift это игралось через Core Haptics: пока гость ведёт актуатор,
 * играется одно непрерывное событие, и каждый кадр двигает его интенсивность
 * и резкость. На Android Core Haptics нет — есть `Vibrator`, который умеет
 * только амплитуду. Резкость (герцы) непредставима вовсе и оставлена гостю;
 * интенсивность идёт как есть: гость сам превращает интенсивность в
 * амплитуду, 6 дБ на каждое уменьшение вдвое, так что шкала совпадает.
 * Там, где амплитудного контроля нет, вибрация просто включается и гаснет.
 *
 * От гостя без звука ничего и не приходит: актуатор принадлежит звуковому
 * железу, которое машина описывает, только когда включён звук гостя.
 */
class HostHaptics private constructor() {

    companion object {
        val shared = HostHaptics()

        /** Есть ли на этом устройстве актуатор, на котором играть вибрацию гостя. */
        val isSupported: Boolean
            get() = shared.vibrator()?.hasVibrator() == true
    }

    private val lock = Any()

    /** Сдвигается каждым запуском и остановкой, чтобы читатель, оставшийся от
     * прежнего запуска — он может висеть в библиотеке ещё полсекунды, —
     * отпустил, а не делил кадры с преемником.
     */
    private var generation = 0

    private val following = AtomicBoolean(false)

    private fun vibrator(): Vibrator? {
        val context = try {
            EngineEnv.requireContext()
        } catch (_: Throwable) {
            return null
        }
        return try {
            if (Build.VERSION.SDK_INT >= 31) {
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
        } catch (_: Throwable) {
            null
        }
    }

    /** Начинает следить за актуатором гостя. Ничего не делает, если уже
     * следит, если машина не работает, если выключатель снят или если играть
     * не на чем.
     */
    fun start() {
        if (!following.compareAndSet(false, true)) return
        if (!Settings.guestHaptics || QemuBridge.shared.state.value != QemuBridge.State.Running) {
            following.set(false)
            return
        }
        // Устройство без актуатора — не то, о чём стоит писать строку в журнал
        // при каждом запуске.
        val vibrator = vibrator()
        if (vibrator == null || !vibrator.hasVibrator()) {
            following.set(false)
            return
        }
        if (!QemuNative.hasSymbol("inferno_haptics_read")) {
            following.set(false)
            LogCapture.note(L("Вибрация: в этой сборке библиотеки её нет."))
            return
        }
        val mine = synchronized(lock) {
            generation += 1
            generation
        }
        engineThread("inferno.haptics", 256L * 1024) { pump(mine) }
    }

    fun stop() {
        if (!following.getAndSet(false)) return
        synchronized(lock) { generation += 1 }
    }

    private fun isCurrent(mine: Int): Boolean = synchronized(lock) { generation == mine }

    private fun pump(mine: Int) {
        val capacity = 64
        // Два float на кадр — так, как InfernoHapticFrame лежит в
        // ui/inferno-embed.h.
        val frames = FloatArray(capacity * 2)
        val player = HapticPlayer(vibrator() ?: return)
        var lastFrame = System.nanoTime()

        while (isCurrent(mine)) {
            // Короткие ожидания, пока игрок держится, — чтобы заглушить и
            // остановить его вовремя; долгие в остальное время, которые не
            // стоят ничего.
            val count = QemuNative.hapticsRead(frames, capacity, if (player.isPlaying) 20 else 500)
            val now = System.nanoTime()
            if (count > 0) lastFrame = now
            for (index in 0 until count) {
                player.play(frames[index * 2], frames[index * 2 + 1], now)
            }
            // Поток, остановленный посреди вибрации, замыкающего кадра не шлёт;
            // говорит только тишина.
            if (count == 0 && now - lastFrame > 60_000_000) player.still(now)
            player.tick(now)
        }
        player.stop()
    }
}

/** `Vibrator`, ведомый кадр за кадром.
 *
 * Используется только с потока читателя. Логика пауз та же, что в Swift:
 * короткая вибрация всё равно играется не короче двадцати миллисекунд, а
 * игрока, замолчавшего на мгновение, гость может тут же оживить — поток
 * гостя спотыкается, когда его ядра отстают, и пауза в пару кадров — это
 * спотыкание, а не конец вибрации.
 */
private class HapticPlayer(private val vibrator: Vibrator) {

    /** Самая короткая вибрация, которую стоит играть. Касание в госте может
     * быть одним кадром, а десять миллисекунд вовсе не чувствуются.
     */
    private val shortestPulse = 20_000_000L

    /** Сколько держится замолчавший игрок, прежде чем его остановят. */
    private val hold = 250_000_000L

    /** Между сменами амплитуды — иначе перезапуски вибрации съедают её сами. */
    private val driveInterval = 20_000_000L

    private val hasAmplitudeControl = vibrator.hasAmplitudeControl()

    /** Игрок жив, пока гость ведёт актуатор и пауза ещё не переросла в тишину. */
    private var active = false
    private var driveAmplitude = 0
    private var roseAt = 0L
    private var silentAt: Long? = null
    private var silenced = false
    private var lastDriveAt = 0L
    private var reportedFirst = false

    val isPlaying: Boolean get() = active

    fun play(level: Float, hertz: Float, now: Long) {
        if (level <= 0f) return still(now)
        val fresh = !active || silenced
        if (!active) begin(level, hertz, now)
        if (!active) return
        if (fresh) roseAt = now
        silentAt = null
        silenced = false

        val wanted = amplitude(level)
        if (wanted != driveAmplitude && now - lastDriveAt >= driveInterval) {
            driveAmplitude = wanted
            lastDriveAt = now
            drive(wanted)
        }
    }

    /** Гость перестал вести актуатор. */
    fun still(now: Long) {
        if (!active || silentAt != null) return
        silentAt = max(now, roseAt + shortestPulse)
    }

    /** Глушит игрока, чей импульс кончился, и останавливает того, кто молчит
     * достаточно долго.
     */
    fun tick(now: Long) {
        val deadline = silentAt ?: return
        if (!active || now < deadline) return
        if (!silenced) {
            silenced = true
            try {
                vibrator.cancel()
            } catch (_: Throwable) {
            }
        }
        if (now >= deadline + hold) stop()
    }

    fun stop() {
        if (!active) return
        active = false
        driveAmplitude = 0
        silentAt = null
        silenced = false
        try {
            vibrator.cancel()
        } catch (_: Throwable) {
        }
    }

    private fun begin(level: Float, hertz: Float, now: Long) {
        active = true
        roseAt = now
        silentAt = null
        silenced = false
        driveAmplitude = amplitude(level)
        lastDriveAt = now
        drive(driveAmplitude)
        if (!reportedFirst) {
            reportedFirst = true
            LogCapture.note(
                L("Вибрация: гость включил актуатор — уровень %.2f, %.0f Гц", level.toDouble(), hertz.toDouble()),
            )
        }
    }

    /** Интенсивность гостя — это и есть амплитуда; 0..1 → 1..255. */
    private fun amplitude(level: Float): Int =
        (level.coerceIn(0f, 1f) * 255f).toInt().coerceIn(1, 255)

    /** Перезапускает повторяющуюся вибрацию с новой амплитудой.
     *
     * Герцы гостя здесь пропадают: у `Vibrator` нет резкости. Без
     * амплитудного контроля остаётся лишь включить и выключить.
     */
    private fun drive(wanted: Int) {
        val effect = if (hasAmplitudeControl) {
            VibrationEffect.createWaveform(longArrayOf(20), intArrayOf(wanted), 0)
        } else {
            VibrationEffect.createWaveform(longArrayOf(20), intArrayOf(VibrationEffect.DEFAULT_AMPLITUDE), 0)
        }
        try {
            vibrator.vibrate(effect)
        } catch (_: Throwable) {
        }
    }
}
