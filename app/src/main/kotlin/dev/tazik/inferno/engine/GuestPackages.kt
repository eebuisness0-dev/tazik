package dev.tazik.inferno.engine

import java.io.File

/**
 * Ставит пакетный менеджер гостя на ноги.
 *
 * Cydia и apt срываются на каждой операции с `Sub-process
 * /usr/libexec/cydia/cydo returned an error code (2)`, и ставящийся пакет тут
 * ни при чём — простое обновление срывается так же. На восстановленном образе
 * сломано три вещи сразу, и каждая молчит поодиночке:
 *
 * - бутстрап держит свою базу в `/Library/dpkg`, а dpkg и apt ищут
 *   `/var/lib/dpkg`; `/var` — отдельный том данных, в который бутстрап так и
 *   не распаковался, так что этого пути вовсе нет;
 * - корневой том смонтирован только на чтение, а пакеты пишут в `/usr` и
 *   `/Library`, так что одной ссылки мало;
 * - собственные пакеты бутстрапа были распакованы из архива и не настроены —
 *   джейлбрейк делает это при первой загрузке, а у этого образа первой
 *   загрузки не было.
 *
 * Перемонтирование — единственная часть, не переживающая перезагрузку гостя,
 * оттого это написано запускать заново столько раз, сколько нужно: каждый шаг
 * идемпотентен.
 *
 * И состояние dpkg было только половиной дела. Cydia — приложение
 * пользователя: корня оно просит через `/usr/libexec/cydia/cydo`, помощник с
 * setuid. В этом госте setuid нет ни у чего — всякий том смонтирован
 * `nosuid`, корень тоже после перемонтирования, — а
 * `/usr/lib/libjailbreak.dylib`, которым настоящий джейлбрейк раздаёт корень
 * вместо него, в этом образе нет. Так что cydo запускал dpkg от `mobile`,
 * dpkg говорил `requested operation requires superuser privilege` и выходил
 * с кодом 2 — тем самым, что показывал Cydia. Измерено в госте: та же
 * команда — 0 от root и 2 от mobile.
 *
 * Вместо недостающей библиотеки стоит маленький корневой скрипт, который
 * launchd запускает, когда в каталоге-очереди появляется запрос, и замена
 * cydo, что кладёт аргументы туда и проигрывает то, что вышло. Подлинник
 * сохраняется как `cydo.real`.
 *
 * Всё здесь блокирует; звать не с главного потока.
 */
object GuestPackages {

    enum class FailureKind { NO_SHELL, STEP, SILENT, STILL_READ_ONLY, CRASHED }

    class Failure(
        message: String,
        val kind: FailureKind,
        val what: String = "",
        val code: Long = 0,
    ) : Exception(message)

    /** Где гость держит то, что напечатали долгие шаги, чтобы провал можно
     * было прочесть здесь, а не угадывать по коду выхода. Cydia показывает
     * только `cydo returned an error code (2)`, и та двойка — от самого dpkg:
     * cydo отвечает на отказ числом 77, — так что слова dpkg и есть то, что
     * стоит иметь.
     */
    private const val log = "/var/mobile/.inferno/packages.log"

    private class Step(
        val title: String,
        val command: String,
        val timeout: Double,
        /** Останавливает ли ненулевой статус всё. Два долгих шага в конце
         * докладывают, а не останавливают: на образе, уже наполовину
         * починенном, они умеют ворчать и всё же оставлять dpkg работающим, и
         * решает проверка в конце.
         */
        val fatal: Boolean,
    )

    private const val queue = "/var/tmp/inferno-cydo"

