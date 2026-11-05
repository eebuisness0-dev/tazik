package dev.tazik.inferno.data

import java.io.File
object GuestData {
    const val ROOT_DISK = "root"
    const val FIRMWARE = "firmware"
    const val SYSCFG = "syscfg"
    const val CTRL_BITS = "ctrl_bits"
    const val NVRAM = "nvram"
    const val EFFACEABLE = "effaceable"
    const val PANIC_LOG = "panic_log"
    const val SEP_NVRAM = "sep_nvram"
    const val SEP_SSC = "sep_ssc"

    data class GuestDiskInfo(val name: String, val bytes: Long, val declaredNsType: Int)

    fun disks(rootSizeGb: Int): List<GuestDiskInfo> = listOf(
        GuestDiskInfo(ROOT_DISK, rootSizeGb * GB, 1),
        GuestDiskInfo(FIRMWARE, 8L * MB, 2),
        GuestDiskInfo(SYSCFG, 128L * KB, 3),
        GuestDiskInfo(CTRL_BITS, 8L * KB, 4),
        GuestDiskInfo(NVRAM, 8L * KB, 5),
        GuestDiskInfo(EFFACEABLE, 4L * KB, 6),
        GuestDiskInfo(PANIC_LOG, 1L * MB, 8),
        GuestDiskInfo(SEP_NVRAM, 64L * KB, 9),
        GuestDiskInfo(SEP_SSC, 128L * KB, 10),
    )

    const val KB = 1024L
    const val MB = 1024L * KB
    const val GB = 1024L * MB

    val REQUIRED_FILES = listOf(
        "root_ticket.der",
        "sep-firmware.n104.RELEASE.new.img4",
        "AppleSEPROM-Cebu-B1",
        "Restore/BuildManifest.plist",
        "Restore/kernelcache.research.iphone12b",
        "Restore/Firmware/all_flash/DeviceTree.n104ap.im4p",
        "Restore/Firmware/038-44135-124.dmg.trustcache",
    )

    fun validate(dir: File): List<PreparedFile> = REQUIRED_FILES.map { rel ->
        val f = File(dir, rel)
        PreparedFile(rel, f.exists() && f.length() > 0L, f.length())
    }

    fun missingRequired(dir: File): List<String> = REQUIRED_FILES.filter { !File(dir, it).exists() }
}
