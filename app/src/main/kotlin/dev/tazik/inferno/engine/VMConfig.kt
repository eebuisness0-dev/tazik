package dev.tazik.inferno.engine

import dev.tazik.inferno.data.PlistParser
import java.io.File
import java.io.RandomAccessFile

/**
 * Порт `VMConfig.swift`: собирает командную строку эмулятора и говорит, чего
 * не хватает.
 *
 * Раскладка та же, что в desktop-комплекте: каталог `InfernoData` плюс SEP ROM,
 * положенные в папку приложения.
 */
class VMConfig {
    var cores: Int = 4          // 3 ядра CPU + ядро SEP
    var memory: String = "3G"
    var vncPort: Int = 5900
    var serialPort: Int = 4555
    var qmpPort: Int = 4556
    var tbSize: Int = 128

    /** Ядра самого устройства вместо транслятора — на Android недоступно. */
    var virtualization: Boolean = false

    /**
     * Обратный tethered-интернет через USB-порт гостя: эмулятор играет
     * USB-хост, поднимает CDC-NCM и NAT через slirp.
     *
     * На Android `apple-ncm-host` делит тот же loopback-порт с `GuestUSB`,
     * поэтому по умолчанию выключен: сначала должен подняться USB-хост
     * восстановления, а сеть включает экран настроек, когда он свободен.
     */
    var network: Boolean = false

    /** Совсем без экрана: гость доступен только через консоль. */
    var headless: Boolean = false

    /** Читать framebuffer на месте, а не через VNC на loopback. */
    var builtInDisplay: Boolean = true

    var audio: Boolean = false
    var displayWidth: Int = 828
    var displayHeight: Int = 1792
    var displayScale: Int = 2

    /** Загрузить этот рамдиск вместо установленной системы: так идёт рестор. */
    var restoreRamdiskPath: String? = null

    /** Отдать USB-порт другой машине через VirtualHere (`host:port`). */
    var usbExport: String? = null

