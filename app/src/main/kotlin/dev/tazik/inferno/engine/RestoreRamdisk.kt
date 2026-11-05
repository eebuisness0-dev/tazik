package dev.tazik.inferno.engine

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * Восстановительный рамдиск, с двумя собственными программами внутри.
 *
 * После записи системы руководством ChefKiss'а человек монтирует результат на
 * Mac и прогоняет по разделяемому кэшу патчер файловой системы. Этот шаг —
 * последний, которому нужен компьютер, и он не обязан им быть: тот же патчер
 * может поработать в госте, в той же загрузке рамдиска, в тот момент, когда
 * восстановление закончило писать. Что ему нужно, — быть в рамдиске.
 *
 * Рамдиск — это `IM4P` вокруг сырого тома HFSX, и добавить файл в HFS+ значит
 * вставить в каталоговое B-дерево — расщеплять узлы, выделять блоки,
 * переписывать заголовки. Заменить содержимое файла, который уже есть, — нет:
 * запись каталога остаётся на месте, блоки остаются выделенными за файлом, и
 * меняются только байты в тех блоках и два поля в записи. Поэтому здесь
 * заменяются три файла, которые восстановлению ни к чему:
 *
 * - два блока прошивки NFC, мегабайты каждый, для secure element, которого у
 *   этой машины нет;
 * - `com.apple.syslogd.plist`, задача launchd, которую рамдиск поставляет
 *   **выключенной** и которая становится задачей, запускающей наш демон.
 *
 * Плисту нужно ещё кое-что. Почти каждый файл рамдиска — HFS+ сжатый:
 * содержимое живёт в расширенном атрибуте, а ветвь данных пуста, так что
 * перезаписывать нечего. Сброс флага сжатия и выделение записи блока из
 * собственного списка свободных блоков тома это чинят, и это всё ещё только
 * запись полей, которые уже есть. Два блока прошивки — из числа немногих
 * файлов, хранимых прямо, и их блоки остаются как есть.
 *
 * Ничего не добавляется, ничего не удаляется, и образ сохраняет точный
 * размер — поэтому результат возвращается в IM4P, из которого пришёл, без
 * перепаковки.
 */
object RestoreRamdisk {

    enum class FailureKind { NOT_IM4P, NOT_HFS, MISSING, TOO_BIG, NO_PROGRAMS, NO_ROOM }

    class Failure(message: String, val kind: FailureKind) : Exception(message)

    /** Куда идут наши программы и что они заменяют.
     *
     * Пути важны и демону: он запускает патчер по пути, а plist задачи называет
     * демона по пути.
     */
    const val daemonPath =
        "/usr/standalone/firmware/nfrestore/firmware/jcop-prod/JCOP-01.30-006-P.bin"
    const val patcherPath =
        "/usr/standalone/firmware/nfrestore/firmware/jcop-prod/JCOP-11.04-015.1-P.bin"
    const val jobPath = "/System/Library/LaunchDaemons/com.apple.syslogd.plist"

