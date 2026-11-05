package dev.tazik.inferno.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.regex.Pattern

/**
 * Маленький терминал.
 *
 * Консоль гостя — настоящий терминал, и программы на нём ведут себя как на
 * терминале: `apt` перерисовывает строку прогресса через carriage return,
 * `neofetch` печатает логотип в восемнадцать строк и потом уводит курсор
 * наверх, чтобы вписать подробности сбоку. Выбросить escape-коды — оставить
 * слова, но испортить форму; поэтому они разбираются в сетку, которую экран
 * рисует.
 *
 * Восемьдесят колонок, потому что в это верит гость: `apt` обрезает строку
 * прогресса на семьдесят девятом знаке и стирает её рядом пробелов ровно такой
 * ширины.
 *
 * В SwiftUI-версии рядом жили ещё и рисовальщики (`render(font:)`,
 * `uiColor`, представления `UITextView`/`NSTextView`) — это view-тела, их
 * заменяет Compose-экран Tazik; здесь остаётся модель, а готовый текст отдаёт
 * `GuestScreen`.
 */
class TerminalEmulator(
    val columns: Int = WIDTH,
    private val scrollback: Int = 500,
) {

    class Cell(
        var ch: String = " ",
        /** Индекс цвета ANSI или -1 — собственный цвет терминала. */
        var fg: Int = -1,
        var bg: Int = -1,
        var bold: Boolean = false,
    ) {
        fun clear() {
            ch = " "
            fg = -1
            bg = -1
            bold = false
        }
    }

    private var lines = mutableListOf(mutableListOf<Cell>())
    val rows: List<List<Cell>> get() = lines

    private var row = 0
    private var col = 0

    /** Где начинается текущий экран. Его двигает только `ESC[2J`: терминал
     * с прокруткой выталкивает старый экран наверх, а не теряет его.
     */
    private var screenTop = 0
    private var savedRow = 0
    private var savedCol = 0

    private var fg = -1
    private var bg = -1
    private var bold = false

    private enum class State { GROUND, ESC, CSI, OSC, SWALLOW }
    private var state = State.GROUND
    private var params = StringBuilder()

    fun reset() {
        lines = mutableListOf(mutableListOf())
        row = 0; col = 0; screenTop = 0; savedRow = 0; savedCol = 0
        fg = -1; bg = -1; bold = false
        state = State.GROUND
        params = StringBuilder()
    }

    /** Кормится по кодовой точке за раз, а не по символу строки: в Swift
     * carriage return и line feed после него сливались в один графемный
     * кластер, и каждый carriage return в потоке, составленном из одних их,
     * терялся бы тихо. В Kotlin строка и так делится по кодовым точкам, но
     * привычка остаётся полезной: замещающие пары здесь по-прежнему одна
     * позиция сетки.
     */
    fun feed(text: String) {
        var index = 0
        while (index < text.length) {
            val ch = text.codePointAt(index)
            step(ch)
            index += Character.charCount(ch)
        }
        trim()
    }

    // MARK: - Разбор

    private fun step(ch: Int) {
        when (state) {
            State.SWALLOW -> state = State.GROUND

            State.GROUND -> when (ch) {
                0x1B -> state = State.ESC
                '\r'.code -> col = 0
                '\n'.code -> lineFeed()
                0x08 -> col = maxOf(col - 1, 0)
                '\t'.code -> col = minOf((col / 8 + 1) * 8, columns - 1)
                else -> {
                    // Всё прочее из управляющего диапазона здесь ничего не значит.
                    if (ch < 0x20 || ch == 0x7F) return
                    put(ch)
                }
            }

            State.ESC -> when (ch) {
                '['.code -> { params = StringBuilder(); state = State.CSI }
                ']'.code -> { params = StringBuilder(); state = State.OSC }
                '7'.code -> { savedRow = row; savedCol = col; state = State.GROUND }
                '8'.code -> { row = savedRow; col = savedCol; state = State.GROUND }
                'M'.code -> { row = maxOf(row - 1, 0); state = State.GROUND }
                'D'.code -> { lineFeed(); state = State.GROUND }
                'E'.code -> { lineFeed(); col = 0; state = State.GROUND }
                // Селектор кодировки несёт ещё один байт, который нам ничего
                // не говорит.
                '('.code, ')'.code, '*'.code, '+'.code, '#'.code, '%'.code -> state = State.SWALLOW
                else -> state = State.GROUND
            }

            State.CSI ->
                if (ch >= 0x40 && ch <= 0x7E) {
                    csi(ch.toChar())
                    state = State.GROUND
                } else {
                    params.appendCodePoint(ch)
                    if (params.length > 48) state = State.GROUND
                }

            State.OSC ->
                // Кончается на BEL или на ESC \ — где backslash ещё придётся
                // проглотить.
                if (ch == 0x07) state = State.GROUND
                else if (ch == 0x1B) state = State.SWALLOW
        }
    }

    private fun csi(final: Char) {
        val raw = params.toString()
        val privileged = raw.startsWith("?") || raw.startsWith(">") || raw.startsWith("=")
        val body = if (privileged) raw.drop(1) else raw
        val nums = body.split(";").map { it.toIntOrNull() ?: 0 }
        /** Отсутствующий или нулевой параметр значит один. */
        fun arg(i: Int): Int = if (i < nums.size && nums[i] > 0) nums[i] else 1
        val mode = nums.firstOrNull() ?: 0

        when (final) {
            'A' -> row = maxOf(row - arg(0), 0)
            'B' -> { row += arg(0); ensure(row) }
            'C' -> col = minOf(col + arg(0), columns - 1)
            'D' -> col = maxOf(col - arg(0), 0)
            'E' -> { row += arg(0); col = 0; ensure(row) }
            'F' -> { row = maxOf(row - arg(0), 0); col = 0 }
            'G', '`' -> col = minOf(arg(0) - 1, columns - 1)
            'H', 'f' -> {
                row = screenTop + (arg(0) - 1)
                col = minOf(if (nums.size > 1) maxOf(nums[1], 1) - 1 else 0, columns - 1)
                ensure(row)
            }
            'J' -> eraseDisplay(mode)
            'K' -> eraseLine(mode)
            'X' -> eraseCells(arg(0))
            'P' -> deleteCells(arg(0))
            '@' -> insertCells(arg(0))
            'm' -> if (!privileged) sgr(if (nums.isEmpty()) listOf(0) else nums)
            's' -> { savedRow = row; savedCol = col }
            'u' -> { row = savedRow; col = savedCol }
            else -> {}
        }
    }

    private fun sgr(nums: List<Int>) {
        var i = 0
        while (i < nums.size) {
            val n = nums[i]
            when (n) {
                0 -> { fg = -1; bg = -1; bold = false }
                1 -> bold = true
                22 -> bold = false
                in 30..37 -> fg = n - 30
                39 -> fg = -1
                in 40..47 -> bg = n - 40
                49 -> bg = -1
                in 90..97 -> fg = n - 90 + 8
                in 100..107 -> bg = n - 100 + 8
                38, 48 -> {
                    if (i + 2 < nums.size && nums[i + 1] == 5) {
                        val c = nums[i + 2] and 0xFF
                        if (n == 38) fg = c else bg = c
                        i += 2
                    } else if (i + 4 < nums.size && nums[i + 1] == 2) {
                        val c = cube(nums[i + 2], nums[i + 3], nums[i + 4])
                        if (n == 38) fg = c else bg = c
                        i += 4
                    }
                }
                else -> {}
            }
            i += 1
        }
    }

    // MARK: - Сетка

    private fun ensure(target: Int) {
        while (lines.size <= target) lines.add(mutableListOf())
    }

    private fun lineFeed() {
        row += 1
        ensure(row)
    }

    private fun put(ch: Int) {
        // Диакритика принадлежит букве перед ней, а не собственной ячейке.
        if (Character.getType(ch) == Character.NON_SPACING_MARK.toInt() && col > 0 &&
            row < lines.size && col - 1 < lines[row].size
        ) {
            lines[row][col - 1].ch += String(Character.toChars(ch))
            return
        }
        if (col >= columns) {
            col = 0
            lineFeed()
        }
        ensure(row)
        while (lines[row].size <= col) lines[row].add(Cell())
        lines[row][col] = Cell(String(Character.toChars(ch)), fg, bg, bold)
        col += 1
    }

    private fun eraseLine(mode: Int) {
        ensure(row)
        when (mode) {
            0 -> if (lines[row].size > col) lines[row].subList(col, lines[row].size).clear()
            1 -> for (i in 0 until minOf(col + 1, lines[row].size)) lines[row][i].clear()
            else -> lines[row] = mutableListOf()
        }
    }

    private fun eraseDisplay(mode: Int) {
        when (mode) {
            0 -> {
                eraseLine(0)
                for (i in row + 1 until lines.size) lines[i] = mutableListOf()
            }
            1 -> {
                eraseLine(1)
                if (screenTop < row) {
                    for (i in screenTop until row) lines[i] = mutableListOf()
                }
            }
            else -> {
                // `clear` — свежий экран под тем, что уже есть.
                screenTop = lines.size
                row = screenTop
                col = 0
                ensure(row)
            }
        }
    }

    private fun eraseCells(n: Int) {
        ensure(row)
        for (i in col until minOf(col + n, lines[row].size)) lines[row][i].clear()
    }

    private fun deleteCells(n: Int) {
        ensure(row)
        if (col >= lines[row].size) return
        lines[row].subList(col, minOf(col + n, lines[row].size)).clear()
    }

    private fun insertCells(n: Int) {
        ensure(row)
        while (lines[row].size < col) lines[row].add(Cell())
        lines[row].addAll(col, List(n) { Cell() })
    }

    /** Держит буфер в границах, двигая за ним каждый курсор. */
    private fun trim() {
        if (lines.size <= scrollback) return
        val drop = lines.size - scrollback
        repeat(drop) { lines.removeAt(0) }
        row = maxOf(row - drop, 0)
        screenTop = maxOf(screenTop - drop, 0)
        savedRow = maxOf(savedRow - drop, 0)
    }

    // MARK: - Рисование

    /** Строки для рисования, без концевых пробелов — нести их дорого множеством
     * атрибутных прогонов, а показываются они ничем.
     */
    fun visible(maxLines: Int): List<List<Cell>> =
        lines.subList(maxOf(0, lines.size - maxLines), lines.size)

    companion object {
        /** Во что верит гость, и против чего экран меряет свой шрифт. */
        const val WIDTH = 80

        /** Палитра xterm, числами, чтобы один и тот же файл собирался и для
         * стенда, и для телефона. Цвет 0 приподнят над чёрным: фон консоли и так
         * тёмный, и чистый чёрный попросту исчез бы. null — цвет терминала.
         */
        fun rgb(index: Int, bold: Boolean): Triple<Double, Double, Double>? {
            var i = index
            if (i < 0) return null
            if (bold && i < 8) i += 8

            return when (i) {
                0 -> Triple(0.42, 0.42, 0.42)
                1 -> Triple(0.80, 0.25, 0.25)
                2 -> Triple(0.35, 0.68, 0.35)
                3 -> Triple(0.76, 0.62, 0.18)
                4 -> Triple(0.31, 0.56, 0.90)
                5 -> Triple(0.72, 0.40, 0.80)
                6 -> Triple(0.20, 0.64, 0.62)
                7 -> Triple(0.72, 0.72, 0.72)
                8 -> Triple(0.52, 0.52, 0.52)
                9 -> Triple(1.00, 0.44, 0.42)
                10 -> Triple(0.44, 0.86, 0.47)
                11 -> Triple(0.93, 0.82, 0.35)
                12 -> Triple(0.50, 0.72, 1.00)
                13 -> Triple(0.88, 0.56, 0.92)
                14 -> Triple(0.42, 0.85, 0.85)
                15 -> Triple(0.95, 0.95, 0.95)
                in 16..231 -> {
                    val n = i - 16
                    val levels = doubleArrayOf(0.0, 0.373, 0.529, 0.686, 0.843, 1.0)
                    Triple(levels[n / 36], levels[(n / 6) % 6], levels[n % 6])
                }
                in 232..255 -> {
                    val v = (i - 232).toDouble() / 23 * 0.85 + 0.10
                    Triple(v, v, v)
                }
                else -> null
            }
        }

        /** Truecolor, отображённый в ближайшую запись куба — достаточно близко,
         * и каждый цвет остаётся одним маленьким числом.
         */
        private fun cube(r: Int, g: Int, b: Int): Int {
            fun level(v: Int): Int = v.coerceIn(0, 255) * 5 / 255
            return 16 + 36 * level(r) + 6 * level(g) + level(b)
        }
    }
}

