package dev.tazik.inferno.engine

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network as NetHandle
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.telephony.TelephonyManager
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Рисует сеть в строке состояния гостя.
 *
 * У машины нет ни модема, ни чипа Wi-Fi, поэтому сам по себе гость показывает
 * четыре серые точки и никакого Wi-Fi, как бы хорошо ни был подключён телефон.
 * Помощник `sbnet` из `guest-tools` вручает SpringBoard'у подмену строки
 * состояния — механизм, что за `simctl status_bar`, — и SpringBoard рисует из
 * неё. Что он может нарисовать и почему ему нужно то, что ему нужно, записано
 * в `netlab/sbnet.m`.
 *
 * Только картинка: приложения гостя по-прежнему не видят ни Wi-Fi, ни
 * оператора, и его трафик идёт по USB-связи.
 */
object GuestStatusBar {

    enum class Mode(val raw: String) {
        /** Строка состояния гостя остаётся такой, какой её сделал гость. */
        OFF("off"),
        /** Wi-Fi или связь, и какое поколение, — как у телефона. */
        PHONE("phone"),
        /** Как говорят настройки. */
        CUSTOM("custom");

        val title: String
            get() = when (this) {
                OFF -> L("Выкл.")
                PHONE -> L("Как на телефоне")
                CUSTOM -> L("Своё")
            }

        companion object {
            fun of(raw: String): Mode? = entries.firstOrNull { it.raw == raw }
        }
    }

    /** Метки, для которых у гостя есть картинки. Числа — собственные iOS 14;
     * 9–11 рисуют знак вопроса, а всё выше откатывается к 4G.
     */
    enum class Network(val raw: Int) {
        G(0), E(1), THREE_G(2), FOUR_G(3), LTE(4), ONE_X(7), FIVE_GE(8);

        val title: String
            get() = when (this) {
                G -> "G"
                E -> "E"
                THREE_G -> "3G"
                FOUR_G -> "4G"
                LTE -> "LTE"
                ONE_X -> "1x"
                FIVE_GE -> "5G E"
            }

        companion object {
            fun of(raw: Int): Network? = entries.firstOrNull { it.raw == raw }
        }
    }

    /** Одно полное состояние строки состояния. */
    data class Look(
        val cellularBars: Int,
        val network: Network,
        val carrier: String,
        val wifi: Boolean,
        val wifiBars: Int,
        /** Только рисуется ли слот вовсе. Его собственная половина панели —
         * деления, оператор, тип сети — нарочно не предлагается: поля
         * `secondary*` пробовали, а гость упорно рисовал слот «нет сети», что
         * бы они ни говорили, так что настройка для них обещала бы то, чего
         * дать не может.
         */
        val secondSIM: Boolean,
        val vpn: Boolean,
        val airplane: Boolean,
    ) {

        /** Командная строка помощника. Всегда с чистого листа, так что
         * нарисованное — ровно это, и ничего из прежнего.
         */
        val arguments: String
            get() {
                // Пункты 4, 6 и 9 — деления, служба и сеть передачи данных;
                // сотовая запись собирается из всех трёх. `serviceContentType` 1
                // означало бы «нет сети» и вернуло бы точки, поэтому прибито
                // к 0.
                val items = mutableListOf(4, 6, 9)
                if (secondSIM) items.add(7)
                if (vpn) items.add(29)
                if (airplane) items.add(3)
                val args = mutableListOf(
                    "-z", "-e", items.joinToString(","),
                    "-s", "0",
                    "-g", cellularBars.coerceIn(0, 4).toString(),
                    "-c", shellWord(carrier),
                )
                // Один слот показывает либо поколение, либо знак Wi-Fi: тип 5 —
                // Wi-Fi, и его сила имеет собственное поле.
                if (wifi) {
                    args += listOf("-n", "5", "-w", wifiBars.coerceIn(0, 3).toString())
                } else {
                    args += listOf("-n", network.raw.toString())
                }
                return args.joinToString(" ")
            }

        /** Тот же вид, как его хочет агент: простой объект, который сам
         * превращает его в структуру подмены. Без кавычек для шелла, потому
         * что ничего здесь шелла не касается.
         */
        val json: Map<String, Any?>
            get() = mapOf(
                "cellularBars" to cellularBars.coerceIn(0, 4),
                "network" to network.raw,
                "carrier" to carrier,
                "wifi" to wifi,
                "wifiBars" to wifiBars.coerceIn(0, 3),
                "secondSIM" to secondSIM,
                "vpn" to vpn,
                "airplane" to airplane,
            )

        companion object {
            /** Имя оператора, которое несёт консоль: простые знаки, в кавычках. */
            private fun shellWord(text: String): String {
                val allowed = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789 _-."
                val clean = text.filter { it.code < 128 && it in allowed }.take(40)
                return "'" + (clean.ifEmpty { "-" }) + "'"
            }
        }
    }

