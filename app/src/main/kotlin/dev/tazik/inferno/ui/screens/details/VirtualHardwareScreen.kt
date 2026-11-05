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
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.tazik.inferno.data.DeviceProbe
import dev.tazik.inferno.data.DeviceStore
import dev.tazik.inferno.data.PanelProfile
import dev.tazik.inferno.ui.components.PixelCard
import dev.tazik.inferno.ui.components.PixelGroup
import dev.tazik.inferno.ui.components.PixelGroupItem
import dev.tazik.inferno.ui.components.SectionLabel
import dev.tazik.inferno.engine.L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VirtualHardwareScreen(
    deviceId: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val store = remember { DeviceStore.get(context) }
    val devices by store.devices.collectAsState()
    val device = devices.firstOrNull { it.id == deviceId } ?: return
    val probe = remember { DeviceProbe.probe(context) }

    Scaffold(topBar = {
        TopAppBar(
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, L("Назад")) }
            },
            title = { Text(L("Виртуальное железо"), style = MaterialTheme.typography.titleLargeEmphasized) }
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
            SectionLabel(L("Экран устройства"))
            PixelGroup {
                PanelProfile.entries.forEachIndexed { i, p ->
                    PixelGroupItem(
                        title = "${p.displayName} · ${p.inches}\"",
                        supporting = {
                            Text("${p.widthPx}×${p.heightPx} @${p.scale}x", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        },
                        trailing = {
                            Switch(
                                checked = p == device.panel,
                                onCheckedChange = { if (it) store.put(device.copy(panel = p)) }
                            )
                        },
                        isFirst = i == 0,
                        isLast = i == PanelProfile.entries.size - 1,
                    )
                }
            }

            SectionLabel(L("Память"))
            PixelCard {
                Text(L("ОЗУ гостя: %d ГБ", device.ramGb), style = MaterialTheme.typography.bodyLargeEmphasized)
                Slider(
                    value = device.ramGb.toFloat(),
                    onValueChange = { store.put(device.copy(ramGb = it.toInt().coerceIn(2, 12))) },
                    valueRange = 2f..12f,
                    steps = 4,
                )
                Text(
                                        L("Рекомендовано для этого устройства: %d ГБ", probe.recommendedRamGb),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            SectionLabel(L("Процессор"))
            PixelCard {
                Text("vCPU: ${device.vcpuCores}", style = MaterialTheme.typography.bodyLargeEmphasized)
                Slider(
                    value = device.vcpuCores.toFloat(),
                    onValueChange = { store.put(device.copy(vcpuCores = it.toInt().coerceIn(1, 8))) },
                    valueRange = 1f..8f,
                    steps = 6,
                )
                Text(
                    text = L("SoC: %@ · ядер: %d · %@", probe.socName, probe.cores, probe.abi),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            SectionLabel(L("Периферия"))
            PixelGroup {
                HardwareToggleRow(L("Аудио"), device.audioEnabled, isFirst = true, onChange = { v -> store.put(device.copy(audioEnabled = v)) })
                HardwareToggleRow(L("Сенсоры"), device.sensorsEnabled, onChange = { v -> store.put(device.copy(sensorsEnabled = v)) })
                HardwareToggleRow(L("Дополнительный экран VNC"), device.showVncDisplay, isLast = true, onChange = { v ->
                    store.put(device.copy(showVncDisplay = v))
                })
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.HardwareToggleRow(
    name: String,
    value: Boolean,
    onChange: (Boolean) -> Unit,
    isFirst: Boolean = false,
    isLast: Boolean = false,
) {
    PixelGroupItem(
        title = name,
        trailing = { Switch(checked = value, onCheckedChange = onChange) },
        isFirst = isFirst,
        isLast = isLast,
    )
}
