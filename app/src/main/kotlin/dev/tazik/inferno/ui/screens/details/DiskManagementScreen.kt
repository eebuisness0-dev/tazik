package dev.tazik.inferno.ui.screens.details

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.tazik.inferno.data.DeviceStore
import dev.tazik.inferno.data.GuestData
import dev.tazik.inferno.ui.components.FilledButton
import dev.tazik.inferno.ui.components.FilledTonalButton
import dev.tazik.inferno.ui.components.SectionLabel
import java.io.File
import dev.tazik.inferno.engine.L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiskManagementScreen(
    deviceId: String,
    diskName: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val store = remember { DeviceStore.get(context) }
    val devices by store.devices.collectAsState()
    val device = devices.firstOrNull { it.id == deviceId } ?: return
    val guestDir = remember(device.id) { store.guestDir(device) }
    val diskFile = remember(guestDir) { File(guestDir, diskName) }
    var newSize by remember { mutableStateOf(device.diskSizeGb.toFloat()) }
    var confirmWipe by remember { mutableStateOf(false) }
    var resizeWarning by remember { mutableStateOf(false) }

    Scaffold(topBar = {
        TopAppBar(
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, L("Назад")) }
            },
            title = { Text(L("Управление дисками"), style = MaterialTheme.typography.titleLargeEmphasized) }
        )
    }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SectionLabel(L("Основной диск (%s)", diskName))
            Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val onDisk = if (diskFile.exists()) diskFile.length() / GuestData.GB else 0L
                    Text(
                                                L("Логический размер: %d ГБ · Занято на диске: %d ГБ", device.diskSizeGb, onDisk),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = L("Sparse-файл. Уменьшение размера уничтожит данные."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Slider(
                        value = newSize,
                        onValueChange = { newSize = it },
                        valueRange = 4f..64f,
                        steps = 14,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        FilledTonalButton(
                            onClick = {
                                if (newSize.toInt() < device.diskSizeGb) {
                                    resizeWarning = true
                                } else {
                                    val f = File(guestDir, GuestData.ROOT_DISK)
                                    java.io.RandomAccessFile(f, "rw").use { it.setLength(newSize.toLong() * GuestData.GB) }
                                    store.put(device.copy(diskSizeGb = newSize.toInt()))
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(L("Применить размер"))
                        }
                    }
                }
            }

            SectionLabel(L("Опасная зона"))
            Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = L("Форматирование диска очистит все разделы, включая iOS. ") +
                            L("После этого потребуется переустановка образа."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    TextButton(
                        onClick = { confirmWipe = true },
                        shape = RoundedCornerShape(50),
                        colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(L("Форматировать"))
                    }
                }
            }
        }
    }

    if (confirmWipe) {
        AlertDialog(
            onDismissRequest = { confirmWipe = false },
            title = { Text(L("Стереть диск?")) },
            text = { Text(L("%s и соседние образы будут удалены. Обратно не вернуть.", diskName)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmWipe = false
                        GuestData.disks(device.diskSizeGb).forEach { d ->
                            File(guestDir, d.name).delete()
                        }
                    }
                ) { Text(L("Стереть"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmWipe = false }) { Text(L("Отмена")) } },
            shape = RoundedCornerShape(48.dp)
        )
    }

    if (resizeWarning) {
        AlertDialog(
            onDismissRequest = { resizeWarning = false },
            title = { Text(L("Уменьшение диска")) },
            text = { Text(L("Уменьшение образа может уничтожить данные в конце диска. Продолжить?")) },
            confirmButton = {
                TextButton(
                    onClick = {
                        resizeWarning = false
                        val f = File(guestDir, GuestData.ROOT_DISK)
                        java.io.RandomAccessFile(f, "rw").use { it.setLength(newSize.toLong() * GuestData.GB) }
                        store.put(device.copy(diskSizeGb = newSize.toInt()))
                    }
                ) { Text(L("Да, уменьшить")) }
            },
            dismissButton = { TextButton(onClick = { resizeWarning = false }) { Text(L("Отмена")) } },
            shape = RoundedCornerShape(48.dp)
        )
    }
}