    /** Запускает dpkg от root по чужой просьбе.
     *
     * Обычный фоновый процесс, а не задача launchd: launchd на этом образе
     * берёт своих демонов из кэша (`launchd_unsecure_cache=1`), так что плист,
     * положенный в /Library/LaunchDaemons, живёт лишь до перезагрузки гостя —
     * а помощник, тихо исчезнувший, хуже отсутствующего: Cydia тогда ждёт его,
     * пока сторожевой таймер не убьёт саму Cydia.
     */
    private val rootScript = listOf(
        "#!/bin/bash",
        "# Runs dpkg as root on behalf of Cydia. Started by Inferno, because",
        "# nothing in this guest is setuid and cydo cannot elevate itself.",
        "set -u",
        "queue=$queue",
        "mkdir -p \"\\\$queue\"",
        "chmod 777 \"\\\$queue\"",
        // Кто был здесь прежде, отходит в сторону. Два помощника на одной
        // очереди не смертельны — они наперегонки хватают запросы и отвечают
        // оба, — но гость небыстрый, и каждый лишний чего-то ему стоит.
        "old=\$(cat \"\\\$queue/pid\" 2>/dev/null || echo 0)",
        "[ \"\\\$old\" != 0 ] && [ \"\\\$old\" != \\\$\\\$ ] && kill -9 \"\\\$old\" 2>/dev/null",
        "echo \\\$\\\$ > \"\\\$queue/pid\"",
        "trap \"rm -f \\\$queue/pid\" EXIT",
        // Разбуженный, а не опрашивающий. Цикл bash, просыпающийся трижды в
        // секунду, на телефоне не стоит ничего, а в госте такой медлительности
        // стоит много — каждое пробуждение это инструкции, которые кто-то
        // должен перевести, и оно отбирало полосу у собственной работы гостя.
        "[ -p \"\\\$queue/wake\" ] || { rm -f \"\\\$queue/wake\"; mkfifo -m 666 \"\\\$queue/wake\"; }",
        "while true; do",
        "    for req in \"\\\$queue\"/*.req; do",
        "        [ -e \"\\\$req\" ] || continue",
        "        id=\"\${req%.req}\"",
        "        mapfile -t args < \"\\\$req\"",
        "        rm -f \"\\\$req\"",
        "        printf %s\\\\n \"--- \\\$id\" \"\${args[@]}\" >> \"\\\$queue/args.log\"",
        // apt вручает dpkg открытый дескриптор файла, чтобы докладывать о
        // ходе, и тот не переходит в этот процесс. dpkg направлен на файл
        // вместо этого, который клиент выливает в собственный дескриптор apt —
        // иначе apt не засчитывает ничего сделанным и говорит об этом:
        // `planned for dpkg to do more than it reported back (0 vs 5)`.
        "        keep=()",
        "        skip=0",
        "        for a in \"\${args[@]}\"; do",
        "            if [ \\\$skip = 1 ]; then skip=0; continue; fi",
        "            case \"\\\$a\" in",
        "                --status-fd) skip=1; keep+=(--status-fd 9); continue;;",
        "                --status-fd=*) keep+=(--status-fd 9); continue;;",
        "                --log-fd) skip=1; continue;;",
        "                --log-fd=*) continue;;",
        "            esac",
        "            keep+=(\"\\\$a\")",
        "        done",
        // cydo ведает не только dpkg. Cydia вручает ему программу, которую
        // надо запустить от root — /bin/rm, /bin/ln, /bin/cp, setnsfpn,
        // firmware.sh, — и передаёт голые опции, только когда имеет в виду сам
        // dpkg. Запускать dpkg в обоих случаях — вот как `/bin/rm -f …`
        // превратилось в `dpkg -f …` и ответило `need an action option`.
        "        case \"\${keep[0]-}\" in",
        // firmware.sh идёт здесь десятки секунд, а Cydia блокирует на этом
        // вызове свой главный поток, что в госте такой медлительности
        // достаточно, чтобы iOS убила Cydia как неотзывчивую. Кнопка починки
        // запускает его по-настоящему; отсюда он отпускается и получает ответ
        // немедленно.
        "            */firmware.sh)",
        "                (nohup \"\${keep[@]}\" >/dev/null 2>&1 &)",
        "                : > \"\\\$id.out\"",
        "                ;;",
        "            /*) \"\${keep[@]}\" > \"\\\$id.out\" 2>&1;;",
        "            *)  /usr/bin/dpkg \"\${keep[@]}\" > \"\\\$id.out\" 2>&1 9> \"\\\$id.status\";;",
        "        esac",
        "        echo \\\$? > \"\\\$id.rc\"",
        "    done",
        // Таймаут — страховочная сеть, а не расписание: если пробуждение
        // когда-нибудь пропустят, очередь всё равно просматривается раз в
        // минуту.
        "    read -r -t 60 _ < \"\\\$queue/wake\" || true",
        "done",
    )

