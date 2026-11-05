package dev.tazik.inferno.ui.components

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingActionButtonMenu
import androidx.compose.material3.FloatingActionButtonMenuScope
import androidx.compose.material3.FloatingActionButtonMenuItem
import androidx.compose.material3.FilledTonalButton as M3FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleFloatingActionButton
import androidx.compose.material3.ToggleFloatingActionButtonDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.tazik.inferno.engine.L

@Composable
fun FilledButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(50),
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    Button(onClick = onClick, modifier = modifier, enabled = enabled, shape = shape, content = content)
}

@Composable
fun FilledTonalButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(50),
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    M3FilledTonalButton(onClick = onClick, modifier = modifier, enabled = enabled, shape = shape, content = content)
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMediumEmphasized,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

enum class ChipTone { OK, WARN, ERROR, NEUTRAL }

@Composable
fun StatusChip(text: String, modifier: Modifier = Modifier, tone: ChipTone = ChipTone.NEUTRAL) {
    val bg = when (tone) {
        ChipTone.OK -> MaterialTheme.colorScheme.secondaryContainer
        ChipTone.WARN -> MaterialTheme.colorScheme.tertiaryContainer
        ChipTone.ERROR -> MaterialTheme.colorScheme.errorContainer
        ChipTone.NEUTRAL -> MaterialTheme.colorScheme.surfaceVariant
    }
    val fg = when (tone) {
        ChipTone.OK -> MaterialTheme.colorScheme.onSecondaryContainer
        ChipTone.WARN -> MaterialTheme.colorScheme.onTertiaryContainer
        ChipTone.ERROR -> MaterialTheme.colorScheme.onErrorContainer
        ChipTone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(text = text, style = MaterialTheme.typography.labelMedium, color = fg)
    }
}

@Composable
fun CircleIcon(icon: ImageVector, modifier: Modifier = Modifier, sizeDp: Int = 24) {
    // Pixel Settings: плоская нейтральная иконка без цветной подложки
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.size((sizeDp + 4).dp)
    )
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(MaterialShapes.Cookie9Sided.toShape())
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp)
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleLargeEmphasized
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp)
        )
        if (actionLabel != null && onAction != null) {
            androidx.compose.material3.FilledTonalButton(
                onClick = onAction,
                shape = RoundedCornerShape(50)
            ) {
                Text(actionLabel)
            }
        }
    }
}

@Composable
fun ErrorBanner(
    message: String,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
    onClose: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(
            imageVector = Icons.Rounded.Build,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onErrorContainer
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.weight(1f)
        )
        if (onRetry != null) {
            Text(
                text = L("Повторить"),
                style = MaterialTheme.typography.labelLargeEmphasized,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onRetry)
                    .padding(4.dp)
            )
        }
        if (onClose != null) {
            Text(
                text = L("Закрыть"),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onClose)
                    .padding(4.dp)
            )
        }
    }
}

object TazikHaptics {
    @Volatile var enabled: Boolean = true
}

fun vibrateTick(context: Context) {
    if (!TazikHaptics.enabled) return
    try {
        val vib = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (!vib.hasVibrator()) return
        vib.vibrate(VibrationEffect.createOneShot(20, 96))
    } catch (t: Throwable) {
    }
}

/*
 * Pixel/Nothing-«подложки настроек»: единая карточка 28dp на SurfaceContainer;
 * пункты разделяются inset-линиями 1dp OutlineVariant, начинающимися у текста;
 * риппл ограничен зоной пункта, первый/последний пункты скруглены.
 */

@Composable
fun PixelGroup(
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer),
        verticalArrangement = Arrangement.spacedBy(0.dp),
        content = content,
    )
}

@Composable
fun PixelCard(
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(16.dp),
        content = content,
    )
}

