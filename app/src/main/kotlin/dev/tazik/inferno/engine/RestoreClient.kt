package dev.tazik.inferno.engine

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID

/**
 * Хостовая половина восстановления: всё, что делает `idevicerestore`, когда
 * `restored` гостя ответил.
 *
 * Здесь идут два разговора. Сам `restored` говорит плистами за четырёхбайтной
 * длиной и просит прошивку по куску за раз; ASR — второе соединение вовсе без
 * рамки, по которому образ файловой системы льётся прямо из IPSW — там он
 * хранится несжатым, так что ничего не надо распаковывать заранее, и именно
 * поэтому это помещается на телефоне.
 *
 * Каждый компонент персонализируется одинаково: `IM4P` из IPSW оборачивается
 * вместе с подделанным тикетом. Apple здесь не контактирует никто.
 */
class RestoreClient(
    private val usb: GuestUSB,
    private val ipsw: IPSW,
    private val identity: Map<String, Any?>,
    private val ticket: ByteArray,
    /** Собственный Cryptex1 IM4M настоящего устройства, из которого
     * `Cryptex1.forge` собирает тикет этой сборки — только iOS 16+ его просит.
     */
    private val cryptexTemplate: ByteArray? = null,
    private val note: (String) -> Unit,
    private val progress: (String, Double) -> Unit = { _, _ -> },
) {

    enum class FailureKind { BROKEN, UNSUPPORTED, GUEST_FAILED }

    class Failure(message: String, val kind: FailureKind, val code: Long = 0) : Exception(message)

    /** Когда прогресс передавался в последний раз, чтобы передача на десять
     * мегабайт в секунду не перерисовывала интерфейс восемьдесят раз в секунду.
     */
    private var lastProgress = 0.0

    /** Последняя живая активность беседы: сообщение от гостя или наш доклад
     * прогресса (заливка рапортует мимо контрольного канала — через ASR).
     * Пульс тишины в run() читает это, чтобы рабочее молчание контрольного
     * канала не называть смертью гостя.
     */
    private val lastActivity = java.util.concurrent.atomic.AtomicLong(
        System.currentTimeMillis() / 1000,
    )

    private fun reportProgress(what: String, fraction: Double) {
        val now = System.nanoTime() / 1_000_000_000.0
        lastActivity.set(System.currentTimeMillis() / 1000)
        if (now - lastProgress < 0.25 && fraction < 1) return
        lastProgress = now
        progress(what, fraction)
    }

    // MARK: - Прогон

    /**
     * Ведёт восстановление от `StartRestore` до финального статуса гостя.
     *
     * Блокируется, пока гость не закончит — минуты работы; звать не с
     * главного потока.
     */
    fun run(on: GuestUSB.Channel, protocolVersion: Int) {
        send(on, startRestore(protocolVersion))
        note(L("Рестор: начат, протокол %d", protocolVersion))

        /* Пульс тишины. Restored подолгу молчит в контрольном канале: заливка
         * рапортует мимо него (через ASR), финальные шаги установки молчат
         * вовсе (run61: 15 минут на 98-м проценте). Голая тишина
         * неотличима от смерти — раз в минуту докладываем, сколько ждём,
         * со счётчиком от последней живой активности (сообщение гостя или
         * наш прогресс). */
        val pulse = Thread {
            while (true) {
                try {
                    Thread.sleep(60_000)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                val quiet = System.currentTimeMillis() / 1000 - lastActivity.get()
                if (quiet >= 60) {
                    note(L("Рестор: тишина %d с — фаза без процентов, ждём", quiet))
                    // Кто именно занят в стоянии: крутится транслятор, стоит
                    // поток устройства или висит диск — решающая разница.
                    if (quiet >= 120) Sampler.report { note(it) }
                }
            }
        }
        pulse.isDaemon = true
        pulse.start()
        try {
            while (true) {
                /* Финальные шаги установки restored делает БЕЗ единого сообщения
                 * в хост: чистка ключей EAN, персонализация, верификация — тихо
                 * и подолгу (наблюдение run61: 15 минут молчания на 98%, а потом
                 * финальный статус). 900 с здесь убивали живой разговор за
                 * несколько секунд до финиша. Даём час: гость на связи (mux
                 * жив, данные текут), просто молчит. */
                val message = receive(on, timeout = 3600.0)
                lastActivity.set(System.currentTimeMillis() / 1000)
                val type = message.str("MsgType") ?: run {
                    note(L("Рестор: сообщение без типа"))
                    continue
                }
                when (type) {
                    // Устройство просит по одному куску прошивки за раз. Асинхронные
                    // запросы тоже отвечаются на месте: они несут собственный порт,
                    // так что ничего не теряется, отвечая по очереди.
                    "DataRequestMsg", "AsyncDataRequestMsg" -> answer(message, on)

                    "ProgressMsg" -> {
                        val percent = message.double("Progress") ?: 0.0
                        val operation = message.long("Operation") ?: 0
                        if (percent <= 100) reportProgress(operationName(operation), percent / 100)
                    }

                    "StatusMsg" -> {
                        val log = message.str("Log")
                        if (!log.isNullOrEmpty()) note(L("Рестор: журнал гостя — %@", log))
                        val status = message.long("Status") ?: 0
                        if (status != 0L) {
                            throw Failure(
                                L("Рестор: гость прервал установку, код %@", statusName(status)),
                                FailureKind.GUEST_FAILED,
                                status,
                            )
                        }
                        send(on, mapOf("MsgType" to "ReceivedFinalStatusMsg"))
                        note(L("Рестор: гость доложил, что закончил"))
                        return
                    }

                    "CheckpointMsg" -> {
                        val name = message.str("CHECKPOINT_NAME") ?: "?"
                        val result = message.long("CHECKPOINT_RESULT") ?: 0
                        if (result != 0L) note(L("Рестор: этап %@ вернул %d", name, result))
                    }

                    "PreviousRestoreLogMsg" -> {
                        val log = message.str("PreviousRestoreLog")
                        if (log != null) note(L("Рестор: журнал прошлой попытки — %@", log))
                    }

                    "CrashLog", "RestoredCrash" -> note(L("Рестор: гость уронил restored"))

                    else -> note(L("Рестор: сообщение %@ пропущено", type))
                }
            }
        } finally {
            pulse.interrupt()
        }
    }

    // MARK: - StartRestore

    /**
     * Опции, которые `idevicerestore` шлёт устройству (в отличие от Mac).
     *
     * `SupportedDataTypes` и `SupportedMessageTypes` переписаны из Apple
     * Configurator как есть: булевы в них не значат «хост умеет это» —
     * `RootTicket` и `NORData` лежат там как false и запрашиваются, и
     * отвечаются всё равно, — так что «чинить» их не надо.
     */
    private fun startRestore(protocolVersion: Int): Map<String, Any?> {
        val options = linkedMapOf<String, Any?>(
            "AutoBootDelay" to 0,
            "BootImageType" to "User",
            "DFUFileType" to "RELEASE",
            "DataImage" to false,
            "FirmwareDirectory" to ".",
            "FlashNOR" to true,
            "KernelCacheType" to "Release",
            "NORImageType" to "production",
            "RestoreBundlePath" to "/tmp/Per2.tmp",
            "SystemImageType" to "User",
            "UpdateBaseband" to true,
            "InstallDiags" to false,
            "PersonalizedDuringPreflight" to true,
            "RootToInstall" to false,
            "CreateFilesystemPartitions" to true,
            "SystemImage" to true,
            "UUID" to UUID.randomUUID().toString(),
            "SupportedDataTypes" to supportedDataTypes,
            "SupportedMessageTypes" to supportedMessageTypes,
        )
        val info = identity.dict("Info")
        options["SystemPartitionPadding"] = info?.dict("SystemPartitionPadding")
            ?: mapOf(
                "8" to 80, "16" to 160, "32" to 320, "64" to 640,
                "128" to 1280, "256" to 1280, "512" to 1280, "768" to 1280, "1024" to 1280,
            )
        return mapOf(
            "Request" to "StartRestore",
            "Label" to "Inferno",
            "RestoreOptions" to options,
            "RestoreProtocolVersion" to protocolVersion,
        )
    }

    // MARK: - Ответ на запрос данных

    private fun answer(request: Map<String, Any?>, restored: GuestUSB.Channel) {
        val type = request.str("DataType") ?: run {
            note(L("Рестор: запрос без типа данных"))
            return
        }
        val arguments = request.dict("Arguments") ?: emptyMap()
        note(L("Рестор: гость просит %@", type))

        when (type) {
            "SystemImageData" -> sendFilesystem(request)

            "RootTicket" -> reply(mapOf("RootTicketData" to ticket), request, restored)

            "KernelCache", "DeviceTree" ->
                reply(mapOf("${type}File" to personalized(type)), request, restored)

            "SystemImageRootHash" ->
                reply(mapOf("SystemImageRootHashFile" to personalized("SystemVolume")), request, restored)

            "SystemImageCanonicalMetadata" ->
                reply(
                    mapOf(
                        "SystemImageCanonicalMetadataFile" to
                            personalized("Ap,SystemVolumeCanonicalMetadata"),
                    ),
                    request,
                    restored,
                )

            "NORData" -> reply(norData(arguments), request, restored)

            "BuildIdentityDict" -> reply(
                mapOf(
                    "BuildIdentityDict" to identity,
                    "Variant" to (arguments.str("Variant") ?: "Erase"),
                ),
                request,
                restored,
            )

            "ReceiptManifest" -> reply(
                mapOf("ReceiptManifest" to (identity.dict("Manifest") ?: emptyMap<String, Any?>())),
                request,
                restored,
            )

            // То, что шлёт iTunes, — пустой словарь, и этого хватает, чтобы
            // устройство продолжило с FDR. Оба preflight'а (`idevicerestore`
            // отвечает им той же самой функцией) берут то же ничто.
            "FDRTrustData", "FirmwareUpdaterPreflight", "DeviceRestoreInfoPreflight" ->
                reply(emptyMap(), request, restored)

            // Семейство запросов, которые значат одно: пройтись по манифесту за
            // компонентами какого-то вида и выслать либо их имена, либо их
            // персонализированные экземпляры.
            "PersonalizedData" ->
                reply(imageData(arguments, list = "ImageList", type = null, data = "ImageData"), request, restored)

            "FUDData" ->
                reply(
                    imageData(arguments, list = "FUDImageList", type = "IsFUDFirmware", data = "FUDImageData"),
                    request,
                    restored,
                )

            "EANData" ->
                reply(
                    imageData(arguments, list = "EANImageList", type = "IsEarlyAccessFirmware", data = "EANData"),
                    request,
                    restored,
                )

            "PersonalizedBootObjectV3" -> sendBootObject(request, restored, personalize = true)

            "SourceBootObjectV4", "SourceBootObjectV5" -> sendBootObject(request, restored, personalize = false)

            "FirmwareUpdaterData" -> {
                val updater = arguments.str("MessageArgUpdaterName") ?: "?"
                // Собственные OS- и app-криптексы iOS 16 — всё остальное здесь —
                // прошивка сопроцессора, которого у эмулируемого телефона нет,
                // и подписать их может только Apple.
                if (updater != "Cryptex1" && updater != "Cryptex1LocalPolicy") {
                    throw Failure(
                        L("Рестор: этого я не умею — %@",
                            L("прошивка сопроцессора %@ — её подписывает Apple", updater)),
                        FailureKind.UNSUPPORTED,
                    )
                }
                if (cryptexTemplate == null) {
                    throw Failure(
                        L("Рестор: этого я не умею — %@", L("нет шаблона Cryptex1 — см. RESTORE.md")),
                        FailureKind.UNSUPPORTED,
                    )
                }
                val responseKey = arguments.dict("DeviceGeneratedTags")
                    ?.list("ResponseTags")
                    ?.firstOrNull() as? String ?: "Cryptex1,Ticket"
                val im4m = Cryptex1.forge(identity, cryptexTemplate)
                note(L("Рестор: подписан тикет Cryptex1, %d байт", im4m.size))
                reply(
                    mapOf("FirmwareResponseData" to mapOf(responseKey to im4m)),
                    request,
                    restored,
                )
            }

            else -> throw Failure(
                L("Рестор: этого я не умею — %@", type),
                FailureKind.UNSUPPORTED,
            )
        }
    }

    /** Куда идёт ответ. С iOS 17 гость называет собственный порт для каждого
     * запроса; до этого всё едет по каналу `restored`.
     */
    private fun reply(body: Map<String, Any?>, request: Map<String, Any?>, restored: GuestUSB.Channel) {
        val port = dataPort(request)
        if (port != null) {
            val channel = usb.connect(port, timeout = 60.0)
            try {
                send(channel, body)
            } finally {
                channel.close()
            }
        } else {
            send(restored, body)
        }
    }

    private fun dataPort(request: Map<String, Any?>): Int? {
        val port = request.long("DataPort") ?: return null
        if ((port and 0xFFFFL) == 0L) return null
        return (port and 0xFFFFL).toInt()
    }

    // MARK: - Компоненты

    /** Один компонент прошивки, обёрнутый нашим тикетом. */
    private fun personalized(component: String): ByteArray =
        IMG4.make(IMG4.readIM4P(raw(component)), ticket)

    private fun raw(component: String): ByteArray {
        val path = IPSW.path(component, identity)
            ?: throw Failure(
                L("Рестор: этого я не умею — %@", L("в манифесте нет компонента %@", component)),
                FailureKind.UNSUPPORTED,
            )
        return ipsw.read(path)
    }

    /** Всё, что идёт в NOR: цепочка загрузки и SEP. */
    private fun norData(arguments: Map<String, Any?>): Map<String, Any?> {
        val body = linkedMapOf<String, Any?>(
            "LlbImageData" to personalized("LLB"),
        )

        // `idevicerestore` предпочитает файл `manifest` рядом с LLB и
        // возвращается к тождеству сборки. Ни один IPSW с iOS 14 его не несёт,
        // так что путь через fallback — настоящий: каждая прошивка payload,
        // плюс вторичные, которые iBoot грузит сам.
        val names = firmwareComponents().toMutableSet()
        names.remove("LLB") // уже выслан, в собственном ключе
        names.remove("RestoreSEP") // выслан ниже, в собственном ключе

        // iBoot должен прийти первым; остальным задан устойчивый порядок,
        // чтобы два прогона одного восстановления посылали одни и те же байты.
        val images = ArrayList<Pair<String, ByteArray>>()
        for (name in names.sorted()) {
            val blob = personalized(name)
            if (name.startsWith("iBoot")) images.add(0, name to blob) else images.add(name to blob)
        }
        body["NorImageData"] = if (arguments["FlashVersion1"] != null) {
            images.associate { it.first to it.second }
        } else {
            images.map { it.second }
        }

        for ((component, key) in listOf(
            "RestoreSEP" to "RestoreSEPImageData",
            "SEP" to "SEPImageData",
            "SepStage1" to "SEPPatchImageData",
        )) {
            if (IPSW.path(component, identity) == null) continue
            body[key] = personalized(component)
        }
        return body
    }

    /** Ответ на `PersonalizedData` и его родню.
     *
     * Гость хочет либо список компонентов какого-то вида — вид это флаг в
     * каждой записи манифеста, вроде `IsFUDFirmware`, — либо сами компоненты,
     * один по имени или все разом.
     */
    private fun imageData(
        arguments: Map<String, Any?>,
        list: String,
        type: String?,
        data: String,
    ): Map<String, Any?> {
        val wantList = arguments.flag(list)
        val imageName = arguments.str("ImageName")
        val kind = type ?: arguments.str("ImageType")
            ?: throw Failure(L("Рестор: гость сказал не то — %@", "ImageType"), FailureKind.BROKEN)
        val manifest = identity.dict("Manifest")
            ?: throw Failure(
                L("Рестор: этого я не умею — %@", L("в манифесте нет компонента %@", kind)),
                FailureKind.UNSUPPORTED,
            )

        val names = mutableListOf<String>()
        val blobs = linkedMapOf<String, Any?>()
        for ((component, entry) in manifest.entries.sortedBy { it.key }) {
            val info = entry.asDict()?.dict("Info") ?: continue
            if (!info.flag(kind)) continue
            if (wantList) {
                names.add(component)
            } else if (imageName == null || imageName == component) {
                blobs[component] = personalized(component)
            }
        }

        if (wantList) {
            note(L("Рестор: %@ — %d штук", kind, names.size))
            return mapOf(list to names)
        }
        if (imageName != null) {
            val body = linkedMapOf<String, Any?>("ImageName" to imageName)
            blobs[imageName]?.let { body[data] = it }
            return body
        }
        return mapOf(data to blobs)
    }

    private fun firmwareComponents(): Set<String> {
        val manifest = identity.dict("Manifest") ?: return emptySet()
        val names = mutableSetOf<String>()
        for ((name, entry) in manifest) {
            val info = entry.asDict()?.dict("Info") ?: continue
            if (info["Path"] !is String) continue
            val firmware = info.flag("IsFirmwarePayload")
            val secondary = info.flag("IsSecondaryFirmwarePayload")
            val byBoot = info.flag("IsLoadedByiBoot")
            if (firmware || (secondary && byBoot)) names.add(name)
        }
        return names
    }

    // MARK: - Загрузочные объекты, по кусочкам

    /** Крупные объекты идут потоком сообщений `FileData`, по 8 КБ, закрытых
     * сообщением `FileDataDone`.
     */
    private fun sendBootObject(
        request: Map<String, Any?>,
        restored: GuestUSB.Channel,
        personalize: Boolean,
    ) {
        val arguments = request.dict("Arguments")
            ?: throw Failure(L("Рестор: гость сказал не то — %@", "ImageName"), FailureKind.BROKEN)
        val name = arguments.str("ImageName")
            ?: throw Failure(L("Рестор: гость сказал не то — %@", "ImageName"), FailureKind.BROKEN)

        val data: ByteArray = when (name) {
            "__RestoreVersion__" -> ipsw.read("RestoreVersion.plist")
            "__SystemVersion__" -> ipsw.read("SystemVersion.plist")
            "__GlobalManifest__" ->
                // Глобальная подпись требует манифеста, который Apple подписывает
                // для всей сборки. У восстановления без компьютера один тикет,
                // и это не тот.
                throw Failure(
                    L("Рестор: этого я не умею — %@", L("глобальный манифест — его подписывает Apple")),
                    FailureKind.UNSUPPORTED,
                )
            else -> if (personalize) personalized(name) else raw(name)
        }

        val ownChannel: GuestUSB.Channel?
        val channel: GuestUSB.Channel
        val port = dataPort(request)
        if (port != null) {
            ownChannel = usb.connect(port, timeout = 60.0)
            channel = ownChannel
        } else {
            ownChannel = null
            channel = restored
        }
        try {
            var sent = 0
            while (sent < data.size) {
                val end = minOf(sent + 8192, data.size)
                send(channel, mapOf("FileData" to data.copyOfRange(sent, end)))
                sent = end
                if (data.size > 0x1000000) {
                    reportProgress(name, sent.toDouble() / data.size.toDouble())
                }
            }
            send(channel, mapOf("FileDataDone" to true))
            note(L("Рестор: отдан %@ (%d КБ)", name, data.size / 1024))
        } finally {
            ownChannel?.close()
        }
    }

    // MARK: - Файловая система, по ASR

    /** Льёт образ системы из IPSW.
     *
     * Образ хранится в архиве несжатым, поэтому запросы ASR к произвольным
     * смещениям отвечаются чтением самого файла прошивки — ничего не
     * распаковывается, и в памяти держится только кусок.
     */
    private fun sendFilesystem(request: Map<String, Any?>) {
        val path = IPSW.path("OS", identity)
            ?: throw Failure(
                L("Рестор: этого я не умею — %@", L("в манифесте нет образа системы")),
                FailureKind.UNSUPPORTED,
            )
        val (offset, length) = ipsw.rangeOfStored(path)
        val port = dataPort(request) ?: GuestUSB.Port.ASR.value

        /* Обрыв NAK посередине образа не лечится переподключением к порту:
         * после EOF гость ASR-слушатель больше не поднимает, пока беседа не
         * пойдёт заново. NOT_READY всплывает, и весь разговор перезапускает
         * RestoreSession свежим клиентом — гость заново выпрашивает образ
         * уже на новом порту. */
        sendFilesystemOnce(offset, length, port)
    }

    /** Один подступ к отдаче образа: соединение, договор и помпинг целиком,
     * с чистого листа. Вынесен отдельно, чтобы сбой повторялся внутри. */
    private fun sendFilesystemOnce(offset: Long, length: Long, port: Int) {
        val file = RandomAccessFile(ipsw.url, "r")
        val channel = openASR(port)
        try {
            val asr = ASR(channel)

            note(L("Рестор: ASR на порту %d, образ %d МБ", port, (length / 1024 / 1024).toInt()))

            // Первое слово всегда Initiate, и оно говорит, должен ли каждый
            // кусок нести собственную контрольную сумму.
            val hello = asr.receive()
            if (hello.str("Command") != "Initiate") {
                throw Failure(
                    L("Рестор: гость сказал не то — %@", "ASR: ${hello.str("Command") ?: "?"}"),
                    FailureKind.BROKEN,
                )
            }
            asr.checksumChunks = hello.flag("Checksum Chunks")
            asr.sendPacketInfo(length)

            // Проверка: устройство читает куски образа по смещениям, которые
            // выбирает само, и только потом просит всё.
            while (true) {
                val packet = asr.receive()
                var payload = false
                when (packet.str("Command")) {
                    "Initiate" -> {
                        if (packet["Checksum Chunks"] != null) {
                            asr.checksumChunks = packet.flag("Checksum Chunks")
                        }
                        asr.sendPacketInfo(length)
                    }
                    "OOBData" -> {
                        val oobOffset = packet.long("OOB Offset")
                        val oobLength = packet.long("OOB Length")
                        if (oobOffset == null || oobLength == null) {
                            throw Failure(
                                L("Рестор: гость сказал не то — %@", "ASR: OOB"),
                                FailureKind.BROKEN,
                            )
                        }
                        file.seek(offset + oobOffset)
                        val chunk = ByteArray(oobLength.toInt())
                        file.readFully(chunk)
                        channel.write(chunk)
                    }
                    "Payload" -> payload = true
                    else -> throw Failure(
                        L("Рестор: гость сказал не то — %@", "ASR: ${packet.str("Command") ?: "?"}"),
                        FailureKind.BROKEN,
                    )
                }
                if (payload) break
            }

            note(L("Рестор: образ проверен, отдаю"))
            file.seek(offset)
            // Диагностика целостности: хэш того, что реально читается из .ipsw,
            // против Digest образа в манифесте. Совпало — файл цел, и порча,
            // если она есть, случилась в USB; нет — битый сам архив.
            val manifestDigest = (identity.dict("Manifest")?.get("OS").asDict()?.get("Digest")) as? ByteArray
            val algorithm = when (manifestDigest?.size) {
                20 -> "SHA-1"
                32 -> "SHA-256"
                48 -> "SHA-384"
                else -> null
            }
            val hasher = algorithm?.let { MessageDigest.getInstance(it) }
            note(
                "ASR диагностика: checksumChunks=${asr.checksumChunks}, длина=$length, " +
                    "смещение в архиве=$offset, дайджест в манифесте=" +
                    (algorithm?.let { "$it ${manifestDigest!!.joinToString("") { b -> "%02x".format(b) }}" } ?: "нет"),
            )
            // Long, не Int: образ — гигабайты, и Int заворачивается на 2 ГБ
            // (run57: прогресс уходил в −31,8 % ровно на этой отметке).
            var sent = 0L
            try {
                while (sent < length) {
                    val size = minOf(131072L, length - sent).toInt()
                    var chunk = ByteArray(size)
                    file.readFully(chunk)
                    hasher?.update(chunk)
                    if (asr.checksumChunks) chunk += sha1(chunk)
                    channel.write(chunk)
                    sent += size
                    reportProgress(L("Образ системы"), sent.toDouble() / length.toDouble())
                }
            } catch (e: GuestUSB.Failure) {
                /* Гость, взяв образ, закрывает поток данных — не подтверждая
                 * хвост размером со своё mux-окно (до 16 МБ; run50: GONE в
                 * четырёх мегабайтах от конца — окно не влезло в терпение).
                 * Закрытие посередине тоже не фатально: живой гость сам
                 * доложит провал через контрольный канал, а вот гасить
                 * сессию из-за закрытия — верный способ потерять рестор на
                 * самом финише. */
                if (e.kind != GuestUSB.FailureKind.GONE) throw e
                reportProgress(L("Образ системы"), 1.0)
                note(L("Рестор: гость закрыл канал данных на %d из %d МБ — фаза отдачи окончена, жду его вердикта", sent / 1048576, length / 1048576))
            }
            note(L("Рестор: образ системы отдан целиком"))
            if (hasher != null && sent == length) {
                val got = hasher.digest()
                val same = manifestDigest != null && got.contentEquals(manifestDigest)
                note(
                    "ASR диагностика: хэш отданного $algorithm=" + got.joinToString("") { b -> "%02x".format(b) } +
                        if (same) " — СОВПАЛ с манифестом, архив цел" else " — НЕ СОВПАЛ с манифестом (или нет дайджеста)",
                )
            } else {
                note("ASR диагностика: отдано $sent из $length байт, хэш не сверен")
            }
        } finally {
            try {
                file.close()
            } catch (_: Throwable) {
            }
            channel.close()
        }
    }

    /** Стучится на порт ASR, пока не ответит.
     *
     * Гость не начинает слушать в момент, когда `restored` просит образ —
     * нужны секунды, и соединение, попробованное между делом, отказывается
     * наотрез, а не ждёт. `idevicerestore` стучит тридцать раз, через две
     * секунды, и здесь так же.
     */
    private fun openASR(port: Int): GuestUSB.Channel {
        var lastFailure: Throwable? = null
        for (attempt in 1..30) {
            try {
                // Достаточно долго, чтобы потерянный SYN был отослан снова
                // (см. `GuestUSB.resendIfStuck`) прежде, чем от этого стука
                // откажутся.
                return usb.connect(port, timeout = 20.0)
            } catch (e: Throwable) {
                lastFailure = e
                if (attempt == 1 || attempt % 5 == 0) {
                    note(L("Рестор: ASR на %d ещё не слушает, попытка %d", port, attempt))
                }
                Thread.sleep(2000)
            }
        }
        throw lastFailure ?: Failure(L("Рестор: гость сказал не то — %@", "ASR"), FailureKind.BROKEN)
    }

    /** ASR говорит плистами без длины впереди: сообщение кончается там, где
     * кончается его XML, и их может прийти больше одного за одно чтение.
     */
    private class ASR(val channel: GuestUSB.Channel) {
        var checksumChunks = false
        private var buffer = ByteArray(0)

        fun receive(timeout: Double = 60.0): Map<String, Any?> {
            while (true) {
                val end = endOfPlist(buffer)
                if (end != null) {
                    val raw = buffer.copyOfRange(0, end)
                    buffer = buffer.copyOfRange(end, buffer.size)
                    return PropertyList.parse(raw).asDict() ?: emptyMap()
                }
                buffer += channel.readAvailable(timeout = timeout)
            }
        }

        fun send(body: Map<String, Any?>) {
            channel.write(PropertyList.document(body))
        }

        /** То, чего устройство ждёт после каждого Initiate, и ничто другое его
         * дальше не двигает.
         */
        fun sendPacketInfo(size: Long) {
            val info = linkedMapOf<String, Any?>(
                "FEC Slice Stride" to 40,
                "Packet Payload Size" to 1450,
                "Packets Per FEC" to 25,
                "Payload" to mapOf("Port" to 1, "Size" to size),
                "Stream ID" to 1,
                "Version" to 1,
            )
            if (checksumChunks) info["Checksum Chunk Size"] = 131072
            send(info)
        }

        companion object {
            private val terminator = "</plist>".toByteArray(Charsets.UTF_8)

            private fun endOfPlist(bytes: ByteArray): Int? {
                if (bytes.size < terminator.size) return null
                outer@ for (start in 0..bytes.size - terminator.size) {
                    for (i in terminator.indices) {
                        if (bytes[start + i] != terminator[i]) continue@outer
                    }
                    var end = start + terminator.size
                    while (end < bytes.size && (bytes[end] == 0x0A.toByte() || bytes[end] == 0x0D.toByte())) end += 1
                    return end
                }
                return null
            }
        }
    }

    // MARK: - Рамка и проводка

    companion object {
        /** `restored` говорит плистами за четырёхбайтной длиной, big-endian —
         * та же рамка, что у lockdownd.
         */
        fun send(channel: GuestUSB.Channel, body: Map<String, Any?>) {
            val payload = PropertyList.document(body)
            channel.write(be32(payload.size) + payload)
        }

        fun receive(channel: GuestUSB.Channel, timeout: Double = 60.0): Map<String, Any?> {
            val head = channel.read(4, timeout = timeout)
            val size = u32be(head, 0)
            if (size <= 0 || size >= 64L * 1024 * 1024) {
                // Иногда первая четвёрка байт — уже начало plist'а: префикс
                // длины теряется в USB-канале (обратный случай — он приходит
                // дважды — разобран ниже). Раз ответ всё равно начинается с
                // него, читаем до `</plist>` и разбираем как есть.
                if (head.startsWithText("<?xm") || head.startsWithText("<pli")) {
                    LogCapture.note(L("Рестор: ответ гостя без длины, читаю до конца plist'а"))
                    return receiveUnframed(channel, head, timeout)
                }
                throw Failure(L("Рестор: гость сказал не то — %@", L("пакет длиной %d", size)), FailureKind.BROKEN)
            }
            var raw = channel.read(size.toInt(), timeout = timeout)
            // Иногда гость повторяет четырёхбайтную длину внутри сообщения:
            // приходит `00 00 01 23 00 00 01 23 <?xml ...`. Тогда длина, которую
            // мы прочли, относится к plist'у, а сам plist ещё раз предварён
            // своей длиной — читаем недостающие четыре байта и разбираем со
            // смещением. Без этого рамка разъезжается на 4 байта и следующий
            // ответ читается как мусорная длина.
            if (raw.size >= 4 && u32be(raw, 0) == size) {
                val tail = channel.read(4, timeout = timeout)
                val joined = raw + tail
                LogCapture.note(L("Рестор: повтор длины %d в ответе гостя, разбираю со смещением", size))
                return decode(joined.copyOfRange(4, joined.size), size, allowResync = false)
            }
            return decode(raw, size)
        }

        /**
         * Разбор ответа гостя с ресинхронизацией.
         *
         * Рамка (четыре байта длины) надёжна, но внутри сообщения встречался
         * мусор впереди plist'а — падение разбора роняло весь разговор, а
         * причины до сих пор не видно. Поэтому: при неудаче в лог пишется
         * hex-дамп начала, а разбор повторяется от первого `<?xml` или
         * `bplist`, если такие байты есть.
         */
        private fun receiveUnframed(
            channel: GuestUSB.Channel,
            head: ByteArray,
            timeout: Double,
        ): Map<String, Any?> {
            /* Безрамочный ответ — уже аномалия канала. Ждать стоит секунды,
             * а не унаследованный таймаут run()'а (900 с): конец plist'а,
             * которого нет, никогда не придёт, а рестор просто стоит. Ниже
             * NOT_READY — разговор уйдёт на повтор целиком. */
            val readTimeout = minOf(timeout, 45.0)
            val buf = ByteArrayOutputStream()
            buf.write(head)
            while (true) {
                val text = String(buf.toByteArray(), Charsets.UTF_8)
                if (text.contains("</plist>")) break
                val chunk = try {
                    channel.readAvailable(timeout = readTimeout)
                } catch (e: GuestUSB.Failure) {
                    if (e.kind == GuestUSB.FailureKind.GONE) throw e
                    throw GuestUSB.Failure(L("Рестор: не дождался конца ответа гостя"), GuestUSB.FailureKind.NOT_READY)
                }
                if (chunk.isEmpty()) {
                    throw GuestUSB.Failure(L("Рестор: не дождался конца ответа гостя"), GuestUSB.FailureKind.NOT_READY)
                }
                buf.write(chunk)
            }
            return PropertyList.parse(buf.toByteArray()).asDict() ?: emptyMap()
        }

        private fun ByteArray.startsWithText(text: String): Boolean {
            val needle = text.toByteArray(Charsets.UTF_8)
            if (needle.size > size) return false
            for (i in needle.indices) if (this[i] != needle[i]) return false
            return true
        }

        private fun decode(raw: ByteArray, size: Long, allowResync: Boolean = true): Map<String, Any?> {
            val value = try {
                PropertyList.parse(raw)
            } catch (e: Throwable) {
                val magic = listOf("<?xml", "<plist", "bplist")
                    .mapNotNull { m -> raw.indexOfText(m)?.takeIf { it > 0 } }
                    .minOrNull()
                LogCapture.note(
                    L("Рестор: ответ гостя не разобрался (%@), %d байт из %d, магия на %@: %@",
                        e.message ?: "?", raw.size, size, magic ?: -1, raw.copyOfRange(0, minOf(96, raw.size)).hex()),
                )
                if (magic != null && allowResync) PropertyList.parse(raw.copyOfRange(magic, raw.size)) else throw e
            }
            return value.asDict() ?: emptyMap()
        }

        private fun ByteArray.indexOfText(text: String): Int? {
            val needle = text.toByteArray(Charsets.UTF_8)
            if (needle.isEmpty() || needle.size > size) return null
            for (start in 0..size - needle.size) {
                var ok = true
                for (i in needle.indices) {
                    if (this[start + i] != needle[i]) { ok = false; break }
                }
                if (ok) return start
            }
            return null
        }

        fun request(channel: GuestUSB.Channel, body: Map<String, Any?>): Map<String, Any?> {
            send(channel, body)
            return receive(channel)
        }

        private fun sha1(data: ByteArray): ByteArray =
            MessageDigest.getInstance("SHA-1").digest(data)

        fun statusName(code: Long): String = when (code) {
            -1L -> L("ошибка проверки")
            6L -> L("диск не отвечает")
            14L -> L("сбой")
            27L -> L("файловые системы не смонтировались")
            50L, 51L -> L("прошивка SEP не загрузилась")
            53L -> L("данные FDR не восстановились")
            else -> "$code"
        }

        private fun operationName(code: Long): String = when (code) {
            3L -> L("Очистка")
            8L -> L("Раздел с данными")
            12L -> L("Прошивка NOR")
            13L -> L("Раздел с системой")
            14L -> L("Система")
            19L -> L("Обновление SEP")
            21L -> L("Проверка SEP")
            27L -> L("Дерево устройства")
            else -> L("Установка")
        }

        /** Переписано из Apple Configurator, как у `idevicerestore`. */
        private val supportedDataTypes: Map<String, Any?> = mapOf(
            "AuthInstallCACert" to true,
            "BasebandBootData" to false,
            "BasebandData" to false,
            "BasebandStackData" to false,
            "BasebandUpdaterOutputData" to false,
            "BootabilityBundle" to false,
            "BootabilityBundleV2" to false,
            "BuildIdentityDict" to false,
            "BuildIdentityDictV2" to false,
            "Cryptex1LocalPolicy" to true,
            "DataType" to false,
            "DeviceRestoreInfoPreflight" to false,
            "DiagData" to false,
            "EANData" to false,
            "FDRMemoryCommit" to false,
            "FDRTrustData" to false,
            "FUDData" to false,
            "FileData" to false,
            "FileDataDone" to false,
            "FirmwareUpdaterData" to false,
            "FirmwareUpdaterDataV2" to false,
            "FirmwareUpdaterDataV3" to true,
            "FirmwareUpdaterPreflight" to true,
            "GrapeFWData" to false,
            "HPMFWData" to false,
            "HostSystemTime" to true,
            "KernelCache" to false,
            "MessageUseStreamedImageFile" to true,
            "NORData" to false,
            "NitrogenFWData" to true,
            "OpalFWData" to false,
            "OverlayRootDataCount" to false,
            "OverlayRootDataForKey" to true,
            "OverlayRootDataForKeyIndex" to true,
            "PeppyFWData" to true,
            "PersonalizedBootObjectV3" to false,
            "PersonalizedData" to true,
            "ProvisioningData" to false,
            "RamdiskFWData" to true,
            "ReceiptManifest" to true,
            "RecoveryOSASRImage" to true,
            "RecoveryOSAppleLogo" to true,
            "RecoveryOSDeviceTree" to true,
            "RecoveryOSFileAssetImage" to true,
            "RecoveryOSIBEC" to true,
            "RecoveryOSIBootFWFilesImages" to true,
            "RecoveryOSImage" to true,
            "RecoveryOSKernelCache" to true,
            "RecoveryOSLocalPolicy" to true,
            "RecoveryOSOverlayRootDataCount" to false,
            "RecoveryOSRootTicketData" to true,
            "RecoveryOSStaticTrustCache" to true,
            "RecoveryOSVersionData" to true,
            "RestoreLocalPolicy" to true,
            "RootData" to false,
            "RootTicket" to false,
            "S3EOverride" to false,
            "SourceBootObjectV3" to false,
            "SourceBootObjectV4" to false,
            "SourceBootObjectV5" to false,
            "SsoServiceTicket" to false,
            "StockholmPostflight" to false,
            "SystemImageCanonicalMetadata" to false,
            "SystemImageData" to false,
            "SystemImageRootHash" to false,
            "URLAsset" to true,
            "USBCFWData" to false,
            "USBCOverride" to false,
            "UpdateVolumeOverlayRootDataCount" to true,
        )

        private val supportedMessageTypes: Map<String, Any?> = mapOf(
            "AsyncDataRequestMsg" to true,
            "AsyncWait" to true,
            "BBUpdateStatusMsg" to false,
            "CheckpointMsg" to true,
            "CrashLog" to true,
            "DataRequestMsg" to false,
            "FDRSubmit" to true,
            "MsgType" to false,
            "PreviousRestoreLogMsg" to false,
            "ProgressMsg" to false,
            "ProvisioningAck" to false,
            "ProvisioningInfo" to false,
            "ProvisioningStatusMsg" to false,
            "ReceivedFinalStatusMsg" to false,
            "RestoreAttestation" to true,
            "RestoreProtocol" to true,
            "RestoredCrash" to true,
            "StatusMsg" to false,
        )
    }
}
