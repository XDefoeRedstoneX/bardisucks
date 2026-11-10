@file:OptIn(ExperimentalMaterial3Api::class)

package bardisucks.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import bardisucks.app.R
import bardisucks.core.home.GroupItem
import bardisucks.core.home.HomeState
import bardisucks.core.home.LightItem
import bardisucks.core.home.Target

@Composable
fun HomeScreen(
    state: HomeState,
    /** Highlighted tile, when the control screen is open next to this one (tablets). */
    selected: Target?,
    onOpen: (Target) -> Unit,
    onToggle: (Target) -> Unit,
    onNewGroup: () -> Unit,
    onSettings: () -> Unit,
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.home_title)) },
                actions = {
                    IconButton(onClick = onSettings) { Icon(AppIcons.Settings, stringResource(R.string.settings_title)) }
                },
            )
        },
    ) { padding ->
        if (state.lights.isEmpty()) {
            EmptyHome(onImport, Modifier.padding(padding))
            return@Scaffold
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 156.dp),
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                SectionHeader(stringResource(R.string.section_groups)) {
                    TextButton(onClick = onNewGroup) {
                        Icon(AppIcons.Add, null, Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text(stringResource(R.string.new_group))
                    }
                }
            }
            items(state.groups, key = { "group:${it.id}" }) { group ->
                val target = Target.Group(group.id)
                Tile(
                    name = group.displayName(),
                    status = group.statusText(),
                    reach = group.reach,
                    on = group.anyOn,
                    colors = group.reachable.filter { it.state!!.on }.map { it.state!!.displayColor() },
                    icon = AppIcons.Group,
                    selected = selected == target,
                    onOpen = { onOpen(target) },
                    onToggle = { onToggle(target) },
                )
            }
            item(span = { GridItemSpan(maxLineSpan) }) { SectionHeader(stringResource(R.string.section_lamps)) }
            items(state.lights, key = { "light:${it.id}" }) { lamp ->
                val target = Target.Light(lamp.id)
                Tile(
                    name = lamp.name,
                    status = lamp.statusText(),
                    reach = lamp.reach,
                    on = lamp.isLit,
                    colors = listOfNotNull(lamp.state?.takeIf { lamp.isLit }?.displayColor()),
                    icon = AppIcons.Bulb,
                    selected = selected == target,
                    onOpen = { onOpen(target) },
                    onToggle = { onToggle(target) },
                )
            }
        }
    }
}

private val LightItem.isLit: Boolean get() = online && state?.on == true

@Composable
private fun SectionHeader(title: String, action: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().height(48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        action()
    }
}

/** A lamp or group: tap the tile to open its controls, tap the round button to switch it. */
@Composable
private fun Tile(
    name: String,
    status: String,
    reach: Reach,
    on: Boolean,
    /** What the lamp(s) are showing right now; empty when off. Tints the tile. */
    colors: List<Color>,
    icon: ImageVector,
    selected: Boolean,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val tint = colors.firstOrNull()
    val container = if (tint != null) tint.copy(alpha = 0.22f).compositeOver(scheme.surfaceContainerHigh) else scheme.surfaceContainer
    Surface(
        modifier = Modifier.height(124.dp).testTag("tile:$name"),
        shape = RoundedCornerShape(24.dp),
        color = container,
        border = if (selected) BorderStroke(2.dp, scheme.primary) else null,
    ) {
        Column(
            Modifier.clickable(onClick = onOpen).padding(start = 16.dp, top = 12.dp, end = 12.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                if (colors.size > 1) {
                    MemberDots(colors)
                } else {
                    Icon(icon, null, Modifier.size(28.dp), tint = tint ?: scheme.onSurfaceVariant.copy(alpha = if (reach == Reach.ONLINE) 0.8f else 0.4f))
                }
                FilledIconButton(
                    onClick = onToggle,
                    enabled = reach == Reach.ONLINE,
                    modifier = Modifier.size(44.dp).testTag("power:$name"),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = if (on) scheme.primary else scheme.surfaceContainerHighest,
                        contentColor = if (on) scheme.onPrimary else scheme.onSurfaceVariant,
                    ),
                ) {
                    Icon(AppIcons.Power, stringResource(if (on) R.string.turn_off else R.string.turn_on), Modifier.size(22.dp))
                }
            }
            Column {
                Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Overlapping dots, one per lit lamp of a group. */
@Composable
private fun MemberDots(colors: List<Color>) {
    val outline = MaterialTheme.colorScheme.surfaceContainerHigh
    Box(Modifier.height(28.dp)) {
        colors.take(4).forEachIndexed { index, color ->
            Box(
                Modifier
                    .offset(x = (index * 18).dp)
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(outline)
                    .padding(2.dp)
                    .clip(CircleShape)
                    .background(color),
            )
        }
    }
}

@Composable
private fun EmptyHome(onImport: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(AppIcons.Bulb, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
        Text(stringResource(R.string.empty_title), style = MaterialTheme.typography.headlineSmall)
        Text(
            stringResource(R.string.empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onImport) {
            Icon(AppIcons.Upload, null, Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.import_devices))
        }
    }
}
