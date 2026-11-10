@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package bardisucks.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import bardisucks.app.R
import bardisucks.core.LightCommand
import bardisucks.core.LightMode
import bardisucks.core.home.LightItem
import bardisucks.core.home.LightProblem
import bardisucks.core.home.Swatch
import bardisucks.core.home.Target

private enum class Tab { WHITE, COLOUR }

/** The controls of one lamp, or of a group steered as if it were one lamp. */
@Composable
fun LightScreen(
    light: Controlled,
    swatches: List<Swatch>,
    actions: LightActions,
    /** Null when the screen sits next to the home screen (tablets), where there is nothing to go back to. */
    onBack: (() -> Unit)?,
    onEdit: () -> Unit,
    onOpenMember: (Target) -> Unit,
    onAddSwatch: (Swatch) -> Unit,
    onRemoveSwatch: (Swatch) -> Unit,
    modifier: Modifier = Modifier,
) {
    val target = light.target
    val state = light.state
    val usable = light.reach == Reach.ONLINE && state != null

    // The tab follows the lamp's mode. A tap overrides it until the lamp has switched.
    var chosenTab by remember(target, state?.mode) { mutableStateOf<Tab?>(null) }
    val tab = chosenTab ?: if (state?.mode == LightMode.COLOUR) Tab.COLOUR else Tab.WHITE

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(light.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    if (onBack != null) IconButton(onClick = onBack) { Icon(AppIcons.Back, stringResource(R.string.back)) }
                },
                actions = { IconButton(onClick = onEdit) { Icon(AppIcons.Edit, stringResource(R.string.edit)) } },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (light.reach != Reach.ONLINE) ReachBanner(light)
            PowerRow(light, enabled = usable, onToggle = { actions.toggle(target) })

            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = tab == Tab.WHITE,
                    onClick = {
                        chosenTab = Tab.WHITE
                        if (state?.mode != LightMode.WHITE || !state.on) actions.apply(target, LightCommand.White())
                    },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                    enabled = usable,
                ) { Text(stringResource(R.string.tab_white)) }
                SegmentedButton(
                    selected = tab == Tab.COLOUR,
                    onClick = {
                        chosenTab = Tab.COLOUR
                        if (state != null && (state.mode != LightMode.COLOUR || !state.on)) {
                            actions.apply(target, LightCommand.Colour(state.colour.h, state.saturation))
                        }
                    },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                    enabled = usable,
                ) { Text(stringResource(R.string.tab_colour)) }
            }

            if (state?.mode == LightMode.SCENE || state?.mode == LightMode.MUSIC) {
                Text(
                    stringResource(R.string.effect_running),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            val range = light.brightnessRange
            LevelSlider(
                label = stringResource(R.string.brightness),
                value = range?.last ?: 100,
                range = 1..100,
                // A group whose lamps differ shows its span until the slider is touched.
                valueText = { shown -> if (range != null && range.first != range.last && shown == range.last) brightnessText(range) else "$shown%" },
                onPreview = { actions.preview(target, LightCommand.Brightness(it)) },
                onCommit = { actions.apply(target, LightCommand.Brightness(it)) },
                enabled = usable,
            )

            when (tab) {
                Tab.WHITE -> {
                    val warm = stringResource(R.string.preset_warm)
                    val neutral = stringResource(R.string.preset_neutral)
                    val cool = stringResource(R.string.preset_cool)
                    LevelSlider(
                        label = stringResource(R.string.white_tone),
                        value = state?.tempPercent ?: 50,
                        range = 0..100,
                        valueText = { if (it < 34) warm else if (it < 67) neutral else cool },
                        onPreview = { actions.preview(target, LightCommand.White(tempPercent = it)) },
                        onCommit = { actions.apply(target, LightCommand.White(tempPercent = it)) },
                        enabled = usable,
                        track = Brush.horizontalGradient(listOf(whiteColor(0), whiteColor(50), whiteColor(100))),
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for ((name, tone) in listOf(warm to 0, neutral to 50, cool to 100)) {
                            AssistChip(
                                onClick = { actions.apply(target, LightCommand.White(tempPercent = tone)) },
                                label = { Text(name) },
                                leadingIcon = { ColorDot(whiteColor(tone), Modifier.size(16.dp)) },
                                enabled = usable,
                            )
                        }
                    }
                }
                Tab.COLOUR -> {
                    ColorWheel(
                        hue = state?.colour?.h ?: 0,
                        saturation = state?.saturation ?: 100,
                        onPreview = { hue, saturation -> actions.preview(target, LightCommand.Colour(hue, saturation)) },
                        onCommit = { hue, saturation -> actions.apply(target, LightCommand.Colour(hue, saturation)) },
                        modifier = Modifier.widthIn(max = 300.dp).fillMaxWidth(),
                        enabled = usable,
                        showThumb = state?.mode == LightMode.COLOUR,
                    )
                    Swatches(
                        swatches = swatches,
                        current = state?.takeIf { it.mode == LightMode.COLOUR }?.let { Swatch(it.colour.h, it.saturation) },
                        enabled = usable,
                        onPick = { actions.apply(target, LightCommand.Colour(it.hue, it.saturation)) },
                        onAdd = onAddSwatch,
                        onRemove = onRemoveSwatch,
                    )
                }
            }

            light.group?.let { group ->
                Members(group.members, onOpen = { onOpenMember(Target.Light(it.id)) }, onToggle = { actions.toggle(Target.Light(it.id)) })
            }
        }
    }
}

