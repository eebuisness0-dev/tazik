package dev.tazik.inferno.engine

import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.zip.Inflater

/**
 * Прошивка, читаемая там, где лежит.
 *
 * Файл восстановления — шесть гигабайт, и у телефона есть применения получше:
 * распаковать его целиком значило бы держать место дважды. Поэтому здесь
 * ничего не распаковывается. Читается центральный каталог, несколько мелких
 * компонентов, которые персонализирует восстановление, достаются по одному, а
 * образ файловой системы — Apple хранит его без сжатия — отдаётся
 * восстановлению как диапазон исходного файла, читаемый по ходу по проводу.
 *
 * Zip64 здесь не опция: один только образ больше четырёх гигабайт, поэтому
 * размеры и смещения живут в extra-поле, а не в заголовке.
 */
class IPSW(val url: File) {

    class Member(
        val path: String,
        /** 0 — без сжатия, 8 — deflate. */
        val method: Int,
        val compressedSize: Long,
        val plainSize: Long,
        /** Где начинаются сжатые байты, за локальным заголовком. */
        val dataOffset: Long,
    ) {
        val isStored: Boolean get() = method == 0
    }

    enum class FailureKind { NOT_ZIP, CORRUPT, MISSING, UNSUPPORTED }

    class Failure(message: String, val kind: FailureKind) : Exception(message)

    private val members = LinkedHashMap<String, Member>()

    /** Все имена в архиве, в порядке центрального каталога. */
    val memberNames: Set<String> get() = members.keys

    init {
        readDirectory()
    }

    // MARK: - Центральный каталог

    private fun readDirectory() {
        RandomAccessFile(url, "r").use { handle ->
            val size = handle.length()
            if (size <= 22) throw Failure(L("Это не архив прошивки"), FailureKind.NOT_ZIP)

            // Запись конца каталога — последняя, за комментарием, которого в
            // IPSW никто не ставит.
            val tailLength = minOf(size, 66L * 1024).toInt()
            handle.seek(size - tailLength)
            val tail = ByteArray(tailLength)
            handle.readFully(tail)
            val eocd = find(0x0605_4B50L, tail)
                ?: throw Failure(L("Это не архив прошивки"), FailureKind.NOT_ZIP)

            var count = u16(tail, eocd + 10).toLong()
            var directoryOffset = u32(tail, eocd + 16)
            var directorySize = u32(tail, eocd + 12)

            // Zip64: настоящие числа в своей записи, которую находят через
            // локатор прямо перед записью конца.
            if (directoryOffset == 0xFFFF_FFFFL || count == 0xFFFFL || directorySize == 0xFFFF_FFFFL) {
                val locator = find(0x0706_4B50L, tail)
                    ?: throw Failure(L("В архиве есть то, что я не умею разбирать: %@", "zip64"), FailureKind.UNSUPPORTED)
                val recordAt = u64(tail, locator + 8)
                handle.seek(recordAt)
                val record = ByteArray(56)
                val got = handle.read(record)
                if (got < 56) throw Failure(L("Архив повреждён: %@", "zip64 record"), FailureKind.CORRUPT)
                if (u32(record, 0) != 0x0606_4B50L) {
                    throw Failure(L("Архив повреждён: %@", "zip64 record"), FailureKind.CORRUPT)
                }
                count = u64(record, 32)
                directorySize = u64(record, 40)
                directoryOffset = u64(record, 48)
            }

            handle.seek(directoryOffset)
            val directory = ByteArray(directorySize.toInt())
            handle.readFully(directory)

            var at = 0
            for (index in 0 until count.toInt()) {
                if (at + 46 > directory.size || u32(directory, at) != 0x0201_4B50L) {
                    throw Failure(L("Архив повреждён: %@", "central directory"), FailureKind.CORRUPT)
                }
                val method = u16(directory, at + 10)
                var compressed = u32(directory, at + 20)
                var plain = u32(directory, at + 24)
                val nameLength = u16(directory, at + 28)
                val extraLength = u16(directory, at + 30)
                val commentLength = u16(directory, at + 32)
                var localOffset = u32(directory, at + 42)

                val nameAt = at + 46
                val name = String(directory, nameAt, nameLength, Charsets.UTF_8)

                // Extra-поле zip64 заполняет то из трёх, что переполнилось,
                // именно в этом порядке и только их.
                var extraAt = nameAt + nameLength
                val extraEnd = extraAt + extraLength
                while (extraAt + 4 <= extraEnd) {
                    val tag = u16(directory, extraAt)
                    val fieldSize = u16(directory, extraAt + 2)
                    if (tag == 0x0001) {
                        var field = extraAt + 4
                        if (plain == 0xFFFF_FFFFL && field + 8 <= extraEnd) {
                            plain = u64(directory, field)
                            field += 8
                        }
                        if (compressed == 0xFFFF_FFFFL && field + 8 <= extraEnd) {
                            compressed = u64(directory, field)
                            field += 8
                        }
                        if (localOffset == 0xFFFF_FFFFL && field + 8 <= extraEnd) {
                            localOffset = u64(directory, field)
                        }
                    }
                    extraAt += 4 + fieldSize
                }
                at = extraEnd + commentLength

                if (name.endsWith("/")) continue

                // Только локальный заголовок говорит, какой длины имя и
                // extra-поле *здесь*; копии в каталоге могут отличаться.
                handle.seek(localOffset)
                val local = ByteArray(30)
                val localGot = handle.read(local)
                if (localGot != 30 || u32(local, 0) != 0x0403_4B50L) {
                    throw Failure(L("Архив повреждён: %@", "local header for $name"), FailureKind.CORRUPT)
                }
                val dataOffset = localOffset + 30 + u16(local, 26) + u16(local, 28)
                members[name] = Member(name, method, compressed, plain, dataOffset)
            }
        }
    }

