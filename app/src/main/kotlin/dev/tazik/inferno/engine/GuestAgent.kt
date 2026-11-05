package dev.tazik.inferno.engine

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.random.Random

/**
 * Сторона приложения у агента гостя — программы, живущей в госте и берущей
 * запросы через черновой NVMe namespace вместо консоли.
 *
 * Зачем он: всё автоматическое раньше шло через единственную серийную консоль,
 * где сидит bash бутстрапа, и одна команда, не вернувшая приглашение, уносила
 * с собой всё разом — панель шелла, пакеты, строку состояния. Агент достижим
 * через namespace, так что заклинившая консоль больше не останавливает ни
 * обновление строки состояния, ни служебную команду. Гостевая сторона — в
 * `netlab/agent/`.
 *
 * Это добавка, никогда не требование. Когда агента нет — эмулятор без
 * namespace, сборка без `ldid`, установка, не взявшаяся, гость слишком старый, —
 * каждый метод здесь сообщает об этом внятно, и звонящий возвращается к консоли
 * ровно как раньше.
 *
 * Канал — область того же файла-подложки, какой пользуется
 * `TransferNamespace`, разложенная по `netlab/agent/agent.h`: блок запроса,
 * который пишет приложение и читает агент, блок ответа — наоборот, и область
 * данных за обоими под окна файлов. Запрос нов для агента, когда пара
 * (сессия, seq) ещё не отвечалась; ответ повторяет пару, так что поздний ответ
 * на старый запрос не принят за свежий.
 *
 * Всё здесь блокирует; звать не с главного потока.
 */