/**
 * Вырезает из потока консоли собственную болтовню ядра.
 *
 * У гостя одна консоль на всё: ядро печатает в неё, пока туда же пишет шелл,
 * поэтому ответ приходит изорванным сообщениями драйверов — и не только между
 * строками. Сообщение может приземлиться посреди строки, сразу после escape:
 *
 *     ESC[0m static IOReturn AppleMobileFileIntegrityUserClient::isCdhash…
 *
 * так что строка рассматривается целиком и режется там, где сообщение
 * начинается, а на его место встаёт carriage return: ровно то, что делало
 * ядро своим же концом строки, и всё, что было на строке, переписывается
 * следующим.
 */
class KernelFilter {

    /** Отметка времени загрузки, `file.c:123:` и C++-имена методов, под которыми
     * пишут драйверы. Последнее может встать где угодно в строке, остальные —
     * только начать её. Каждая форма, в какой сообщение драйвера видели в
     * снятой консоли, и каждая может стоять где угодно в строке, не только
     * в начале.
     */
    private val anywhere: Pattern = Pattern.compile(
        listOf(
            // Метод C++ с каким ни попадя типом возврата перед ним:
            //   static IOReturn AppleMobileFileIntegrityUserClient::isCdhashInTrust…
            "(?:(?:static|virtual|inline)\\s+)?(?:[A-Za-z_][A-Za-z0-9_]*\\s+)?"
                + "[A-Za-z_][A-Za-z0-9_]*::[A-Za-z_~]",
            // Обычная сигнатура C, напечатанная целиком перед сообщением:
            //   int gl_rec_write(struct gl_ctx *const, …, size_t): Writing record…
            "(?:(?:static|virtual|inline|const|unsigned)\\s+)*[A-Za-z_][A-Za-z0-9_]*\\s+\\**"
                + "[A-Za-z_][A-Za-z0-9_]*\\([^)]{0,200}\\)\\s*(?:const\\s*)?:",
            // Позиция в исходнике: apfs.c:123: или tx_flush:1075:
            "\\b[A-Za-z_][A-Za-z0-9_]*(?:\\.[ch](?:pp)?)?:[0-9]+:",
            // Имена, под которыми пишут вещи.
            "\\b(?:AssertMacros|AIDLog|IOMFB|IOAccessory|ACMRM|ACMKernel|RTBuddy|SEP|NAND|ANS2?)\\s*:",
            "\\bApple[A-Z][A-Za-z0-9_]*\\s*:",
            "\\b(?:apfs_[a-z_]+|nx_[a-z_]+|spaceman_[a-z_]+|obj_[a-z_]+|tx_flush|handle_mount|dev_init)\\b",
            // syslog, когда launchd поднялся.
            "<(?:Notice|Error|Warning|Debug|Info|Critical)>:",
            "\\[effaceable",
        ).joinToString("|"),
    )