    private fun find(signature: Long, bytes: ByteArray): Int? {
        if (bytes.size < 4) return null
        var probe = bytes.size - 4
        while (probe >= 0) {
            if (u32(bytes, probe) == signature) return probe
            probe -= 1
        }
        return null
    }

    // MARK: - Достать содержимое

    fun member(path: String): Member =
        members[path] ?: throw Failure(L("В прошивке нет файла %@", path), FailureKind.MISSING)

    /**
     * Один компонент в памяти. Для мелких — ядра, дерева устройства,
     * тикета, — но не для образа файловой системы.
     */
    fun read(path: String): ByteArray {
        val member = member(path)
        RandomAccessFile(url, "r").use { handle ->
            handle.seek(member.dataOffset)
            val raw = ByteArray(member.compressedSize.toInt())
            handle.readFully(raw)
            return when (member.method) {
                0 -> raw
                8 -> {
                    val out = inflate(raw, member.plainSize.toInt())
                    out ?: throw Failure(L("Архив повреждён: %@", path), FailureKind.CORRUPT)
                }
                else -> throw Failure(
                    L("В архиве есть то, что я не умею разбирать: %@", L("способ сжатия %d", member.method)),
                    FailureKind.UNSUPPORTED,
                )
            }
        }
    }

    /** Пишет компонент на диск, не держа его в памяти. */
    fun extract(path: String, to: File, progress: ((Double) -> Unit)? = null) {
        val member = member(path)
        RandomAccessFile(url, "r").use { reader ->
            reader.seek(member.dataOffset)
            to.parentFile?.mkdirs()
            FileOutputStream(to).use { writer ->
                val chunk = 4 * 1024 * 1024
                var left = member.compressedSize

                if (member.isStored) {
                    val buffer = ByteArray(chunk)
                    while (left > 0) {
                        val want = minOf(chunk.toLong(), left).toInt()
                        reader.readFully(buffer, 0, want)
                        writer.write(buffer, 0, want)
                        left -= want
                        progress?.invoke(
                            (member.compressedSize - left).toDouble() / member.compressedSize.toDouble(),
                        )
                    }
                    return
                }

                // Сырой DEFLATE, разбираемый по окну за раз.
                val inflater = Inflater(true)
                try {
                    val input = ByteArray(chunk)
                    val output = ByteArray(chunk)
                    while (true) {
                        if (inflater.needsInput() && left > 0) {
                            val want = minOf(chunk.toLong(), left).toInt()
                            reader.readFully(input, 0, want)
                            left -= want
                            inflater.setInput(input, 0, want)
                            progress?.invoke(
                                (member.compressedSize - left).toDouble() / member.compressedSize.toDouble(),
                            )
                        }
                        val produced = inflater.inflate(output)
                        if (produced > 0) writer.write(output, 0, produced)
                        if (inflater.finished()) break
                        if (produced == 0 && left == 0L && inflater.needsInput()) {
                            throw Failure(L("Архив повреждён: %@", path), FailureKind.CORRUPT)
                        }
                    }
                } finally {
                    inflater.end()
                }
            }
        }
    }

    private fun inflate(raw: ByteArray, expected: Int): ByteArray? {
        if (expected == 0) return ByteArray(0)
        val inflater = Inflater(true)
        try {
            inflater.setInput(raw)
            val out = ByteArray(expected)
            var filled = 0
            while (filled < expected) {
                val n = inflater.inflate(out, filled, expected - filled)
                if (n == 0) {
                    if (inflater.finished() || inflater.needsInput()) break
                }
                filled += n
            }
            return if (filled == expected) out else null
        } finally {
            inflater.end()
        }
    }

    /**
     * Где байты несжатого компонента лежат в самом IPSW.
     *
     * Образ файловой системы хранится без сжатия — именно это делает
     * восстановление без компьютера на телефоне возможным: его можно читать
     * прямо из прошивки, как просит ASR, ничего заранее не распаковывая.
     */
    fun rangeOfStored(path: String): Pair<Long, Long> {
        val member = member(path)
        if (!member.isStored) {
            throw Failure(
                L("В архиве есть то, что я не умею разбирать: %@", L("%@ сжат, а нужен как есть", path)),
                FailureKind.UNSUPPORTED,
            )
        }
        return member.dataOffset to member.plainSize
    }

    // MARK: - Манифест

    /** Тождество сборки, которое использует чистая установка, для этого устройства. */
    fun buildIdentity(deviceClass: String = "n104ap", erase: Boolean = true): Map<String, Any?> {
        val raw = read("BuildManifest.plist")
        val plist = PropertyList.parse(raw).asDict()
            ?: throw Failure(L("Архив повреждён: %@", "BuildManifest.plist"), FailureKind.CORRUPT)
        val identities = plist["BuildIdentities"].asList()
            ?: throw Failure(L("Архив повреждён: %@", "BuildManifest.plist"), FailureKind.CORRUPT)
        for (rawIdentity in identities) {
            val identity = rawIdentity.asDict() ?: continue
            val info = identity.dict("Info") ?: continue
            if ((info.str("DeviceClass") ?: "").lowercase() != deviceClass) continue
            if (info.str("RestoreBehavior") != (if (erase) "Erase" else "Update")) continue
            return identity
        }
        throw Failure(L("В прошивке нет файла %@", deviceClass), FailureKind.MISSING)
    }

    companion object {
        /** Путь компонента тождества сборки внутри IPSW. */
        fun path(component: String, identity: Map<String, Any?>): String? {
            val manifest = identity.dict("Manifest") ?: return null
            val entry = manifest[component].asDict() ?: return null
            val info = entry.dict("Info") ?: return null
            return info.str("Path")
        }
    }
}
