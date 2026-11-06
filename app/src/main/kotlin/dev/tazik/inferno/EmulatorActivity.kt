package dev.tazik.inferno

import android.content.Context
import android.content.Intent
import android.graphics.RectF
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.tazik.inferno.engine.LogCapture
import dev.tazik.inferno.engine.QemuBridge
import dev.tazik.inferno.engine.RestoreSession
import dev.tazik.inferno.engine.VMModel
import dev.tazik.inferno.ui.theme.TazikTheme
import kotlin.math.min
import kotlinx.coroutines.delay
import dev.tazik.inferno.engine.L

class EmulatorActivity : ComponentActivity() {

    companion object {
        const val EXTRA_VPM = "virtual_phone"
        fun intent(context: Context): Intent = Intent(context, EmulatorActivity::class.java)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val vpm = intent.getBooleanExtra(EXTRA_VPM, false)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        window.attributes.layoutInDisplayCutoutMode =
            android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        setContent {
            TazikTheme {
                EmulatorScreen(vpm, onExit = { finish() })
            }
        }
    }
}

@Composable
private fun EmulatorScreen(vpm: Boolean, onExit: () -> Unit) {
    val model = remember { TazikEngine.model }
    val image by model.picture.image.collectAsState()
    val state by model.qemuState.collectAsState()
    val hasRun by model.hasRun.collectAsState()
    val restoreStage by RestoreSession.shared.stage.collectAsState()
    var menuOpen by remember { mutableStateOf(false) }
    var logsOpen by remember { mutableStateOf(false) }
    var frozen by remember { mutableStateOf(false) }
    var taps by remember { mutableStateOf(0) }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            // Прямоугольник, который картинка занимает на самом деле: вписанная
            // с полями, не весь вид. Тач ложится на пиксель под пальцем.
            var box by remember { mutableStateOf(RectF()) }
            val containerW = constraints.maxWidth.toFloat()
            val containerH = constraints.maxHeight.toFloat()
            val bmp = image
            if (bmp != null) {
                val scale = min(containerW / bmp.width, containerH / bmp.height)
                val w = bmp.width * scale
                val h = bmp.height * scale
                box = RectF(
                    (containerW - w) / 2f, (containerH - h) / 2f,
                    (containerW + w) / 2f, (containerH + h) / 2f,
                )
            }

            val drawn = image?.asImageBitmap()
            if (drawn != null) Image(
                bitmap = drawn,
                contentDescription = L("Экран гостя"),
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = true)
                            model.tap(down.position.x, down.position.y, box)
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull() ?: break
                                if (change.isConsumed || !change.pressed) {
                                    model.release(change.position.x, change.position.y, box)
                                    break
                                }
                                // Палец едет — гость видит ведённое касание в новых
                                // пикселях: свайпы и перетаскивания работают.
                                model.tap(change.position.x, change.position.y, box)
                            }
                        }
                    },
            )
        }

        if (vpm) {
            // Пилюли поверх экрана, как в режиме правки хом-скрина iOS:
            // слева меню, справа выход.
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Box {
                    Pill(L("Изменить"), onClick = { menuOpen = true })
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(L("Домой")) },
                            onClick = { menuOpen = false; model.press(VMModel.HardwareButton.HOME) },
                        )
                        DropdownMenuItem(
                            text = { Text(L("Питание")) },
                            onClick = { menuOpen = false; model.press(VMModel.HardwareButton.POWER) },
                        )
                        DropdownMenuItem(
                            text = { Text(L("Громче")) },
                            onClick = { menuOpen = false; model.press(VMModel.HardwareButton.VOLUME_UP) },
                        )
                        DropdownMenuItem(
                            text = { Text(L("Тише")) },
                            onClick = { menuOpen = false; model.press(VMModel.HardwareButton.VOLUME_DOWN) },
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text(if (frozen) L("Продолжить") else L("Заморозить")) },
                            onClick = { menuOpen = false; frozen = !frozen; model.freeze(frozen) },
                        )
                        DropdownMenuItem(
                            text = { Text(L("Логи")) },
                            onClick = { menuOpen = false; logsOpen = true },
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text(L("Завершить работу")) },
                            onClick = {
                                menuOpen = false
                                model.shutdown()
                            },
                        )
                    }
                }
                Pill(L("Готово"), onClick = { model.shutdown() })
            }
        } else {
            // iPhone Mode: без всяких панелей, меню — три быстрых тапа в углу.
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 6.dp, end = 6.dp)
                    .size(width = 48.dp, height = 36.dp)
                    .clickable { taps++; if (taps >= 3) { taps = 0; menuOpen = true } }
            )
        }

        when (val s = state) {
            is QemuBridge.State.Failed -> {
                AlertDialog(
                    onDismissRequest = { },
                    title = { Text(L("Запуск не удался")) },
                    text = { Text(s.why) },
                    confirmButton = {
                        TextButton(onClick = onExit) { Text(L("Выйти")) }
                    },
                    shape = RoundedCornerShape(48.dp)
                )
            }
            is QemuBridge.State.Stopped -> {
                LaunchedEffect(s) { delay(1500); onExit() }
            }
            QemuBridge.State.Idle -> {
                if (hasRun) {
                    // QEMU поднимается минуты: ядро t8030 чинит SEP и тома до
                    // того, как главный цикл пойдёт.
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        LoadingIndicator(modifier = Modifier.size(64.dp))
                        Text(
                            text = L("Запуск машины"),
                            color = Color.White,
                            style = MaterialTheme.typography.bodyLargeEmphasized,
                            modifier = Modifier.padding(top = 16.dp)
                        )
                    }
                } else {
                    LaunchedEffect(Unit) { onExit() }
                }
            }
            QemuBridge.State.Running -> { }
        }

        if (restoreStage.isBusy || restoreStage is RestoreSession.Stage.Failed) {
            RestoreBanner(
                stage = restoreStage,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp),
            )
        }

        if (logsOpen) {
            val logs by LogCapture.text.collectAsState()
            AlertDialog(
                onDismissRequest = { logsOpen = false },
                title = { Text(L("Логи")) },
                text = {
                    Text(
                        logs.takeLast(8000).ifBlank { L("Пока пусто") },
                        modifier = Modifier
                            .heightIn(max = 420.dp)
                            .verticalScroll(rememberScrollState()),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                },
                confirmButton = {
                    TextButton(onClick = { logsOpen = false }) { Text(L("Закрыть")) }
                },
                shape = RoundedCornerShape(48.dp)
            )
        }

        if (!vpm && menuOpen) {
            AlertDialog(
                onDismissRequest = { menuOpen = false },
                title = { Text(L("Меню эмуляции")) },
                text = { Text(L("Завершить работу виртуальной машины и вернуться в Tazik?")) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            menuOpen = false
                            model.shutdown()
                        }
                    ) { Text(L("Завершить")) }
                },
                dismissButton = {
                    TextButton(onClick = { menuOpen = false }) { Text(L("Отмена")) }
                },
                shape = RoundedCornerShape(48.dp)
            )
        }
    }
}

