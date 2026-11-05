package dev.tazik.inferno.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.tazik.inferno.ui.components.CircleIcon
import dev.tazik.inferno.ui.components.PixelGroup
import dev.tazik.inferno.ui.components.PixelGroupItem
import dev.tazik.inferno.ui.components.SectionLabel
import dev.tazik.inferno.ui.navigation.Destination
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Help
import dev.tazik.inferno.engine.L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SupportScreen(
    onBack: () -> Unit,
    onNavigate: (Destination) -> Unit,
) {
    Scaffold(topBar = {
        TopAppBar(
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, L("Назад")) }
            },
            title = { Text(L("Поддержка"), style = MaterialTheme.typography.titleLargeEmphasized) }
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
            SectionLabel(L("Документация Inferno"))
            val docRows = listOf(
                L("Как это работает") to Destination.Info("how"),
                L("Образы и файлы") to Destination.Info("restore"),
                L("Сеть и порт-форвардинг") to Destination.Info("net"),
                L("Ограничения Android") to Destination.Info("limits"),
                L("Чего не хватает") to Destination.Info("todo"),
            )
            PixelGroup {
                docRows.forEachIndexed { i, (title, dest) ->
                    InfoRow(title, { onNavigate(dest) }, i == 0, i == docRows.size - 1)
                }
            }

            SectionLabel(L("Форум и issues"))
            PixelGroup {
                PixelGroupItem(
                    leading = { CircleIcon(Icons.Rounded.Help) },
                    title = "GitHub: MakrSas/Inferno-iOS",
                    supporting = { Text(L("Основной репозиторий проекта и обсуждения"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    isFirst = true,
                    isLast = true,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun androidx.compose.foundation.layout.ColumnScope.InfoRow(
    title: String,
    onClick: () -> Unit,
    isFirst: Boolean,
    isLast: Boolean,
) {
    PixelGroupItem(onClick = onClick, title = title, isFirst = isFirst, isLast = isLast)
}