    /** Формы, стоящие только в начале строки: отметка времени загрузки и штамп
     * даты, который launchd ставит перед собственными заметками.
     */
    private val atStart: Pattern = Pattern.compile(
        listOf(
            "^[0-9]{6}\\.[0-9]{6} ",
            "^[A-Z][a-z]{2} [A-Z][a-z]{2} +[0-9]{1,2} [0-9]{2}:[0-9]{2}:[0-9]{2} [0-9]{4} ",
        ).joinToString("|"),
    )

    private val prefixes = listOf(
        "Apple", "IOAccessory", "IOMFB", "AIDLog", "ACMRM", "ACMKernel", "apfs_", "nx_",
        "spaceman_", "tx_flush", "handle_mount", "dev_init", "SEP ", "RTBuddy", "[effaceable",
        "AssertMacros", "AppleSEP", "PMU", "hfs:", "nvme",
    )

    /** Собираемая строка и то, сколько из неё уже ушло. Кодовые точки — по той
     * же причине, по которой их читает терминал.
     */
    private var pending = mutableListOf<Int>()
    private var emitted = 0

    /** Что `pending` держало в конце прошлого куска. Строка, не подросшая с тех
     * пор, — строка, которую гость перестал писать, то есть приглашение, — её
     * можно показать, не дожидаясь newline, которого может не быть никогда.
     */
    private var settled = -1

