package dev.tazik.inferno.engine

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * Всё, что нужно эмулятору, сделанное на телефоне из того, что человек может
 * скачать на него.
 *
 * На десктопе это страница shell: `qemu-img create` девять раз, `unzip`, два
 * питоновских скрипта для тикетов и один вызов `img4` для прошивки SEP. Ни
 * одному из них не нужен компьютер — нужен читатель zip, DER и AES, и всё три
 * у приложения есть. Что человек всё ещё приносит — то, что может только он:
 * архив прошивки, тикет, публикуемый ChefKiss'ом, SEP ROM и ключ SEP с вики.
 */
object RestorePrep {

    enum class FailureKind { CANNOT_READ, NO_SEP, NO_ROOM }

    class Failure(message: String, val kind: FailureKind) : Exception(message)

    class Inputs(
        /** Архив прошивки, как он скачан. */
        val ipsw: File,
        /** `ticket.shsh2`, тикет, из которого делаются оба подделанных. */
        val shsh2: File,
        /** `AppleSEPROM-Cebu-B1`, если его ещё нет на месте. */
        val sepROM: File?,
        /** Ключ прошивки SEP ровно для этой сборки: 96 hex-цифр, IV первым. */
        val sepKey: String,
        /** Собственный Cryptex1 IM4M любого настоящего устройства — только
         * iOS 16+ его просит, см. `Cryptex1`. Пропущенный целиком, рестор iOS 14
         * его и не заметит.
         */
        val cryptexTemplate: File?,
    )

    /** Диски, которые хочет машина, и размер каждого.
     *
     * `root` — 32 ГБ дыр и не стоит ничего, пока восстановление его не
     * заполнит, и каждый из них начинается пустым, как их делает руководство
     * ChefKiss'а.
     *
     * Четыре из них — `effaceable`, `nvram`, `sep_nvram` и `sep_ssc` — когда-то
     * заполнялись копиями с настоящего устройства, чтобы пройти SEP-панику
     * (`sars`) посреди восстановления. То лекарство было хуже: восстановление с
     * ними заканчивается, но система, которую оно оставляет, не может открыть
     * собственный том данных — ни `Unlock notification` в журнале, ни ключа для
     * тома — и никогда не доходит до экрана. Пустыми восстановление проходит
     * вовсе без паники, и система поднимается.
     */
    val disks: List<Pair<String, Long>> = listOf(
        "root" to (32L shl 30),
        "firmware" to (8L shl 20),
        "syscfg" to (128L shl 10),
        "ctrl_bits" to (8L shl 10),
        "nvram" to (8L shl 10),
        "effaceable" to (4L shl 10),
        "panic_log" to (1L shl 20),
        "sep_nvram" to (64L shl 10),
        "sep_ssc" to (128L shl 10),
    )

    /** Делает весь набор. Блокирует; звать не с главного потока. */
    fun prepare(
        inputs: Inputs,
        note: (String) -> Unit,
        progress: (Double) -> Unit = {},
    ) {
        val data = VMConfig.dataDirectory
        data.mkdirs()

        // 1. Диски.
        for ((name, size) in disks) {
            val disk = File(data, name)
            if (!disk.isFile) {
                makeBlank(disk, size)
                note(L("Подготовка: создан %@", name))
            }
        }

        // 2. Сам архив, занесённый на телефон, если его там ещё нет. Всё
        // дальнейшее — распаковка здесь и передача ASR позже, минутами, —
        // читает его много раз.
        val localIPSW = localCopy(inputs.ipsw, data, note)

        // 3. То, что грузит эмулятор, из архива. Образ системы и криптексы —
        // гигабайты, которых он никогда не трогает: восстановление читает их
        // прямо из архива.
        val ipsw = IPSW(localIPSW)
        val identity = ipsw.buildIdentity()
        val skip = bigImages(ipsw)
        val wanted = ipsw.memberNames.filter { !skip.contains(it) && !it.endsWith("/") }
        note(L("Подготовка: распаковываю %d файлов", wanted.size))
        val restore = File(data, "Restore")
        for ((index, path) in wanted.sorted().withIndex()) {
            val destination = File(restore, path)
            destination.parentFile?.mkdirs()
            ipsw.extract(path, destination)
            progress((index + 1).toDouble() / wanted.size)
        }

        // 4. Тикеты. Ни один не подписан Apple, и ни один Apple не спрашивает.
        val shsh = try {
            inputs.shsh2.readBytes()
        } catch (_: Throwable) {
            throw Failure(L("Не читается: %@", inputs.shsh2.name), FailureKind.CANNOT_READ)
        }
        val apTicket = Ticket.forgeAP(shsh, identity)
        File(data, "root_ticket.der").writeBytes(apTicket)
        note(L("Подготовка: тикет AP, %d байт", apTicket.size))

        // 5. Прошивка SEP, расшифрованная ключом с вики и под поручительством
        // тикета SEP.
        val sepTicket = Ticket.forgeSEP(shsh, identity)
        val sepPath = IPSW.path("SEP", identity) ?: throw Failure(L("В прошивке нет SEP — не тот архив?"), FailureKind.NO_SEP)
        val key = SEPFirmware.Key.parse(inputs.sepKey)
        val firmware = SEPFirmware.rebuild(
            encrypted = ipsw.read(sepPath),
            key = key,
            ticket = sepTicket,
        )
        File(data, "sep-firmware.n104.RELEASE.new.img4").writeBytes(firmware)
        note(L("Подготовка: прошивка SEP, %d байт", firmware.size))

        // 6. SEP ROM — это загрузка, а не то, что можно сделать.
        inputs.sepROM?.let { rom ->
            if (!rom.isFile) {
                throw Failure(L("Не читается: %@", rom.name), FailureKind.CANNOT_READ)
            }
            val destination = VMConfig.sepROM
            try {
                destination.delete()
            } catch (_: Throwable) {
            }
            rom.copyTo(destination)
            note(L("Подготовка: SEP ROM на месте"))
        }

        // 7. Шаблон Cryptex1 — только iOS 16+, и подойдёт собственный тикет
        // любого настоящего устройства; `RestoreClient` читает свою копию из
        // InfernoData, как и всё остальное здесь.
        inputs.cryptexTemplate?.let { template ->
            if (!template.isFile) {
                throw Failure(L("Не читается: %@", template.name), FailureKind.CANNOT_READ)
            }
            try {
                VMConfig.cryptexTemplate.delete()
            } catch (_: Throwable) {
            }
            template.copyTo(VMConfig.cryptexTemplate)
            note(L("Подготовка: шаблон Cryptex1 на месте"))
        }

        // Само восстановление читает архив отсюда — указывает ему на локальную
        // копию, а не на выбранное в файлах.
        RestoreSession.shared.remember(localIPSW)

        note(L("Готово. Дальше: настройки → «Восстановление» → «Начать рестор»"))
    }