    companion object {
        /** Папка приложения, которую видно в «Файлах». */
        val documents: File get() = EngineEnv.documents

        val dataDirectory: File get() = EngineEnv.dataDirectory()

        /** Общая область обмена: приложение пишет, гость читает как блочное устройство. */
        val transferImage: File get() = EngineEnv.transferImage()

        /** 16 МиБ, разрежённо: места не занимает, пока не понадобится. */
        const val transferBytes: Long = 16L * 1024 * 1024

        fun ensureTransferImage() {
            val path = transferImage
            if (path.exists()) return
            try {
                path.parentFile?.mkdirs()
                RandomAccessFile(path, "rw").use { it.setLength(transferBytes) }
            } catch (_: Throwable) {
            }
        }

        /** В Swift здесь был chdir эмулятора; на Android сокеты TCP, каталог — cache. */
        val socketDirectory: File get() = socketDirectory()

        val usbSocketName: String = "inferno-usb.sock"

        /** Всё, что печатает гость, с первого байта, на диске. */
        val guestConsoleLog: File get() = EngineEnv.guestConsoleLog()

        val sepROM: File get() = EngineEnv.sepRom()

        val sepROMPresent: Boolean get() = sepROM.isFile

        /** Чужой настоящий Cryptex1 IM4M — только iOS 16+. */
        val cryptexTemplate: File get() = File(dataDirectory, "cryptex_template.im4m")

        val cryptexTemplatePresent: Boolean get() = cryptexTemplate.isFile

        /**
         * Есть ли на диске система вообще.
         *
         * Свежесозданный диск — 32 ГБ нулей, и загрузка с него не падает: машина
         * просто ищет, что запустить, что снаружи неотличимо от зависания.
         */
        val systemInstalled: Boolean
            get() {
                val image = rootImage ?: return false
                if (image.format == "qcow2") return qcow2HasAllocatedData(File(image.path))
                val file = File(image.path)
                if (!file.isFile) return false
                return try {
                    RandomAccessFile(file, "r").use { raf ->
                        val head = ByteArray(64 * 1024)
                        val n = raf.read(head)
                        (0 until n).any { head[it] != 0.toByte() }
                    }
                } catch (_: Throwable) {
                    false
                }
            }

        /**
         * Было ли в qcow2 хоть что-то записано: у пустого образа таблица L1
         * целиком нулевая.
         */
        private fun qcow2HasAllocatedData(file: File): Boolean {
            if (!file.isFile) return true
            return try {
                RandomAccessFile(file, "r").use { raf ->
                    val header = ByteArray(104)
                    val n = raf.read(header)
                    if (n < 48) return true
                    if (!header.copyOfRange(0, 4).contentEquals(byteArrayOf(0x51, 0x46, 0x49, 0xFB.toByte()))) {
                        return true
                    }
                    val l1Size = u32be(header, 36).toInt()
                    val l1Offset = u64be(header, 40)
                    if (l1Size <= 0) return false
                    raf.seek(l1Offset)
                    val table = ByteArray(l1Size * 8)
                    val got = raf.read(table)
                    (0 until got).any { table[it] != 0.toByte() }
                }
            } catch (_: Throwable) {
                true
            }
        }

        /** Образ устройства: сырой файл из комплекта или qcow2-конверсия. */
        val rootImage: Image?
            get() {
                val qcow = File(dataDirectory, "root.qcow2")
                if (qcow.isFile) return Image(qcow.path, "qcow2")
                val raw = File(dataDirectory, "root")
                if (raw.isFile) return Image(raw.path, "raw")
                return null
            }

        data class Image(val path: String, val format: String)

        /** Рамдиск, с которого идёт рестор, если его положили рядом с прошивкой. */
        val restoreRamdisk: File?
            get() {
                val manifest = buildManifest() ?: return null
                val name = manifest.path("RestoreRamDisk") ?: return null
                val file = File(File(dataDirectory, "Restore"), name)
                return if (file.isFile) file else null
            }

        data class Firmware(
            var kernel: String,
            var deviceTree: String,
            var trustcache: String,
        )

        val firmware: Firmware
            get() {
                val fallback = Firmware(
                    kernel = "Restore/kernelcache.release.iphone12b",
                    deviceTree = "Restore/Firmware/all_flash/DeviceTree.n104ap.im4p",
                    trustcache = "Restore/Firmware/038-44135-124.dmg.trustcache",
                )
                val manifest = buildManifest() ?: return fallback
                val ramdisk = manifest.path("RestoreRamDisk")
                val trustcache = manifest.path("RestoreTrustCache")
                    ?: ramdisk?.let { "Firmware/$it.trustcache" }
                val kernel = manifest.path("KernelCache") ?: return fallback
                val tree = manifest.path("DeviceTree") ?: return fallback
                if (trustcache == null) return fallback
                val resolved = Firmware(
                    kernel = "Restore/$kernel",
                    deviceTree = "Restore/$tree",
                    trustcache = "Restore/$trustcache",
                )
                // Манифест, который называет файлы, которых никто не скопировал,
                // хуже отсутствия манифеста: машина откажется стартовать.
                val present = listOf(resolved.kernel, resolved.deviceTree, resolved.trustcache)
                    .all { usable(File(dataDirectory, it)) }
                return if (present) resolved else fallback
            }

        /** Тождество стирания для iPhone 11 из `BuildManifest.plist`. */
        private class BuildIdentity(val manifest: Map<String, Any?>) {
            fun path(component: String): String? {
                val entry = manifest[component] as? Map<*, *> ?: return null
                val info = entry["Info"] as? Map<*, *> ?: return null
                return info["Path"] as? String
            }
        }

        private var cachedManifest: Pair<Long, BuildIdentity?>? = null
        private val manifestLock = Any()

        /**
         * Тождество стирания, разобранное один раз.
         *
         * Файл — полмегабайта, а спрашивают его из тел экранов: во время
         * рестора те перерисовываются так же часто, как движется образ.
         * Перечитывается только когда меняется сам файл.
         */
        private fun buildManifest(): BuildIdentity? {
            val file = File(File(dataDirectory, "Restore"), "BuildManifest.plist")
            val stamp = file.lastModified()
            synchronized(manifestLock) {
                cachedManifest?.let { (cachedStamp, identity) ->
                    if (cachedStamp == stamp) return identity
                }
            }
            val found = parseManifest(file) ?: return null
            synchronized(manifestLock) {
                cachedManifest = stamp to found
            }
            return found
        }

        private fun parseManifest(file: File): BuildIdentity? {
            if (!file.isFile) return null
            val plist = try {
                file.inputStream().use { PlistParser.parse(it) }
            } catch (_: Throwable) {
                null
            } ?: return null
            val identities = plist["BuildIdentities"] as? List<*> ?: return null
            for (raw in identities) {
                val identity = raw as? Map<*, *> ?: continue
                val info = identity["Info"] as? Map<*, *> ?: continue
                if ((info["DeviceClass"] as? String)?.lowercase() != "n104ap") continue
                if ((info["Variant"] as? String)?.contains("Erase") != true) continue
                val manifest = identity["Manifest"] as? Map<*, *> ?: continue
                @Suppress("UNCHECKED_CAST")
                return BuildIdentity(manifest as Map<String, Any?>)
            }
            return null
        }

        /** Всё, что машине нужно на диске, в порядке, в котором это чинят. */
        val requiredFiles: List<Pair<String, String>> = listOf(
            L("Прошивка NVMe") to "InfernoData/firmware",
            "syscfg" to "InfernoData/syscfg",
            "ctrl_bits" to "InfernoData/ctrl_bits",
            "nvram" to "InfernoData/nvram",
            "effaceable" to "InfernoData/effaceable",
            "panic_log" to "InfernoData/panic_log",
            "SEP nvram" to "InfernoData/sep_nvram",
            "SEP ssc" to "InfernoData/sep_ssc",
            L("Тикет") to "InfernoData/root_ticket.der",
            L("Прошивка SEP") to "InfernoData/sep-firmware.n104.RELEASE.new.img4",
            "SEP ROM" to "AppleSEPROM-Cebu-B1",
        )

        fun missingFiles(): List<String> {
            val loaded = firmware
            val all = requiredFiles + listOf(
                "Kernelcache" to "InfernoData/" + loaded.kernel,
                "Device tree" to "InfernoData/" + loaded.deviceTree,
                "TrustCache" to "InfernoData/" + loaded.trustcache,
            )
            val missing = all.mapNotNull { (label, relative) ->
                val file = EngineEnv.absoluteOf(relative)
                if (usable(file)) null else label
            }.toMutableList()
            if (rootImage == null) {
                missing.add(0, L("Диск устройства (root.qcow2 или root)"))
            }
            return missing
        }

        /**
         * Является ли файл, нужный эмулятору, действительно файлом.
         *
         * `exists()` недостаточно: распакованный архив может оставить *папку*
         * с именем `firmware`, проверка пройдёт, старт включится, а QEMU
         * скажет `'file' driver requires '…/firmware' to be a regular file` и
         * вызовет `exit(1)` внутри нашего же процесса.
         */
        fun usable(file: File): Boolean = file.isFile && file.length() > 0

        /** Командная строка ядра. */
        var bootArguments: String = "tlto_us=-1 agm-genuine=1 agm-authentic=1 agm-trusted=1 " +
            "serial=3 wdt=-1 launchd_unexecutable_cache=1 -vm_compressor_wk_sw"
            get() = field.ifBlank {
                "tlto_us=-1 agm-genuine=1 agm-authentic=1 agm-trusted=1 serial=3 wdt=-1 " +
                    "launchd_unexecutable_cache=1 -vm_compressor_wk_sw"
            }
    }

