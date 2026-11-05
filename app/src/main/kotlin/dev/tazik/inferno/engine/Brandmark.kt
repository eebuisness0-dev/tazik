package dev.tazik.inferno.engine

/**
 * Логотип, которого в наборе символов нет, — нарисованный, а не привезённый
 * картинкой.
 *
 * Растр пришлось бы возить в каталоге ресурсов; контур не требует ничего: он
 * в бинарнике, остаётся острым в любом размере и принимает любой цвет, какой
 * ему дадут.
 *
 * Контур хранится в том виде, в каком картинка опубликована, — данные пути
 * SVG, нормализованные в коробку 1×1, чтобы форму можно было попросить любого
 * размера. Встречаются только перемещения, линии, кубические кривые и
 * закрытия, и сканер ниже знает ровно это.
 *
 * В Swift это был SwiftUI-`Shape` с `path(in:)`; здесь разобранные команды
 * отдаёт `parse()`, а рисует их Compose-вид: масштаб — меньшая сторона
 * коробки, знак — в её центре.
 */
class Brandmark private constructor(val outline: String) {

    /** Одна команда разобранного контура, в координатах 1×1. */
    sealed class Command {
        data class Move(val x: Double, val y: Double) : Command()
        data class Line(val x: Double, val y: Double) : Command()
        data class Cubic(
            val x1: Double,
            val y1: Double,
            val x2: Double,
            val y2: Double,
            val x: Double,
            val y: Double,
        ) : Command()

        data object Close : Command()
    }

    /** Разбирает данные пути в команды.
     *
     * Знаки квадратные; коробке непропорциональной полагается знак по центру
     * неё, а не растянутый.
     */
    fun parse(): List<Command> {
        val path = ArrayList<Command>()
        var cursorX = 0.0          // где перо, в коробке 1×1
        var cursorY = 0.0
        var openedX = 0.0          // где начался текущий подпуть
        var openedY = 0.0

        /** Одна команда и числа за ней. Лишние числа повторяют команду — то,
         * чем данные заменяют переписывание буквы.
         */
        fun run(letter: Char, values: List<Double>) {
            val step = when (letter) {
                'M', 'm', 'L', 'l' -> 2
                'H', 'h', 'V', 'v' -> 1
                'C', 'c' -> 6
                'Z', 'z' -> {
                    path.add(Command.Close)
                    cursorX = openedX
                    cursorY = openedY
                    return
                }
                else -> return
            }

            var letter = letter
            var index = 0
            while (index + step <= values.size) {
                val value = values.subList(index, index + step)
                when (letter) {
                    'M', 'm' -> {
                        val relative = letter == 'm'
                        cursorX = if (relative) cursorX + value[0] else value[0]
                        cursorY = if (relative) cursorY + value[1] else value[1]
                        path.add(Command.Move(cursorX, cursorY))
                        openedX = cursorX
                        openedY = cursorY
                        // Перемещение с более чем одной парой рисует линии к
                        // остальным.
                        letter = if (letter == 'm') 'l' else 'L'
                    }
                    'L', 'l' -> {
                        val relative = letter == 'l'
                        cursorX = if (relative) cursorX + value[0] else value[0]
                        cursorY = if (relative) cursorY + value[1] else value[1]
                        path.add(Command.Line(cursorX, cursorY))
                    }
                    'H', 'h' -> {
                        cursorX = if (letter == 'h') cursorX + value[0] else value[0]
                        path.add(Command.Line(cursorX, cursorY))
                    }
                    'V', 'v' -> {
                        cursorY = if (letter == 'v') cursorY + value[0] else value[0]
                        path.add(Command.Line(cursorX, cursorY))
                    }
                    else -> {
                        val relative = letter == 'c'
                        fun at(x: Double, y: Double): Pair<Double, Double> =
                            if (relative) cursorX + x to cursorY + y else x to y
                        val (x1, y1) = at(value[0], value[1])
                        val (x2, y2) = at(value[2], value[3])
                        val (ex, ey) = at(value[4], value[5])
                        path.add(Command.Cubic(x1, y1, x2, y2, ex, ey))
                        cursorX = ex
                        cursorY = ey
                    }
                }
                index += step
            }
        }

        var letter: Char? = null
        var values = ArrayList<Double>()
        for (token in scan(outline)) {
            when (token) {
                is Token.Number -> values.add(token.value)
                is Token.Command -> {
                    letter?.let { run(it, values) }
                    letter = token.letter
                    values = ArrayList()
                }
            }
        }
        letter?.let { run(it, values) }
        return path
    }

