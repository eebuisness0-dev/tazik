package dev.tazik.inferno.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.tazik.inferno.TazikEngine
import dev.tazik.inferno.data.DeviceStore
import dev.tazik.inferno.data.GuestData
import dev.tazik.inferno.engine.QemuBridge
import dev.tazik.inferno.ui.components.CircleIcon
import dev.tazik.inferno.ui.components.PixelGroup
import dev.tazik.inferno.ui.components.PixelGroupItem
import dev.tazik.inferno.ui.components.PixelRow
import dev.tazik.inferno.ui.components.SectionLabel
import dev.tazik.inferno.ui.sheets.LaunchModeSheet
import dev.tazik.inferno.ui.sheets.InstallIosSheet
import dev.tazik.inferno.ui.components.FilledButton
import dev.tazik.inferno.ui.navigation.Destination
import java.io.File
import dev.tazik.inferno.engine.L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceDetailScreen(
    deviceId: String,
    onBack: () -> Unit,
    onNavigate: (Destination) -> Unit,
) {
    val context = LocalContext.current
    val store = remember { DeviceStore.get(context) }
    val devices by store.devices.collectAsState()
    val device = devices.firstOrNull { it.id == deviceId }
    val state by TazikEngine.model.qemuState.collectAsState()
    val hasRun by TazikEngine.model.hasRun.collectAsState()
    // Машина одна на процесс; карточка показывает её состояние.
    val running = state == QemuBridge.State.Running || state is QemuBridge.State.Failed ||
        (state == QemuBridge.State.Idle && hasRun)
    var installOpen by remember { mutableStateOf(false) }
    var launchOpen by remember { mutableStateOf(false) }
    var notReady by remember { mutableStateOf(false) }
    var deleteOpen by remember { mutableStateOf(false) }

    Scaffold(topBar = {
        TopAppBar(
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = L("Назад"))
                }
            },
            title = {
                Text(device?.name ?: L("Устройство"), style = MaterialTheme.typography.titleLargeEmphasized)
            },
            actions = {
                IconButton(onClick = { deleteOpen = true }) {
                    Icon(Icons.Rounded.Delete, contentDescription = L("Удалить"))
                }
            },
        )
    }) { padding ->
        if (device == null) {
            Box(modifier = Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(L("Устройство не найдено"))
            }
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 8.dp)
        ) {
            StatusCard(
                device = device,
                state = state,
                booting = state == QemuBridge.State.Idle && hasRun,
                guestDir = store.guestDir(device),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )

            SectionLabel(L("Установка iOS"))
            PixelRow(
                onClick = { if (device.firmwareBuild.isBlank()) installOpen = true },
                leading = { CircleIcon(Icons.Rounded.Download) },
                title = if (device.firmwareVersion.isBlank()) L("Установить iOS")
                        else "iOS ${device.firmwareVersion} (${device.firmwareBuild})",
                supporting = {
                    Text(
                        if (device.firmwareVersion.isBlank()) L("Выберите .ipsw из Apple")
                        else device.firmwareFile,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                },
            )

            SectionLabel(L("Окружение"))
            PixelGroup {
                PixelGroupItem(onClick = { onNavigate(Destination.VirtualHardware(deviceId)) }, leading = { CircleIcon(Icons.Rounded.Memory) }, title = L("Виртуальное железо"), isFirst = true)
                PixelGroupItem(onClick = { onNavigate(Destination.Bootloader(deviceId)) }, leading = { CircleIcon(Icons.Rounded.Terminal) }, title = L("Загрузка (Bootargs)"))
                PixelGroupItem(
                    onClick = { onNavigate(Destination.Snapshots(deviceId)) },
                    leading = { CircleIcon(Icons.Rounded.CameraAlt) },
                    title = L("Снимок состояния"),
                    supporting = if (device.snapshots.isEmpty()) null else {
                        { Text(L("%d шт.", device.snapshots.size), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    },
                )
                PixelGroupItem(onClick = { onNavigate(Destination.SharedFolders(deviceId)) }, leading = { CircleIcon(Icons.Rounded.Folder) }, title = L("Общие папки"))
                PixelGroupItem(onClick = { onNavigate(Destination.UsbDevices(deviceId)) }, leading = { CircleIcon(Icons.Rounded.Usb) }, title = L("USB и периферия"))
                PixelGroupItem(onClick = { onNavigate(Destination.Network(deviceId)) }, leading = { CircleIcon(Icons.Rounded.Wifi) }, title = L("Сеть"))
                PixelGroupItem(onClick = { onNavigate(Destination.DiskManagement(deviceId, GuestData.ROOT_DISK)) }, leading = { CircleIcon(Icons.Rounded.Description) }, title = L("Управление дисками"))
                PixelGroupItem(onClick = { onNavigate(Destination.Logs(deviceId)) }, leading = { CircleIcon(Icons.Rounded.Description) }, title = L("Логи запуска"), isLast = true)
            }

            Spacer(Modifier.height(24.dp))
            FilledButton(
                enabled = device.firmwareBuild.isNotBlank(),
                shape = RoundedCornerShape(50),
                onClick = {
                    if (device.firmwareBuild.isBlank()) {
                        notReady = true
                    } else {
                        launchOpen = true
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
            ) {
                Text(if (state == QemuBridge.State.Running) L("ОТКРЫТЬ") else L("ЗАПУСТИТЬ"))
            }
            if (device.firmwareBuild.isBlank()) {
                Text(
                    text = L("Для запуска сначала установите iOS"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .clickable { installOpen = true },
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    if (launchOpen && device != null) {
        LaunchModeSheet(device = device, onDismiss = { launchOpen = false })
    }
    if (installOpen && device != null) {
        InstallIosSheet(device = device, onDismiss = { installOpen = false })
    }
    if (notReady) {
        AlertDialog(
            onDismissRequest = { notReady = false },
            title = { Text(L("iOS не установлена")) },
            text = { Text(L("Сначала установите образ iOS: карточка «Установка iOS» на этом экране.")) },
            confirmButton = { TextButton(onClick = { notReady = false }) { Text(L("Ок")) } }
        )
    }
    if (deleteOpen && device != null) {
        AlertDialog(
            onDismissRequest = { deleteOpen = false },
            title = { Text(L("Удалить устройство?")) },
            text = { Text(L("«%@» и все его данные будут удалены безвозвратно.", device.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteOpen = false
                        TazikEngine.model.shutdown()
                        store.remove(device)
                        onBack()
                    }
                ) { Text(L("Удалить"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteOpen = false }) { Text(L("Отмена")) } }
        )
    }
}

@Composable
private fun StatusCard(
    device: dev.tazik.inferno.data.VirtualDevice,
    state: QemuBridge.State,
    booting: Boolean,
    guestDir: File,
    modifier: Modifier = Modifier,
) {
    val isRunning = state == QemuBridge.State.Running
    val shape = if (isRunning)
        RoundedCornerShape(24.dp, 24.dp, 0.dp, 24.dp)
    else
        RoundedCornerShape(24.dp)
    Card(shape = shape, modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = device.name.firstOrNull()?.uppercase() ?: "i",
                        style = MaterialTheme.typography.titleLargeEmphasized,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(device.name, style = MaterialTheme.typography.titleLargeEmphasized)
                    Text(
                        text = device.panel.displayName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = if (isRunning) L("работает")
                    else if (booting) L("запускается") else L("выключен"),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isRunning || booting) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = listOfNotNull(
                    device.firmwareVersion.takeIf { it.isNotBlank() }?.let { "iOS $it" } ?: L("iOS не установлена"),
                    if (device.firmwareBuild.isNotBlank()) "build ${device.firmwareBuild}" else null,
                                        L("Диск %d ГБ", device.diskSizeGb),
                    L("ОЗУ %d ГБ", device.ramGb),
                    "${device.panel.widthPx}×${device.panel.heightPx}",
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (booting) {
                Text(L("QEMU поднимает машину"), style = MaterialTheme.typography.labelMedium)
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            if (state is QemuBridge.State.Failed) {
                Text(
                    state.why,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
