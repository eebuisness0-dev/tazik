package dev.tazik.inferno.data

import kotlinx.serialization.Serializable
import dev.tazik.inferno.engine.L

@Serializable
enum class PanelProfile(val widthPx: Int, val heightPx: Int, val scale: Float, val displayName: String, val inches: String) {
    IPHONE_11(828, 1792, 2.0f, "iPhone 11", "6.1"),
    IPHONE_8(752, 1336, 2.0f, "iPhone 8", "4.7"),
    IPHONE_SE_1(640, 1136, 2.0f, "iPhone SE", "4.0"),
}

@Serializable
enum class NetworkMode(val id: String, val title: String) {
    NAT("nat", "NAT"),
    BRIDGE("bridge", "Bridge"),
    HOST_ONLY("host_only", "Host-only"),
    OFF("off", L("Выкл")),
}

@Serializable
data class VmDisk(
    val id: String,
    val path: String,
    val sizeGb: Int,
    val role: String,
    val controller: String = "nvme",
    val cacheEnabled: Boolean = true,
    val readOnly: Boolean = false,
    val offsetGb: Int = 0,
)

@Serializable
data class SharedFolder(
    val id: String,
    val hostUri: String,
    val hostName: String,
    val guestPath: String,
    val readOnly: Boolean = false,
)

@Serializable
data class SnapshotInfo(
    val id: String,
    val name: String,
    val createdAtEpochMs: Long,
    val ramHeiIncluded: Boolean = false,
    val file: String = "",
    val sizeBytes: Long = 0L,
)

@Serializable
data class BootEntry(
    val id: String,
    val name: String,
    val kind: String,
    val kernelPath: String = "",
    val argsOverride: String = "",
)

@Serializable
data class PortForward(
    val id: String,
    val protocol: String = "tcp",
    val hostPort: Int = 0,
    val guestPort: Int = 0,
)

@Serializable
data class VirtualDevice(
    val id: String,
    val name: String,
    val createdAtEpochMs: Long,
    val panel: PanelProfile = PanelProfile.IPHONE_11,
    val diskSizeGb: Int = 8,
    val diskPath: String = "",
    val firmwareVersion: String = "",
    val firmwareBuild: String = "",
    val firmwareFile: String = "",
    val vcpuCores: Int = 3,
    val ramGb: Int = 4,
    val tbSizeMb: Int = 512,
    val verboseBoot: Boolean = false,
    val safeMode: Boolean = false,
    val debugOutput: Boolean = false,
    val keepSymbols: Boolean = true,
    val extraBootArgs: String = "",
    val audioEnabled: Boolean = false,
    val networkEnabled: Boolean = true,
    val sensorsEnabled: Boolean = true,
    val showVncDisplay: Boolean = true,
    val networkMode: NetworkMode = NetworkMode.NAT,
    val macAddress: String = "auto",
    val ipAddress: String = "",
    val gateway: String = "",
    val dnsServer: String = "",
    val portForwards: List<PortForward> = emptyList(),
    val sharedFolders: List<SharedFolder> = emptyList(),
    val snapshots: List<SnapshotInfo> = emptyList(),
    val bootEntries: List<BootEntry> = emptyList(),
    val activeBootEntryId: String = "",
    val activeSnapshotId: String? = null,
    val extDisks: List<VmDisk> = emptyList(),
    val usbPassthrough: List<String> = emptyList(),
    val qemuPath: String = "",
    val statsResumeAtEpochMs: Long = 0L,
) {
    companion object {
        const val BOOT_ENTRY_MAIN = "entry_main"
        const val BOOT_ENTRY_RECOVERY = "entry_recovery"
    }
}

@Serializable
data class PreparedFile(
    val path: String,
    val present: Boolean,
    val sizeBytes: Long = 0L,
)