    /** Задача, запускающая демон. `RunAtLoad`, потому что больше никто в рамдиске её не попросит. */
    val job: ByteArray
        get() = buildString {
            appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
            appendLine("<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">")
            appendLine("<plist version=\"1.0\">")
            appendLine("<dict>")
            appendLine("\t<key>Label</key>")
            appendLine("\t<string>com.inferno.patcher</string>")
            appendLine("\t<key>ProgramArguments</key>")
            appendLine("\t<array>")
            appendLine("\t\t<string>$daemonPath</string>")
            appendLine("\t</array>")
            appendLine("\t<key>RunAtLoad</key>")
            appendLine("\t<true/>")
            appendLine("\t<key>StandardOutPath</key>")
            appendLine("\t<string>/dev/console</string>")
            appendLine("\t<key>StandardErrorPath</key>")
            appendLine("\t<string>/dev/console</string>")
            appendLine("</dict>")
            appendLine("</plist>")
            appendLine()
        }.toByteArray(Charsets.UTF_8)

    /** Две программы, как сборка кладёт их в приложение. */
    val programs: Pair<File, File>?
        get() {
            val bundle = EngineEnv.guestToolsDir ?: return null
            val daemon = File(bundle, "inferno_patcher")
            val patcher = File(bundle, "inferno_fs_patcher")
            if (!daemon.isFile || !patcher.isFile) return null
            return daemon to patcher
        }

    /**
     * Собирает пропатченный рамдиск из стокового. Результат — копия;
     * оригинал из IPSW остаётся как был.
     */
    fun build(stock: File, into: File, note: (String) -> Unit = {}) {
        val files = programs ?: throw Failure(L("В приложении нет программ для гостя"), FailureKind.NO_PROGRAMS)

        try {
            into.delete()
        } catch (_: Throwable) {
        }
        copy(stock, into)

        RandomAccessFile(into, "rw").use { handle ->
            val volume = Volume(handle, payloadOffset(handle))
            volume.replace(jobPath, job, 0x1A4)
            volume.replace(daemonPath, files.first.readBytes(), 0x1ED)
            volume.replace(patcherPath, files.second.readBytes(), 0x1ED)
            handle.fd.sync()
            note(L("Подготовка: RAM-диск с патчером собран"))
        }
    }

    /** Копия, не держащая все сто мегабайт в памяти. */
    private fun copy(source: File, destination: File) {
        if (!destination.isFile && !destination.createNewFile()) {
            throw Failure(L("В RAM-диске нет файла %@", destination.name), FailureKind.MISSING)
        }
        FileInputStream(source).use { reader ->
            FileOutputStream(destination).use { writer ->
                val buffer = ByteArray(8 shl 20)
                while (true) {
                    val n = reader.read(buffer)
                    if (n <= 0) break
                    writer.write(buffer, 0, n)
                }
            }
        }
    }

    /** Где том HFSX начинается внутри `IM4P`.
     *
     * Контейнер — SEQUENCE из «IM4P», четырёхсимвольного типа, строки версии и
     * payload как OCTET STRING; том — содержимое payload, для рамдиска без
     * сжатия. Заголовок разбирается руками, чтобы сто мегабайт не попали в
     * кучу, — парсер из `IMG4` пришлось бы держать payload целиком.
     */
    private fun payloadOffset(handle: RandomAccessFile): Long {
        handle.seek(0)
        val header = ByteArray(256)
        val got = handle.read(header)
        if (got < 8) throw Failure(L("RAM-диск не IM4P — не тот файл?"), FailureKind.NOT_IM4P)
        var at = 0

        fun element(): Triple<Int, Int, Int> {
            if (at + 2 > got) throw Failure(L("RAM-диск не IM4P — не тот файл?"), FailureKind.NOT_IM4P)
            val tag = header[at].toInt() and 0xFF
            var length = header[at + 1].toInt() and 0xFF
            at += 2
            if ((length and 0x80) != 0) {
                val count = length and 0x7F
                if (count <= 0 || count > 4 || at + count > got) {
                    throw Failure(L("RAM-диск не IM4P — не тот файл?"), FailureKind.NOT_IM4P)
                }
                length = 0
                for (i in 0 until count) length = (length shl 8) or (header[at + i].toInt() and 0xFF)
                at += count
            }
            return Triple(tag, at, length)
        }

        val outer = element() // SEQUENCE
        if (outer.first != 0x30) throw Failure(L("RAM-диск не IM4P — не тот файл?"), FailureKind.NOT_IM4P)
        val magic = element() // IA5String "IM4P"
        if (magic.first != 0x16 || magic.third != 4 ||
            String(header, magic.second, 4, Charsets.UTF_8) != "IM4P"
        ) {
            throw Failure(L("RAM-диск не IM4P — не тот файл?"), FailureKind.NOT_IM4P)
        }
        at = magic.second + magic.third
        val type = element() // IA5String, четырёхсимвольный тип
        at = type.second + type.third
        val version = element() // IA5String
        at = version.second + version.third
        val payload = element() // OCTET STRING
        if (payload.first != 0x04) throw Failure(L("RAM-диск не IM4P — не тот файл?"), FailureKind.NOT_IM4P)
        return payload.second.toLong()
    }

    // MARK: - HFS+

    /** Ровно столько HFS+, чтобы найти файл и перезаписать его там, где он лежит.
     *
     * Всё здесь big-endian и фиксированной ширины, и именно поэтому всё это
     * коротко: заголовок тома говорит, где каталог, листья каталога — связный
     * список, а запись файла несёт собственные экстенты. Ничего не выделяется
     * и ни один ключ никогда не сравнивается — листья читаются по порядку и
     * имена сопоставляются по ходу.
     */
    private class Volume(private val handle: RandomAccessFile, private val base: Long) {

        class Fork(
            val logicalSize: Long,
            val totalBlocks: Long,
            val extents: List<Pair<Long, Long>>, // start to count
        )

        /** Файл из каталога: где его запись в образе и где его содержимое. */
        class Entry(
            val isFolder: Boolean,
            val id: Long,
            val recordOffset: Long, // данных записи, в образе
            val fork: Fork,
        )

        private val blockSize: Long
        private val totalBlocks: Long
        private val allocation: Fork
        private val catalog: Fork
        private val nodeSize: Int
        private val firstLeaf: Long

        init {
            val header = read(handle, base + 1024, 512)
            val signature = String(header, 0, 2, Charsets.UTF_8)
            if (signature != "H+" && signature != "HX") {
                throw Failure(L("В RAM-диске не найден том HFS+"), FailureKind.NOT_HFS)
            }
            blockSize = be32(header, 40)
            totalBlocks = be32(header, 44)
            allocation = fork(header, 112)
            catalog = fork(header, 272)

            // Узел ноль несёт заголовочную запись дерева, и все смещения дерева
            // считаются в размере узла, который он называет, — поэтому сам узел
            // ноль находится без него: он начинается там, где начинается fork.
            val start = base + (catalog.extents.firstOrNull()?.first ?: 0) * blockSize
            val node = read(handle, start, 512)
            nodeSize = be16(node, 14 + 18).toInt()
            firstLeaf = be32(node, 14 + 10)
        }

        /** Заменяет содержимое файла. Файлу с запасом блоков оставляют его
         * блоки; без запаса — а сжатый файл не держит ничего в ветви данных —
         * блоки выдаются из списка свободных тома.
         */
        fun replace(path: String, data: ByteArray, mode: Int) {
            val entry = find(path) ?: throw Failure(L("В RAM-диске нет файла %@", path), FailureKind.MISSING)
            if (entry.isFolder) throw Failure(L("В RAM-диске нет файла %@", path), FailureKind.MISSING)
            var extents = entry.fork.extents.toMutableList()
            val room = extents.sumOf { it.second * blockSize }
            if (room < data.size) {
                val needed = (data.size + blockSize - 1) / blockSize
                extents = mutableListOf((allocate(needed).toLong() to needed))
                // Поля самого fork: сколько блоков держит и где они. Семь
                // экстентов после первого обнуляются, чтобы от того, что
                // говорил раньше, ничего не осталось.
                write(handle, entry.recordOffset + 100, bytes32(needed))
                var descriptors = bytes32(extents[0].first) + bytes32(needed)
                descriptors += ByteArray(7 * 8)
                write(handle, entry.recordOffset + 104, descriptors)
                // Какими ни были старые блоки, они больше не этого файла. Они
                // остаются помеченными занятыми: этот образ делается для одного
                // восстановления и выбрасывается вместе с ним, и утечка внутри
                // него не стоит ничего рядом с кодом, который бы за этим убрал.
            }

            var written = 0
            for (extent in extents) {
                if (written >= data.size) break
                val chunk = minOf((extent.second * blockSize).toInt(), data.size - written)
                write(handle, base + extent.first * blockSize, data.copyOfRange(written, written + chunk))
                written += chunk
            }

            // Остаток записи: длина того, что в блоках; режим — задаче launchd
            // положено быть читаемой, а программе исполняемой, и блок прошивки
            // не то и не другое; root как владелец; и флаг сжатия сброшен,
            // потому что содержимое больше не в расширенном атрибуте и ядро не
            // должно искать его там.
            write(handle, entry.recordOffset + 88, bytes64(data.size.toLong()))
            write(handle, entry.recordOffset + 42, bytes16(mode or 0x8000))
            write(handle, entry.recordOffset + 32, bytes64(0)) // owner, group
            write(handle, entry.recordOffset + 41, byteArrayOf(0)) // ownerFlags
        }

        /** Берёт серию блоков, которые том не использует, и помечает занятыми.
         *
         * Файл размещения — бит на блок, старший бит первым; счётчик свободных
         * блоков в заголовке тома должен следовать за ним.
         */
        private fun allocate(blocks: Long): Long {
            var bitmap = read(allocation, ((totalBlocks + 7) / 8).toInt())
            var run = 0
            var start = 0
            for (block in 0 until totalBlocks.toInt()) {
                val mask = (0x80 shr (block and 7)).toByte()
                if ((bitmap[block shr 3].toInt() and 0xFF) and mask.toInt() != 0) {
                    run = 0
                    continue
                }
                if (run == 0) start = block
                run += 1
                if (run != blocks.toInt()) continue
                for (taken in start until start + run) {
                    bitmap[taken shr 3] = ((bitmap[taken shr 3].toInt() and 0xFF) or (0x80 shr (taken and 7))).toByte()
                }
                write(allocation, bitmap)
                val free = be32(read(handle, base + 1024 + 48, 4), 0)
                write(handle, base + 1024 + 48, bytes32(free - blocks))
                return start.toLong()
            }
            throw Failure(L("В RAM-диске не осталось свободных блоков"), FailureKind.NO_ROOM)
        }

        /** Идёт по пути от корня, по одному компоненту за раз. */
        fun find(path: String): Entry? {
            var parent: Long = 2 // собственный идентификатор корневой папки
            var entry: Entry? = null
            for (name in path.split("/").filter { it.isNotEmpty() }) {
                val found = lookup(name, parent) ?: return null
                parent = found.id
                entry = found
            }
            return entry
        }

        /** Читает листья по порядку, ища одно имя под одним родителем. */
        private fun lookup(name: String, parent: Long): Entry? {
            val wanted = name.toByteArray(Charsets.UTF_16BE)
            var index = firstLeaf
            while (index != 0L) {
                val node = read(handle, imageOffset(ofNode = index), nodeSize)
                val next = be32(node, 0)
                val records = be16(node, 10).toInt()
                for (record in 0 until records) {
                    val offset = be16(node, nodeSize - (record + 1) * 2).toInt()
                    if (offset + 8 >= node.size) continue
                    val keyLength = be16(node, offset).toInt()
                    if (be32(node, offset + 2) != parent) continue
                    // HFSUniStr255 хранит длину в символах, а не в байтах
                    // UTF-16: без удвоения ни одно имя не сравнится.
                    val nameLength = be16(node, offset + 6).toInt() * 2
                    if (nameLength != wanted.size) continue
                    var same = true
                    for (i in 0 until nameLength) {
                        if (node[offset + 8 + i] != wanted[i]) {
                            same = false
                            break
                        }
                    }
                    if (!same) continue

                    // Данные записи идут за ключом, выровненные на два байта.
                    var at = offset + 2 + keyLength
                    if (at % 2 != 0) at += 1
                    val type = be16(node, at)
                    val recordOffset = imageOffset(ofNode = index) + at
                    if (type == 1L) { // папка
                        return Entry(
                            true, be32(node, at + 8), recordOffset,
                            Fork(0, 0, emptyList()),
                        )
                    }
                    if (type == 2L) { // файл
                        return Entry(
                            false, be32(node, at + 8), recordOffset,
                            fork(node, at + 88),
                        )
                    }
                }
                index = next
            }
            return null
        }

        // MARK: Чтение раскладки

        /** Читает fork целиком. Так читаются только мелкие — карта размещения
         * тома на сто мегабайт — это несколько килобайт.
         */
        private fun read(fork: Fork, count: Int): ByteArray {
            val bytes = ByteArray(0)
            var out = bytes
            var read = 0
            for (extent in fork.extents) {
                if (read >= count) break
                val chunk = minOf((extent.second * blockSize).toInt(), count - read)
                out += read(handle, base + extent.first * blockSize, chunk)
                read += chunk
            }
            return out
        }

        private fun write(fork: Fork, data: ByteArray) {
            var written = 0
            for (extent in fork.extents) {
                if (written >= data.size) break
                val chunk = minOf((extent.second * blockSize).toInt(), data.size - written)
                write(handle, base + extent.first * blockSize, data.copyOfRange(written, written + chunk))
                written += chunk
            }
        }

        /** Где байтовое смещение внутри каталогового fork сидит в образе. Fork —
         * список экстентов блоков размещения, поэтому смещение за первым
         * переходит в следующий.
         */
        private fun catalogOffset(offset: Long): Long {
            var seen = 0L
            for (extent in catalog.extents) {
                val length = extent.second * blockSize
                if (offset < seen + length) {
                    return base + extent.first * blockSize + (offset - seen)
                }
                seen += length
            }
            return base + (catalog.extents.firstOrNull()?.first ?: 0) * blockSize
        }

        /** Где узел каталога начинается в образе. Узел нумеруется внутри
         * каталогового fork, а тот лежит в образе кусками-экстентами —
         * переводом через них и живёт.
         */
        private fun imageOffset(ofNode: Long): Long = catalogOffset(ofNode * nodeSize)

        companion object {
            private fun fork(bytes: ByteArray, at: Int): Fork {
                val extents = mutableListOf<Pair<Long, Long>>()
                for (i in 0 until 8) {
                    val start = be32(bytes, at + 16 + i * 8)
                    val count = be32(bytes, at + 20 + i * 8)
                    if (count != 0L) extents.add(start to count)
                }
                return Fork(be64(bytes, at), be32(bytes, at + 12), extents)
            }

            private fun be16(bytes: ByteArray, at: Int): Long {
                if (at + 2 > bytes.size) return 0
                return ((bytes[at].toLong() and 0xFF) shl 8) or (bytes[at + 1].toLong() and 0xFF)
            }

            private fun be32(bytes: ByteArray, at: Int): Long {
                if (at + 4 > bytes.size) return 0
                var v = 0L
                for (i in 0 until 4) v = (v shl 8) or (bytes[at + i].toLong() and 0xFF)
                return v
            }

            private fun be64(bytes: ByteArray, at: Int): Long {
                if (at + 8 > bytes.size) return 0
                var v = 0L
                for (i in 0 until 8) v = (v shl 8) or (bytes[at + i].toLong() and 0xFF)
                return v
            }

            private fun bytes16(value: Int): ByteArray =
                byteArrayOf(((value shr 8) and 0xFF).toByte(), (value and 0xFF).toByte())

            private fun bytes32(value: Long): ByteArray = ByteArray(4) { i ->
                ((value ushr (8 * (3 - i))) and 0xFF).toByte()
            }

            private fun bytes64(value: Long): ByteArray = ByteArray(8) { i ->
                ((value ushr (8 * (7 - i))) and 0xFF).toByte()
            }

            private fun read(handle: RandomAccessFile, at: Long, count: Int): ByteArray {
                handle.seek(at)
                val bytes = ByteArray(count)
                handle.readFully(bytes)
                return bytes
            }

            private fun write(handle: RandomAccessFile, at: Long, data: ByteArray) {
                handle.seek(at)
                handle.write(data)
            }
        }
    }
}
