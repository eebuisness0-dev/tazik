package dev.tazik.inferno.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.PhoneIphone
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.unit.dp
import dev.tazik.inferno.data.DeviceProbe
import dev.tazik.inferno.data.DeviceStore
import dev.tazik.inferno.data.PanelProfile
import dev.tazik.inferno.data.VirtualDevice
import dev.tazik.inferno.engine.L
import dev.tazik.inferno.ui.components.ChipTone
import dev.tazik.inferno.ui.components.EmptyState
import dev.tazik.inferno.ui.components.ErrorBanner
import dev.tazik.inferno.ui.components.FabItem
import dev.tazik.inferno.ui.components.StatusChip
import dev.tazik.inferno.ui.components.TazikFabMenu
import dev.tazik.inferno.TazikEngine
import dev.tazik.inferno.ui.theme.cardRadius
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesScreen(
    onOpenDetail: (String) -> Unit,
    onSupport: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val store = remember { DeviceStore.get(context) }
    val devices by store.devices.collectAsState()
    val state by TazikEngine.model.qemuState.collectAsState()
    val engineRunning = state == dev.tazik.inferno.engine.QemuBridge.State.Running
    var createOpen by remember { mutableStateOf(false) }
    var manageIpsw by remember { mutableStateOf(false) }
    var contextDevice by remember { mutableStateOf<VirtualDevice?>(null) }
    val facts = remember(context) { DeviceProbe.probe(context) }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 16.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Column {
                    Text(
                        text = "Tazik",
                        style = MaterialTheme.typography.headlineLargeEmphasized,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                    if (facts.warnings.isNotEmpty()) {
                        ErrorBanner(
                            message = facts.warnings.first(),
                            modifier = Modifier.padding(horizontal = 16.dp),
                            onClose = { }
                        )
                    }
                }
            }
            if (devices.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.Rounded.PhoneIphone,
                        title = L("Пока нет устройств"),
                        description = L("Создайте виртуальный iPhone 11 и установите в него образ iOS"),
                        actionLabel = L("Создать устройство"),
                        onAction = { createOpen = true },
                        modifier = Modifier.padding(top = 24.dp)
                    )
                }
            } else {
                items(devices, key = { it.id }) { device ->
                    DeviceCard(
                        device = device,
                        running = engineRunning,
                        onClick = { onOpenDetail(device.id) },
                        onLongPress = { contextDevice = device },
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            }
        }

        TazikFabMenu(
            fabItems = listOf(
                FabItem(Icons.Rounded.Add, L("Создать устройство")) { createOpen = true },
                FabItem(Icons.Rounded.Download, L("Импорт .ipsw")) { manageIpsw = true },
            ),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(24.dp)
        )
    }

    if (createOpen) {
        CreateDeviceSheet(onDismiss = { createOpen = false })
    }
    if (manageIpsw) {
        IpswManagerSheet(onDismiss = { manageIpsw = false })
    }
    contextDevice?.let { device ->
        DeviceActionsSheet(device = device, onDismiss = { contextDevice = null })
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun DeviceCard(
    device: VirtualDevice,
    running: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tensionShape = if (running) {
        RoundedCornerShape(
            20.dp, 20.dp, 20.dp, 0.dp
        )
    } else {
        RoundedCornerShape(20.dp)
    }
    Card(
        shape = tensionShape,
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongPress)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.PhoneIphone,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(24.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.name,
                    style = MaterialTheme.typography.titleLargeEmphasized
                )
                Text(
                    text = "${device.firmwareVersion.ifBlank { L("iOS не установлена") }} · ${L("%d ГБ", device.ramGb)} · ${device.panel.inches}\"",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            val (chipText, chipTone) = when {
                running -> L("Работает") to ChipTone.OK
                device.firmwareBuild.isNotBlank() -> L("Остановлен") to ChipTone.NEUTRAL
                else -> L("Ошибка") to ChipTone.WARN
            }
            StatusChip(text = chipText, tone = chipTone)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeviceActionsSheet(device: VirtualDevice, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val store = remember { DeviceStore.get(context) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(32.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            TextButton(
                onClick = {
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            ) {
                Text(L("Запустить"))
            }
            TextButton(
                onClick = {
                    scope.launch {
                        store.duplicate(device)
                    }
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            ) {
                Text(L("Дублировать"))
            }
            TextButton(
                onClick = {
                    scope.launch {
                        store.remove(device)
                    }
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            ) {
                Text(L("Удалить"), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
