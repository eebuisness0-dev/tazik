package dev.tazik.inferno.engine

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * Показывает гостю собственную батарею телефона.
 *
 * SMC машины раньше отвечал «69 %, от батареи», что бы телефон ни делал:
 * цифра была вшита в эмулятор. Библиотека теперь берёт настоящую через
 * `inferno_battery_set`, говорит гостю, что состояние питания сменилось, и
 * батарейный драйвер гостя читает её за несколько секунд, а не по
 * собственному двадцатисекундному опросу.
 *
 * Библиотека хранит последнее сказанное вне машины, так что значение,
 * отправленное до подъёма машины, — то, с которым она загрузится, и
 * перезагрузка гостя его не теряет.
 *
 * В Swift заряд шёл пятёрками, потому что iOS больше приложению не даёт:
 * `UIDevice.batteryLevel` округлён до пяти, IOKit отвечал так же, а данные
 * строки состояния из чужого процесса не вытащить. На Android
 * `BatteryManager` отдаёт ёмкость как она есть — точнее, чем iPhone, — так
 * что округление здесь не нужно.
 */
class HostBattery private constructor() {

    data class Reading(val percent: Int, val external: Boolean, val charging: Boolean)

    companion object {
        val shared = HostBattery()
    }

    private val running = AtomicBoolean(false)
    private var reported: Reading? = null

    /** Начинает следить за батареей или, если уже следит, докладывает снова. */
    fun start() {
        if (!running.compareAndSet(false, true)) {
            report(force = true)
            return
        }
        if (!QemuNative.hasSymbol("inferno_battery_set")) {
            running.set(false)
            LogCapture.note(L("Батарея: в этой сборке библиотеки её нет, гость видит 69 %."))
            return
        }
        // Тридцать секунд — тот же темп, что у таймера Swift; уведомления
        // Android о смене состояния приходят и чаще, но читается здесь всё
        // равно всё сразу, так что опрос дешевле подписок.
        engineThread("inferno.battery", 128L * 1024) {
            while (running.get()) {
                report(force = false)
                try {
                    Thread.sleep(30_000)
                } catch (_: InterruptedException) {
                    return@engineThread
                }
            }
        }
        report(force = true)
    }

    private fun report(force: Boolean) {
        val reading = read() ?: return
        // Отправляется каждый раз — дёшево; только журнал ждёт перемены.
        QemuNative.batterySet(reading.percent, reading.external, reading.charging)
        val changed = reading != reported
        reported = reading
        if (!force && !changed) return
        val state = when {
            reading.charging -> L("заряжается")
            reading.external -> L("от сети")
            else -> L("от батареи")
        }
        LogCapture.note(L("Батарея гостя: %d %%, %@", reading.percent, state))
    }

    /** null, где батареи нет — симулятор отвечает ровно так, и гостю лучше
     * оставить его собственную цифру.
     */
    private fun read(): Reading? {
        val settings = Settings
        if (!settings.guestBatteryReal) {
            val charging = settings.guestBatteryCharging
            return Reading(settings.guestBatteryPercent.roundToInt(), charging, charging)
        }

        val context = EngineEnv.requireContext()
        val intent = try {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        } catch (_: Throwable) {
            null
        }
        val manager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager

        var percent = -1
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        if (level >= 0 && scale > 0) {
            percent = (level * 100.0 / scale).roundToInt()
        }
        if (percent < 0) {
            val capacity = manager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            if (capacity in 0..100) percent = capacity
        }
        if (percent < 0) return null

        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
            ?: BatteryManager.BATTERY_STATUS_UNKNOWN
        val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        if (status == BatteryManager.BATTERY_STATUS_UNKNOWN && plugged == 0) return null

        // Полный на кабеле всё ещё считается зарядкой. Настоящий iPhone держит
        // молнию на 100 %, а гость зажигает её от одного лишь CHSC, — «в сети,
        // не заряжается» гасило молнию ровно тогда, когда телефон был полон.
        val full = status == BatteryManager.BATTERY_STATUS_FULL
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || full
        val external = plugged != 0 || full
        return Reading(percent, external, charging)
    }
}
