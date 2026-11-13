@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package bardisucks.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import bardisucks.app.R
import bardisucks.core.home.HomeController
import bardisucks.core.home.HomeState
import bardisucks.core.home.SceneAction
import bardisucks.core.home.SceneConfig
import bardisucks.core.home.SceneLook
import bardisucks.core.home.SceneStyle
import bardisucks.core.home.Swatch
import bardisucks.core.home.Target
import bardisucks.core.home.encode

private const val MAX_LOOKS = 6
private val OFF_AFTER_MINUTES = listOf(15, 30, 60, 120)

/** Everything the scene editor can ask for. Implemented by [HomeController]. */
class SceneEdits(
    val save: (SceneConfig) -> String,
    val delete: (String) -> Unit,
    /** The lamps' current look, one step per reachable lamp. */
    val capture: () -> SceneLook,
    /** Shows a look on the lamps now. */
    val tryLook: (SceneLook) -> Unit,
    /** "Add to home screen"; null where the launcher can't pin shortcuts. */
    val pin: ((SceneConfig) -> Unit)?,
)

/** A new scene starts with one step for every lamp (or the only lamp), switched on. */
fun newSceneDraft(state: HomeState): SceneConfig {
    val target = when {
        state.groups.any { it.id == HomeController.ALL_LAMPS } -> Target.Group(HomeController.ALL_LAMPS)
        state.lights.isNotEmpty() -> Target.Light(state.lights.first().id)
        else -> null
    }
    return SceneConfig("", "", SceneStyle.NORMAL, listOf(SceneLook(actions = listOfNotNull(target?.let { SceneAction(it.encode()) }))))
}

@Composable
fun SceneEditorScreen(
    initial: SceneConfig,
    state: HomeState,
    edits: SceneEdits,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var draft by remember(initial.id) { mutableStateOf(initial) }
    var confirmDelete by remember { mutableStateOf(false) }
    val isNew = initial.id.isBlank()
    val scheme = MaterialTheme.colorScheme

    fun setLook(index: Int, look: SceneLook) {
        draft = draft.copy(looks = draft.looks.mapIndexed { i, l -> if (i == index) look else l })
    }

    val valid = draft.name.isNotBlank() && draft.looks.isNotEmpty() && draft.looks.all { it.actions.isNotEmpty() }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (isNew) R.string.scene_new_title else R.string.scene_edit_title)) },
                navigationIcon = { IconButton(onClick = onDone) { Icon(AppIcons.Back, stringResource(R.string.back)) } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            OutlinedTextField(
                value = draft.name,
                onValueChange = { draft = draft.copy(name = it.take(30)) },
                label = { Text(stringResource(R.string.scene_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("sceneName"),
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    val styles = listOf(SceneStyle.NORMAL to R.string.style_normal, SceneStyle.TOGGLE to R.string.style_toggle, SceneStyle.CYCLE to R.string.style_cycle)
                    styles.forEachIndexed { i, (style, label) ->
                        SegmentedButton(
                            selected = draft.style == style,
                            onClick = { draft = draft.copy(style = style) },
                            shape = SegmentedButtonDefaults.itemShape(i, styles.size),
                        ) { Text(stringResource(label)) }
                    }
                }
                Text(
                    stringResource(
                        when (draft.style) {
                            SceneStyle.NORMAL -> R.string.style_normal_body
                            SceneStyle.TOGGLE -> R.string.style_toggle_body
                            SceneStyle.CYCLE -> R.string.style_cycle_body
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }

            val looks = if (draft.style == SceneStyle.CYCLE) draft.looks else draft.looks.take(1)
            looks.forEachIndexed { index, look ->
                LookEditor(
                    look = look,
                    index = index,
                    cycle = draft.style == SceneStyle.CYCLE,
                    canRemove = draft.style == SceneStyle.CYCLE && draft.looks.size > 1,
                    state = state,
                    onChange = { setLook(index, it) },
                    onRemove = { draft = draft.copy(looks = draft.looks.filterIndexed { i, _ -> i != index }) },
                    onCapture = { setLook(index, edits.capture().copy(name = look.name)) },
                    onTry = { edits.tryLook(look) },
                )
            }
            if (draft.style == SceneStyle.CYCLE && draft.looks.size < MAX_LOOKS) {
                OutlinedButton(
                    onClick = { draft = draft.copy(looks = draft.looks + draft.looks.last().copy(name = "")) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(AppIcons.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.add_look))
                }
            }
            if (draft.style != SceneStyle.CYCLE && draft.looks.size > 1) {
                Text(stringResource(R.string.only_first_look_kept), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }

            Button(
                onClick = {
                    val saved = if (draft.style == SceneStyle.CYCLE) draft else draft.copy(looks = draft.looks.take(1))
                    edits.save(saved.copy(name = saved.name.trim()))
                    onDone()
                },
                enabled = valid,
                modifier = Modifier.fillMaxWidth().testTag("saveScene"),
            ) { Text(stringResource(R.string.save)) }
            if (!isNew && edits.pin != null) {
                OutlinedButton(onClick = { edits.pin.invoke(draft) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.add_to_home_screen))
                }
            }
            if (!isNew) {
                TextButton(
                    onClick = { confirmDelete = true },
                    colors = ButtonDefaults.textButtonColors(contentColor = scheme.error),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.delete_scene)) }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete_scene)) },
            text = { Text(stringResource(R.string.delete_scene_confirm, initial.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        edits.delete(initial.id)
                        onDone()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun LookEditor(
    look: SceneLook,
    index: Int,
    cycle: Boolean,
    canRemove: Boolean,
    state: HomeState,
    onChange: (SceneLook) -> Unit,
    onRemove: () -> Unit,
    onCapture: () -> Unit,
    onTry: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(24.dp), color = scheme.surfaceContainerLow) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (cycle) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = look.name,
                        onValueChange = { onChange(look.copy(name = it.take(20))) },
                        label = { Text(stringResource(R.string.look_n, index + 1)) },
                        placeholder = { Text(stringResource(R.string.look_name_hint)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    if (canRemove) {
                        IconButton(onClick = onRemove) { Icon(AppIcons.Delete, stringResource(R.string.remove_look)) }
                    }
                }
            }
            look.actions.forEachIndexed { i, action ->
                StepEditor(
                    action = action,
                    state = state,
                    onChange = { changed -> onChange(look.copy(actions = look.actions.mapIndexed { j, a -> if (j == i) changed else a })) },
                    onRemove = { onChange(look.copy(actions = look.actions.filterIndexed { j, _ -> j != i })) },
                )
            }
            if (look.actions.isEmpty()) {
                Text(stringResource(R.string.look_empty), style = MaterialTheme.typography.bodySmall, color = scheme.error)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                AddStepButton(state, onAdd = { ref -> onChange(look.copy(actions = look.actions + SceneAction(ref))) })
                TextButton(onClick = onCapture) { Text(stringResource(R.string.use_current_lamps)) }
                TextButton(onClick = onTry, enabled = look.actions.isNotEmpty()) { Text(stringResource(R.string.try_it)) }
            }
        }
    }
}

@Composable
private fun AddStepButton(state: HomeState, onAdd: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) {
            Icon(AppIcons.Add, null, Modifier.size(18.dp))
            Spacer(Modifier.size(6.dp))
            Text(stringResource(R.string.add_step))
        }
        TargetMenu(state, open, onDismiss = { open = false }, onPick = {
            open = false
            onAdd(it)
        })
    }
}

