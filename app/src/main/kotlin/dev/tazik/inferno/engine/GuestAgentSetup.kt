package dev.tazik.inferno.engine

import java.io.File

/**
 * Заносит агента гостя в гостя и поднимает его.
 *
 * После установки `agent install` правит кэш launchd, чтобы агент стартовал на
 * каждой будущей загрузке, и заводит копию для этой загрузки, не дожидаясь
 * никого. Дальше приложение достаёт его через namespace.
 *
 * Ничего из этого не требуется. Когда нет чернового namespace, нет собранного
 * агента или установка не взялась, здесь возвращается «недоступно» и приложение
 * работает через консоль ровно как раньше.
 *
 * Всё здесь блокирует; звать не с главного потока.
 */
object GuestAgentSetup {

    /** Итог попытки. */
    sealed class Outcome {
        data class Installed(val agent: GuestAgent) : Outcome()

        /** `retry` — true, когда причина лишь в неготовности гостя (нет шелла
         * на консоли, передача потеряла байты), и false, когда пробовать в этой
         * сессии незачем (нет namespace, нет собранного агента).
         */
        data class Unavailable(val why: String, val retry: Boolean) : Outcome()
    }

    /** Собранный агент вместо лежащего в приложении. Ставит только стенд,
     * у которого нет bundle'а приложения; в приложении всегда null.
     */
    @Volatile
    var packedOverride: ByteArray? = null

    /** Агент из приложения, упакованный, либо null, когда сборка не подписала. */
    private val bundled: ByteArray?
        get() {
            packedOverride?.let { return it }
            val bundle = EngineEnv.guestToolsDir ?: return null
            return try {
                File(bundle, "agent.gz").takeIf { it.isFile }?.readBytes()
            } catch (_: Throwable) {
                null
            }
        }

    /** Возвращает живого клиента, установив агента, если нужно. */
    fun bringUp(serial: SerialConsole, note: (String) -> Unit): Outcome {
        // Уже там и отвечает — обычный случай после первой загрузки.
        GuestAgent.discover()?.let {
            note(L("Агент уже в госте."))
            return Outcome.Installed(it)
        }

        // Без чернового namespace нет и канала к агенту; приложение остаётся на
        // консоли. Отличено от «нет упакованного помощника», чтобы в логе было
        // видно, что именно. Ни то, ни другое не стоит пробовать снова.
        if (!VMConfig.transferImage.isFile) {
            return Outcome.Unavailable(L("нет namespace xfer — агент не используется"), retry = false)
        }
        val packed = bundled ?: run {
            return Outcome.Unavailable(L("агент не собран (нет ldid) — работаю через консоль"), retry = false)
        }

        // Первая установка въезжает по консоли, значит, на ней должен отвечать
        // шелл. В начале загрузки его ещё нет; вместо того чтобы просиживать
        // целую передачу по таймаутам по минуте, спрашивают один раз и откладывают.
        //
        // Спрашивают, а не выводят. `serial.interactive` говорит лишь, что сокет
        // соединён, — это не то же, что bash на том конце.
        val answers = serial.exclusive { GuestShell(serial).number("echo 1", timeout = 20.0) == 1L }
        if (!answers) {
            return Outcome.Unavailable(L("консоль гостя ещё не готова — попробую позже"), retry = true)
        }

        val sum = PosixChecksum()
        sum.update(packed)
        val remote = "${TransferNamespace.toolsDirectory}/agent-%08x"
            .format(sum.value.toLong() and 0xFFFF_FFFFL)

        val delivered = serial.exclusive {
            val shell = GuestShell(serial)
            // Бинарник несёт контрольную сумму в имени, поэтому уже занесённая
            // сборка находится, а не шлётся снова, — и никогда не перезаписывается,
            // на что ядро отвечает `Killed: 9`.
            if (shell.number("test -x $remote && echo 1 || echo 0") == 1L) {
                true
            } else {
                deliver(packed, remote, sum.value, shell, note)
            }
        }
        if (!delivered) {
            return Outcome.Unavailable(L("агента не удалось занести в гостя"), retry = true)
        }

        note(L("Ставлю агента (launchd)…"))
        val installed = serial.exclusive {
            val shell = GuestShell(serial)
            // Установка пишет на системный том, который после загрузки только
            // для чтения; то же перемонтирование, что у починки пакетов.
            shell.line("mount -uw /", timeout = 120.0)
            // С запасом: установка читает, правит и переписывает кэш launchd
            // на полтора мегабайта, а разбор его на эмулируемых ядрах не быстр.
            val said = shell.text("$remote install 2>&1 | tail -1", timeout = 300.0) ?: ""
            note(L("Агент: %@", said))
            said.contains("AGENT-INSTALL OK")
        }
        if (!installed) {
            return Outcome.Unavailable(L("agent install не удался"), retry = true)
        }

        // Установка завела обслуживающую копию; дать ей мгновение открыть
        // устройство, а затем достучаться через namespace.
        repeat(10) {
            GuestAgent.discover()?.let {
                note(L("Агент поднялся."))
                return Outcome.Installed(it)
            }
            Thread.sleep(1000)
        }
        return Outcome.Unavailable(L("агент установлен, но не отвечает по namespace"), retry = true)
    }