    /** Занимает место cydo: те же аргументы, тот же вывод, тот же статус
     * выхода.
     *
     * Отказывает немедленно, когда помощник не работает, и говорит почему.
     * Cydia блокируется на этом вызове, так что молчаливое ожидание кончается
     * тем, что сторожевой таймер убивает Cydia — именно так пошло не так в
     * первом варианте.
     */
    private val clientScript = listOf(
        "#!/bin/bash",
        "# Stands in for Cydia's setuid helper. The real one is cydo.real.",
        "set -u",
        "queue=$queue",
        "pid=\$(cat \"\\\$queue/pid\" 2>/dev/null || echo 0)",
        "if ! ps -p \"\\\$pid\" >/dev/null 2>&1; then",
        "    echo \"cydo: Inferno helper is not running - press Repair the package manager in the app\" >&2",
        "    exit 2",
        "fi",
        "id=\"\$queue/\\\$\\\$-\\\$RANDOM\"",
        "fd=\"\"",
        "prev=\"\"",
        "for a in \"\\\$@\"; do",
        "    case \"\\\$prev\" in --status-fd) fd=\"\\\$a\";; esac",
        "    case \"\\\$a\" in --status-fd=*) fd=\"\${a#--status-fd=}\";; esac",
        "    prev=\"\\\$a\"",
        "done",
        "printf %s\\\\n \"\\\$@\" > \"\\\$id.tmp\"",
        "touch \"\\\$id.out\" \"\\\$id.status\"",
        "mv \"\\\$id.tmp\" \"\\\$id.req\"",
        "(echo x > \"\\\$queue/wake\" &) 2>/dev/null",
        "tail -n +1 -f \"\\\$id.out\" 2>/dev/null &",
        "tail_pid=\\\$!",
        "status_pid=0",
        "if [ -n \"\\\$fd\" ] && { : >&\"\\\$fd\"; } 2>/dev/null; then",
        "    tail -n +1 -f \"\\\$id.status\" >&\"\\\$fd\" 2>/dev/null &",
        "    status_pid=\\\$!",
        "fi",
        "for _ in \$(seq 1 3600); do",
        "    [ -e \"\\\$id.rc\" ] && break",
        "    sleep 1",
        "done",
        "sleep 1",
        "kill \"\\\$tail_pid\" 2>/dev/null",
        "[ \"\\\$status_pid\" != 0 ] && kill \"\\\$status_pid\" 2>/dev/null",
        "rc=\$(cat \"\\\$id.rc\" 2>/dev/null || echo 2)",
        "rm -f \"\\\$id.out\" \"\\\$id.rc\" \"\\\$id.status\"",
        "exit \"\\\$rc\"",
    )