    fun reset() {
        pending = mutableListOf()
        emitted = 0
        settled = -1
    }

    fun process(chunk: String): String {
        val out = StringBuilder()
        var index = 0
        while (index < chunk.length) {
            val ch = chunk.codePointAt(index)
            index += Character.charCount(ch)
            pending.add(ch)
            if (ch == '\n'.code) {
                out.append(decide())
                pending = mutableListOf()
                emitted = 0
                settled = -1
            }
        }
        if (pending.isNotEmpty() && pending.size == settled && emitted < pending.size) {
            out.append(text(pending.subList(emitted, pending.size)))
            emitted = pending.size
        }
        settled = pending.size
        return out.toString()
    }

    /** Отпускает недописанную строку — когда проигрывается весь журнал и дальше
     * куска, с которым можно свериться, не будет.
     */
    fun flush(): String {
        if (emitted >= pending.size) return ""
        val rest = text(pending.subList(emitted, pending.size))
        emitted = pending.size
        return rest
    }

    /** Решает, что из закончившейся строки можно показать.
     *
     * То, чем замещается вырезанное сообщение, важнее, чем кажется. Конец строки
     * ядра правда сдвигал курсор вниз, так что сохранить строку — честно; но
     * `apt` перерисовывает одну строку прогресса сотни раз, и ядро перебивает
     * большинство из них, — остались бы сотни протухших `0% [Working]`. Поэтому
     * строка стирается и используется заново, кроме случая, когда она кончается
     * приглашением шелла: оно стоит собственной строки, будучи единственным
     * знаком, что шелл вообще есть.
     */
    private fun decide(): String {
        val cut = kernelStart(pending) ?: return text(pending.subList(emitted, pending.size))
        val end = maxOf(cut, emitted)
        if (end <= 0) return ""
        val ours = text(pending.subList(emitted, maxOf(emitted, cut)))
        return ours + if (endsInPrompt(pending.subList(0, end))) "\r\n" else "\r\u001B[K"
    }

