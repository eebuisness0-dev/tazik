package dev.tazik.inferno.engine

import java.io.File
import java.io.FileOutputStream
import java.util.zip.Inflater

/**
 * Чтение `.ipa` и запись `.tar` на телефоне.
 *
 * На гостя нельзя положиться в распаковке zip: `unzip` в голом bootstrap'е
 * нет, а `tar` есть на каждом образе. Поэтому телефон сам открывает `.ipa` и
 * отдаёт гостю tar — формат, который достаточно прост, чтобы записать его
 * страницей кода.
 *
 * Здесь ничего не нужно от сторонних библиотек: запись zip держит сырой
 * DEFLATE, а это ровно то, что разбирает `Inflater` в режиме raw.
 */
object Archive {

    enum class FailureKind { NOT_ZIP, UNSUPPORTED, CORRUPT }

    class Failure(message: String, val kind: FailureKind) : Exception(message)

    /**
     * Один файл из архива, с правами, с которыми он был записан.
     */
    class Entry(
        val path: String,
        val data: ByteArray,
        /**
         * Unix-режим, который zip хранит в старшей половине external
         * attributes. Он несёт бит исполнения, а без него приложение,
         * попадающее в гостя, не запустится вовсе.
         */
        val mode: Int,
        val isDirectory: Boolean,
        val isSymlink: Boolean,
    )

    // MARK: - Чтение zip

    /** Всё в архиве, в порядке, в котором его перечисляет центральный каталог.
     *
     * Читается каталог, а не поток локальных заголовков: только каталог
     * гарантированно несёт external attributes, и именно там живёт бит
     * исполнения.
     */
    fun entries(zip: File): List<Entry> {
        val bytes = zip.readBytes()
        if (bytes.size <= 22) throw Failure(L("Это не .ipa: внутри нет оглавления zip."), FailureKind.NOT_ZIP)

        // Конец центрального каталога, найденный отходом назад через
        // комментарий.
        var eocd = -1
        val lowest = maxOf(0, bytes.size - 22 - 0xFFFF)
        var probe = bytes.size - 22
        while (probe >= lowest) {
            if (u32(bytes, probe) == 0x0605_4B50L) {
                eocd = probe
                break
            }
            probe -= 1
        }
        if (eocd < 0) throw Failure(L("Это не .ipa: внутри нет оглавления zip."), FailureKind.NOT_ZIP)

        val count = u16(bytes, eocd + 10)
        var offset = u32(bytes, eocd + 16).toInt()
        if (offset == -1 || count == 0xFFFF) {
            throw Failure(
                L("В архиве есть то, что я не умею разбирать: %@", "zip64"),
                FailureKind.UNSUPPORTED,
            )
        }

        val result = ArrayList<Entry>(count)

        for (index in 0 until count) {
            if (offset + 46 > bytes.size || u32(bytes, offset) != 0x0201_4B50L) {
                throw Failure(L("Архив повреждён: %@", "central directory"), FailureKind.CORRUPT)
            }
            val method = u16(bytes, offset + 10)
            val compressed = u32(bytes, offset + 20).toInt()
            val plain = u32(bytes, offset + 24).toInt()
            val nameLength = u16(bytes, offset + 28)
            val extraLength = u16(bytes, offset + 30)
            val commentLength = u16(bytes, offset + 32)
            val external = u32(bytes, offset + 38).toInt()
            val localOffset = u32(bytes, offset + 42).toInt()

            val nameStart = offset + 46
            val name = String(bytes, nameStart, nameLength, Charsets.UTF_8)
            offset = nameStart + nameLength + extraLength + commentLength

            // Старшая половина — unix-режим, когда архив собирал unix-пакер.
            // Всё остальное получает разумные значения по умолчанию.
            var mode = (external ushr 16) and 0xFFFF
            val directory = name.endsWith("/")
            val symlink = (mode and 0xF000) == 0xA000
            if (mode and 0x1FF == 0) mode = if (directory) 0x1ED else 0x1A4

            if (directory) {
                result.add(Entry(name.dropLast(1), ByteArray(0), mode, true, false))
                continue
            }

            // Локальный заголовок повторяет имя и extra-поле, и только он
            // говорит, какой длины они *здесь* — копия в каталоге может
            // отличаться.
            if (localOffset + 30 > bytes.size || u32(bytes, localOffset) != 0x0403_4B50L) {
                throw Failure(L("Архив повреждён: %@", "local header for $name"), FailureKind.CORRUPT)
            }
            val dataStart = localOffset + 30 + u16(bytes, localOffset + 26) + u16(bytes, localOffset + 28)
            if (dataStart + compressed > bytes.size) {
                throw Failure(L("Архив повреждён: %@", name), FailureKind.CORRUPT)
            }
            val raw = bytes.copyOfRange(dataStart, dataStart + compressed)

            val content: ByteArray = when (method) {
                0 -> raw
                8 -> inflate(raw, plain, name)
                else -> throw Failure(
                    L("В архиве есть то, что я не умею разбирать: %@", L("способ сжатия %d", method)),
                    FailureKind.UNSUPPORTED,
                )
            }
            if (content.size != plain) throw Failure(L("Архив повреждён: %@", name), FailureKind.CORRUPT)

            result.add(Entry(name, content, mode, false, symlink))
        }
        return result
    }