    /** О чём просят настройки прямо сейчас; null, когда гостя оставят в покое. */
    fun requested(settings: Settings = Settings, phone: PhoneNetwork = PhoneNetwork.shared): Look? {
        val mode = Mode.of(settings.statusBarMode) ?: Mode.OFF
        return when (mode) {
            Mode.OFF -> null
            Mode.PHONE -> {
                // Сила — не телефонная: iOS не даёт приложению уровня сигнала
                // вовсе, поэтому показан полный, как и та связь, которую он
                // изображает.
                Look(
                    cellularBars = 4,
                    network = phone.radio.value,
                    carrier = settings.statusBarCarrier,
                    wifi = phone.onWiFi.value,
                    wifiBars = 3,
                    secondSIM = false,
                    vpn = false,
                    airplane = false,
                )
            }
            Mode.CUSTOM -> Look(
                cellularBars = settings.statusBarBars,
                network = Network.of(settings.statusBarNetwork) ?: Network.LTE,
                carrier = settings.statusBarCarrier,
                wifi = settings.statusBarWifi,
                wifiBars = settings.statusBarWifiBars,
                secondSIM = settings.statusBarSecondSIM,
                vpn = settings.statusBarVPN,
                airplane = settings.statusBarAirplane,
            )
        }
    }

    enum class FailureKind { MISSING_HELPER, DELIVERY, SILENT }

    class Failure(message: String, val kind: FailureKind, val at: String = "") : Exception(message)

    /** Рисует `look`, а при null забирает подмену обратно. Блокирует и хочет
     * консоль себе: звать внутри `serial.exclusive` или `serial.ifFree`, не с
     * главного потока.
     *
     * Команда, которой не ответили, прерывается прежде, чем сдаться. Оставленная
     * в покое, она держит bash занятым, и всякий разговор после неё — панель
     * шелла, пакеты, эта — печатает в консоль, которую никто не читает.
     */
    fun apply(look: Look?, shell: GuestShell) {
        val helper = ensureHelper(shell)
        val arguments = look?.arguments ?: "-z"
        if (shell.line("$helper $arguments >/dev/null 2>&1", timeout = 60.0) == null) {
            shell.reset()
            throw Failure(L("консоль гостя не ответила"), FailureKind.SILENT)
        }
    }

    // MARK: - Помощник

    /** Кладёт помощника в гостя однажды, а после находит его уже там.
     *
     * По консоли, а не передачей файлов: той нужен USB-сетевой канал — ровно
     * то, что гость любит ронять, — а помощник упакован в три килобайта. Имя
     * несёт контрольную сумму, потому что ядро гостя убивает бинарник,
     * записанный по пути, который уже видел подписанным.
     */
    private fun ensureHelper(shell: GuestShell): String {
        val packed = File(EngineEnv.guestToolsDir, "sbnet.gz").takeIf { it.isFile }?.readBytes()
            ?: throw Failure(L("помощника строки состояния нет в приложении"), FailureKind.MISSING_HELPER)

        val sum = PosixChecksum()
        sum.update(packed)
        val directory = TransferNamespace.toolsDirectory
        val remote = "$directory/sbnet-%08x".format(sum.value.toLong() and 0xFFFF_FFFFL)
        when (shell.number("test -x $remote && echo 1 || echo 0")) {
            1L -> return remote
            0L -> {}
            // Ответа нет вовсе: лить в такое три килобайта — только сделать
            // хуже.
            else -> {
                shell.reset()
                throw Failure(L("консоль гостя не ответила"), FailureKind.SILENT)
            }
        }

        // Семьдесят шесть знаков на строку: более длинные на занятом госте
        // перестают доходить целиком, и каждая строка ждёт ответа перед
        // следующей.
        val staging = "$directory/sbnet.b64"
        if (shell.line("mkdir -p $directory && : > $staging", timeout = 60.0) != 0L) {
            throw Failure(L("помощник не доехал до гостя (%@)", "mkdir"), FailureKind.DELIVERY, "mkdir")
        }
        val text = Base64.encodeToString(packed, Base64.NO_WRAP).toCharArray()
        var start = 0
        while (start < text.size) {
            val piece = text.concatToString(start, minOf(start + 76, text.size))
            if (shell.line("printf %s $piece >> $staging", timeout = 60.0) != 0L) {
                throw Failure(L("помощник не доехал до гостя (%@)", "printf"), FailureKind.DELIVERY, "printf")
            }
            start += 76
        }

        // GNU base64 на этом образе, BSD на голом; контрольная сумма говорит,
        // долетел ли каждый байт, прежде чем что-то запускать.
        val decode = "{ base64 -d $staging 2>/dev/null || base64 -D -i $staging; }"
        val line = shell.text("$decode | cksum", timeout = 60.0)
        val crc = line?.trim()?.split(" ")?.firstOrNull()?.toLongOrNull()
        if (crc == null || crc != (sum.value.toLong() and 0xFFFF_FFFFL)) {
            throw Failure(L("помощник не доехал до гостя (%@)", "cksum"), FailureKind.DELIVERY, "cksum")
        }

        if (shell.line(
                "$decode | gunzip -c > $remote && chmod +x $remote && rm -f $staging",
                timeout = 60.0,
            ) != 0L
        ) {
            throw Failure(L("помощник не доехал до гостя (%@)", "gunzip"), FailureKind.DELIVERY, "gunzip")
        }
        return remote
    }
}