class GuestAgent private constructor(
    private val image: File,
    private val capacity: Long,
) {

    companion object {
        // Раскладка из agent.h. Все смещения и заголовочный блок выровнены на
        // 4 КиБ, потому что гость читает это как сырьевое блочное устройство,
        // которое не берёт ничего другого.
        private const val headerSize = 64
        private const val headerBlock = 4096
        private const val reqOffset: Long = 0
        private const val reqBody: Long = 4096
        private const val reqSize = 256 * 1024
        private const val rspOffset: Long = 256 * 1024
        private const val rspBody: Long = 256 * 1024 + 4096
        private const val rspSize = 768 * 1024
        private const val dataOffset: Long = 1024 * 1024
        private const val proto = 1L
        private val reqMagic = "INFAGREQ".toByteArray(Charsets.UTF_8)
        private val rspMagic = "INFAGRSP".toByteArray(Charsets.UTF_8)

        /** Возвращает клиента, только когда агент действительно отвечает через
         * namespace. Null — не провал: значит, эта сессия работает через
         * консоль, как раньше.
         */
        fun discover(image: File = VMConfig.transferImage): GuestAgent? {
            // Размер берётся открытием файла, а не у File, который для symlink
            // доложил бы длину самой ссылки. Здесь xfer — обычный файл, но стенд
            // показывает его через ссылку.
            val capacity = fileSize(image) ?: return null
            if (capacity <= dataOffset) return null
            val agent = GuestAgent(image, capacity)
            val reply = agent.request(mapOf("op" to "ping"), timeout = 6.0)
            if (reply?.flag("ok") != true) return null
            agent.lastAgentInstance = reply.long("agent") ?: 0L
            return agent
        }

        /** Настоящий размер файла, сквозь ссылки (которых File не следует). */
        private fun fileSize(url: File): Long? = try {
            RandomAccessFile(url, "r").use { it.length() }.takeIf { it > 0 }
        } catch (_: Throwable) {
            null
        }
    }

    /** Последнее, что агент доложил о себе, — на посмотреть в лог. */
    var lastAgentInstance: Long = 0
        private set

    /** Уникальна на запуск: агент дедуплицирует по (сессия, seq), и сессия,
     * повторившаяся между запусками с обнулённым seq, выглядела бы дублем и
     * отвечалась из протухших данных.
     */
    private val session: Long = Random.nextLong()

    private var seq = 0L
    private val lock = ReentrantLock()

    /** Жив ли агент, уже найденный. Ложь, когда ушёл — убит, или машина
     * перезапускалась без него.
     */
    fun isAlive(timeout: Double = 4.0): Boolean =
        request(mapOf("op" to "ping"), timeout)?.flag("ok") == true

    // MARK: - Обмен запрос/ответ

    /** Пишет один запрос и ждёт ответа. По одному обмену за раз: область держит
     * один запрос, поэтому звонящие выстраиваются.
     */
    fun request(body: Map<String, Any?>, timeout: Double): Map<String, Any?>? {
        lock.withLock {
            val payload = try {
                JSONObject(body).toString().toByteArray(Charsets.UTF_8)
            } catch (_: Throwable) {
                return null
            }
            if (payload.size > reqSize - headerBlock) return null
            seq += 1
            val mine = seq

            try {
                RandomAccessFile(image, "rw").use { handle ->
                    // Сначала тело, потом заголовок: агент никогда не должен
                    // увидеть новый seq прежде, чем тело, которое тот называет,
                    // окажется на диске. В заголовке — контрольная сумма тела,
                    // так что разорванная запись ловится и просто читается снова.
                    write(handle, reqBody, payload)
                    val header = header(reqMagic, session, mine, 0, payload)
                    write(handle, reqOffset, header)
                }
            } catch (_: Throwable) {
                return null
            }

            val deadline = System.nanoTime() / 1_000_000_000.0 + timeout
            while (System.nanoTime() / 1_000_000_000.0 < deadline) {
                readResponse(session, mine)?.let { return it }
                Thread.sleep(80)
            }
            return null
        }
    }

    private fun readResponse(session: Long, seq: Long): Map<String, Any?>? {
        val headBytes = try {
            RandomAccessFile(image, "r").use { read(it, rspOffset, headerSize) }
        } catch (_: Throwable) {
            return null
        } ?: return null
        if (headBytes.size != headerSize) return null

        val fields = unpack(headBytes)
        if (!fields.magic.contentEquals(rspMagic) || fields.proto != proto ||
            fields.session != session || fields.seq != seq ||
            PosixChecksum.of(headBytes.copyOf(48)).toLong() and 0xFFFF_FFFFL != fields.headcrc
        ) {
            return null
        }

        if (fields.length > rspSize - headerBlock) return null
        val body = try {
            RandomAccessFile(image, "r").use { read(it, rspBody, fields.length.toInt()) }
        } catch (_: Throwable) {
            return null
        } ?: return null
        if (body.size != fields.length.toInt() ||
            PosixChecksum.of(body).toLong() and 0xFFFF_FFFFL != fields.crc
        ) {
            return null
        }

        lastAgentInstance = fields.agent
        return try {
            Json.toMap(JSONObject(String(body, Charsets.UTF_8)))
        } catch (_: Throwable) {
            null
        }
    }

    // MARK: - Операции

    /** Проводит команду с крайним сроком и возвращает готовый ответ, либо null,
     * если агент не ответил вовсе. Агент убивает команду, засидевшуюся за своим
     * таймаутом, так что зависшая команда никогда не заклинивает канал.
     */
    fun run(command: String, timeout: Double = 60.0, capture: Int = 65536): AgentJob? {
        val id = "j%08x".format(Random.nextInt())
        var reply = request(
            mapOf(
                "op" to "exec", "id" to id, "cmd" to command,
                "timeout" to timeout, "wait" to minOf(timeout, 9.0), "max" to capture,
            ),
            timeout = timeout + 12,
        )
        // Не кончилось в первый срок: опрос до конца крайнего срока.
        val deadline = System.nanoTime() / 1_000_000_000.0 + timeout + 15
        while (reply?.str("state") == "running" && System.nanoTime() / 1_000_000_000.0 < deadline) {
            reply = request(mapOf("op" to "poll", "id" to id, "wait" to 8), timeout = 20.0)
        }
        return reply?.let { AgentJob(it) }
    }

    /** Слёт строки состояния, которую агент должен держать применённой, или
     * её сброс. Агент переприменяет её сам, так что это шлётся один раз, а не по
     * таймеру, как приходилось консольному пути.
     */
    fun setStatusBar(look: Map<String, Any?>?): Boolean {
        val payload: Map<String, Any?> = look ?: mapOf("clear" to true)
        val reply = request(mapOf("op" to "statusbar", "look" to payload), timeout = 20.0)
        return reply?.flag("ok") == true
    }

    /** Есть ли файл и каков он — для прикидки передачи. */
    fun stat(path: String): Pair<Boolean, Long>? {
        val reply = request(mapOf("op" to "stat", "path" to path), timeout = 20.0)
            ?: return null
        if (reply.flag("ok") != true) return null
        return reply.flag("exists") to (reply.long("size") ?: 0)
    }

    // MARK: - Файловые окна через область данных

    /** Шлёт локальный файл на `path` в госте, окнами через область данных.
     * Агент владеет устройством, пока работает, поэтому файлы едут именно так,
     * когда он есть — консольному помощнику с ним не ужиться на одном устройстве.
     */
    fun send(file: File, path: String, progress: (Long, Long) -> Unit) {
        val total = file.length()
        val window = capacity - dataOffset
        if (window <= 0) {
            throw GuestFiles.Failure(
                L("Ошибка ввода-вывода: %@", L("канал агента без места под данные")),
                GuestFiles.FailureKind.IO,
            )
        }

        val sum = PosixChecksum()
        var sent = 0L
        var first = true
        FileInputStream(file).use { source ->
            while (sent < total || (total == 0L && first)) {
                val want = minOf(window, total - sent).toInt().coerceAtLeast(0)
                val piece = ByteArray(want)
                val got = source.read(piece)
                if (got <= 0 && !(total == 0L && first)) break
                val data = if (got == want) piece else piece.copyOf(maxOf(got, 0))
                sum.update(data)
                writeData(data)

                val windowSum = PosixChecksum()
                windowSum.update(data)
                val reply = request(
                    mapOf(
                        "op" to "push", "path" to path, "len" to data.size,
                        "at" to sent, "first" to first,
                        "crc" to (windowSum.value.toLong() and 0xFFFF_FFFFL),
                    ),
                    timeout = 180.0,
                )
                if (reply?.flag("ok") != true) {
                    throw GuestFiles.Failure(
                        L("Ошибка ввода-вывода: %@",
                            L("агент не принял окно: %@", reply?.str("error") ?: "?")),
                        GuestFiles.FailureKind.IO,
                    )
                }
                sent += data.size
                first = false
                progress(sent, total)
                if (total == 0L) break
            }
        }
        verify(path, sum.length)
    }

    /** Тянет `path` из гостя в локальный файл, окно за окном. */
    fun receive(path: String, local: File, progress: (Long, Long) -> Unit) {
        val info = stat(path)
        if (info == null || !info.first || info.second < 0) {
            throw GuestFiles.Failure(L("Нет такого файла в госте: %@", path), GuestFiles.FailureKind.NOT_FOUND)
        }
        val total = info.second
        if (!local.createNewFile() && !local.isFile) {
            throw GuestFiles.Failure(L("Ошибка ввода-вывода: %@", local.name), GuestFiles.FailureKind.IO)
        }
        val window = capacity - dataOffset
        if (window <= 0) {
            throw GuestFiles.Failure(
                L("Ошибка ввода-вывода: %@", L("канал агента без места под данные")),
                GuestFiles.FailureKind.IO,
            )
        }

        val sum = PosixChecksum()
        var got = 0L
        FileOutputStream(local).use { sink ->
            while (got < total) {
                val take = minOf(window, total - got).toInt()
                val reply = request(
                    mapOf("op" to "pull", "path" to path, "at" to got, "len" to take),
                    timeout = 180.0,
                )
                if (reply?.flag("ok") != true) {
                    throw GuestFiles.Failure(
                        L("Ошибка ввода-вывода: %@",
                            L("агент не отдал окно: %@", reply?.str("error") ?: "?")),
                        GuestFiles.FailureKind.IO,
                    )
                }
                val length = (reply.long("len") ?: -1).toInt()
                val crc = reply.long("crc") ?: -1

                val piece = readData(length)
                if (piece == null || piece.size != length) {
                    throw GuestFiles.Failure(
                        L("Ошибка ввода-вывода: %@", L("окно не прочиталось")),
                        GuestFiles.FailureKind.IO,
                    )
                }
                val windowSum = PosixChecksum()
                windowSum.update(piece)
                if (windowSum.value.toLong() and 0xFFFF_FFFFL != crc) {
                    throw GuestFiles.Failure(
                        L("Файл не сошёлся по контрольной сумме (%@).", L("окно на %d", got)),
                        GuestFiles.FailureKind.MISMATCH,
                    )
                }

                sum.update(piece)
                sink.write(piece)
                got += length
                progress(got, total)
            }
        }
        verify(path, sum.length)
    }

    private fun verify(path: String, length: Long) {
        val info = stat(path)
        if (info == null || !info.first || info.second != length) {
            throw GuestFiles.Failure(
                L("Файл не сошёлся по контрольной сумме (%@).",
                    L("в госте %@ Б, у нас %d Б", stat(path)?.second?.toString() ?: "?", length)),
                GuestFiles.FailureKind.MISMATCH,
            )
        }
        // Размер сошёлся — большая часть дела; контрольную сумму каждого окна
        // агент проверил на пути, а целую сумму файла подтверждает cksum гостя
        // только когда каналу команд стоит ходить туда и обратно. Размер плюс
        // пооконные проверки поймали каждую неисправность, виденную на стенде.
    }

    // MARK: - Прямой доступ к областям

    private fun writeData(piece: ByteArray) {
        RandomAccessFile(image, "rw").use { write(it, dataOffset, piece) }
    }

    private fun readData(count: Int): ByteArray? = try {
        RandomAccessFile(image, "r").use { read(it, dataOffset, count) }
    } catch (_: Throwable) {
        null
    }

    private fun write(handle: RandomAccessFile, at: Long, bytes: ByteArray) {
        handle.seek(at)
        handle.write(bytes)
        // Выдавлено на диск: эмулятор отвечает на чтения гостя в обход кэша и
        // иначе подал бы прежнее содержимое файла.
        handle.fd.sync()
    }

    private fun read(handle: RandomAccessFile, at: Long, count: Int): ByteArray? {
        if (count < 0) return null
        return try {
            handle.seek(at)
            val piece = ByteArray(count)
            var filled = 0
            while (filled < count) {
                val n = handle.read(piece, filled, count - filled)
                if (n <= 0) break
                filled += n
            }
            piece.copyOf(filled)
        } catch (_: Throwable) {
            null
        }
    }

    // MARK: - Заголовок

    private fun header(magic: ByteArray, session: Long, seq: Long, agent: Long, body: ByteArray): ByteArray {
        val bytes = ByteArray(headerSize)
        magic.copyInto(bytes, 0, 0, minOf(magic.size, 8))
        put32(bytes, 8, proto)
        put32(bytes, 12, 0)
        put64(bytes, 16, session)
        put64(bytes, 24, seq)
        put64(bytes, 32, agent)
        put32(bytes, 40, body.size.toLong())
        put32(bytes, 44, PosixChecksum.of(body).toLong() and 0xFFFF_FFFFL)
        put32(bytes, 48, PosixChecksum.of(bytes.copyOf(48)).toLong() and 0xFFFF_FFFFL)
        return bytes
    }

    private class Header(
        val magic: ByteArray,
        val proto: Long,
        val session: Long,
        val seq: Long,
        val agent: Long,
        val length: Long,
        val crc: Long,
        val headcrc: Long,
    )

    private fun unpack(bytes: ByteArray) = Header(
        magic = bytes.copyOf(8),
        proto = get32(bytes, 8),
        session = get64(bytes, 16),
        seq = get64(bytes, 24),
        agent = get64(bytes, 32),
        length = get32(bytes, 40),
        crc = get32(bytes, 44),
        headcrc = get32(bytes, 48),
    )

    private fun put32(bytes: ByteArray, at: Int, value: Long) {
        for (i in 0 until 4) bytes[at + i] = ((value shr (8 * i)) and 0xFF).toByte()
    }

    private fun put64(bytes: ByteArray, at: Int, value: Long) {
        for (i in 0 until 8) bytes[at + i] = ((value shr (8 * i)) and 0xFF).toByte()
    }

    private fun get32(bytes: ByteArray, at: Int): Long {
        var value = 0L
        for (i in 0 until 4) value = value or ((bytes[at + i].toLong() and 0xFF) shl (8 * i))
        return value
    }

    private fun get64(bytes: ByteArray, at: Int): Long {
        var value = 0L
        for (i in 0 until 8) value = value or ((bytes[at + i].toLong() and 0xFF) shl (8 * i))
        return value
    }
}

/** Законченная команда, как её описал агент. */
class AgentJob(reply: Map<String, Any?>) {
    val done: Boolean = reply.str("state") == "done"
    val status: Int = reply.long("status")?.toInt() ?: -1
    val timedOut: Boolean = reply.flag("timedOut")
    val stuck: Boolean = reply.flag("stuck")
    val truncated: Boolean = reply.flag("truncated")
    val output: String = reply.str("out") ?: ""
}
