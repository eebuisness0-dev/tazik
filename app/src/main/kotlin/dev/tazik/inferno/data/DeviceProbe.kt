package dev.tazik.inferno.data

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.system.Os
import android.system.OsConstants._SC_PAGESIZE
import java.io.File
import dev.tazik.inferno.engine.L

data class CoreCluster(val maxFreqKHz: Long, val count: Int)

data class DeviceFacts(
    val abi: String,
    val pageSizeBytes: Int,
    val ramTotalBytes: Long,
    val ramAvailableBytes: Long,
    val cores: Int,
    val clusters: List<CoreCluster>,
    val storageTotalBytes: Long,
    val storageAvailableBytes: Long,
    val filesystemType: String,
    val socName: String,
    val supported: Boolean,
    val warnings: List<String>,
    val recommendedRamGb: Int,
)

object DeviceProbe {
    @Suppress("DEPRECATION")
    fun probe(context: Context): DeviceFacts {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memory = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memory)
        val pageSize = try { Os.sysconf(_SC_PAGESIZE).toInt() } catch (t: Throwable) { 4096 }

        val cores = Runtime.getRuntime().availableProcessors()
        val freqs = mutableListOf<Long>()
        for (i in 0 until 24) {
            val f = File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq")
            if (f.exists()) {
                val kHz = f.readText().trim().toLongOrNull() ?: 0L
                if (kHz > 0L) freqs.add(kHz) else break
            }
        }
        val clusters = freqs.groupBy { it }.map { (f, list) -> CoreCluster(f, list.size) }.sortedByDescending { it.maxFreqKHz }

        val st = StatFs(Environment.getDataDirectory().path)
        val fsType = readFsType(context)

        val abi = if (Build.SUPPORTED_ABIS.isNotEmpty()) Build.SUPPORTED_ABIS.first() else "unknown"
        val soc = detectSoc()

        val warnings = mutableListOf<String>()
        if (abi != "arm64-v8a" && abi != "arm64") warnings.add(L("ABI не arm64: трансляция будет крайне медленной (%s)", abi))
        if (pageSize != 4096 && pageSize != 16384) warnings.add(L("Неизвестный размер страницы: %d", pageSize))
        if (!fsType.contains("f2fs") && !fsType.contains("ext4")) warnings.add(L("Файловая система %s может не поддерживать файлы больше 4 ГБ", fsType))
        if (fsType.isEmpty()) warnings.add(L("Не удалось определить файловую систему хранилища"))

        val ramTotal = memory.totalMem
        val ramAvailable = memory.availMem
        if (ramTotal < 6L * GuestData.GB) warnings.add(L("Меньше 6 ГБ ОЗУ: гость 2 ГБ — по нижней границе"))

        val storageTotal = st.totalBytes
        val storageAvailable = st.availableBytes

        val recommended = when {
            ramTotal >= 12L * GuestData.GB -> 4
            ramTotal >= 8L * GuestData.GB -> 3
            else -> 2
        }

        return DeviceFacts(
            abi = abi,
            pageSizeBytes = pageSize,
            ramTotalBytes = ramTotal,
            ramAvailableBytes = ramAvailable,
            cores = cores,
            clusters = clusters,
            storageTotalBytes = storageTotal,
            storageAvailableBytes = storageAvailable,
            filesystemType = fsType.ifBlank { "unknown" },
            socName = soc,
            supported = warnings.isEmpty(),
            warnings = warnings,
            recommendedRamGb = recommended,
        )
    }

    private fun readFsType(context: Context): String {
        return try {
            val path = context.filesDir.absolutePath
            val target = File(path).canonicalPath
            val mount = File("/proc/self/mountinfo").readText()
            var best = ""
            mount.lines().forEach { line ->
                val fields = line.split(" ")
                val sep = fields.indexOf("-")
                if (sep > 4 && fields.size > sep + 1) {
                    val mountPath = fields[4]
                    if ((mountPath == "/data" || target.startsWith(mountPath) || mountPath.startsWith(target)) &&
                        mountPath.length >= best.length
                    ) {
                        best = fields[sep + 1]
                    }
                }
            }
            best
        } catch (t: Throwable) {
            ""
        }
    }

    private fun detectSoc(): String {
        val p = Build.SOC_MODEL.ifBlank {
            runCatching {
                File("/etc/device_details").readText()
            }.getOrNull()
        } ?: ""
        return when {
            p.isNotBlank() -> p
            Build.HARDWARE.contains("qcom") -> Build.HARDWARE
            else -> Build.HARDWARE.ifBlank { Build.DEVICE }
        }
    }
}