/** Groups first, then lamps. */
@Composable
private fun TargetMenu(state: HomeState, expanded: Boolean, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        for (group in state.groups) {
            DropdownMenuItem(
                text = { Text(group.displayName()) },
                leadingIcon = { Icon(AppIcons.Group, null, Modifier.size(20.dp)) },
                onClick = { onPick(Target.Group(group.id).encode()) },
            )
        }
        for (lamp in state.lights) {
            DropdownMenuItem(
                text = { Text(lamp.name) },
                leadingIcon = { Icon(AppIcons.Bulb, null, Modifier.size(20.dp)) },
                onClick = { onPick(Target.Light(lamp.id).encode()) },
            )
        }
    }
}

private enum class LightChoice { KEEP, WHITE, COLOUR }

/** One step: which lamp or group, on or off, and what to set. */
@Composable
private fun StepEditor(action: SceneAction, state: HomeState, onChange: (SceneAction) -> Unit, onRemove: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    var pickTarget by remember { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(18.dp), color = scheme.surfaceContainer) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    Row(
                        Modifier.clip(RoundedCornerShape(12.dp)).clickable { pickTarget = true }.padding(horizontal = 6.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(if (action.target.startsWith("group:")) AppIcons.Group else AppIcons.Bulb, null, Modifier.size(20.dp))
                        Spacer(Modifier.size(10.dp))
                        Text(
                            state.targetName(action.target) ?: stringResource(R.string.missing_target),
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Icon(AppIcons.ChevronRight, null, Modifier.size(18.dp), tint = scheme.onSurfaceVariant)
                    }
                    TargetMenu(state, pickTarget, onDismiss = { pickTarget = false }, onPick = {
                        pickTarget = false
                        onChange(action.copy(target = it))
                    })
                }
                SingleChoiceSegmentedButtonRow(Modifier.widthIn(max = 150.dp)) {
                    SegmentedButton(
                        selected = action.on,
                        onClick = { onChange(action.copy(on = true)) },
                        shape = SegmentedButtonDefaults.itemShape(0, 2),
                        icon = {},
                    ) { Text(stringResource(R.string.power_on)) }
                    SegmentedButton(
                        selected = !action.on,
                        onClick = { onChange(action.copy(on = false)) },
                        shape = SegmentedButtonDefaults.itemShape(1, 2),
                        icon = {},
                    ) { Text(stringResource(R.string.power_off)) }
                }
                IconButton(onClick = onRemove) { Icon(AppIcons.Delete, stringResource(R.string.remove_step), tint = scheme.onSurfaceVariant) }
            }
            if (!action.on) return@Column

            HorizontalDivider(color = scheme.outlineVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.set_brightness), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Switch(
                    checked = action.brightness != null,
                    onCheckedChange = { onChange(action.copy(brightness = if (it) 100 else null)) },
                )
            }
            action.brightness?.let { level ->
                LevelSlider(
                    label = stringResource(R.string.brightness),
                    value = level,
                    range = 1..100,
                    valueText = { "$it%" },
                    onPreview = {},
                    onCommit = { onChange(action.copy(brightness = it)) },
                )
            }

            val choice = when {
                action.colour != null -> LightChoice.COLOUR
                action.white != null -> LightChoice.WHITE
                else -> LightChoice.KEEP
            }
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                val options = listOf(LightChoice.KEEP to R.string.light_keep, LightChoice.WHITE to R.string.tab_white, LightChoice.COLOUR to R.string.tab_colour)
                options.forEachIndexed { i, (option, label) ->
                    SegmentedButton(
                        selected = choice == option,
                        onClick = {
                            onChange(
                                when (option) {
                                    LightChoice.KEEP -> action.copy(white = null, colour = null)
                                    LightChoice.WHITE -> action.copy(white = action.white ?: 30, colour = null)
                                    LightChoice.COLOUR -> action.copy(white = null, colour = action.colour ?: state.swatches.firstOrNull() ?: Swatch(30, 100))
                                },
                            )
                        },
                        shape = SegmentedButtonDefaults.itemShape(i, options.size),
                    ) { Text(stringResource(label), maxLines = 1) }
                }
            }
            when (choice) {
                LightChoice.KEEP -> Unit
                LightChoice.WHITE -> {
                    val warm = stringResource(R.string.preset_warm)
                    val neutral = stringResource(R.string.preset_neutral)
                    val cool = stringResource(R.string.preset_cool)
                    LevelSlider(
                        label = stringResource(R.string.white_tone),
                        value = action.white ?: 30,
                        range = 0..100,
                        valueText = { if (it < 34) warm else if (it < 67) neutral else cool },
                        onPreview = {},
                        onCommit = { onChange(action.copy(white = it)) },
                        track = Brush.horizontalGradient(listOf(whiteColor(0), whiteColor(50), whiteColor(100))),
                    )
                }
                LightChoice.COLOUR -> ColourPicker(action.colour!!, state.swatches, onPick = { onChange(action.copy(colour = it)) })
            }

            Text(stringResource(R.string.off_after), style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = action.offAfterMinutes == null,
                    onClick = { onChange(action.copy(offAfterMinutes = null)) },
                    label = { Text(stringResource(R.string.off_after_never)) },
                )
                for (minutes in OFF_AFTER_MINUTES) {
                    FilterChip(
                        selected = action.offAfterMinutes == minutes,
                        onClick = { onChange(action.copy(offAfterMinutes = minutes)) },
                        label = { Text(durationText(minutes)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun durationText(minutes: Int): String =
    if (minutes % 60 == 0) stringResource(R.string.hours_short, minutes / 60) else stringResource(R.string.minutes_short, minutes)

/** Saved colours to tap, plus the wheel behind a "More" toggle so the editor stays short. */
@Composable
private fun ColourPicker(selected: Swatch, swatches: List<Swatch>, onPick: (Swatch) -> Unit) {
    var wheel by rememberSaveable { mutableStateOf(selected !in swatches) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for (swatch in swatches) {
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(hueColor(swatch.hue, swatch.saturation))
                        .then(if (swatch == selected) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
                        .clickable { onPick(swatch) },
                )
            }
            TextButton(onClick = { wheel = !wheel }) { Text(stringResource(if (wheel) R.string.fewer_colours else R.string.more_colours)) }
        }
        if (wheel) {
            ColorWheel(
                hue = selected.hue,
                saturation = selected.saturation,
                onPreview = { _, _ -> },
                onCommit = { h, s -> onPick(Swatch(h, s)) },
                modifier = Modifier.widthIn(max = 220.dp).fillMaxWidth(),
            )
        }
    }
}
