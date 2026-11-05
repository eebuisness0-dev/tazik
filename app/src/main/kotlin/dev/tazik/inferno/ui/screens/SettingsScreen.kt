package dev.tazik.inferno.ui.screens

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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.tazik.inferno.data.AppSettings
import dev.tazik.inferno.data.SettingsStore
import dev.tazik.inferno.data.ThemeMode
import dev.tazik.inferno.engine.AppLanguage
import dev.tazik.inferno.engine.L
import dev.tazik.inferno.engine.Settings
import dev.tazik.inferno.ui.components.PixelCard
import dev.tazik.inferno.ui.components.PixelGroup
import dev.tazik.inferno.ui.components.PixelGroupItem
import dev.tazik.inferno.ui.components.SectionLabel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val store = remember { SettingsStore.get(context) }
    val settings by store.settings.collectAsState(initial = AppSettings())
    val scope = rememberCoroutineScope()
    // Ревизия настроек движка: смена языка заставляет экран перечитать
    // строки L() — селектор ниже читает её как ключ.
    val revision by Settings.revision.collectAsState()
    val currentLanguage = remember(revision) { AppLanguage.of(Settings.language) }

    fun edit(block: (AppSettings) -> AppSettings) {
        scope.launch { store.set(block) }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(L("Настройки"), style = MaterialTheme.typography.titleLargeEmphasized) }
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
            SectionLabel(L("Оформление"))
            PixelCard {
                ThreeChoice(
                    options = listOf(
                        ThemeMode.SYSTEM to L("Авто"),
                        ThemeMode.LIGHT to L("Светлая"),
                        ThemeMode.DARK to L("Тёмная"),
                    ),
                    selected = settings.themeMode
                ) { mode -> edit { s -> s.copy(themeMode = mode) } }
            }


            PixelGroup {
                PixelGroupItem(
                    title = L("AMOLED-чёрный фон"),
                    trailing = { Switch(checked = settings.oledBlack, onCheckedChange = { v -> edit { s -> s.copy(oledBlack = v) } }) },
                    isFirst = true,
                )
                PixelGroupItem(
                    title = L("Динамическая палитра системы (Android 12+)"),
                    trailing = { Switch(checked = settings.dynamicColor, onCheckedChange = { v -> edit { s -> s.copy(dynamicColor = v) } }) },
                    isLast = true,
                )
            }

            SectionLabel(L("Запуск устройств"))
            PixelGroup {
                PixelGroupItem(
                    title = L("Спрашивать режим запуска (iPhone Mode / Virtual Phone)"),
                    trailing = { Switch(checked = settings.askLaunchMode, onCheckedChange = { v -> edit { s -> s.copy(askLaunchMode = v) } }) },
                    isFirst = true,
                    isLast = true,
                )
            }

            SectionLabel(L("Язык"))
            PixelCard {
                ThreeChoice(
                    options = listOf(
                        AppLanguage.SYSTEM to L("Авто"),
                        AppLanguage.RU to "RU",
                        AppLanguage.EN to "EN",
                        AppLanguage.UA to "UA",
                    ),
                    selected = currentLanguage
                ) { lang -> Settings.language = lang.code }
            }
        }
    }
}

@Composable
private fun <T> ThreeChoice(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (option, label) ->
            SegmentedButton(
                selected = option == selected,
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size)
            ) {
                Text(label)
            }
        }
    }
}

@Composable
private fun ToggleRow(name: String, value: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        content = { Text(name) },
        trailingContent = { Switch(checked = value, onCheckedChange = onChange) }
    )
}