@Composable
fun androidx.compose.foundation.layout.ColumnScope.PixelGroupItem(
    onClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    title: String,
    supporting: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    isFirst: Boolean = false,
    isLast: Boolean = false,
) {
    val shape: androidx.compose.ui.graphics.Shape = when {
        isFirst && isLast -> RoundedCornerShape(28.dp)
        isFirst -> RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp, bottomStart = 0.dp, bottomEnd = 0.dp)
        isLast -> RoundedCornerShape(topStart = 0.dp, topEnd = 0.dp, bottomStart = 28.dp, bottomEnd = 28.dp)
        else -> androidx.compose.ui.graphics.RectangleShape
    }
    Column {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                if (leading != null) {
                    Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) { leading() }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.bodyLarge)
                    if (supporting != null) supporting()
                }
                if (trailing != null) trailing()
            }
        }
        if (!isLast) {
            HorizontalDivider(
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }
    }
}

@Composable
fun PixelRow(
    onClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    title: String,
    supporting: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    container: Color? = null,
    modifier: Modifier = Modifier,
) {
    PixelGroup(modifier = modifier) {
        PixelGroupItem(
            onClick = onClick,
            leading = leading,
            title = title,
            supporting = supporting,
            trailing = trailing,
            isFirst = true,
            isLast = true,
        )
    }
}

data class DockedItem(
    val icon: ImageVector,
    val label: String,
)

@Composable
fun TazikDockedToolbar(
    items: List<DockedItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Раздельные пункты без общей подложки (Pixel nav bar):
    // выделенный пункт — самостоятельная пилюля secondaryContainer,
    // расширяется кроновом morph-ом без жального jelly (NoBouncy, быстрая пружина).
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEachIndexed { index, item ->
            val selected = index == selectedIndex
            val iconScale by animateFloatAsState(
                targetValue = if (selected) 1.08f else 1f,
                animationSpec = tween(160, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                label = "dockIconScale",
            )
            Box(
                modifier = Modifier
                    .height(48.dp)
                    .clip(RoundedCornerShape(50))
                    .background(
                        if (selected) MaterialTheme.colorScheme.secondaryContainer
                        else Color.Transparent
                    )
                    .animateContentSize(
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMedium,
                        ),
                        alignment = Alignment.Center,
                    )
                    .clickable { onSelect(index) }
                    .padding(horizontal = if (selected) 16.dp else 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = item.label,
                        tint = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .size(22.dp)
                            .graphicsLayer { scaleX = iconScale; scaleY = iconScale },
                    )
                    AnimatedVisibility(
                        visible = selected,
                        enter = fadeIn(tween(120)),
                        exit = fadeOut(tween(70)),
                    ) {
                        Text(
                            text = item.label,
                            maxLines = 1,
                            softWrap = false,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }
        }
    }
}

data class FabItem(
    val icon: ImageVector,
    val label: String,
    val onClick: () -> Unit,
)

@Composable
fun TazikFabMenu(
    fabItems: List<FabItem>,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val context = LocalContext.current

    FloatingActionButtonMenu(
        expanded = expanded,
        modifier = modifier,
        button = {
            val corner: Dp by animateDpAsState(
                targetValue = if (expanded) 26.dp else 32.dp,
                animationSpec = tween(200),
                label = "fabCorner",
            )
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(corner))
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .clickable {
                        expanded = !expanded
                    },
                contentAlignment = Alignment.Center,
            ) {
                AnimatedContent(
                    targetState = if (expanded) Icons.Rounded.Close else Icons.Rounded.Add,
                    transitionSpec = {
                        fadeIn(tween(120)) togetherWith fadeOut(tween(90))
                    },
                    label = "fabIcon",
                ) { icon ->
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        },
        content = {
            fabItems.forEach { item ->
                FloatingActionButtonMenuItem(
                    onClick = {
                        expanded = false
                        item.onClick()
                    },
                    icon = { Icon(item.icon, contentDescription = null) },
                    text = { Text(item.label, maxLines = 1, softWrap = false) }
                )
            }
        }
    )
}
