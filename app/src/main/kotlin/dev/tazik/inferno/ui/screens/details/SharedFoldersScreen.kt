package dev.tazik.inferno.ui.screens.details

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.rounded.Folder
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
import dev.tazik.inferno.data.SharedFolder
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
fun SharedFoldersScreen(
    deviceId: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val store = remember { DeviceStore.get(context) }
    val devices by store.devices.collectAsState()
    val device = devices.firstOrNull { it.id == deviceId } ?: return
    var guestPath by remember { mutableStateOf("Documents") }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            val name = uri.lastPathSegment?.substringAfterLast(':') ?: "folder"
            store.put(
                device.copy(
                    sharedFolders = device.sharedFolders + SharedFolder(
                        id = UUID.randomUUID().toString(),
                        hostUri = uri.toString(),
                        hostName = name,
                        guestPath = guestPath.ifBlank { "Documents" },
                    )
                )
            )
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, L("Назад")) }
            },
            title = { Text(L("Общие папки"), style = MaterialTheme.typography.titleLargeEmphasized) }
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
            SectionLabel(L("Добавить папку"))
            Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextField(
                        value = guestPath,
                        onValueChange = { guestPath = it },
                        placeholder = { Text(L("Путь в iOS (например: Documents)")) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                    )
                    FilledTonalButton(
                        onClick = { pickFolder.launch(null) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(L("Выбрать папку на устройстве"))
                    }
                }
            }

            if (device.sharedFolders.isEmpty()) {
                EmptyState(
                    icon = Icons.Rounded.Folder,
                    title = L("Папок нет"),
                    description = L("Общие папки позволяют перекидывать файлы между Android и iOS")
                )
            } else {
                PixelGroup {
                    device.sharedFolders.forEachIndexed { i, folder ->
                        PixelGroupItem(
                            leading = { CircleIcon(Icons.Rounded.Folder) },
                            title = folder.guestPath,
                            supporting = { Text(folder.hostName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                            trailing = {
                                IconButton(
                                    onClick = {
                                        store.put(
                                            device.copy(
                                                sharedFolders = device.sharedFolders.filterNot { it.id == folder.id }
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
            }
        }
    }
}
