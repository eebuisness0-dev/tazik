package dev.tazik.inferno.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * Всё, что пользователь может поменять без пересборки.
 *
 * Это ровно те ручки, которые важны, когда что-то отказывается грузиться:
 * сколько ядер, сколько памяти, какого размера буфер транслятора. Умение
 * бисектить их на устройстве экономит круг сборки на каждую догадку. Живут
 * здесь и ручки подачи картинки, чтобы один экран держал всё, и не пришлось
 * охотиться за остальным по панелям.
 *
 * В Swift это был `ObservableObject` с `@AppStorage`; здесь — объект, чьи
 * свойства лежат в `Prefs`, а `revision` плывёт при каждом изменении — то,
 * чем там был `objectWillChange`.
 */
object Settings {

    private val revisionFlow = MutableStateFlow(0)

    /** Плывёт при каждом изменении любой ручки. */
    val revision: StateFlow<Int> get() = revisionFlow.asStateFlow()

    private fun touch() {
        revisionFlow.value = revisionFlow.value + 1
    }

    private class Flag(key: String, fallback: Boolean) : Pref<Boolean>(key, fallback)
    private class Count(key: String, fallback: Int) : Pref<Int>(key, fallback)
    private class Real(key: String, fallback: Double) : Pref<Double>(key, fallback)
    private class Text(key: String, fallback: String) : Pref<String>(key, fallback)

    private open class Pref<T>(
        private val key: String,
        private val fallback: T,
    ) : ReadWriteProperty<Any?, T> {
        @Suppress("UNCHECKED_CAST")
        override fun getValue(thisRef: Any?, property: KProperty<*>): T =
            when (fallback) {
                is Boolean -> Prefs.flag(key, fallback) as T
                is Int -> Prefs.number(key, fallback.toLong()).toInt() as T
                is Double -> Prefs.real(key, fallback) as T
                is String -> Prefs.string(key, fallback) as T
                else -> fallback
            }

        override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
            when (value) {
                is Boolean -> Prefs.setFlag(key, value)
                is Int -> Prefs.setNumber(key, value.toLong())
                is Double -> Prefs.setReal(key, value)
                is String -> Prefs.setString(key, value)
                else -> {}
            }
            touch()
        }
    }

    // Машина
    var cores by Count("cores", 4)
    /** Ниже четырёх ничего: одно ядро уходит SEP'у, а когда рядом с ним остаётся
     * меньше трёх, SEP паникует на инициализации своего хранилища ключей, и
     * гость не грузится вовсе. 2 и 3 когда-то предлагались и только подлавливали
     * людей.
     */
    val coreChoices = listOf(4, 5, 7)
    var memory by Text("memory", "3G")
    /** Нарочно оставлено посреди диапазона. Больше — быстрее: на телефоне
     * измерено, 64 МБ давали 8–11 кадров в секунду, 256 — 21–25, — но этот
     * буфер делит три гигабайта процесса с собственной памятью гостя, и
     * значение по умолчанию, выигрывающее кадры ценой предела памяти, не
     * значение по умолчанию. Поднять — в один тап, в Параметры → Транслятор.
     * Самый большой буфер, про который известно, что работает: с 512 МБ два
     * чужих телефона остановились на первой же инструкции эмулятора, а 256
     * обоих завели; бо́льшие размеры остаются в списке, за предупреждением.
     */
    val safeTBSize = 256
    var tbSize by Count("tbSize", 128)
    /** Ядра телефона вместо транслятора, когда ядро позволяет. Включено по
     * умолчанию: где недоступно, ничего не меняет, а переключатель существует,
     * чтобы сравнивать. На Android недоступно всегда.
     */
    var virtualization by Flag("virtualization", true)
    /** Просит ли ядра гостя у телефона быстрые ядра. */
    var vcpuPriority by Flag("vcpuPriority", true)

    // Картинка
    var headless by Flag("headless", false)
    var builtInDisplay by Flag("builtInDisplay", true)
    var panel by Text("panel", GuestPanel.IPHONE11.raw)
    /** Доходит ли звук гостя до динамика телефона. Выключено по умолчанию:
     * сэмплы готовят те же эмулируемые ядра, что рисуют экран.
     */
    var guestAudio by Flag("guestAudio", false)
    /** Играется ли вибрация гостя на телефоне. Включено по умолчанию: без
     * звука гостя нечему следовать, а где у устройства нет актуатора, ничего
     * вовсе не заводится.
     */
    var guestHaptics by Flag("guestHaptics", true)
    var autoRepairPackages by Flag("autoRepairPackages", true)
    var smoothUpscale by Flag("smoothUpscale", true)
    var roundedScreen by Flag("roundedScreen", true)
    var showFPS by Flag("showFPS", false)

    // Терминал
    var hideKernel by Flag("hideKernel", true)
    var terminalFollow by Flag("terminalFollow", true)

    // Что гостю рассказывается о батарее
    var guestBatteryReal by Flag("guestBatteryReal", true)
    var guestBatteryPercent by Real("guestBatteryPercent", 69.0)
    var guestBatteryCharging by Flag("guestBatteryCharging", false)

    // Часовой пояс гостя
    var guestTimeZone by Flag("guestTimeZone", true)

    // Что сделано показать в строке состояния гостя
    var statusBarMode by Text("statusBarMode", GuestStatusBar.Mode.PHONE.raw)
    var statusBarCarrier by Text("statusBarCarrier", "vm_operator")
    var statusBarBars by Count("statusBarBars", 4)
    var statusBarNetwork by Count("statusBarNetwork", GuestStatusBar.Network.LTE.raw)
    var statusBarWifi by Flag("statusBarWifi", true)
    var statusBarWifiBars by Count("statusBarWifiBars", 3)
    var statusBarSecondSIM by Flag("statusBarSecondSIM", false)
    var statusBarVPN by Flag("statusBarVPN", false)
    var statusBarAirplane by Flag("statusBarAirplane", false)

    // Связь
    /** На Android `apple-ncm-host` делит loopback-порт с `GuestUSB`, поэтому
     * по умолчанию выключена — как в `VMConfig`: сначала поднимается USB-хост
     * восстановления, а сеть включает экран настроек, когда порт свободен.
     */
    var network by Flag("network", false)
    var netAutoFix by Flag("netAutoFix", true)
    var usbExport by Flag("usbExport", false)
    /** Взять устройство может кто угодно в сети, так что не loopback: смысл —
     * Mac в другом месте. Порт — собственный умолчальный порт эмулятора.
     */
    var usbExportAddress by Text("usbExportAddress", "0.0.0.0:8030")

    /** Язык приложения. Пишется строкой кода; попутно ведёт `L10n`. */
    var language: String
        get() = Prefs.string("language", AppLanguage.SYSTEM.code)
        set(value) {
            Prefs.setString("language", value)
            L10n.language = AppLanguage.of(value)
            touch()
        }

    /** Что эмулятору сказано через окружение. Пусто означает старое поведение
     * в обоих случаях, так что незнакомая сборка ведёт себя как всегда.
     */
    val emulatorEnvironment: Map<String, String>
        get() {
            val env = linkedMapOf<String, String>()
            if (vcpuPriority) env["INFERNO_VCPU_QOS"] = "interactive"
            // Звуковое железо описывается гостю только когда это задано: драйверы
            // за теми узлами device tree стоят времени загрузки и простаивающего
            // процессора, так что машина, запущенная без звука, не несёт их вовсе.
            if (guestAudio) env["INFERNO_AUDIO"] = "1"
            return env
        }

    val config: VMConfig
        get() {
            val c = VMConfig()
            c.cores = cores
            c.memory = memory
            c.tbSize = tbSize
            // HVF на Android нет: транслятор всегда TCG, что бы ни говорила
            // ручка.
            c.virtualization = false
            c.network = network
            c.usbExport = if (usbExport) usbExportAddress else null
            c.headless = headless
            c.builtInDisplay = builtInDisplay
            c.audio = guestAudio
            val pixels = (GuestPanel.of(panel) ?: GuestPanel.IPHONE11).pixels
            c.displayWidth = pixels.width
            c.displayHeight = pixels.height
            c.displayScale = pixels.scale
            return c
        }

    init {
        // Счёт, сохранённый, пока предлагались ещё и 2 с 3, иначе дошёл бы до
        // командной строки и оставил выбор без отмеченного пункта.
        if (cores < coreChoices[0]) cores = coreChoices[0]
        L10n.language = AppLanguage.of(language)
    }
}