    private fun endsInPrompt(scalars: List<Int>): Boolean {
        val tail = scalars.takeLast(2)
        if (tail.size != 2 || tail[1] != ' '.code) return false
        return tail[0] == '#'.code || tail[0] == '$'.code || tail[0] == '%'.code || tail[0] == '>'.code
    }

    private fun text(scalars: List<Int>): String {
        val out = StringBuilder()
        for (scalar in scalars) out.appendCodePoint(scalar)
        return out.toString()
    }

    /** Где в этой строке начинается сообщение ядра, как индекс в сырых символах,
     * или null, если строка — собственный вывод гостя.
     */
    private fun kernelStart(raw: List<Int>): Int? {
        val (plain, map) = strip(raw)
        if (plain.isEmpty()) return null
        val line = text(plain)

        for (prefix in prefixes) if (line.startsWith(prefix)) return 0

        val started = atStart.matcher(line)
        if (started.find()) return 0
        val matcher = anywhere.matcher(line)
        if (matcher.find()) {
            // Позиция совпадения дана в символах строки, а карта ведёт по
            // кодовым точкам исходной — пересчитать.
            val codePoints = line.codePointCount(0, matcher.start())
            return if (codePoints < map.size) map[codePoints] else null
        }
        return null
    }

    /** Escape-последовательности слов не несут, но каждую позицию сдвигают,
     * поэтому чистый текст держится рядом с картой обратно в оригинал.
     */
    private fun strip(raw: List<Int>): Pair<List<Int>, List<Int>> {
        fun isFinal(s: Int): Boolean = s >= 0x40 && s <= 0x7E
        val plain = mutableListOf<Int>()
        val map = mutableListOf<Int>()
        var i = 0
        while (i < raw.size) {
            if (raw[i] != 0x1B) {
                if (raw[i] != '\n'.code && raw[i] != '\r'.code) {
                    plain.add(raw[i])
                    map.add(i)
                }
                i += 1
                continue
            }
            var j = i + 1
            if (j < raw.size && raw[j] == '['.code) {
                j += 1
                while (j < raw.size && !isFinal(raw[j])) j += 1
                if (j < raw.size) j += 1
            } else if (j < raw.size && raw[j] == ']'.code) {
                while (j < raw.size && raw[j] != 0x07) j += 1
                if (j < raw.size) j += 1
            } else {
                j = minOf(j + 1, raw.size)
            }
            i = j
        }
        return plain to map
    }
}

