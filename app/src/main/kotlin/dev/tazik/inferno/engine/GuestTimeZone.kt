package dev.tazik.inferno.engine

import java.util.TimeZone

/**
 * Даёт гостю часовой пояс телефона.
 *
 * Часы гостя верны — машина держит свой RTC в UTC, — но его пояс тот, с каким
 * образ был сделан: US/Pacific у образов по руководству ChefKiss'а, так что
 * время на его экране уходит от телефона на часы. iOS держит системный пояс
 * одной ссылкой, `/var/db/timezone/localtime`, показывающей в zoneinfo. Она
 * живёт на томе данных, так что смена не требует перемонтирования, переживает
 * перезагрузку, и докладывать некому: проверено на стенде, загруженный
 * SpringBoard показывает новый пояс со своей следующей минуты.
 */
object GuestTimeZone {

    const val link = "/var/db/timezone/localtime"

    /** Пояс телефона по имени, или null, когда имя не такое, которое строка
     * шелла может несть как есть.
     */
    val phone: String?
        get() {
            // Системный пояс кэшируется на процесс; смена пояса на телефоне
            // иначе осталась бы незамеченной до перезапуска приложения.
            TimeZone.setDefault(null)
            val name = TimeZone.getDefault().id
            // Уходит в команду шелла без кавычек, поэтому только то, из чего
            // составлены имена поясов: буквы, цифры, `/`, `_`, `+` и `-`.
            val allowed = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789/_+-"
            if (name.isEmpty() || name.startsWith("-") || name.startsWith("/") || name.contains("..")) {
                return null
            }
            if (name.any { it !in allowed }) return null
            return name
        }

    /** Одна строка для шелла гостя: направляет ссылку на пояс и печатает, куда
     * ссылка смотрит теперь, — или `NOZONE`, когда в zoneinfo этого гостя
     * такого пояса нет, чего может не быть у старого образа. Коротко, потому
     * что консоль теряет байты внутри длинных строк.
     */
    fun command(zone: String): String {
        val target = "/var/db/timezone/zoneinfo/$zone"
        return "if [ -e $target ]; then ln -sfn $target $link && readlink $link; else echo NOZONE; fi"
    }

    enum class Outcome { SET, MISSING, UNEXPECTED }

    /** Что значит ответ гостя на `command(for:)`. */
    fun outcome(answer: String, zone: String): Outcome {
        val said = answer.trim()
        if (said.endsWith("/zoneinfo/$zone")) return Outcome.SET
        if (said.endsWith("NOZONE")) return Outcome.MISSING
        return Outcome.UNEXPECTED
    }
}