    // MARK: - Доставка по консоли

    /** Льёт упакованного агента в гостя base64, по куску за раз, проверяя каждый
     * прежде, чем идти дальше. True, когда весь файл на месте и его контрольная
     * сумма сошлась.
     */
    private fun deliver(
        packed: ByteArray,
        remote: String,
        crc: Int,
        shell: GuestShell,
        note: (String) -> Unit,
    ): Boolean {
        note(L("Заношу агента в гостя (один раз)…"))
        val directory = TransferNamespace.toolsDirectory
        val staging = "$directory/agent.b64"
        val text = java.util.Base64.getEncoder().encodeToString(packed)

        // Строки по 76: широкие на занятом госте перестают приходить целиком.
        // Шлются группами, так что потерянный байт стоит группы, а не файла;
        // счёт байт каждой группы сверяется, прежде чем идти к следующей.
        val lineWidth = 76
        val linesPerGroup = 48

        for (attempt in 0 until 3) {
            if (shell.line("mkdir -p $directory && : > $staging", timeout = 60.0) != 0L) continue
            var ok = true
            var expected = 0
            var start = 0
            while (start < text.length) {
                val group = StringBuilder()
                var lines = 0
                while (start < text.length && lines < linesPerGroup) {
                    val end = minOf(start + lineWidth, text.length)
                    group.append(text, start, end).append('\n')
                    expected += end - start
                    start = end
                    lines += 1
                }
                // `printf %s` одного аргумента без здесь-документов: никаких
                // игр с кавычками, а число newline — наше. Группа кончается
                // проверкой, что файл ровно такой длины, какой должен быть
                // к этому моменту.
                if (!writeGroup(group.toString(), staging, shell)) {
                    ok = false
                    break
                }
                if (shell.number("wc -c < $staging") != expected.toLong()) {
                    ok = false
                    break
                }
            }
            if (!ok) {
                shell.reset()
                if (attempt < 2) Thread.sleep(1000)
                continue
            }

            // GNU base64 хочет -d, BSD — -D; в образе GNU, но голый может быть
            // и другим. Контрольная сумма подтверждает каждый байт прежде, чем
            // что-то запускается.
            val decode = "{ base64 -d $staging 2>/dev/null || base64 -D -i $staging; }"
            val line = shell.text("$decode | cksum", timeout = 120.0)
            val got = line?.split(" ")?.firstOrNull()?.toLongOrNull()
            if (got == null || got != (crc.toLong() and 0xFFFF_FFFFL)) {
                note(L("Агент: контрольная сумма не сошлась, повтор %d", attempt + 1))
                shell.reset()
                if (attempt < 2) Thread.sleep(1000)
                continue
            }

            if (shell.line(
                    "$decode | gunzip -c > $remote && chmod +x $remote && rm -f $staging",
                    timeout = 120.0,
                ) != 0L
            ) {
                continue
            }
            return true
        }
        return false
    }

    /** Пишет одну группу строк base64, каждая отдельной короткой командой, чтобы
     * консоль никогда не держала многого нашего сразу.
     */
    private fun writeGroup(group: String, staging: String, shell: GuestShell): Boolean {
        for (line in group.split("\n")) {
            if (line.isEmpty()) continue
            if (shell.line("printf %s $line >> $staging", timeout = 60.0) != 0L) return false
        }
        return true
    }
}