/** Собственная связь телефона, насколько о ней сказано приложению: Wi-Fi или
 * мобильная сеть, и поколение мобильной. Объект не с главного потока — state'ы
 * плывут из любого потока.
 */
class PhoneNetwork private constructor() {

    companion object {
        val shared = PhoneNetwork()
    }

    private val _onWiFi = MutableStateFlow(false)
    val onWiFi: StateFlow<Boolean> = _onWiFi.asStateFlow()

    private val _radio = MutableStateFlow(GuestStatusBar.Network.LTE)
    val radio: StateFlow<GuestStatusBar.Network> = _radio.asStateFlow()

    /** Зовётся при всякой перемене того или другого. */
    @Volatile
    var onChange: (() -> Unit)? = null

    private var registered = false

    /** В Swift тут был `NWPathMonitor`; на Android — `ConnectivityManager`. */
    fun start() {
        synchronized(this) {
            if (registered) return
            registered = true
        }
        try {
            val cm = EngineEnv.requireContext()
                .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.registerNetworkCallback(
                request,
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: NetHandle) = reevaluate()
                    override fun onLost(network: NetHandle) = reevaluate()
                    override fun onCapabilitiesChanged(network: NetHandle, caps: NetworkCapabilities) =
                        reevaluate()
                },
            )
        } catch (_: Throwable) {
        }
        reevaluate()
    }

    private fun reevaluate() {
        val wifi = try {
            val cm = EngineEnv.requireContext()
                .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.getNetworkCapabilities(cm.activeNetwork)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        } catch (_: Throwable) {
            false
        }
        update(wifi)
    }

    private fun update(wifi: Boolean) {
        val radio = generation()
        if (wifi == _onWiFi.value && radio == _radio.value) return
        _onWiFi.value = wifi
        _radio.value = radio
        onChange?.invoke()
    }

    /** Ближайшая метка, которую гость может нарисовать для технологии радио.
     *
     * `CTTelephonyNetworkInfo` здесь отвечает `TelephonyManager`; без права
     * READ_PHONE_STATE на новых Android он отказывает, и остаётся значение по
     * умолчанию — как на Mac, где у системы и вовсе нет радио.
     */
    @SuppressLint("MissingPermission")
    private fun generation(): GuestStatusBar.Network {
        val type = try {
            val tm = EngineEnv.requireContext()
                .getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            tm.dataNetworkType
        } catch (_: Throwable) {
            return GuestStatusBar.Network.LTE
        }
        return when (type) {
            TelephonyManager.NETWORK_TYPE_NR -> GuestStatusBar.Network.FIVE_GE
            TelephonyManager.NETWORK_TYPE_LTE,
            113, // NETWORK_TYPE_LTE_CA: константа убрана из новых SDK.
            -> GuestStatusBar.Network.LTE
            TelephonyManager.NETWORK_TYPE_UMTS,
            TelephonyManager.NETWORK_TYPE_HSDPA,
            TelephonyManager.NETWORK_TYPE_HSUPA,
            TelephonyManager.NETWORK_TYPE_HSPAP,
            TelephonyManager.NETWORK_TYPE_EVDO_0,
            TelephonyManager.NETWORK_TYPE_EVDO_A,
            TelephonyManager.NETWORK_TYPE_EVDO_B,
            TelephonyManager.NETWORK_TYPE_EHRPD,
            TelephonyManager.NETWORK_TYPE_TD_SCDMA,
            -> GuestStatusBar.Network.THREE_G
            TelephonyManager.NETWORK_TYPE_EDGE -> GuestStatusBar.Network.E
            TelephonyManager.NETWORK_TYPE_GPRS -> GuestStatusBar.Network.G
            TelephonyManager.NETWORK_TYPE_CDMA,
            TelephonyManager.NETWORK_TYPE_1xRTT,
            -> GuestStatusBar.Network.ONE_X
            else -> GuestStatusBar.Network.LTE
        }
    }
}