    fun arguments(): List<String> {
        val data = dataDirectory.path
        val sep = sepROM.path
        val loaded = firmware

        val parts = mutableListOf("t8030")
        if (usbExport != null) {
            parts += "usb-uplink-type=virtualhere"
            parts += "usb-uplink-addr=$usbExport"
        } else {
            parts += "usb-uplink-type=inferno"
            parts += "usb-uplink-addr=tcp:127.0.0.1:${EngineEnv.usbPort}"
        }
        parts += listOf(
            "trustcache=$data/${loaded.trustcache}",
            "ticket=$data/root_ticket.der",
            "sep-fw=$data/sep-firmware.n104.RELEASE.new.img4",
            "sep-rom=$sep",
            "kaslr-off=true",
        )
        // Куда машина уходит из recovery, говорится каждый раз, а не берётся из
        // NVRAM: там может остаться `auto-boot=false` от незаконченного рестора.
        parts += if (restoreRamdiskPath == null) "boot-mode=exit_recovery" else "boot-mode=enter_recovery"
        parts += listOf(
            "disp-width=$displayWidth",
            "disp-height=$displayHeight",
            "disp-scale=$displayScale",
        )
        val machine = parts.joinToString(",")

        val dataDir = EngineEnv.qemuDataDir?.absolutePath

        // Ядра: HVF где ядро разрешает, иначе многопоточный TCG. Никогда один
        // поток: SEP и AP должны двигаться вместе, иначе SEP паникует на
        // инициализации key store и гость не загружается.
        val accel = if (virtualization) {
            "hvf"
        } else {
            "tcg,thread=multi,tb-size=$tbSize" + if (JIT.needsSplitWX) ",split-wx=on" else ""
        }

        // argv[0] обязателен: QEMU читает его как имя программы и начинает разбор
        // с индекса 1. Без него список сдвигается, и первый аргумент (`-L`) сам
        // превращается в опцию, а путь к share — в позиционный «диск-образ»,
        // который late-блокдрайвер честно отказывается открывать (это каталог).
        val argv = mutableListOf("qemu-system-aarch64")
        if (dataDir != null) argv += listOf("-L", dataDir)
        argv += listOf(
            "-accel", accel,
            "-M", machine,
            "-kernel", "$data/${loaded.kernel}",
            "-dtb", "$data/${loaded.deviceTree}",
            "-append", bootArguments,
            "-smp", cores.toString(),
            "-m", memory,
            // Консоль пишется в файл, а не только в сокет: сокет выбрасывает всё,
            // что напечатано до подключения клиента, а гость начинает говорить
            // задолго до того, как интерфейс сможет подключиться.
            "-chardev", "socket,id=serial0,host=127.0.0.1,port=$serialPort,server=on,wait=off,logfile=${guestConsoleLog.path},logappend=on",
            "-serial", "chardev:serial0",
            "-qmp", "tcp:127.0.0.1:$qmpPort,server,nowait",
            "-drive", "file=$data/sep_nvram,if=pflash,format=raw",
            "-drive", "file=$data/sep_ssc,if=pflash,format=raw",
        )

        val ramdisk = restoreRamdiskPath
        if (ramdisk != null) {
            argv += listOf("-initrd", ramdisk)
            // Рестор заканчивается просьбой гостя о сбросе, и машина ушла бы
            // вместе с ним — вместе с рамдиском и нашим патчером внутри него.
            argv += listOf("-global", "driver=apple-smc,property=hold-reset,value=on")
        }

        if (!audio) {
            // Тишина просится явно: без audiodev звуковая карта берёт первый
            // выход, который предложит сборка. Глобал написан длинной формой
            // нарочно: короткая режет имя драйвера по первой точке.
            argv += listOf(
                "-audiodev", "none,id=quiet",
                "-global", "driver=apple.mca,property=audiodev,value=quiet",
            )
        }

        if (headless || builtInDisplay) {
            argv += listOf("-display", "none")
        } else {
            argv += listOf("-vnc", "127.0.0.1:${vncPort - 5900}")
        }

        rootImage?.let { root ->
            /* Без cache=root гость упирается в fdatasync растущего qcow2 на
             * флеше телефона: writeback-пробка на ~2 ГБ заливки, деградация
             * темпа и заклинивание. xfer-драйв ниже уже живёт с cache=none.
             * Долговечность NAND при обрыве питания эмулятору не нужна. */
            argv += listOf("-drive", "file=${root.path},format=${root.format},if=none,id=root,cache=unsafe")
            argv += listOf(
                "-device",
                "nvme-ns,drive=root,bus=nvme-bus.0,nsid=1,nstype=1,logical_block_size=4096,physical_block_size=4096",
            )
        }

        // Остальные NVMe-пространства, в том порядке, в котором их ждёт машина.
        val namespaces = listOf(
            Triple("firmware", 2, 2),
            Triple("syscfg", 3, 3),
            Triple("ctrl_bits", 4, 4),
            Triple("effaceable", 6, 6),
            Triple("panic_log", 7, 8),
        )
        for ((name, nsid, nstype) in namespaces) {
            argv += listOf("-drive", "file=$data/$name,format=raw,if=none,id=$name")
            argv += listOf(
                "-device",
                "nvme-ns,drive=$name,bus=nvme-bus.0,nsid=$nsid,nstype=$nstype,logical_block_size=4096,physical_block_size=4096",
            )
        }

        // Общая область: приложение пишет байты в файл, гость читает их прямо
        // с блочного устройства. cache=none здесь не настройка, а условие:
        // иначе эмулятор отвечает из page cache хоста и гость читает то, что
        // файл держал раньше, — это выглядит как испорченная передача.
        val transfer = transferImage
        if (transfer.isFile) {
            argv += listOf("-drive", "file=${transfer.path},format=raw,if=none,id=xfer,cache=none")
            argv += listOf(
                "-device",
                "nvme-ns,drive=xfer,bus=nvme-bus.0,nsid=8,nstype=2,logical_block_size=4096,physical_block_size=4096",
            )
        }

        if (network && usbExport == null) {
            argv += listOf("-netdev", "user,id=net0")
            argv += listOf(
                "-device",
                "apple-ncm-host,netdev=net0,conn-addr=tcp:127.0.0.1:${EngineEnv.usbPort}",
            )
        }

        // nvram — собственный тип устройства, не просто namespace.
        argv += listOf("-drive", "file=$data/nvram,if=none,format=raw,id=nvram")
        argv += listOf(
            "-device",
            "apple-nvram,drive=nvram,bus=nvme-bus.0,nsid=5,nstype=5,id=nvram,logical_block_size=4096,physical_block_size=4096",
        )

        return argv
    }
}
