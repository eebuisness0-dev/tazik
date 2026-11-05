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
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Close
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.tazik.inferno.data.DeviceStore
import dev.tazik.inferno.data.SnapshotInfo
import dev.tazik.inferno.ui.components.PixelGroup
import dev.tazik.inferno.ui.components.PixelGroupItem
import dev.tazik.inferno.ui.components.CircleIcon
import dev.tazik.inferno.ui.components.EmptyState
import dev.tazik.inferno.ui.components.FilledTonalButton
import dev.tazik.inferno.ui.components.SectionLabel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import dev.tazik.inferno.engine.L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SnapshotsScreen(
    deviceId: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val store = remember { DeviceStore.get(context) }
    val devices by store.devices.collectAsState()
    val device = devices.firstOrNull { it.id == deviceId } ?: return
    var newName by remember { mutableStateOf("") }
    val fmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    Scaffold(topBar = {
        TopAppBar(
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, L("Назад")) }
            },
            title = { Text(L("Снимки состояния"), style = MaterialTheme.typography.titleLargeEmphasized) }
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
            SectionLabel(L("Создать снимок"))
            Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextField(
                        value = newName,
                        onValueChange = { newName = it },
                        placeholder = { Text(L("Название (или пустое = авто)")) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                    )
                    FilledTonalButton(
                        enabled = newName.isNotBlank(),
                        onClick = {
                            val name = newName.trim()
                            val snap = SnapshotInfo(
                                id = UUID.randomUUID().toString(),
                                name = name,
                                createdAtEpochMs = System.currentTimeMillis(),
                                file = "${device.id}_$name.zip",
                            )
                            store.put(device.copy(snapshots = device.snapshots + snap))
                            newName = ""
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(L("Сделать снимок"))
                    }
                    Text(
                        text = L("Снимки сохраняются как QEMU migrate-to-file во время работы виртуальной машины."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (device.snapshots.isEmpty()) {
                EmptyState(
                    icon = Icons.Rounded.CameraAlt,
                    title = L("Снимков нет"),
                    description = L("Сделайте снимок, чтобы вернуться к нему позже")
                )
            } else {
                PixelGroup {
                    device.snapshots.forEachIndexed { i, snap ->
                        PixelGroupItem(
                            leading = { CircleIcon(Icons.Rounded.CameraAlt) },
                            title = snap.name,
                            supporting = { Text(L("Создан: %@", fmt.format(Date(snap.createdAtEpochMs))), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                            trailing = {
                                IconButton(
                                    onClick = {
                                        store.put(device.copy(snapshots = device.snapshots.filterNot { it.id == snap.id }))
                                    }
                                ) {
                                    Icon(Icons.Rounded.Close, contentDescription = L("Удалить"))
                                }
                            },
                            isFirst = i == 0,
                            isLast = i == device.snapshots.size - 1,
                        )
                    }
                }
            }
        }
    }
}