    /** Ставит `.deb` в гостя, без Cydia на пути.
     *
     * Cydia блокирует собственный главный поток, пока работает dpkg, и на
     * госте такой медлительности это достаточно, чтобы iOS убила его как
     * неотзывчивый — что заодно убивает клиента помощника и оставляет dpkg
     * при базе. Здесь никто не ждёт у экрана, так что пакет может взять свои
     * минуты.
     *
     * `--force-overwrite` потому, что бутстрап и пакеты, с которыми он пришёл,
     * не согласны о том, кто владеет горсткой файлов в /etc/apt; на этом, и
     * больше ни на чём, спотыкается всякая установка в этом образе.
     */
    fun installDeb(
        local: File,
        serial: SerialConsole,
        files: GuestFiles,
        progress: (Long, Long) -> Unit,
        note: (String) -> Unit,
    ): String {
        val remote = "/var/mobile/.inferno/install.deb"

        awaitShell(serial)
        ensureWritable(serial)

        // Переноска — один разговор, и должна быть им: это последовательность
        // команд, на которые гость отвечает по порядку. Всё после — одна
        // длинная команда, и таким оставляют идти самим.
        val packageName: String = serial.exclusive {
            val shell = GuestShell(serial)
            shell.line("mkdir -p /var/mobile/.inferno", timeout = 60.0)

            note(L("Переношу пакет в гостя…"))
            files.carry(local, remote, remote, shell, progress, note)

            // Имя читается прежде, чем файл уйдёт: по нему ищут приложения
            // ниже.
            shell.text("dpkg-deb -f $remote Package", timeout = 300.0) ?: ""
        }

        note(L("Ставлю пакет…"))
        val code = runDetached("dpkg -i --force-overwrite $remote >> $log 2>&1", serial, 1800.0)
            ?: throw Failure(
                L("Шаг «%@» не ответил вовремя.", L("Ставлю пакет")),
                FailureKind.SILENT,
                L("Ставлю пакет"),
            )

        note(L("Настраиваю пакеты…"))
        runDetached("dpkg --configure -a >> $log 2>&1", serial, 1800.0)

        // Пакет, принёсший приложение, оставляет его на диске, и больше
        // ничего: о нём SpringBoard узнаёт от uicache. Только об этом
        // приложении, впрочем — `uicache --all` обходит всякое приложение
        // системы, что на этом госте тянется минутами с клином SpringBoard
        // ради всех них.
        if (packageName.isNotEmpty()) refreshIcons(packageName, serial, note)

        val said = serial.exclusive {
            val shell = GuestShell(serial)
            shell.line("rm -f $remote", timeout = 60.0)
            shell.text("grep -v '^\$' $log | tail -3 | tr '\\n' ' ' | cut -c1-240", timeout = 120.0) ?: ""
        }
        if (code != 0L) {
            throw Failure(
                L(
                    L("Шаг «%@» в госте вернул %d."),
                    L("Ставлю пакет") + (if (said.isEmpty()) "" else ": $said"),
                    code,
                ),
                FailureKind.STEP,
                L("Ставлю пакет"),
                code,
            )
        }
        return said
    }

    /** Что у гостя установлено, как имя пакета к версии.
     *
     * Читается через файл, а не с консоли: ответ тянется на триста строк, а
     * консоль теряет байты внутри длинных.
     */
    fun installed(serial: SerialConsole, files: GuestFiles): Map<String, String> {
        val listing = "/var/mobile/.inferno/installed.txt"

        awaitShell(serial)
        serial.exclusive {
            val shell = GuestShell(serial)
            shell.line("mkdir -p /var/mobile/.inferno", timeout = 60.0)
            if (shell.line(
                    "dpkg-query -W -f='\${Package}\t\${Version}\n' > $listing 2>/dev/null",
                    timeout = 600.0,
                ) == null
            ) {
                throw Failure(
                    L("Шаг «%@» не ответил вовремя.", L("Читаю установленное")),
                    FailureKind.SILENT,
                    L("Читаю установленное"),
                )
            }
        }

        val file = files.receive(listing) { _, _ -> }
        val text = try {
            file.readText()
        } catch (_: Throwable) {
            ""
        }
        val out = linkedMapOf<String, String>()
        for (line in text.split("\n")) {
            val parts = line.split("\t", limit = 2)
            if (parts.size != 2) continue
            out[parts[0]] = parts[1]
        }
        return out
    }