/** Уголок экрана iPhone 11.
 *
 * 41,5 pt на экране шириной 414 pt — почти ровно десятая часть ширины.
 * Держится той самой дробью, а не точками, чтобы углы гостя оставались
 * правильными при любом размере его картинки, и остались правильными, если
 * машине когда-нибудь дадут другую панель.
 */
object GuestBezel {
    val radiusOverWidth: Double = 41.5 / 414
}

/** Панель, которую машина показывает гостю.
 *
 * Пиксели — то, за что платят эмулируемые ядра. GPU в госте нет, поэтому iOS
 * собирает каждый кадр программно на тех же ядрах, а затем те же пиксели
 * вычитываются из памяти машины и несутся на экран. Меньшая панель — меньше
 * всего этого.
 *
 * Масштаб всюду остаётся два. Соблазн уронить его до единицы и взять в четыре
 * раза меньше пикселей обманчив: iOS перестаёт рисовать Retina, откатывается
 * к картинке @1x, которой современный iOS почти не возит, и интерфейс выходит
 * не маленьким, а неправильным. Меньшая панель при масштабе два — меньший
 * телефон, нарисованный ровно так же остро.
 * Всякая ширина здесь кратна четырём, чтобы строка кадра была кратна
 * шестнадцати байтам. Это не предпочтение: при 750 пикселях ширины гость
 * вовсе не заканчивает загрузку.
 */
enum class GuestPanel(val raw: String) {
    IPHONE11("iphone11"),
    IPHONE8("iphone8"),
    IPHONESE("iphoneSE");

    data class Pixels(val width: Int, val height: Int, val scale: Int)

    val pixels: Pixels
        get() = when (this) {
            IPHONE11 -> Pixels(828, 1792, 2)
            // Не собственные 750×1334 iPhone 8: строка кадра должна быть кратна
            // шестнадцати байтам, а 750×4 — это 3000, что не так. На два пикселя
            // шире — и строка 3008, что кратна. Иначе гость виснет при загрузке —
            // машина клинит, и главный цикл не получает перерисовки вовсе.
            IPHONE8 -> Pixels(752, 1336, 2)
            IPHONESE -> Pixels(640, 1136, 2)
        }

    val title: String
        get() = when (this) {
            IPHONE11 -> L("iPhone 11")
            IPHONE8 -> L("iPhone 8")
            IPHONESE -> L("iPhone SE")
        }

    /** Что это стоит — для строки под выбором. */
    val detail: String
        get() {
            val p = pixels
            return L("%d×%d, точек %d×%d", p.width, p.height, p.width / p.scale, p.height / p.scale)
        }

    companion object {
        fun of(raw: String): GuestPanel? = entries.firstOrNull { it.raw == raw }
    }
}