    /** Сырой DEFLATE — то, что хранит zip. */
    private fun inflate(data: ByteArray, size: Int, named: String): ByteArray {
        if (size == 0) return ByteArray(0)
        val inflater = Inflater(true)
        try {
            inflater.setInput(data)
            val out = ByteArray(size)
            var filled = 0
            while (filled < size) {
                val n = inflater.inflate(out, filled, size - filled)
                if (n == 0 && (inflater.finished() || inflater.needsInput())) break
                filled += n
            }
            if (filled != size) throw Failure(L("Архив повреждён: %@", named), FailureKind.CORRUPT)
            return out
        } finally {
            inflater.end()
        }
    }

    // MARK: - Запись tar

    private const val block = 512

    /** Пишет записи как ustar-архив, сразу на диск.
     *
     * Потоково, а не собранно в памяти: распакованное приложение — это десятки
     * мегабайт, и у телефона есть применения получше.
     */
    fun writeTar(entries: List<Entry>, to: File) {
        FileOutputStream(to).use { out ->
            for (entry in entries) {
                val body = if (entry.isSymlink) ByteArray(0) else entry.data
                val link = if (entry.isSymlink) String(entry.data, Charsets.UTF_8) else ""
                val type: Int = when {
                    entry.isDirectory -> 0x35
                    entry.isSymlink -> 0x32
                    else -> 0x30
                }

                // ustar делит длинное имя между двумя полями и не может вместить
                // имя длиннее, чем оба вместе. Запись длинного имени GNU такого
                // предела не имеет, и tar гостя её читает.
                if (splitName(entry.path) == null) {
                    val header = ByteArray(block)
                    val name = entry.path.toByteArray(Charsets.UTF_8)
                    fill(header, "././@LongLink", 0, 100)
                    fill(header, octal(0, 7), 100, 8)
                    fill(header, octal(0, 7), 108, 8)
                    fill(header, octal(0, 7), 116, 8)
                    fill(header, octal(name.size + 1, 11), 124, 12)
                    fill(header, octal(0, 11), 136, 12)
                    header[156] = 0x4C // 'L'
                    fill(header, "ustar  ", 257, 8)
                    seal(header)
                    out.write(header)
                    out.write(pad(name + byteArrayOf(0)))
                }

                out.write(makeHeader(entry, type, link, body.size))
                if (body.isNotEmpty()) out.write(pad(body))
            }
            // Два пустых блока кончают архив.
            out.write(ByteArray(block * 2))
        }
    }

    private fun makeHeader(entry: Entry, type: Int, link: String, size: Int): ByteArray {
        val header = ByteArray(block)
        val (prefix, name) = splitName(entry.path) ?: ("" to entry.path.takeLast(100))
        fill(header, name, 0, 100)
        fill(header, octal(entry.mode and 0x1FF, 7), 100, 8)
        fill(header, octal(0, 7), 108, 8) // uid: root
        fill(header, octal(0, 7), 116, 8) // gid: wheel
        fill(header, octal(size, 11), 124, 12)
        fill(header, octal((System.currentTimeMillis() / 1000).toInt(), 11), 136, 12)
        header[156] = type.toByte()
        fill(header, link, 157, 100)
        fill(header, "ustar", 257, 6)
        fill(header, "00", 263, 2)
        fill(header, "root", 265, 32)
        fill(header, "wheel", 297, 32)
        fill(header, prefix, 345, 155)
        seal(header)
        return header
    }

    /**
     * ustar хранит последний компонент в `name`, а остальное в `prefix`.
     * Возвращает null, когда путь не влезает ни в одно из них — случай
     * длинного имени.
     */
    private fun splitName(path: String): Pair<String, String>? {
        if (path.toByteArray(Charsets.UTF_8).size <= 100) return "" to path
        val parts = path.split("/")
        for (index in 1 until parts.size) {
            val head = parts.subList(0, index).joinToString("/")
            val tail = parts.subList(index, parts.size).joinToString("/")
            if (head.toByteArray(Charsets.UTF_8).size <= 155 && tail.toByteArray(Charsets.UTF_8).size <= 100) {
                return head to tail
            }
        }
        return null
    }

    /** Контрольная сумма считается со своим полем, прочитанным как пробелы. */
    private fun seal(header: ByteArray) {
        for (index in 148 until 156) header[index] = 0x20
        var sum = 0
        for (byte in header) sum += byte.toInt() and 0xFF
        fill(header, octal(sum, 6), 148, 8)
        header[154] = 0
        header[155] = 0x20
    }

    private fun octal(value: Int, width: Int): String = "%0${width}o".format(value)

    private fun fill(header: ByteArray, text: String, at: Int, size: Int) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        for (index in 0 until minOf(bytes.size, size)) header[at + index] = bytes[index]
    }

    private fun pad(data: ByteArray): ByteArray {
        val remainder = data.size % block
        return if (remainder == 0) data else data + ByteArray(block - remainder)
    }
}