@Composable
private fun ReachBanner(light: Controlled) {
    val text = when {
        light.problem == LightProblem.KEY_UNREADABLE -> stringResource(R.string.banner_reimport)
        light.problem == LightProblem.NO_ADDRESS -> stringResource(R.string.banner_no_address)
        light.reach == Reach.CONNECTING -> stringResource(R.string.banner_connecting)
        light.group != null -> stringResource(R.string.banner_group_offline)
        else -> stringResource(R.string.banner_offline)
    }
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (light.reach == Reach.OFFLINE) Icon(AppIcons.Warning, null, tint = MaterialTheme.colorScheme.error)
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun PowerRow(light: Controlled, enabled: Boolean, onToggle: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val tint = light.state?.takeIf { light.on }?.displayColor()
    Surface(
        onClick = onToggle,
        enabled = enabled,
        shape = RoundedCornerShape(28.dp),
        color = if (tint != null) tint.copy(alpha = 0.22f).compositeOver(scheme.surfaceContainerHigh) else scheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().height(80.dp).testTag("powerRow"),
    ) {
        Row(Modifier.padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(48.dp).clip(CircleShape).background(if (light.on) scheme.primary else scheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                Icon(AppIcons.Power, null, tint = if (light.on) scheme.onPrimary else scheme.onSurfaceVariant)
            }
            Spacer(Modifier.size(16.dp))
            Column {
                val title = when {
                    light.reach == Reach.OFFLINE -> R.string.status_offline
                    light.reach == Reach.CONNECTING -> R.string.status_connecting
                    light.on -> R.string.power_on
                    else -> R.string.power_off
                }
                Text(stringResource(title), style = MaterialTheme.typography.titleLarge)
                if (enabled) {
                    Text(
                        stringResource(if (light.on) R.string.tap_to_turn_off else R.string.tap_to_turn_on),
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Saved colours: tap to use one, hold to remove it, "+" keeps the lamp's current colour. */
@Composable
private fun Swatches(
    swatches: List<Swatch>,
    current: Swatch?,
    enabled: Boolean,
    onPick: (Swatch) -> Unit,
    onAdd: (Swatch) -> Unit,
    onRemove: (Swatch) -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            stringResource(R.string.swatches),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            for (swatch in swatches) {
                var menu by remember { mutableStateOf(false) }
                Box {
                    Box(
                        Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(hueColor(swatch.hue, swatch.saturation))
                            .then(
                                if (swatch == current) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier,
                            )
                            .combinedClickable(enabled = enabled, onClick = { onPick(swatch) }, onLongClick = { menu = true }),
                    )
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.remove)) },
                            onClick = {
                                menu = false
                                onRemove(swatch)
                            },
                        )
                    }
                }
            }
            if (current != null && current !in swatches) {
                FilledIconButton(
                    onClick = { onAdd(current) },
                    modifier = Modifier.size(44.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
                ) {
                    Icon(AppIcons.Add, stringResource(R.string.add_swatch), tint = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

@Composable
private fun Members(members: List<LightItem>, onOpen: (LightItem) -> Unit, onToggle: (LightItem) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.members_title),
            style = MaterialTheme.typography.labelLarge,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Surface(shape = RoundedCornerShape(20.dp), color = scheme.surfaceContainer) {
            Column {
                for (lamp in members) {
                    val lit = lamp.online && lamp.state?.on == true
                    Row(
                        Modifier.fillMaxWidth().clickable { onOpen(lamp) }.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ColorDot(if (lit) lamp.state!!.displayColor() else scheme.surfaceContainerHighest, Modifier.size(20.dp))
                        Spacer(Modifier.size(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(lamp.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(lamp.statusText(), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { onToggle(lamp) }, enabled = lamp.reach == Reach.ONLINE) {
                            Icon(
                                AppIcons.Power,
                                stringResource(if (lit) R.string.turn_off else R.string.turn_on),
                                tint = if (lit) scheme.primary else scheme.onSurfaceVariant.copy(alpha = if (lamp.reach == Reach.ONLINE) 1f else 0.4f),
                            )
                        }
                    }
                }
            }
        }
    }
}
