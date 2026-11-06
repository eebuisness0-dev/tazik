package dev.tazik.inferno.ui.sheets

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CropFree
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PhoneIphone
import androidx.compose.material.icons.rounded.SettingsBackupRestore
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.tazik.inferno.EmulatorActivity
import dev.tazik.inferno.TazikEngine
import dev.tazik.inferno.data.DeviceStore
import dev.tazik.inferno.data.SettingsStore
import dev.tazik.inferno.data.VirtualDevice
import dev.tazik.inferno.emulator.EmulatorOverlayService
import dev.tazik.inferno.engine.QemuBridge
import dev.tazik.inferno.engine.RestoreSession
import dev.tazik.inferno.ui.components.CircleIcon
import dev.tazik.inferno.ui.components.PixelRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.tazik.inferno.engine.L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LaunchModeSheet(
    device: VirtualDevice,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settingsStore = remember { SettingsStore.get(context) }
    var skipNext by remember { androidx.compose.runtime.mutableStateOf(false) }
    var refuseWhy by remember { mutableStateOf<String?>(null) }

    // без прошивки старт даёт чёрный экран и выход
    fun launchEmulator(vpm: Boolean) {
        if (device.firmwareBuild.isBlank()) {
            refuseWhy = L("Сначала установите iOS: на устройстве нет прошивки")
            return
        }
        val state = TazikEngine.model.qemuState.value
        if (state !is QemuBridge.State.Running) {
            val why = TazikEngine.startMachine(context)
            if (why != null) {
                refuseWhy = why
                return
            }
        }
        onDismiss()
        context.startActivity(
            Intent(context, EmulatorActivity::class.java)
                .putExtra(EmulatorActivity.EXTRA_VPM, vpm)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(32.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
            Text(
                text = L("Выберите режим"),
                style = MaterialTheme.typography.titleLargeEmphasized,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            PixelRow(
                onClick = { launchEmulator(vpm = false) },
                leading = { CircleIcon(Icons.Rounded.PhoneIphone) },
                title = "iPhone Mode",
                supporting = { Text(L("Полноэкранное погружение"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                trailing = { Icon(Icons.Rounded.CropFree, contentDescription = null, modifier = Modifier.size(20.dp)) },
            )
            PixelRow(
                onClick = {
                    scope.launch {
                        // Рестор перед стартом машины собирает рамдиск с
                        // патчером — секунды работы, не на главном потоке.
                        withContext(Dispatchers.Default) {
                            TazikEngine.startRestore(context)
                        }
                        onDismiss()
                        // Если рестор отказался стартовать (машина занята, нет
                        // набора) — экран эмулятора не открываем: баннер собьёт
                        // с толку, причина уже в логах.
                        val stage = RestoreSession.shared.stage.value
                        if (stage.isBusy) {
                            context.startActivity(Intent(context, EmulatorActivity::class.java))
                        } else if (stage is RestoreSession.Stage.Failed) {
                            refuseWhy = stage.why
                        }
                    }
                },
                leading = { CircleIcon(Icons.Rounded.SettingsBackupRestore) },
                title = L("Восстановление iOS"),
                supporting = { Text(L("Рестор с телефона: рамдиск, ASR, патчи"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                trailing = { Icon(Icons.Rounded.CropFree, contentDescription = null, modifier = Modifier.size(20.dp)) },
            )
            PixelRow(
                onClick = { launchEmulator(vpm = true) },
                leading = { CircleIcon(Icons.Rounded.PhoneAndroid) },
                title = "Virtual Phone Mode",
                supporting = { Text(L("Экран машины внутри Tazik: тулбар, пауза, логи"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                trailing = { Icon(Icons.Rounded.PhoneAndroid, contentDescription = null, modifier = Modifier.size(20.dp)) },
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                androidx.compose.material3.Checkbox(
                    checked = skipNext,
                    onCheckedChange = {
                        skipNext = it
                        if (it) scope.launch { settingsStore.set { s -> s.copy(askLaunchMode = false) } }
                    },
                    modifier = Modifier.size(40.dp)
                )
                Text(
                    text = L("Не показывать при следующем запуске"),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }

    val why = refuseWhy
    if (why != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { refuseWhy = null },
            title = { Text(L("Запустить нельзя")) },
            text = { Text(why) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { refuseWhy = null }) {
                    Text(L("Ок"))
                }
            },
            shape = RoundedCornerShape(48.dp),
        )
    }
}