/** Кнопка-пилюля в стиле режима правки хом-скрина iOS. */
@Composable
private fun Pill(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        color = Color(0xFF007AFF),
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.9f))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 5.dp),
    )
}

/** Полоса хода восстановления внизу экрана — пока рестор идёт. */
@Composable
private fun RestoreBanner(stage: RestoreSession.Stage, modifier: Modifier = Modifier) {
    val text = when (stage) {
        is RestoreSession.Stage.Booting -> L("Восстановление: машина грузится с рамдиска")
        is RestoreSession.Stage.WaitingForDevice -> L("Восстановление: жду USB гостя")
        is RestoreSession.Stage.Ready -> L("Восстановление: %@ готов", stage.type)
        is RestoreSession.Stage.Restoring ->
            if (stage.done > 0) L("Восстановление: %@, %.0f %%", stage.what, stage.done * 100)
            else L("Восстановление: %@", stage.what)
        is RestoreSession.Stage.Failed -> L("Восстановление не удалось: %@", stage.why)
        else -> return
    }
    Column(modifier = modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = text,
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
        )
        if (stage is RestoreSession.Stage.Restoring && stage.done > 0) {
            LinearProgressIndicator(
                progress = { stage.done.toFloat() },
                modifier = Modifier
                    .fillMaxWidth(0.6f)
                    .padding(top = 8.dp),
            )
        }
    }
}
