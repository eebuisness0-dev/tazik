package dev.tazik.inferno.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.tazik.inferno.data.DownloadPhase
import dev.tazik.inferno.data.FirmwareEntry
import dev.tazik.inferno.data.IpswCatalog
import dev.tazik.inferno.data.IpswManager
import dev.tazik.inferno.service.IpswDownloadService
import dev.tazik.inferno.ui.components.PixelRow
import java.io.File
import dev.tazik.inferno.engine.L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IpswManagerSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var entries by remember { mutableStateOf<List<FirmwareEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val phase by IpswManager.phase.collectAsState()
    val ipswDir = remember { File(context.filesDir, "ipsw") }
    var downloaded by remember { mutableStateOf(ipswDir.listFiles()?.toList() ?: emptyList()) }
    LaunchedEffect(Unit) {
        IpswCatalog.fetch().fold(
            onSuccess = { entries = it.reversed() },
            onFailure = { error = it.message }
        )
        loading = false
    }
    LaunchedEffect(phase) {
        if (phase is DownloadPhase.Done) {
            downloaded = ipswDir.listFiles()?.toList() ?: emptyList()
        }
    }
    ModalBottomSheet(
                onDismissRequest = onDismiss,
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)
            ) {
    Column(modifier = Modifier.padding(16.dp)) {
        Text(
            text = L("Загрузка iOS"),
            style = MaterialTheme.typography.titleLargeEmphasized,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        val p = phase
        when (p) {
            is DownloadPhase.Downloading -> {
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Text(text = "iOS ${p.version} (${p.buildId})", style = MaterialTheme.typography.titleMediumEmphasized)
                    LinearProgressIndicator(
                        progress = { if (p.bytesTotal > 0) p.bytesDone.toFloat() / p.bytesTotal else 0f },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                    )
                    Text("${humanBytes(p.bytesDone)} / ${humanBytes(p.bytesTotal)}", style = MaterialTheme.typography.bodySmall)
                    FilledTonalButton(
                        onClick = { IpswDownloadService.cancel(context) },
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        Text(L("Отменить"))
                    }
                }
            }
            is DownloadPhase.Failed -> {
                Text(
                    text = L("Ошибка: %@", p.message),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            else -> { }
        }
        if (loading) {
            CircularProgressIndicator(Modifier.padding(16.dp))
        } else {
            if (error != null) {
                Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(downloaded, key = { "dl_${it.name}" }) { file ->
                    PixelRow(
                        title = file.name,
                        supporting = { Text(humanBytes(file.length()), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                        trailing = {
                            IconButton(onClick = {
                                file.delete()
                                downloaded = ipswDir.listFiles()?.toList() ?: emptyList()
                            }) {
                                Icon(Icons.Rounded.Delete, contentDescription = L("Удалить"))
                            }
                        }
                    )
                }
                items(entries, key = { "cat_${it.buildid}_${it.version}" }) { fw ->
                    PixelRow(
                        title = "iOS ${fw.version}",
                        supporting = { Text("${fw.buildid} · ${humanBytes(fw.filesize)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                        trailing = {
                            IconButton(onClick = {
                                IpswDownloadService.start(context, fw.url, fw.version, fw.buildid, fw.sha1sum)
                            }) {
                                Icon(Icons.Rounded.Download, contentDescription = L("Загрузить"))
                            }
                        }
                    )
                }
            }
        }
        TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text(L("Закрыть"))
        }
    }
    }
}
