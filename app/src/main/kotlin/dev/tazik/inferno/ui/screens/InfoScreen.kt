package dev.tazik.inferno.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.tazik.inferno.engine.L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InfoScreen(
    topic: String,
    onBack: () -> Unit,
) {
    Scaffold(topBar = {
        TopAppBar(
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, L("Назад")) }
            },
            title = { Text(infoTitle(topic), style = MaterialTheme.typography.titleLargeEmphasized) }
        )
    }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            infoParagraphs(topic).forEach { (title, body) ->
                Text(text = title, style = MaterialTheme.typography.titleMediumEmphasized)
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

private fun infoTitle(topic: String): String = when (topic) {
    "how" -> L("Как это работает")
    "restore" -> L("Образы и файлы")
    "net" -> L("Сеть и порт-форвардинг")
    "limits" -> L("Ограничения Android")
    else -> L("Чего не хватает")
}

private fun infoParagraphs(topic: String): List<Pair<String, String>> = when (topic) {
    "how" -> listOf(
        Pair(
            L("Суть Inferno"),
            L("Inferno — форк QEMU с виртуализацией Apple-устройств. ")
                + L("QEMU эмулирует SoC A13 (T8030) и обходит проверки SEP и контроллера, ")
                + L("чтобы ядро XNU поверило в «настоящий» iPhone 11. ")
                + L("Tazik запускает ту же сборку libqemu-aarch64-softmmu.so на Android.")
        ),
        Pair(
            L("Что эмулируется"),
            L("CPU: 6 ядер A13 (multi-threaded TCG). Память: до 4 ГБ гостевых. ")
                + L("Диски: NVMe-образы с разделами APFS. Сеть: apple-ncm-host + slirp (NAT).")
        ),
        Pair(
            L("Почему TCG"),
            L("Гостевой и хостовый ISA различаются деталями Apple ISA, поэтому используется ")
                + L("чистая эмуляция через TCG, а не KVM.")
        ),
        Pair(
            "iPhone Mode",
            L("На весь экран рисуется кадр VNC с точной панелью 828×1792 (iPhone 11), ")
                + L("как будто у вас в руках настоящий iPhone.")
        )
    )
    "restore" -> listOf(
        Pair(
            L("Файлы внутри IPSW"),
            L("Из архива .ipsw распаковываются BuildManifest и все IMG4-образы ")
                + L("(sep-firmware, dp-firmware, kernelcaches, device trees). Сами .dmg пропускаются.")
        ),
        Pair(
            L("Что нужно от пользователя"),
            L("Тикеты для SEP и подписи (root_ticket.der, AppleSEPROM, sep-firmware) нельзя ")
                + L("сгенерировать на Android: их получают на настольном окружении по рецептам ChefKiss.")
        ),
        Pair(
            L("Структура GuestData"),
            L("Диски NVMe: root, firmware, syscfg, ctrl_bits, nvram, effaceable, panic_log, ")
                + "sep_nvram, sep_ssc."
        ),
        Pair(
            L("Установка"),
            L("После копирования файлов Tazik записывает Restore/BuildManifest.plist и метаданные ")
                + L("в каталог filesDir каждого устройства.")
        )
    )
    "net" -> listOf(
        Pair(
            L("Как работает NAT"),
            L("В Inferno iOS-сеть идёт через apple-ncm-host: iOS видит USB-NCM сеть, ")
                + L("Android-хост поднимает slirp и передаёт трафик в интернет.")
        ),
        Pair(
            L("Порт-форвардинг"),
            L("Каждое правило превращается в hostfwd в QEMU — например, для ssh, VNC или WebKit Remote DevTools.")
        ),
        Pair(
            L("Bridge и Host-only"),
            L("Требуют VPN-туннель на Android или мостовую сетевую карту, пока не реализовано полностью: ")
                + L("зарезервировано для будущих релизов.")
        )
    )
    "limits" -> listOf(
        Pair(
            L("Скорость"),
            L("TCG-эмуляция примерно в 2–5 раз медленнее реального A13 на сопоставимом железе. ")
                + L("JIT-ускорение для гостя на Android пока недоступно.")
        ),
        Pair(
            L("Хостовая память"),
            L("ОЗУ гостя ограничено 4 ГБ (максимум iPhone 11). Хосту нужно зарезервировать эти ГБ, ")
                + L("иначе low-memory killer Android может закрыть приложение.")
        ),
        Pair(
            L("iOS-сервисы"),
            L("Некоторые приложения iOS не общаются с серверами Apple из-за антифрод-проверок ")
                + L("(IDFA, время загрузки, жесты).")
        )
    )
    else -> listOf(
        Pair(
            "Snapshots",
            L("Восстановление снимков состояния (migrate) появится в следующих релизах.")
        ),
        Pair(
            L("USB-проброс"),
            L("QEMU hostdev для Android USB — пока регистрация VID:PID только в лог.")
        ),
        Pair(
            L("Аудио"),
            L("Звук гостя через QEMU audio — не реализовано, на будущее.")
        )
    )
}
