package dev.tazik.inferno.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.PhoneIphone
import androidx.compose.material3.ExperimentalMaterial3Api
import dev.tazik.inferno.ui.components.PixelRow
import dev.tazik.inferno.ui.components.FilledButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.tazik.inferno.data.DeviceProbe
import dev.tazik.inferno.data.DeviceStore
import java.io.File
import dev.tazik.inferno.engine.L

fun humanBytes(b: Long): String = when {
    b >= 1L shl 30 -> L("%.1f ГБ", b / 1073741824.0)
    b >= 1L shl 20 -> L("%.1f МБ", b / 1048576.0)
    b >= 1L shl 10 -> L("%d КБ", b / 1024L)
    else -> L("%d Б", b)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateDeviceSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val store = remember { DeviceStore.get(context) }
    val facts = remember(context) { DeviceProbe.probe(context) }
    var name by remember { mutableStateOf("") }
    var ipswUri by remember { mutableStateOf<Uri?>(null) }
    var diskSize by remember { mutableStateOf(8f) }
    val existing = store.devices.collectAsState().value.map { it.name }

    val pickIpsw = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) {
            }
            ipswUri = uri
        }
    }

    ModalBottomSheet(
                onDismissRequest = onDismiss,
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)
            ) {
    Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            text = L("Создать устройство"),
            style = MaterialTheme.typography.titleLargeEmphasized,
            modifier = Modifier.padding(top = 8.dp)
        )
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(32) },
            label = { Text(L("Имя устройства")) },
            placeholder = { Text("iPhone ${store.devices.value.size + 1}") },
            supportingText = {
                val isValid = name.isNotBlank() && !existing.contains(name)
                if (!isValid && name.isNotBlank()) Text(L("Имя занято"))
            },
            modifier = Modifier.fillMaxWidth()
        )

        PixelRow(
            title = L("Образ iOS"),
            supporting = {
                Text(ipswUri?.let { L("Выбран: %@", it.lastPathSegment?.take(64) ?: "") } ?: L("Файл не выбран (.ipsw)"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            },
            leading = { Icon(Icons.Rounded.PhoneIphone, contentDescription = null) },
            trailing = {
                FilledTonalButton(onClick = {
                    pickIpsw.launch(arrayOf("application/octet-stream", "application/x-zip-compressed", "application/zip"))
                }) {
                    Text(L("Выбрать"))
                }
            }
        )

        Column {
            Text(L("Размер диска: %d ГБ", diskSize.toInt()), style = MaterialTheme.typography.bodyLargeEmphasized)
            Slider(
                value = diskSize,
                onValueChange = { diskSize = it },
                valueRange = 4f..64f,
                steps = 14,
            )
            Text(
                                L("Доступно: %@ из %@", humanBytes(facts.storageAvailableBytes), humanBytes(facts.storageTotalBytes)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        PixelRow(
            title = L("Хранилище образов"),
            supporting = { Text("tazik/guests/<id>", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) },
            leading = { Icon(Icons.Rounded.Folder, contentDescription = null) },
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            TextButton(
                onClick = { onDismiss() },
                modifier = Modifier.weight(1f)
            ) {
                Text(L("Отмена"))
            }
            FilledButton(
                enabled = name.isNotBlank() && diskSize.toInt() in 4..64,
                onClick = {
                    val id = store.freshDevice(name, diskSize.toInt())
                    ipswUri?.let { uri ->
                        val device = store.devices.value.first { it.id == id.id }
                        val i = device.copy(firmwareFile = uri.toString())
                        store.put(i)
                    }
                    onDismiss()
                },
                modifier = Modifier.weight(1f)
            ) {
                Text(L("Создать"))
            }
        }
    }
    }
}
