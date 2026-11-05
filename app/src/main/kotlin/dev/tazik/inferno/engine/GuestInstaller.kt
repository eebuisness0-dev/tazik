package dev.tazik.inferno.engine

import java.io.File

/**
 * Ставит `.ipa` в гостя за один проход.
 *
 * Ядро в образе гостя пропатчено на «bypass code signature checks» и «all
 * binaries in trustcache», поэтому приложению не нужна подпись — его достаточно
 * распаковать в `/Applications` и показать SpringBoard'у через `uicache`. Не
 * хватало никогда не установки, а способа занести мегабайты. Теперь их два, и
 * здесь берётся лучший из доступных: черновой NVMe namespace, когда эмулятор
 * его даёт, USB-сеть в остальных случаях. Что нужно быстрому — см.
 * `TransferNamespace`.
 *
 * Всё здесь блокирует; звать не с главного потока.
 */
class GuestInstaller(
    private val serial: SerialConsole,
    private val files: GuestFiles,
) {

    enum class FailureKind { NO_PAYLOAD, NO_APP, STEP }

    class Failure(message: String, val kind: FailureKind, val what: String = "", val code: Long = 0) :
        Exception(message)

    private val stagedTar = TransferNamespace.toolsDirectory + "/install.tar"

    /** Ставится, когда приложение встало, а гость слишком стар для него. Не
     * ошибка: файлы установлены, SpringBoard о них знает, он просто не запустит
     * приложение, собранное под более новый iOS. Стоит сказать вслух, иначе это
     * выглядит как провал установщика.
     */
    var warning: String? = null
        private set

    /** Распаковывает, заносит и ставит. Возвращает, где приложение оказалось
     * в госте.
     */
    fun install(ipa: File, progress: (Long, Long) -> Unit, note: (String) -> Unit): String {
        // Распаковка происходит здесь, на телефоне: `unzip` в голом бутстрапе
        // нет, а `tar` есть на каждом образе.
        note(L("Распаковываю .ipa…"))
        val entries = Archive.entries(ipa)
        if (entries.none { it.path.startsWith("Payload/") }) {
            throw Failure(L("В .ipa нет папки Payload — это не приложение."), FailureKind.NO_PAYLOAD)
        }

        val appName = entries.firstNotNullOfOrNull { entry ->
            val parts = entry.path.split("/")
            if (parts.size >= 2 && parts[0] == "Payload" && parts[1].endsWith(".app")) parts[1] else null
        } ?: throw Failure(L("В Payload нет ни одного .app."), FailureKind.NO_APP)

        // `Payload/` сбрасывается, чтобы архив распаковывался прямо в
        // /Applications, а не в папку Payload внутри него.
        val staged = entries
            .map { entry ->
                Archive.Entry(
                    entry.path.drop("Payload/".length),
                    entry.data, entry.mode, entry.isDirectory, entry.isSymlink,
                )
            }
            .filter { it.path.isNotEmpty() }

        val scratch = File(EngineEnv.documents, "install-%s.tar".format(java.util.UUID.randomUUID()))
        try {
            Archive.writeTar(staged, to = scratch)

            val wanted = minimumOS(entries, appName)
            val target = "/Applications/$appName"
            return serial.exclusive {
                val shell = GuestShell(serial)
                shell.requireAnswer("echo 1")
                shell.run("mkdir -p ${TransferNamespace.toolsDirectory}")

                files.carry(scratch, stagedTar, stagedTar, shell, progress, note)
                unpack(shell, target, note)
                checkAge(shell, wanted, note)
                target
            }
        } finally {
            scratch.delete()
        }
    }

    /** Что приложение просит, из его собственного Info.plist. */
    private fun minimumOS(entries: List<Archive.Entry>, app: String): Int? {
        val plist = entries.firstOrNull { it.path == "Payload/$app/Info.plist" } ?: return null
        val parsed = PropertyList.parse(plist.data).asDict() ?: return null
        val version = parsed.str("MinimumOSVersion") ?: return null
        return version.split(".").firstOrNull()?.toIntOrNull()
    }

    /** Сверяет это с собственным iOS гостя. Версия берётся из ядра, а не из
     * плиста: `uname -r` есть на каждом образе и не требует парсера, а старший
     * номер Darwin ровно на шесть впереди номера iOS.
     */
    private fun checkAge(shell: GuestShell, wanted: Int?, note: (String) -> Unit) {
        wanted ?: return
        val release = shell.text("uname -r") ?: return
        val darwin = release.split(".").firstOrNull()?.toIntOrNull() ?: return
        if (darwin <= 6) return
        val guestOS = darwin - 6
        if (wanted <= guestOS) return
        warning = L("Приложению нужна iOS %d, а в госте iOS %d — оно встало, но не запустится.", wanted, guestOS)
        note(warning ?: "")
    }

    // MARK: - Установка на место

    private fun unpack(shell: GuestShell, target: String, note: (String) -> Unit) {
        // По одной короткой команде: занятый гость теряет байты внутри длинной
        // строки, а изувеченная строка оставляет bash ждать внутри незакрытой
        // кавычки.
        //
        // Никакая не может кончаться трубой. Возвращается статус последней
        // команды, а для трубы это `head` — сорвавшийся `tar` доложил бы об
        // успехе.
        val steps = listOf(
            // /Applications живёт на системном томе, смонтированном только для
            // чтения; без этого первый же файл не распакуется. Держится до
            // перезагрузки гостя, поэтому делается каждый раз.
            Triple(L("перемонтирую корень"), "mount -uw /", true),
            Triple(L("убираю прежнюю копию"), "rm -rf ${GuestFiles.quote(target)}", false),
            Triple(L("распаковываю"), "tar xf $stagedTar -C /Applications", true),
            Triple(
                L("права"),
                "chown -R root:wheel ${GuestFiles.quote(target)} && chmod -R 755 ${GuestFiles.quote(target)}",
                true,
            ),
            // uicache жалуется, когда гость старше MinimumOSVersion приложения.
            // Файлы и так на месте, так что это не повод считать установку
            // сорвавшейся.
            Triple(L("показываю SpringBoard"), "/usr/bin/uicache -p ${GuestFiles.quote(target)}", false),
            Triple(L("прибираю"), "rm -f $stagedTar", false),
        )

        for ((label, command, critical) in steps) {
            note("$label…")
            val status = shell.run(command, timeout = 600.0) ?: -1
            if (critical && status != 0L) {
                throw Failure(L("Шаг «%@» в госте вернул %d.", label, status), FailureKind.STEP, label, status)
            }
        }

        if (shell.number("test -d ${GuestFiles.quote(target)} && echo 1 || echo 0") != 1L) {
            throw Failure(L("Шаг «%@» в госте вернул %d.", L("проверка"), 1), FailureKind.STEP, L("проверка"), 1)
        }
    }
}
