package dev.tazik.inferno.engine

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * Быстрый путь в гостя и обратно: черновой NVMe namespace.
 *
 * Приложение пишет байты в файл-подложку namespace, а гость читает то же место
 * как блочное устройство — ничего не идёт ни через консоль, ни через сеть, так
 * что это мегабайты в секунду, а не сотни килобайт.
 *
 * Сойтись должны три вещи, и каждая ломается по-своему тихо:
 *
 * 1. **Эмулятор обязан описать namespace в дереве устройств.** Гость никогда
 *    не опрашивает контроллер; список namespace'ов он берёт из дерева и говорит
 *    об этом — `Obtained N namespaces from DT`. Эмулятор без той правки просто
 *    не показывает лишнего устройства, и всё здесь вежливо сдаётся, чтобы
 *    звонящий пошёл по сети.
 * 2. **Шелл гостя не может открыть блочные устройства.** `dd if=/dev/rdisk2`
 *    отвечает `Operation not permitted` даже root'у, тогда как `/sbin/fsck_hfs`
 *    читает то же самое устройство. Решают энтитлементы бинарника, поэтому
 *    маленький подписанный помощник везётся в приложении и ставится в гостя
 *    один раз.
 * 3. **Помощник должен оказаться под именем, которого ядро ещё не видело.**
 *    Записать новый бинарник поверх пути, уже подписанного, — значит получить
 *    его убитым при первом взгляде — `Killed: 9`, прежде чем `main()` успеет
 *    начаться. Поэтому имя несёт контрольную сумму бинарника, и новая сборка
 *    просто получает новое имя.
 *
 * Всё здесь блокирует; звать не с главного потока.
 */
