package dev.tazik.inferno.ui.sheets

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.tazik.inferno.TazikEngine
import dev.tazik.inferno.engine.L
import dev.tazik.inferno.engine.LogCapture
import dev.tazik.inferno.engine.RestorePrep
import dev.tazik.inferno.engine.VMConfig
import dev.tazik.inferno.ui.components.PixelRow
import dev.tazik.inferno.ui.components.FilledButton
import dev.tazik.inferno.ui.components.FilledTonalButton
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Подготовка набора для движка из выбранного .ipsw: диски, Restore/, тикеты,
 * прошивка SEP. То, что в Inferno делала страница подготовки.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstallIosSheet(
    device: dev.tazik.inferno.data.VirtualDevice,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var ipswUri by remember { mutableStateOf<Uri?>(null) }
    var ticketUri by remember { mutableStateOf<Uri?>(null) }
    var sepKey by remember {
        mutableStateOf(
            // Ключ прошивки SEP этой сборки (14.5 beta 18E5186a, iPhone12,1):
            // IV первым, потом ключ — 96 hex-цифр. Для другой сборки берётся
            // со страницы Keys той же вики.
            "36c679fd60bec7748727bdd470612382" +
                "c1edaebe685cba7d3658aadd186723457b4f56db8bc7d9dd8d76cb92ebd96831",
        )
    }
    var job by remember { mutableStateOf<Job?>(null) }
    val stage = remember { MutableStateFlow<String?>(null) }
    val stageState by stage.collectAsState()
    var failure by remember { mutableStateOf<String?>(null) }

    val working = job != null

    val pickIpsw = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) {
            }
            ipswUri = uri
        }
    }
    val pickTicket = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) {
            }
            ticketUri = uri
        }
    }

    ModalBottomSheet(
        onDismissRequest = { if (!working) onDismiss() },
        shape = RoundedCornerShape(32.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = L("Установка iOS"),
                style = MaterialTheme.typography.titleLargeEmphasized,
                modifier = Modifier.padding(top = 8.dp)
            )
            Text(
                text = L("Из архива прошивки делается набор движка: диски, загрузочные образы, тикеты. Дальше — «Восстановление iOS»."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PixelRow(
                title = ipswUri?.lastPathSegment ?: L("Файл не выбран"),
                supporting = {
                    Text(L(".ipsw из Apple"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                },
                leading = { Icon(Icons.Rounded.Description, contentDescription = null) },
                trailing = {
                    FilledTonalButton(enabled = !working, onClick = {
                        pickIpsw.launch(arrayOf("application/octet-stream", "application/zip", "application/x-zip-compressed"))
                    }) {
                        Text(L("Выбрать"))
                    }
                }
            )
            PixelRow(
                title = ticketUri?.lastPathSegment ?: L("Тикет не выбран"),
                supporting = {
                    Text(L("ticket.shsh2 из набора ChefKiss"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                },
                leading = { Icon(Icons.Rounded.Description, contentDescription = null) },
                trailing = {
                    FilledTonalButton(enabled = !working, onClick = {
                        pickTicket.launch(arrayOf("*/*"))
                    }) {
                        Text(L("Выбрать"))
                    }
                }
            )
            OutlinedTextField(
                value = sepKey,
                onValueChange = { sepKey = it.filterNot { c -> c.isWhitespace() } },
                label = { Text(L("Ключ прошивки SEP (IV+KEY)")) },
                singleLine = true,
                enabled = !working,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                TextButton(
                    enabled = true,
                    onClick = {
                        job?.cancel()
                        job = null
                        if (!working) onDismiss()
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (working) L("Отмена (после шага)") else L("Отмена"))
                }
                FilledButton(
                    enabled = !working && ipswUri != null && ticketUri != null,
                    onClick = {
                        val ipsw = ipswUri!!
                        val ticket = ticketUri!!
                        job = scope.launch {
                            failure = runCatching {
                                prepare(context, ipsw, ticket, sepKey, stage)
                            }.exceptionOrNull()?.message ?: ""
                            if (failure == "") stage.value = L("Готово. Дальше — «Восстановление iOS».")
                            job = null
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(L("Установить"))
                }
            }
            stageState?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }

    failure?.let { why ->
        if (why.isNotEmpty()) {
            AlertDialog(
                onDismissRequest = { failure = null },
                title = { Text(L("Ошибка установки")) },
                text = { Text(why) },
                confirmButton = {
                    TextButton(onClick = { failure = null }) { Text(L("Ок")) }
                },
                shape = RoundedCornerShape(48.dp)
            )
        }
    }
}

/** Копирует SAF-uri в файл движка и запускает подготовку набора. */
private suspend fun prepare(
    context: android.content.Context,
    ipsw: Uri,
    ticket: Uri,
    sepKey: String,
    stage: MutableStateFlow<String?>,
): String = withContext(Dispatchers.IO) {
    val data = VMConfig.dataDirectory
    data.mkdirs()

    // Прошивка: гигабайты. Пишем сразу на место набора, чтобы RestorePrep
    // не копировал второй раз.
    val name = ipsw.lastPathSegment?.substringAfterLast('/') ?: "firmware.ipsw"
    val ipswFile = File(data, name)
    val size = contentSize(context, ipsw)
    val alreadyHere = ipswFile.isFile && ipswFile.length() > 0 &&
        (size <= 0 || ipswFile.length() == size)
    if (!alreadyHere) {
        ipswFile.delete()
        stage.value = L("Копирую прошивку…")
        context.contentResolver.openInputStream(ipsw)?.use { input ->
            java.io.FileOutputStream(ipswFile).use { output ->
                val buffer = ByteArray(8 shl 20)
                var copied = 0L
                while (true) {
                    if (Thread.currentThread().isInterrupted) return@withContext L("Отменено")
                    val n = input.read(buffer)
                    if (n <= 0) break
                    output.write(buffer, 0, n)
                    copied += n
                    if (size > 0) {
                        val percent = (copied.toDouble() / size * 100).toInt()
                        val text = L("Копирую прошивку, %d %%", percent)
                        if (stage.value != text) {
                            stage.value = text
                            LogCapture.note(L("Подготовка: копирую прошивку, %d %%", percent))
                        }
                    }
                }
            }
        } ?: return@withContext L("Не читается: %s", name)
    }

    // Мелкие файлы — в кэш.
    val ticketFile = copyToCache(context, ticket, "ticket.shsh2")
        ?: return@withContext L("Не читается: тикет")

    stage.value = L("Готовлю набор…")
    LogCapture.note(L("Подготовка: делаю набор"))
    RestorePrep.prepare(
        inputs = RestorePrep.Inputs(
            ipsw = ipswFile,
            shsh2 = ticketFile,
            sepROM = null,
            sepKey = sepKey,
            cryptexTemplate = null,
        ),
        note = { LogCapture.note(it) },
        progress = { done ->
            stage.value = L("Распаковка набора, %d %%", (done * 100).toInt())
        },
    )
    "" // без ошибки
}

private fun contentSize(context: android.content.Context, uri: Uri): Long = try {
    context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
} catch (_: Throwable) {
    -1L
}

private fun copyToCache(context: android.content.Context, uri: Uri, name: String): File? {
    val target = File(context.cacheDir, name)
    return try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: return null
        target
    } catch (_: Throwable) {
        null
    }
}
