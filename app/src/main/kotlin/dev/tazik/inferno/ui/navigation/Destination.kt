package dev.tazik.inferno.ui.navigation

sealed class Destination(val id: String) {
    data object Devices : Destination("devices")
    data object Settings : Destination("settings")
    data object Support : Destination("support")
    data class DeviceDetail(val deviceId: String) : Destination("device/$deviceId")
    data class VirtualHardware(val deviceId: String) : Destination("vh/$deviceId")
    data class Bootloader(val deviceId: String) : Destination("boot/$deviceId")
    data class Snapshots(val deviceId: String) : Destination("snap/$deviceId")
    data class SharedFolders(val deviceId: String) : Destination("folders/$deviceId")
    data class UsbDevices(val deviceId: String) : Destination("usb/$deviceId")
    data class Network(val deviceId: String) : Destination("net/$deviceId")
    data class DiskManagement(val deviceId: String, val diskPath: String) : Destination("disk/$deviceId/$diskPath")
    data class Logs(val deviceId: String) : Destination("logs/$deviceId")
    data class Info(val topic: String) : Destination("info/$topic")
}
