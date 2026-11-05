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
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
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
import dev.tazik.inferno.data.NetworkMode
import dev.tazik.inferno.data.PortForward
import dev.tazik.inferno.ui.components.PixelGroup
import dev.tazik.inferno.ui.components.PixelGroupItem
import dev.tazik.inferno.ui.components.CircleIcon
import dev.tazik.inferno.ui.components.EmptyState
import dev.tazik.inferno.ui.components.FilledTonalButton
import dev.tazik.inferno.ui.components.SectionLabel
import java.util.UUID
import dev.tazik.inferno.engine.L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkScreen(
    deviceId: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val store = remember { DeviceStore.get(context) }
    val devices by store.devices.collectAsState()
    val device = devices.firstOrNull { it.id == deviceId } ?: return
    var hostPort by remember { mutableStateOf("") }
    var guestPort by remember { mutableStateOf("") }

    Scaffold(topBar = {
        TopAppBar(
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, L("Назад")) }
            },
            title = { Text(L("Сеть"), style = MaterialTheme.typography.titleLargeEmphasized) }
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
            SectionLabel(L("Режим"))
            Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        NetworkMode.entries.forEachIndexed { index, mode ->
                            SegmentedButton(
                                selected = device.networkMode == mode,
                                onClick = { if (mode != NetworkMode.OFF || device.networkEnabled) {
                                    store.put(device.copy(networkMode = mode, networkEnabled = mode != NetworkMode.OFF))
                                } },
                                shape = SegmentedButtonDefaults.itemShape(index = index, count = NetworkMode.entries.size)
                            ) {
                                Text(mode.title)
                            }
                        }
                    }
                    Text(
                        text = L("NAT — выход в интернет через хост (по умолчанию). Bridge требует Privileged VPN ") +
                            L("и подходит продвинутым. Host-only — без внешней сети."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            SectionLabel(L("MAC-адрес"))
            Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                TextField(
                    value = device.macAddress,
                    onValueChange = { store.put(device.copy(macAddress = it)) },
                    placeholder = { Text(L("auto или AA:BB:CC:DD:EE:FF")) },
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    shape = RoundedCornerShape(16.dp),
                )
            }

            SectionLabel(L("Проброс портов"))
            Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TextField(
                            value = hostPort,
                            onValueChange = { hostPort = it.filter { c -> c.isDigit() }.take(5) },
                            label = { Text(L("Порт хоста")) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(16.dp),
                        )
                        TextField(
                            value = guestPort,
                            onValueChange = { guestPort = it.filter { c -> c.isDigit() }.take(5) },
                            label = { Text(L("Порт в iOS")) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(16.dp),
                        )
                    }
                    FilledTonalButton(
                        enabled = hostPort.isNotBlank() && guestPort.isNotBlank(),
                        onClick = {
                            store.put(
                                device.copy(
                                    portForwards = device.portForwards + PortForward(
                                        id = UUID.randomUUID().toString(),
                                        hostPort = hostPort.toInt(),
                                        guestPort = guestPort.toInt(),
                                    )
                                )
                            )
                            hostPort = ""
                            guestPort = ""
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(L("Добавить"))
                    }
                }
            }

            if (device.portForwards.isNotEmpty()) {
                PixelGroup {
                    device.portForwards.forEachIndexed { i, pf ->
                        PixelGroupItem(
                            leading = { CircleIcon(Icons.Rounded.Wifi) },
                            title = "${pf.protocol.uppercase()}:${pf.hostPort} → ${pf.guestPort}",
                            trailing = {
                                IconButton(
                                    onClick = {
                                        store.put(
                                            device.copy(
                                                portForwards = device.portForwards.filterNot { it.id == pf.id }
                                            )
                                        )
                                    }
                                ) {
                                    Icon(Icons.Rounded.Close, contentDescription = L("Удалить"))
                                }
                            }
                        )
                    }
                }
            } else {
                EmptyState(
                    icon = Icons.Rounded.Wifi,
                    title = L("Проброса нет"),
                    description = L("PORT пробрасывает порт хоста на порт в iOS")
                )
            }
        }
    }
}