    /** Убирает пакет, с файлами его настроек, оставленными на месте — то же,
     * что делает собственное удаление Cydia.
     */
    fun remove(packageName: String, serial: SerialConsole): String {
        ensureWritable(serial)
        awaitShell(serial)
        serial.exclusive { GuestShell(serial).line(": > $log", timeout = 60.0) }
        // Читается до удаления: после него dpkg уже не знает, чем владел
        // пакет.
        val apps = appPaths(packageName, serial)

        val code = runDetached("dpkg -r $packageName >> $log 2>&1", serial, 1800.0)
            ?: throw Failure(
                L("Шаг «%@» не ответил вовремя.", L("Удаляю пакет")),
                FailureKind.SILENT,
                L("Удаляю пакет"),
            )
        for (app in apps) {
            runDetached("uicache -p $app >> $log 2>&1", serial, 1800.0)
        }

        val said = serial.exclusive {
            GuestShell(serial).text(
                "grep -v '^\$' $log | tail -3 | tr '\\n' ' ' | cut -c1-240",
                timeout = 120.0,
            ) ?: ""
        }
        if (code != 0L) {
            throw Failure(
                L(
                    L("Шаг «%@» в госте вернул %d."),
                    L("Удаляю пакет") + (if (said.isEmpty()) "" else ": $said"),
                    code,
                ),
                FailureKind.STEP,
                L("Удаляю пакет"),
                code,
            )
        }
        return said
    }

    /** Ждёт шелла на консоли вместо того, чтобы требовать его сразу.
     *
     * Гость отвечает `echo 1` в одно мгновение, когда простаивает, и за минуту,
     * когда нет, — а простаивающим он не бывает ни сразу после загрузки, ни
     * посреди пакета. Один взгляд и отказ стоили загрузки, которую уже
     * занесли.
     */
    private fun awaitShell(serial: SerialConsole) {
        for (attempt in 0 until 10) {
            // Консоль берут для вопроса, не для ожидания: держать её сквозь
            // паузы значило не давать панели шелла открываться, пока это
            // работает.
            val answered = serial.exclusive {
                GuestShell(serial).number("echo 1", timeout = 30.0) == 1L
            }
            if (answered) return
            if (attempt < 9) Thread.sleep(5000)
        }
        throw Failure(L("Гость не отвечает на консоли — дождитесь загрузки и повторите."), FailureKind.NO_SHELL)
    }

    /** Перемонтирует корень на запись и убеждается, что он таким остался.
     *
     * `mount -uw /` отвечает 0 и не меняет ничего, когда гость ещё грузится —
     * iOS возвращает корень на только-чтение на своём пути вверх, — поэтому
     * ответу верить нельзя и вместо него читают таблицу монтирования. Спрашивают
     * заново каждые десять секунд: что бы гость с ним ни делал, он перестаёт
     * делать это в пределах минуты.
     */
    private fun ensureWritable(serial: SerialConsole) {
        for (attempt in 0 until 6) {
            val writable = serial.exclusive {
                val shell = GuestShell(serial)
                shell.line("mount -uw /", timeout = 120.0)
                shell.number("mount | grep -c ' on / .*read-only'") == 0L
            }
            if (writable) return
            if (attempt < 5) Thread.sleep(10_000)
        }
        throw Failure(
            L("Корень гостя остался только для чтения — пакеты писать некуда."),
            FailureKind.STILL_READ_ONLY,
        )
    }

