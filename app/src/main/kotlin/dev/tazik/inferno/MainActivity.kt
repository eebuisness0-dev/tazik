package dev.tazik.inferno

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Help
import androidx.compose.material.icons.rounded.PhoneIphone
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.core.content.ContextCompat
import dev.tazik.inferno.data.AppSettings
import dev.tazik.inferno.data.SettingsStore
import dev.tazik.inferno.ui.components.DockedItem
import dev.tazik.inferno.ui.components.TazikDockedToolbar
import dev.tazik.inferno.ui.navigation.Destination
import dev.tazik.inferno.ui.screens.DevicesScreen
import dev.tazik.inferno.ui.screens.DeviceDetailScreen
import dev.tazik.inferno.ui.screens.InfoScreen
import dev.tazik.inferno.ui.screens.LogsScreen
import dev.tazik.inferno.ui.screens.SupportScreen
import dev.tazik.inferno.ui.screens.SettingsScreen
import dev.tazik.inferno.ui.screens.details.BootloaderScreen
import dev.tazik.inferno.ui.screens.details.DiskManagementScreen
import dev.tazik.inferno.ui.screens.details.NetworkScreen
import dev.tazik.inferno.ui.screens.details.SnapshotsScreen
import dev.tazik.inferno.ui.screens.details.SharedFoldersScreen
import dev.tazik.inferno.ui.screens.details.UsbDevicesScreen
import dev.tazik.inferno.ui.screens.details.VirtualHardwareScreen
import dev.tazik.inferno.ui.theme.TazikTheme
import dev.tazik.inferno.engine.L

class MainActivity : ComponentActivity() {
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            val settings by remember { SettingsStore.get(this) }.settings.collectAsState(initial = AppSettings())
            TazikTheme(settings = settings) {
                TazikRoot()
            }
        }
    }
}

private val DESTS = listOf(Destination.Devices, Destination.Settings, Destination.Support)

@Composable
fun TazikRoot() {
    var current by remember { mutableStateOf<Destination>(Destination.Devices) }
    var stack by remember { mutableStateOf<List<Destination>>(emptyList()) }
    val go: (Destination) -> Unit = { dest ->
        stack = stack + current
        current = dest
    }
    val back: () -> Unit = {
        current = stack.lastOrNull() ?: Destination.Devices
        stack = stack.dropLast(1)
    }
    BackHandler(enabled = stack.isNotEmpty()) { back() }
    val topLevel = current == Destination.Devices || current == Destination.Settings || current == Destination.Support
    val railMode = LocalConfiguration.current.screenWidthDp >= 600

    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        ) { padding ->
            Row(modifier = Modifier.padding(padding)) {
                if (railMode) {
                    NavigationRail(modifier = Modifier.fillMaxHeight()) {
                        DESTS.forEach { d ->
                            NavigationRailItem(
                                selected = d == current,
                                onClick = { current = d },
                                icon = { Icon(iconFor(d), contentDescription = labelFor(d)) },
                                label = { Text(labelFor(d)) }
                            )
                        }
                    }
                }
                Box(modifier = Modifier.fillMaxSize().weight(1f)) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        AnimatedContent(
                            targetState = current,
                            transitionSpec = {
                                // Shared-axis (Pixel): вход лёгкий slide+fade+scale-поп,
                                // направление по порядку следования top-level
                                val idx = DESTS.indexOf(targetState)
                                val curIdx = DESTS.indexOf(initialState)
                                if (idx >= 0 && curIdx >= 0) {
                                    val forward = idx > curIdx
                                    val dir = if (forward) 1 else -1
                                    (slideInHorizontally(tween(300)) { it / 8 * dir } + fadeIn(tween(240)) + scaleIn(initialScale = 0.96f, animationSpec = tween(300))) togetherWith
                                        (fadeOut(tween(110)) + scaleOut(targetScale = 0.97f, animationSpec = tween(110)))
                                } else {
                                    (fadeIn(tween(220)) + scaleIn(initialScale = 0.94f, animationSpec = tween(280, easing = FastOutSlowInEasing))) togetherWith fadeOut(tween(90))
                                }
                            },
                            label = "root-nav",
                            modifier = Modifier.fillMaxSize().weight(1f),
                        ) { dest ->
                            when (dest) {
                                Destination.Devices -> DevicesScreen(
                                    onOpenDetail = { deviceId -> go(Destination.DeviceDetail(deviceId)) },
                                    onSupport = { go(Destination.Support) },
                                )
                                Destination.Settings -> SettingsScreen(onBack = back)
                                Destination.Support -> SupportScreen(
                                    onBack = back,
                                    onNavigate = { next -> go(next) }
                                )
                                is Destination.DeviceDetail -> DeviceDetailScreen(
                                    deviceId = dest.deviceId,
                                    onBack = back,
                                    onNavigate = { next -> go(next) },
                                )
                                is Destination.VirtualHardware -> VirtualHardwareScreen(dest.deviceId, onBack = back)
                                is Destination.Bootloader -> BootloaderScreen(dest.deviceId, onBack = back)
                                is Destination.Snapshots -> SnapshotsScreen(dest.deviceId, onBack = back)
                                is Destination.SharedFolders -> SharedFoldersScreen(dest.deviceId, onBack = back)
                                is Destination.UsbDevices -> UsbDevicesScreen(dest.deviceId, onBack = back)
                                is Destination.Network -> NetworkScreen(dest.deviceId, onBack = back)
                                is Destination.DiskManagement -> DiskManagementScreen(dest.deviceId, dest.diskPath, onBack = back)
                                is Destination.Logs -> LogsScreen(dest.deviceId, onBack = back)
                                is Destination.Info -> InfoScreen(dest.topic, onBack = back)
                            }
                        }
                        if (!railMode && topLevel) {
                            NavigationBar(
                                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                            ) {
                                DESTS.forEachIndexed { idx, d ->
                                    NavigationBarItem(
                                        selected = d == current,
                                        onClick = { current = d },
                                        icon = { Icon(iconFor(d), contentDescription = labelFor(d)) },
                                        label = { Text(labelFor(d), maxLines = 1, softWrap = false) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun iconFor(dest: Destination) = when (dest) {
    Destination.Devices -> Icons.Rounded.PhoneIphone
    Destination.Settings -> Icons.Rounded.Settings
    Destination.Support -> Icons.Rounded.Help
    else -> Icons.Rounded.PhoneIphone
}

private fun labelFor(dest: Destination) = when (dest) {
    Destination.Devices -> L("Устройства")
    Destination.Settings -> L("Настройки")
    Destination.Support -> L("Поддержка")
    else -> "Tazik"
}
