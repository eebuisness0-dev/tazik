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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.tazik.inferno.data.DeviceStore
import dev.tazik.inferno.ui.components.PixelCard
import dev.tazik.inferno.ui.components.PixelGroup
import dev.tazik.inferno.ui.components.PixelGroupItem
import dev.tazik.inferno.ui.components.SectionLabel
import dev.tazik.inferno.engine.L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BootloaderScreen(
    deviceId: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val store = remember { DeviceStore.get(context) }
    val devices by store.devices.collectAsState()
    val device = devices.firstOrNull { it.id == deviceId } ?: return

    Scaffold(topBar = {
        TopAppBar(
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, L("Назад")) }
            },
            title = { Text(L("Загрузка (Bootargs)"), style = MaterialTheme.typography.titleLargeEmphasized) }
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
            SectionLabel(L("Режимы загрузки"))
            PixelGroup {
                    BootToggle(L("Подробная загрузка (verbose)"), device.verboseBoot, isFirst = true, onChange = {
                        store.put(device.copy(verboseBoot = it))
                    })
                    BootToggle("Safe Mode", device.safeMode, onChange = {
                        store.put(device.copy(safeMode = it))
                    })
                    BootToggle(L("Отладочный вывод ядра"), device.debugOutput, onChange = {
                        store.put(device.copy(debugOutput = it))
                    })
                    BootToggle(L("Keep symbols (для отладки)"), device.keepSymbols, isLast = true, onChange = {
                        store.put(device.copy(keepSymbols = it))
                    })
            }

            SectionLabel(L("Дополнительные boot-args"))
            PixelCard {
                TextField(
                    value = device.extraBootArgs,
                    onValueChange = { store.put(device.copy(extraBootArgs = it)) },
                    placeholder = { Text(L("например: -vm_compressor_timeshare")) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                )
            }

            SectionLabel(L("Выход в Recovery"))
            PixelGroup {
                PixelGroupItem(
                    title = L("Загрузка в Recovery"),
                    supporting = {
                        Text(L("Следующий запуск начнётся с boot-mode=enter_recovery"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    },
                    trailing = {
                        Switch(
                            checked = device.activeSnapshotId == dev.tazik.inferno.data.VirtualDevice.BOOT_ENTRY_RECOVERY,
                            onCheckedChange = { recovery ->
                                store.put(
                                    device.copy(
                                        activeSnapshotId = if (recovery)
                                            dev.tazik.inferno.data.VirtualDevice.BOOT_ENTRY_RECOVERY else null
                                    )
                                )
                            }
                        )
                    },
                    isFirst = true,
                    isLast = true,
                )
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.BootToggle(
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