    /** Заносит архив на телефон, если его там ещё нет, с прогрессом: это
     * гигабайты, и такое молчаливое ожидание выглядит как зависание.
     *
     * Уже-локальный узнаётся по размеру против источника, а не просто по
     * существованию имени — копия, оборванная прошлой неудачей, тоже файл на
     * том же пути, и счесть её законченной отдавало `IPSW` усечённый архив без
     * способа объяснить человеку, почему тот не разбирается.
     */
    private fun localCopy(source: File, into: File, note: (String) -> Unit): File {
        val destination = File(into, source.name)
        if (source.absolutePath == destination.absolutePath) return source

        if (!source.isFile) {
            throw Failure(L("Не читается: %@", source.name), FailureKind.CANNOT_READ)
        }
        val size = source.length()

        if (destination.isFile && destination.length() == size && size > 0) {
            note(L("Подготовка: прошивка уже скопирована"))
            return destination
        }
        try {
            destination.delete()
        } catch (_: Throwable) {
        }

        val free = EngineEnv.freeSpace()
        if (free != null && free < size + (1L shl 30)) {
            throw Failure(
                L("Мало места для копии прошивки: нужно %.1f ГБ, свободно %.1f",
                    (size + (1L shl 30)).toDouble() / 1_073_741_824,
                    free.toDouble() / 1_073_741_824),
                FailureKind.NO_ROOM,
            )
        }

        note(L("Подготовка: копирую прошивку, %.1f ГБ", size.toDouble() / 1_073_741_824))
        var copied = 0L
        var lastNote = 0.0
        FileInputStream(source).use { reader ->
            FileOutputStream(destination).use { writer ->
                val buffer = ByteArray(8 shl 20)
                while (true) {
                    val n = reader.read(buffer)
                    if (n <= 0) break
                    writer.write(buffer, 0, n)
                    copied += n
                    val now = System.nanoTime() / 1_000_000_000.0
                    if (size > 0 && now - lastNote >= 2) {
                        lastNote = now
                        note(
                            L("Подготовка: копирую прошивку, %d %%",
                                (copied.toDouble() / size.toDouble() * 100).toInt()),
                        )
                    }
                }
            }
        }
        note(L("Подготовка: прошивка скопирована"))
        return destination
    }

    /** Образы, которые восстановление льёт из архива, не распаковывая. */
    private fun bigImages(ipsw: IPSW): Set<String> {
        val identity = try {
            ipsw.buildIdentity()
        } catch (_: Throwable) {
            return emptySet()
        }
        val manifest = identity.dict("Manifest") ?: return emptySet()
        val skip = mutableSetOf<String>()
        for ((component, entry) in manifest) {
            if (component != "OS" && !component.startsWith("Cryptex1,")) continue
            val path = entry.asDict()?.dict("Info")?.str("Path") ?: continue
            if (path.endsWith(".dmg")) skip.add(path)
        }
        return skip
    }

    /** Файл нужного размера и ничего в нём. Разреженный: `root` на бумаге
     * 32 ГБ и несколько блоков на диске, пока что-то туда не пишется.
     */
    private fun makeBlank(url: File, size: Long) {
        url.parentFile?.mkdirs()
        RandomAccessFile(url, "rw").use { it.setLength(size) }
    }

    private fun File.copyTo(destination: File) {
        FileInputStream(this).use { reader ->
            FileOutputStream(destination).use { writer ->
                reader.copyTo(writer, 1 shl 20)
            }
        }
    }
}