    /** Запускает долгую команду в госте, не сидя на консоли.
     *
     * dpkg тянется здесь минутами, и держать блокировку разговора все их
     * значило: никто другой не мог сказать ни слова — панель шелла ждала своей
     * очереди, пока не выглядела сломанной. Команда стартует отвязанной, её
     * статус остаётся в файле, а консоль берётся только для вопроса: короткая
     * проверка каждые несколько секунд.
     */
    private fun runDetached(command: String, serial: SerialConsole, timeout: Double): Long? {
        val done = "/var/mobile/.inferno/step.rc"
        // Гость, ушедший в панику, больше не отвечает никогда, а ожидание ниже
        // тянется полчаса. Консоль говорит, когда это случилось, так что счёт
        // берётся теперь и досматривается до конца ожидания.
        val alive = serial.guestDeaths
        val started = serial.exclusive {
            val shell = GuestShell(serial)
            shell.line("rm -f $done", timeout = 60.0)
            shell.line("(nohup /bin/bash -c '{ $command ;}; echo \$? > $done' >/dev/null 2>&1 &)", timeout = 120.0) == 0L
        }
        if (!started) return null

        val deadline = System.nanoTime() / 1_000_000_000.0 + timeout
        while (System.nanoTime() / 1_000_000_000.0 < deadline) {
            Thread.sleep(3000)
            if (serial.guestDeaths != alive) {
                throw Failure(
                    L("Гость упал и перезагружается, команда не доведена до конца: %@", serial.guestDeathReason),
                    FailureKind.CRASHED,
                    serial.guestDeathReason,
                )
            }
            val code: Long? = serial.exclusive {
                val shell = GuestShell(serial)
                if (shell.number("test -e $done; echo \$?", timeout = 60.0) != 0L) {
                    null
                } else {
                    shell.number("cat $done", timeout = 60.0)
                }
            }
            if (code != null) return code
        }
        return null
    }

    /** Записи `/Applications`, которыми владеет пакет, если есть. */
    private fun appPaths(packageName: String, serial: SerialConsole): List<String> {
        val listed = serial.exclusive {
            GuestShell(serial).text(
                "dpkg -L $packageName 2>/dev/null | grep -E '^/Applications/[^/]+\\.app\$' | tr '\\n' ' '",
                timeout = 300.0,
            ) ?: ""
        }
        return listed.split(" ").filter { it.isNotEmpty() }
    }

    /** Показывает SpringBoard'у то, что принёс пакет, и ничего сверх. */
    private fun refreshIcons(packageName: String, serial: SerialConsole, note: (String) -> Unit) {
        val apps = appPaths(packageName, serial)
        if (apps.isEmpty()) return
        note(L("Показываю приложение SpringBoard…"))
        for (app in apps) {
            runDetached("uicache -p $app >> $log 2>&1", serial, 1800.0)
        }
    }

    /** Перезапускает SpringBoard.
     *
     * Твики загружаются в него при старте, так что только что поставленный
     * ничего не делает, пока этого не случится. Cydia зовёт это respring и
     * спрашивает сначала; здесь это пункт меню по той же причине.
     */
    fun respring(serial: SerialConsole) {
        awaitShell(serial)
        serial.exclusive {
            val shell = GuestShell(serial)
            shell.line("killall -9 SpringBoard", timeout = 120.0)
        }
    }

    /** Пишет файл в госте по строке за раз. По команде на строку нарочно:
     * консоль роняет байты внутри длинных строк, а here-document оставил бы
     * наш собственный текст сидеть в терминале, пока гость занят, — и ровно
     * так передачи и ломались раньше.
     */
    private fun write(lines: List<String>, to: String, shell: GuestShell) {
        if (shell.line("rm -f $to", timeout = 60.0) != 0L) {
            throw Failure(L("Шаг «%@» в госте вернул %d.", to, -1), FailureKind.STEP, to, -1)
        }
        for (line in lines) {
            val quoted = line.replace("'", "'\\''")
            if (shell.line("printf '%s\\n' '$quoted' >> $to", timeout = 60.0) != 0L) {
                throw Failure(L("Шаг «%@» в госте вернул %d.", to, -1), FailureKind.STEP, to, -1)
            }
        }
    }

    /** Что гость теряет при каждой перезагрузке и что поэтому приходится делать
     * заново всякий раз, когда машина стартует. Секунды, не минуты.
     */
    private val fastSteps: List<Step>
        get() = listOf(
            Step(
                L("Готовлю папки apt"),
                "mkdir -p /var/lib /var/cache/apt/archives/partial /var/lib/apt/lists/partial",
                60.0,
                true,
            ),
            Step(L("Ставлю ссылку на базу dpkg"), "ln -sfn /Library/dpkg /var/lib/dpkg", 60.0, true),
        )