class TransferNamespace private constructor(
    val device: String,
    val helper: String,
    private val image: File,
    private val capacity: Long,
) {

    // MARK: - Поиск

    companion object {
        /** Где приложение держит своё внутри гостя. На томе данных, чтобы
         * пережило перезагрузку гостя.
         */
        const val toolsDirectory = "/var/mobile/.inferno"
        private const val chunkFile = "$toolsDirectory/chunk.bin"

        /** Возвращает канал, когда его можно иметь. Null — не провал: эта
         * передача пойдёт по сети.
         *
         * `deliver` несёт локальный файл на путь в госте теми медленными
         * средствами, что уже работают; зовётся один раз — ради самого
         * помощника.
         */
        fun discover(
            shell: GuestShell,
            deliver: (File, String) -> Unit,
            note: (String) -> Unit,
        ): TransferNamespace? {
            val image = VMConfig.transferImage
            val capacity = if (image.isFile) image.length() else 0
            if (capacity <= 0) return null

            // Дёшево наперёд: если гость не показывает устройства, кроме своего
            // диска и namespace прошивки, эмулятор нашего не описал, и настраивать
            // нечего.
            val listing = shell.text("ls /dev/rdisk2 2>/dev/null | head -1")
            if (listing?.contains("rdisk") != true) return null

            val helper = try {
                ensureHelper(shell, deliver, note)
            } catch (_: Throwable) {
                return null
            }

            // Какой из /dev/rdiskN — это — зависит от того, сколько namespace'ов
            // гость решил показать, поэтому ищут по размеру, а не по номеру: наш
            // — единственный ровно такого размера.
            for (index in 1..5) {
                val candidate = "/dev/rdisk$index"
                val line = shell.text("$helper size $candidate 2>/dev/null", timeout = 30.0)
                val bytes = line?.split("=")?.lastOrNull()?.trim()?.toLongOrNull()
                if (bytes == capacity) {
                    return TransferNamespace(candidate, helper, image, capacity)
                }
            }
            return null
        }

        /** Ставит помощника в гостя один раз и находит его уже там после. */
        private fun ensureHelper(
            shell: GuestShell,
            deliver: (File, String) -> Unit,
            note: (String) -> Unit,
        ): String {
            val bundle = EngineEnv.guestToolsDir
                ?: throw GuestFiles.Failure(
                    L("Ошибка ввода-вывода: %@", L("помощника нет в приложении")),
                    GuestFiles.FailureKind.IO,
                )
            val data = File(bundle, "nsio").takeIf { it.isFile }?.readBytes()
                ?: throw GuestFiles.Failure(
                    L("Ошибка ввода-вывода: %@", L("помощника нет в приложении")),
                    GuestFiles.FailureKind.IO,
                )
            val sum = PosixChecksum()
            sum.update(data)
            val remote = "$toolsDirectory/nsio-%08x".format(sum.value.toLong() and 0xFFFF_FFFFL)

            if (shell.number("test -x $remote && echo 1 || echo 0") == 1L) return remote

            note(L("Ставлю помощника в гостя — это один раз…"))
            shell.run("mkdir -p $toolsDirectory")

            // Помощник въезжает по сети — единственный канал, который есть, пока
            // его нет. У свежезагруженного гостя интерфейс ещё не поднят, и одна
            // неудавшаяся попытка раньше стоила бы быстрого канала всей передаче,
            // поэтому линка дожидаются, а отправку повторяют.
            waitForLink(shell)
            var lastError: Throwable? = null
            for (attempt in 0 until 3) {
                try {
                    deliver(File(bundle, "nsio"), remote)
                    lastError = null
                    break
                } catch (e: Throwable) {
                    lastError = e
                    shell.reset()
                    if (attempt < 2) Thread.sleep(3000)
                }
            }
            lastError?.let { throw it }

            if (shell.run("chmod +x $remote") != 0L) {
                throw GuestFiles.Failure(
                    L("Ошибка ввода-вывода: %@", L("помощника не удалось сделать исполняемым")),
                    GuestFiles.FailureKind.IO,
                )
            }
            return remote
        }

        /** Ждёт, пока у гостя будет собственный адрес, сначала попросив его.
         *
         * Гость поднимает свой конец, просит по DHCP и потом опускает обратно;
         * `ipconfig set en0 DHCP` изнутри — то, что это действительно
         * укладывает.
         */
        private fun waitForLink(shell: GuestShell) {
            val address = shell.text("/usr/sbin/ipconfig getifaddr en0 2>/dev/null")
            if (address?.startsWith("10.") == true) return
            shell.run("/usr/sbin/ipconfig set en0 DHCP", timeout = 90.0)
            repeat(20) {
                val got = shell.text("/usr/sbin/ipconfig getifaddr en0 2>/dev/null")
                if (got?.startsWith("10.") == true) return
                Thread.sleep(1500)
            }
        }
    }

    // MARK: - Перенос байтов

    /** Шлёт локальный файл на `remote`, который уже выражение шелла. */
    fun send(file: File, remote: String, shell: GuestShell, progress: (Long, Long) -> Unit) {
        val total = file.length()

        val sum = PosixChecksum()
        var sent = 0L
        var first = true
        FileInputStream(file).use { source ->
            while (sent < total) {
                val want = minOf(capacity, total - sent).toInt()
                val piece = ByteArray(want)
                val got = source.read(piece)
                if (got <= 0) break
                val data = if (got == want) piece else piece.copyOf(got)
                sum.update(data)
                writeWindow(data)

                // `>` для первого окна и `>>` после, чтобы файл собирался за один
                // проход без отдельной команды, создающей его.
                val append = if (first) ">" else ">>"
                val command = "$helper read $device 0 ${data.size} $chunkFile" +
                    " && cat $chunkFile $append $remote"
                val status = shell.run(command, timeout = 600.0) ?: -1
                if (status != 0L) {
                    throw GuestFiles.Failure(
                        L("Ошибка ввода-вывода: %@", L("гость не прочитал носитель (%d)", status)),
                        GuestFiles.FailureKind.IO,
                    )
                }

                sent += data.size
                first = false
                progress(sent, total)
            }
        }
        shell.run("rm -f $chunkFile")
        verify(remote, shell, sum.length, sum.value)
    }

    /** Вытягивает `remote` из гостя в локальный файл. */
    fun receive(remote: String, local: File, shell: GuestShell, progress: (Long, Long) -> Unit) {
        val total = shell.number("wc -c < $remote") ?: 0
        if (total <= 0) {
            throw GuestFiles.Failure(L("Нет такого файла в госте: %@", remote), GuestFiles.FailureKind.NOT_FOUND)
        }

        if (!local.createNewFile() && !local.isFile) {
            throw GuestFiles.Failure(L("Ошибка ввода-вывода: %@", local.name), GuestFiles.FailureKind.IO)
        }

        val sum = PosixChecksum()
        var got = 0L
        var index = 0
        FileOutputStream(local).use { sink ->
            while (got < total) {
                val take = minOf(capacity, total - got).toInt()
                // Гость вырезает одно окно из файла и кладёт его на устройство;
                // мы читаем то же место обратно.
                val command = "dd if=$remote of=$chunkFile bs=$capacity skip=$index count=1 2>/dev/null" +
                    " && $helper write $device 0 $chunkFile"
                val status = shell.run(command, timeout = 600.0) ?: -1
                if (status != 0L) {
                    throw GuestFiles.Failure(
                        L("Ошибка ввода-вывода: %@", L("гость не записал носитель (%d)", status)),
                        GuestFiles.FailureKind.IO,
                    )
                }

                val piece = readWindow(take)
                sum.update(piece)
                sink.write(piece)

                got += take
                index += 1
                progress(got, total)
            }
        }
        shell.run("rm -f $chunkFile")
        verify(remote, shell, sum.length, sum.value)
    }

    // MARK: - Сам namespace

    private fun writeWindow(piece: ByteArray) {
        RandomAccessFile(image, "rw").use { sink ->
            sink.seek(0)
            sink.write(piece)
            // Выдавлено наружу, а не просто записано: эмулятор читает файл заново
            // при следующем запросе гостя, и всё, что ещё сидит в буфере, было бы
            // подано как прежнее содержимое файла.
            sink.fd.sync()
        }
    }

    private fun readWindow(length: Int): ByteArray {
        RandomAccessFile(image, "r").use { source ->
            source.seek(0)
            val piece = ByteArray(length)
            var filled = 0
            while (filled < length) {
                val n = source.read(piece, filled, length - filled)
                if (n <= 0) break
                filled += n
            }
            return if (filled == length) piece else piece.copyOf(filled)
        }
    }

    /** Обе стороны считают одинаково, так что неверный байт где угодно всплывёт
     * здесь.
     */
    private fun verify(remote: String, shell: GuestShell, length: Long, crc: Int) {
        val slack = 60.0 + length / 200_000.0
        val size = shell.number("wc -c < $remote", timeout = slack)
        val theirs = shell.number("cksum < $remote | cut -d' ' -f1", timeout = slack)
        if (size != length || theirs != (crc.toLong() and 0xFFFF_FFFFL)) {
            throw GuestFiles.Failure(
                L("Файл не сошёлся по контрольной сумме (%@).",
                    L("в госте %@ Б, у нас %d Б", size?.toString() ?: "?", length)),
                GuestFiles.FailureKind.MISMATCH,
            )
        }
    }
}
