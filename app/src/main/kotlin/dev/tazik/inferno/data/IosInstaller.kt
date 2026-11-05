package dev.tazik.inferno.data

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.zip.ZipFile
import dev.tazik.inferno.engine.L

sealed interface InstallStage {
    data object Copying : InstallStage
    data object Validate : InstallStage
    data class Extract(val percent: Int, val entryName: String) : InstallStage
    data object CreateDisks : InstallStage
    data object Finalize : InstallStage
    data class Done(val missing: List<String>) : InstallStage
    data class Failed(val message: String) : InstallStage
}

object IosInstaller {
    const val TAG = "tazik-install"
    const val MAX_LOGICAL_BYTES = 28L * GuestData.GB

    fun install(
        context: Context,
        guestDir: File,
        device: VirtualDevice,
        ipswSrc: Uri,
        verifySha: Boolean,
    ): Flow<InstallStage> = flow {
        val ipswDir = File(context.filesDir, "ipsw").apply { mkdirs() }
        val ipswPost = File(ipswDir, "import_${System.currentTimeMillis()}.ipsw")

        emit(InstallStage.Copying)
        try {
            context.contentResolver.openInputStream(ipswSrc)?.use { input ->
                ipswPost.outputStream().buffered(1 shl 18).use { output ->
                    val buf = ByteArray(1 shl 18)
                    var read = input.read(buf)
                    while (read > 0) {
                        output.write(buf, 0, read)
                        read = input.read(buf)
                    }
                }
            } ?: throw IllegalStateException(L("Не удалось открыть файл (нет доступа Uri)"))
        } catch (t: Throwable) {
            Log.e(TAG, "copy", t)
            ipswPost.delete()
            emit(InstallStage.Failed(L("Копирование образа не удалось: %@", t.message)))
            return@flow
        }

        installFromLocal(context, guestDir, device, ipswPost, verifySha = false).collect { emit(it) }
    }.flowOn(Dispatchers.IO)

    fun installFromLocal(
        context: Context,
        guestDir: File,
        device: VirtualDevice,
        ipsw: File,
        verifySha: Boolean,
    ): Flow<InstallStage> = flow {
        val restore = File(guestDir, "Restore").apply { mkdirs() }
        val store = DeviceStore.get(context)

        emit(InstallStage.Validate)
        val manifestInfo: BuildManifestInfo
        try {
            ZipFile(ipsw).use { zip ->
                val itm = zip.getEntry("BuildManifest.plist") ?: zip.getEntry("Restore/BuildManifest.plist")
                    ?: throw IllegalStateException(L("BuildManifest.plist не найден в IPSW"))
                val parsed = PlistParser.parse(zip.getInputStream(itm)) ?: throw IllegalStateException(L("BuildManifest не разобран"))
                manifestInfo = BuildManifestInfo.from(parsed) ?: throw IllegalStateException(L("Erase-идентичность iPhone12,1 не найдена"))
            }
            val planned = device.diskSizeGb * GuestData.GB + ipsw.length()
            if (planned > MAX_LOGICAL_BYTES) {
                throw IllegalStateException(L("Лимит 20 ГБ превышен (нужно %d ГБ)", planned / GuestData.GB))
            }
        } catch (t: Throwable) {
            Log.e(TAG, "validate", t)
            emit(InstallStage.Failed(t.message ?: "$t"))
            return@flow
        }
        val manifest = manifestInfo

        emit(InstallStage.CreateDisks)
        try {
            GuestData.disks(device.diskSizeGb).forEach { d ->
                val f = File(guestDir, d.name)
                RandomAccessFile(f, "rw").use { if (f.length() < d.bytes) it.setLength(d.bytes) }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "disks", t)
            emit(InstallStage.Failed(L("Не удалось создать образы: %@", t.message)))
            return@flow
        }

        try {
            ZipFile(ipsw).use { zip ->
                val entries = zip.entries().toList()
                val skip = manifest.skipDmgPaths
                val totalSmall = entries.filter { e -> e.name !in skip }.sumOf { it.compressedSize }
                var done = 0L
                val buf = ByteArray(1 shl 18)
                entries.forEach { entry ->
                    if (entry.name in skip) return@forEach
                    val out = File(restore, entry.name)
                    if (entry.isDirectory) {
                        out.mkdirs()
                        return@forEach
                    }
                    out.parentFile?.mkdirs()
                    emit(InstallStage.Extract((done * 100 / totalSmall.coerceAtLeast(1)).toInt(), entry.name))
                    zip.getInputStream(entry).use { input ->
                        out.outputStream().buffered(1 shl 16).use { output ->
                            var read = input.read(buf)
                            while (read > 0) {
                                output.write(buf, 0, read)
                                done += read
                                read = input.read(buf)
                            }
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "extract", t)
            emit(InstallStage.Failed(L("Распаковка не удалась: %@", t.message)))
            return@flow
        }

        emit(InstallStage.Finalize)
        val stored = store.devices.value.firstOrNull { it.id == device.id } ?: device
        store.put(
            stored.copy(
                firmwareVersion = manifest.version,
                firmwareBuild = manifest.buildId,
                firmwareFile = ipsw.name,
                diskPath = File(guestDir, GuestData.ROOT_DISK).absolutePath,
            )
        )

        val missing = GuestData.missingRequired(guestDir)
        emit(InstallStage.Done(missing))
    }.flowOn(Dispatchers.IO)
}