    /** Нужны раз на образ и достаточно медленны, чтобы стоили кнопки: на
     * телефоне эти два тянутся минутами и держат консоль, пока работают.
     */
    private val slowSteps: List<Step>
        get() = listOf(
            Step(L("Регистрирую прошивку"), "/usr/libexec/cydia/firmware.sh >> $log 2>&1", 900.0, false),
            Step(L("Настраиваю пакеты"), "dpkg --configure -a >> $log 2>&1", 1200.0, false),
            Step(L("Проверяю базу"), "dpkg --audit >> $log 2>&1", 600.0, false),
        )

    /** Прогоняет починку. `note` зовётся с шагом, который сейчас пойдёт, и
     * возвращает строки, стоящие показа, когда всё кончено. Всё, включая
     * долгие шаги. Это кнопка.
     */
    fun repair(serial: SerialConsole, note: (String) -> Unit): List<String> {
        note(L("Перемонтирую корень на запись…"))
        ensureWritable(serial)
        return run(serial, fastSteps + slowSteps, note)
    }

    /** Только то, что отменила перезагрузка: перемонтирование, папки,
     * помощник. Запускается на каждом старте тихо, потому что без него Cydia
     * снова сломана, а её ошибка ничего не говорит о причине.
     */
    fun prepare(serial: SerialConsole): List<String> {
        ensureWritable(serial)
        return run(serial, fastSteps) { _ -> }
    }

