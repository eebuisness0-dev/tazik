package dev.tazik.inferno.ui.screens.details

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import dev.tazik.inferno.ui.components.PixelGroup
import dev.tazik.inferno.ui.components.PixelGroupItem
import dev.tazik.inferno.ui.components.CircleIcon
import dev.tazik.inferno.ui.components.EmptyState
import dev.tazik.inferno.ui.components.FilledTonalButton
import dev.tazik.inferno.ui.components.SectionLabel
import dev.tazik.inferno.engine.L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsbDevicesScreen(
    deviceId: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val store = remember { DeviceStore.get(context) }
    val devices by store.devices.collectAsState()
    val device = devices.firstOrNull { it.id == deviceId } ?: return
    var vid by remember { mutableStateOf("") }
    var pid by remember { mutableStateOf("") }

    Scaffold(topBar = {
        TopAppBar(
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, L("Назад")) }
            },
            title = { Text(L("USB и периферия"), style = MaterialTheme.typography.titleLargeEmphasized) }
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
            SectionLabel(L("USB-устройства для проброса"))
            Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        L("VID:PID подключённого USB-устройства, например 05ac:12a8"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextField(
                        value = vid,
                        onValueChange = { vid = it.take(6) },
                        label = { Text("VID (hex)") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                    )
                    TextField(
                        value = pid,
                        onValueChange = { pid = it.take(6) },
                        label = { Text("PID (hex)") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                    )
                    FilledTonalButton(
                        enabled = vid.isNotBlank() && pid.isNotBlank(),
                        onClick = {
                            val entry = "${vid.lowercase()}:${pid.lowercase()}"
                            if (entry !in device.usbPassthrough) {
                                store.put(device.copy(usbPassthrough = device.usbPassthrough + entry))
                            }
                            vid = ""
                            pid = ""
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(L("Добавить"))
                    }
                }
            }

            if (device.usbPassthrough.isEmpty()) {
                EmptyState(
                    icon = Icons.Rounded.Usb,
                    title = L("Проброс пуст"),
                    description = L("Добавьте VID:PID устройства, чтобы QEMU подключил его к iOS")
                )
            } else {
                PixelGroup {
                    device.usbPassthrough.forEachIndexed { i, entry ->
                        PixelGroupItem(
                            leading = { CircleIcon(Icons.Rounded.Usb) },
                            title = entry,
                            trailing = {
                                IconButton(
                                    onClick = {
                                        store.put(
                                            device.copy(
                                                usbPassthrough = device.usbPassthrough.filterNot { it == entry }
                                            )
                                        )
                                    }
                                ) {
                                    Icon(Icons.Rounded.Close, contentDescription = L("Удалить"))
                                }
                            },
                            isFirst = i == 0,
                            isLast = i == device.usbPassthrough.size - 1,
                        )
                    }
                }
            }

            SectionLabel(L("Подсказка"))
            Text(
                text = L("Реальный проброс USB работает через hostdev в QEMU; поддержка зависит ") +
                    L("от сборки libqemu. Перечисленные устройства передаются в MachineLauncher."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
    }
}