/**
 * Что показывает панель консоли гостя: эмулятор, фильтр перед ним и готовый
 * текст для экрана.
 *
 * В Swift это был `ObservableObject`, публикующий `NSAttributedString` —
 * рисунок с цветами шрифтом терминала. Рисование — дело view, и его заменяет
 * Compose-экран Tazik; здесь публикуется plain-text той же сетки: строки
 * концевые пробелы сбрасывают, строки разделены `\n`. Сетке с цветами экран
 * при желании читает `term` напрямую.
 */
class GuestScreen {

    private val _content = MutableStateFlow("")
    val content: StateFlow<String> = _content.asStateFlow()

    /** Поднимается на всякое изменение — экрану следить за ним дешевле, чем за
     * текстом.
     */
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    val term = TerminalEmulator()
    private val filter = KernelFilter()
    private var hideKernel = true
    private var consumed = -1

    /** Проигрывает весь журнал — при первом появлении и всякий раз, когда
     * переключают фильтр ядра, раз от этого меняется то, как должна была
     * выглядеть каждая прошлая строка.
     */
    fun rebuild(from: String, hideKernel: Boolean, sequence: Int) {
        this.hideKernel = hideKernel
        term.reset()
        filter.reset()
        term.feed(if (hideKernel) filter.process(from) + filter.flush() else from)
        consumed = sequence
        publish()
    }

    /** Начинает заново — когда канал открывается с нуля. */
    fun reset() {
        term.reset()
        filter.reset()
        consumed = -1
        publish()
    }

    /** Текст от канала, которому не нужен фильтр и нечего проигрывать: всё, что
     * приходит, — то, что написал дальний конец.
     */
    fun append(text: String) {
        if (text.isEmpty()) return
        term.feed(text)
        publish()
    }

    /** Пустой кусок — не ничто: он говорит, что консоль замолчала, а это то,
     * что позволяет фильтру отпустить строку без newline на ней — внутри
     * `process`, по тому, что строка не подросла с прошлого куска.
     */
    fun feed(chunk: String, sequence: Int) {
        if (sequence <= consumed) return
        consumed = sequence
        val text = if (hideKernel) filter.process(chunk) else chunk
        if (text.isEmpty()) return
        term.feed(text)
        publish()
    }

    private fun publish() {
        _content.value = render()
        _revision.value += 1
    }

    private fun render(maxLines: Int = 240): String {
        val rows = term.visible(maxLines)
        val out = StringBuilder()
        for (rowCells in rows) {
            var line = rowCells
            while (line.isNotEmpty() && line.last().ch == " " && line.last().bg < 0) {
                line = line.subList(0, line.size - 1)
            }
            for (cell in line) out.append(cell.ch)
            out.append('\n')
        }
        return out.toString()
    }
}
