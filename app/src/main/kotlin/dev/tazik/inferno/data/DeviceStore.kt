package dev.tazik.inferno.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.util.UUID
import dev.tazik.inferno.engine.L
import dev.tazik.inferno.engine.Settings

class DeviceStore private constructor(context: Context) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val dir = File(context.filesDir, "devices").apply { mkdirs() }
    private val guestRoot = File(context.filesDir, "guests").apply { mkdirs() }

    private val _devices = MutableStateFlow<List<VirtualDevice>>(emptyList())
    val devices: StateFlow<List<VirtualDevice>> = _devices

    init {
        load()
    }

    fun guestDir(device: VirtualDevice): File = File(guestRoot, device.id)

    private fun load() {
        val result = dir.listFiles()?.filterNotNull()?.mapNotNull { f ->
            runCatching { json.decodeFromString(VirtualDevice.serializer(), f.readText()) }.getOrNull()
        }?.sortedBy { it.createdAtEpochMs } ?: emptyList()
        _devices.value = result
    }

    fun put(device: VirtualDevice) {
        val file = File(dir, "${device.id}.json")
        file.writeText(json.encodeToString(device))
        _devices.value = _devices.value.filter { it.id != device.id } + device
        _devices.value = _devices.value.sortedBy { it.createdAtEpochMs }
    }

    fun remove(device: VirtualDevice) {
        File(dir, "${device.id}.json").delete()
        _devices.value = _devices.value.filter { it.id != device.id }
        guestDir(device).deleteRecursively()
    }

    fun duplicate(device: VirtualDevice): VirtualDevice {
        val copy = device.copy(id = UUID.randomUUID().toString(), name = L("%@ (копия)", device.name), createdAtEpochMs = System.currentTimeMillis())
        val src = guestDir(device)
        val dst = guestDir(copy)
        src.copyRecursively(dst, false)
        put(copy)
        return copy
    }

    fun freshDevice(name: String, diskSizeGb: Int): VirtualDevice {
        val id = UUID.randomUUID().toString()
        val dev = VirtualDevice(
            id = id,
            name = name.ifBlank { "iPhone 11" },
            createdAtEpochMs = System.currentTimeMillis(),
            diskSizeGb = diskSizeGb,
            // Показывать то, что реально получит машина: конфиг запуска
            // читает Settings, а не устройство.
            ramGb = Settings.memory.dropLast(1).toIntOrNull() ?: 4,
        )
        val dir = guestDir(dev)
        dir.mkdirs()
        val guides = GuestData.disks(diskSizeGb)
        guides.forEach {
            val f = File(dir, it.name)
            if (!f.exists()) f.createNewFile()
        }
        put(dev)
        return dev
    }

    suspend fun update(device: VirtualDevice) = withContext(Dispatchers.IO) {
        put(device)
    }

    companion object {
        @Volatile
        private var instance: DeviceStore? = null

        fun get(context: Context): DeviceStore =
            instance ?: synchronized(this) { instance ?: DeviceStore(context.applicationContext).also { instance = it } }
    }
}