    private sealed class Token {
        data class Command(val letter: Char) : Token()
        data class Number(val value: Double) : Token()
    }

    companion object {

        /** Знак GitHub, из трафарета, который возит сама Apple, — полужирное
         * начертание, то самое, в каком стоят кнопки вокруг него.
         */
        val github = Brandmark(
            outline = """
                M0.5 0.0082C0.2235 0.0082 0 0.2327 0 0.5104C0 0.7324 0.1432 0.9203 0.3419
                0.9868C0.3667 0.9918 0.3758 0.976 0.3758 0.9627C0.3758 0.9511 0.375 0.9112 0.375
                0.8696C0.2359 0.8995 0.207 0.8097 0.207 0.8097C0.1846 0.7515 0.1515 0.7366 0.1515
                0.7366C0.106 0.7058 0.1548 0.7058 0.1548 0.7058C0.2053 0.7091 0.2318 0.7573 0.2318
                0.7573C0.2765 0.8338 0.3485 0.8122 0.3775 0.7989C0.3816 0.7665 0.3949 0.744 0.4089
                0.7316C0.298 0.7199 0.1813 0.6767 0.1813 0.4838C0.1813 0.4289 0.2012 0.384 0.2326
                0.3491C0.2277 0.3366 0.2103 0.2851 0.2376 0.216C0.2376 0.216 0.2798 0.2027 0.375
                0.2676C0.4158 0.2566 0.4578 0.251 0.5 0.251C0.5422 0.251 0.5853 0.2568 0.625
                0.2676C0.7202 0.2027 0.7624 0.216 0.7624 0.216C0.7898 0.2851 0.7724 0.3366 0.7674
                0.3491C0.7997 0.384 0.8187 0.4289 0.8187 0.4838C0.8187 0.6767 0.702 0.7191 0.5902
                0.7316C0.6085 0.7474 0.6242 0.7773 0.6242 0.8247C0.6242 0.892 0.6234 0.9461 0.6234
                0.9627C0.6234 0.976 0.6325 0.9918 0.6573 0.9868C0.856 0.9203 0.9992 0.7324 0.9992
                0.5104C1 0.2327 0.7757 0.0082 0.5 0.0082Z
            """.trimIndent(),
        )

        /** Знак X. Щель сквозь заднюю ногу — часть логотипа, а не ошибка
         * обводки: знак на x.com имеет её тоже. Это дыра, так что направление
         * обхода того подпути должно остаться, как есть.
         */
        val x = Brandmark(
            outline = "M0.7876 0.048L0.9409 0.048L0.6059 0.4309L1 0.952L0.6914 0.952L0.4497 0.636L0.1732 0.952L0.0197 0.952L0.3781 0.5424L0 0.048L0.3164 0.048L0.5349 0.3369ZM0.7337 0.8602L0.8187 0.8602L0.2702 0.135L0.1791 0.135Z",
        )

        /** Режет данные пути на буквы и числа.
         *
         * Не разрез по пробелам: грамматика позволяет числам идти вплотную,
         * где минус или вторая точка — всё, что начинает следующее.
         */
        private fun scan(data: String): List<Token> {
            val tokens = ArrayList<Token>()
            var number = StringBuilder()

            fun finish() {
                if (number.isNotEmpty()) {
                    number.toString().toDoubleOrNull()?.let { tokens.add(Token.Number(it)) }
                }
                number = StringBuilder()
            }

            for (character in data) {
                when (character) {
                    in '0'..'9' -> number.append(character)
                    '.' -> {
                        if (number.contains('.')) finish()
                        number.append(character)
                    }
                    '-', '+' -> {
                        // Знак принадлежит показателю, за которым следует, и
                        // начинает новое число везде, где это не так.
                        if (number.endsWith("e") || number.endsWith("E")) {
                            number.append(character)
                        } else {
                            finish()
                            number.append(character)
                        }
                    }
                    'e', 'E' -> if (number.isNotEmpty()) number.append(character)
                    in 'a'..'z', in 'A'..'Z' -> {
                        finish()
                        tokens.add(Token.Command(character))
                    }
                    else -> finish()
                }
            }
            finish()
            return tokens
        }
    }
}