    private fun run(serial: SerialConsole, steps: List<Step>, note: (String) -> Unit): List<String> {
        val complaints = ArrayList<String>()

        // Ничего не печатается в консоль, на которой ещё нет шелла: команды
        // приземлились бы в журнале загрузки и выглядели бы выполненными.
        // Ожидание — за пределами блокировки, чтобы панель шелла могла
        // открыться, пока это тянется.
        awaitShell(serial)

        serial.exclusive {
            GuestShell(serial).line("mkdir -p /var/mobile/.inferno; : > $log", timeout = 60.0)
        }

        // Каждый шаг сам по себе, а долгие — отвязанно: firmware.sh и dpkg
        // тянутся здесь минутами, и держать консоль для них — то, из-за чего
        // панель шелла выглядела сломанной, пока шла починка.
        for (step in steps) {
            note(step.title + "…")
            val code = runDetached(step.command, serial, step.timeout)
            if (code == null) {
                if (step.fatal) {
                    throw Failure(L("Шаг «%@» не ответил вовремя.", step.title), FailureKind.SILENT, step.title)
                }
                complaints.add(L("«%@» не ответил вовремя.", step.title))
                continue
            }
            if (code != 0L) {
                if (step.fatal) {
                    throw Failure(
                        L("Шаг «%@» в госте вернул %d.", step.title, code),
                        FailureKind.STEP,
                        step.title,
                        code,
                    )
                }
                complaints.add(L("«%@» вернул %d.", step.title, code))
                continue
            }
        }

        serial.exclusive {
            val shell = GuestShell(serial)
            // Замена помощника с setuid. Пишется каждый раз: это дёшево, а
            // образ, где Cydia переустановили, снова имеет подлинника на
            // месте.
            note(L("Ставлю помощника для Cydia…"))
            // Запись двух скриптов стоит по команде на строку, и консоль
            // держится все их — на каждом старте достаточно долго, чтобы
            // панель шелла сдавалась, ожидая очереди. Поэтому пишутся они
            // только когда их ещё нет в этом точном виде. Один лишь маркер
            // такого не говорит: установка или обновление Cydia кладёт
            // setuid-подлинник обратно поверх cydo и оставляет маркер как был,
            // и Cydia снова ломалась, хотя все проверки здесь проходили. Так
            // что смотрят и на сам cydo.
            val stamp = PosixChecksum()
            val script = (rootScript + clientScript).joinToString("\n")
            // utf8CString в Swift нес завершающий NUL — он тоже входил в сумму.
            stamp.update(script.toByteArray(Charsets.UTF_8) + 0)
            val version = "%08x".format(stamp.value.toLong() and 0xFFFF_FFFFL)
            val marker = "/var/mobile/.inferno/cydo.version"
            val ours = "grep -qs '^# Stands in for Cydia' /usr/libexec/cydia/cydo"
            val same = shell.number(
                "test -x /usr/libexec/cydia/cydo.real && $ours && "
                    + "grep -qs '^$version\$' $marker && echo 1 || echo 0",
            ) == 1L

            if (!same) {
                write(rootScript, "/usr/libexec/cydia/cydo-root.sh", shell)
                write(clientScript, "/tmp/cydo.new", shell)
            }
            val install = listOf(
                "chmod 755 /usr/libexec/cydia/cydo-root.sh",
                // Всякий cydo, кроме замены, — подлинник: бутстраповский в
                // первый раз, новой Cydia после обновления. Ничего не запускает
                // cydo.real; он хранится, чтобы вернуть.
                "$ours || mv -f /usr/libexec/cydia/cydo /usr/libexec/cydia/cydo.real",
                if (same) "true" else "cp /tmp/cydo.new /usr/libexec/cydia/cydo",
                "chmod 755 /usr/libexec/cydia/cydo",
                "rm -f /tmp/cydo.new",
                "echo $version > $marker",
                // Ранняя версия возила задачу launchd. На этом образе она не
                // переживает перезагрузку, так что её убирают.
                "launchctl unload /Library/LaunchDaemons/com.inferno.cydo.plist >/dev/null 2>&1",
                "rm -f /Library/LaunchDaemons/com.inferno.cydo.plist",
                // Запросы, оставленные, пока помощник не работал: их никто
                // больше не ждёт.
                "mkdir -p $queue",
                "chmod 777 $queue",
                "rm -f $queue/*.req $queue/*.out $queue/*.rc $queue/*.tmp $queue/*.status $queue/wake",
                // По списку процессов, а не по имени: pkill гостя не берёт `-f`,
                // так что помощники прежних запусков его переживали — двое из
                // них сожгли по минуте процессора гостя, опрашивая, — и это
                // ровно та медлительность, которую здесь хотели кончить.
                "ps ax | grep '[c]ydo-root.sh' | sed 's/^ *//;s/ .*//' | xargs kill -9 2>/dev/null",
                // В подоболочке, и после него на строке ничего: голый `&` с
                // маркером, дописанным позади, — синтаксическая ошибка, и bash
                // тогда не исполняет ничего из этого вовсе.
                "(nohup /bin/bash /usr/libexec/cydia/cydo-root.sh >/dev/null 2>&1 &)",
            ).joinToString("; ")
            if (shell.line(install, timeout = 180.0) != 0L) {
                complaints.add(L("Помощника не удалось разложить."))
            }
            // Свой pid он пишет первым действием, так что короткое ожидание
            // отличает работающего помощника от свалившегося на старте.
            if (shell.number("sleep 2; test -e $queue/pid; echo \$?") != 0L) {
                complaints.add(L("Помощник не запустился — Cydia останется без прав."))
            }

            note(L("Проверяю…"))
            // В конце спросить всё ещё стоит: гость может вернуть корень
            // обратно, пока идут долгие шаги.
            if (shell.number("mount | grep -c ' on / .*read-only'") != 0L) {
                throw Failure(
                    L("Корень гостя остался только для чтения — пакеты писать некуда."),
                    FailureKind.STILL_READ_ONLY,
                )
            }
            if (shell.number("test -e /var/lib/dpkg/status; echo \$?") != 0L) {
                complaints.add(L("База dpkg на месте не найдена — Cydia может всё ещё ругаться."))
            }
            // Что бы ни сказал dpkg, одной строкой: консоль не место для
            // абзацев, и последнее в нём — то, что важно.
            val said = shell.text("grep -v '^\$' $log | tail -3 | tr '\\n' ' ' | cut -c1-240", timeout = 120.0)
            if (!said.isNullOrEmpty()) {
                complaints.add(L("dpkg сказал: %@", said))
            }
        }

        return complaints
    }
}
