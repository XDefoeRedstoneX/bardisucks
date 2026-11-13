@file:OptIn(ExperimentalMaterial3Api::class)

package bardisucks.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.RadioButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import bardisucks.app.R
import bardisucks.core.home.Action
import bardisucks.core.home.GroupItem
import bardisucks.core.home.HomeState
import bardisucks.core.home.Target
import bardisucks.core.home.ImportSummary
import bardisucks.core.home.LightItem
import bardisucks.core.home.SkipReason

/** New group ([group] null) or an existing one. The built-in group of all lamps only offers the brightness option. */
@Composable
fun GroupEditorScreen(
    group: GroupItem?,
    lights: List<LightItem>,
    onSave: (name: String, members: List<String>, relativeBrightness: Boolean) -> Unit,
    onDelete: (() -> Unit)?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val builtIn = group?.builtIn == true
    var name by remember(group?.id) { mutableStateOf(group?.name.orEmpty()) }
    var members by remember(group?.id) { mutableStateOf(group?.members?.map { it.id }?.toSet() ?: emptySet()) }
    var relative by remember(group?.id) { mutableStateOf(group?.relativeBrightness ?: false) }
    val scheme = MaterialTheme.colorScheme

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when {
                            group == null -> stringResource(R.string.group_new_title)
                            builtIn -> stringResource(R.string.all_lamps)
                            else -> stringResource(R.string.group_edit_title)
                        },
                    )
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(AppIcons.Back, stringResource(R.string.back)) } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            if (!builtIn) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(40) },
                    label = { Text(stringResource(R.string.group_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("groupName"),
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.group_lamps), style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
                    Surface(shape = RoundedCornerShape(20.dp), color = scheme.surfaceContainer) {
                        Column {
                            for (lamp in lights) {
                                val checked = lamp.id in members
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable { members = if (checked) members - lamp.id else members + lamp.id }
                                        .padding(start = 8.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Checkbox(checked = checked, onCheckedChange = null, modifier = Modifier.padding(12.dp))
                                    Text(lamp.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
            Surface(shape = RoundedCornerShape(20.dp), color = scheme.surfaceContainer) {
                Row(
                    Modifier.fillMaxWidth().clickable { relative = !relative }.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.keep_ratio_title), style = MaterialTheme.typography.bodyLarge)
                        Text(stringResource(R.string.keep_ratio_body), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.size(12.dp))
                    Switch(checked = relative, onCheckedChange = null)
                }
            }
            Button(
                onClick = { onSave(name, lights.map { it.id }.filter { it in members }, relative) },
                enabled = builtIn || (name.isNotBlank() && members.isNotEmpty()),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.save)) }
            if (onDelete != null) {
                TextButton(
                    onClick = onDelete,
                    colors = ButtonDefaults.textButtonColors(contentColor = scheme.error),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.delete_group)) }
            }
        }
    }
}

@Composable
fun SettingsScreen(
    state: HomeState,
    version: String,
    onImport: () -> Unit,
    onEditLight: (LightItem) -> Unit,
    onBindTile: (slot: Int, action: Action?) -> Unit,
    /** Asks the system to add the tile to Quick Settings; null where Android can't (before Android 13). */
    onAddTile: ((slot: Int) -> Unit)?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val lights = state.lights
    val scheme = MaterialTheme.colorScheme
    var pickingSlot by remember { mutableStateOf<Int?>(null) }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(AppIcons.Back, stringResource(R.string.back)) } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.section_lamps), style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
            if (lights.isNotEmpty()) {
                Surface(shape = RoundedCornerShape(20.dp), color = scheme.surfaceContainer) {
                    Column {
                        for (lamp in lights) {
                            Row(
                                Modifier.fillMaxWidth().clickable { onEditLight(lamp) }.padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(lamp.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        listOf(lamp.ip.ifBlank { stringResource(R.string.status_no_address) }, lamp.statusText()).distinct().joinToString(" · "),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = scheme.onSurfaceVariant,
                                    )
                                }
                                Icon(AppIcons.ChevronRight, null, tint = scheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            Text(stringResource(R.string.settings_import_body), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            FilledTonalButton(onClick = onImport) {
                Icon(AppIcons.Upload, null, Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.import_devices))
            }
            Spacer(Modifier.size(12.dp))
            Text(stringResource(R.string.tiles_title), style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
            Text(stringResource(R.string.tiles_body), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            Surface(shape = RoundedCornerShape(20.dp), color = scheme.surfaceContainer) {
                Column {
                    for (slot in 1..TILE_SLOTS) {
                        val action = state.tiles[slot]
                        Row(
                            Modifier.fillMaxWidth().clickable { pickingSlot = slot }.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp).testTag("tileSlot:$slot"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.tile_n, slot), style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    action?.let { state.actionLabel(it) } ?: stringResource(R.string.tile_unused),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = scheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (action != null && onAddTile != null) {
                                TextButton(onClick = { onAddTile(slot) }) { Text(stringResource(R.string.tile_add)) }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.size(12.dp))
            Text(stringResource(R.string.about_title), style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
            Text(stringResource(R.string.about_body, version), style = MaterialTheme.typography.bodyMedium)
        }
    }

    pickingSlot?.let { slot ->
        ActionPicker(
            state = state,
            title = stringResource(R.string.tile_n, slot),
            current = state.tiles[slot],
            onPick = { action ->
                pickingSlot = null
                onBindTile(slot, action)
            },
            onDismiss = { pickingSlot = null },
        )
    }
}

const val TILE_SLOTS = 6

/** "Scene: Movie", "Switch: All lamps"... */
@Composable
fun HomeState.actionLabel(action: Action): String {
    val name = actionName(action) ?: return stringResource(R.string.missing_target)
    return when (action) {
        is Action.RunScene -> stringResource(R.string.action_scene, name)
        is Action.Switch -> stringResource(R.string.action_switch, name)
    }
}

/** Pick what a tile does: nothing, a scene, or switching a group or lamp. */
@Composable
fun ActionPicker(state: HomeState, title: String, current: Action?, onPick: (Action?) -> Unit, onDismiss: () -> Unit) {
    val options = buildList<Action?> {
        add(null)
        state.scenes.forEach { add(Action.RunScene(it.id)) }
        state.groups.forEach { add(Action.Switch(Target.Group(it.id))) }
        state.lights.forEach { add(Action.Switch(Target.Light(it.id))) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                for (option in options) {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onPick(option) }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = option == current, onClick = null, modifier = Modifier.padding(8.dp))
                        Text(option?.let { state.actionLabel(it) } ?: stringResource(R.string.tile_unused), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Rename a lamp, fix its address, or remove it. */
@Composable
fun LightEditDialog(
    lamp: LightItem,
    onSave: (name: String, ip: String) -> Boolean,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember(lamp.id) { mutableStateOf(lamp.name) }
    var ip by remember(lamp.id) { mutableStateOf(lamp.ip) }
    var badAddress by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(stringResource(R.string.remove_lamp)) },
            text = { Text(stringResource(R.string.remove_lamp_confirm, lamp.name)) },
            confirmButton = {
                TextButton(onClick = onRemove, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Text(stringResource(R.string.remove))
                }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text(stringResource(R.string.cancel)) } },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_lamp_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(40) },
                    label = { Text(stringResource(R.string.lamp_name)) },
                    singleLine = true,
                    modifier = Modifier.testTag("lampName"),
                )
                OutlinedTextField(
                    value = ip,
                    onValueChange = {
                        ip = it.filter { c -> c.isDigit() || c == '.' }.take(15)
                        badAddress = false
                    },
                    label = { Text(stringResource(R.string.lamp_address)) },
                    singleLine = true,
                    isError = badAddress,
                    supportingText = { if (badAddress) Text(stringResource(R.string.address_invalid)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                TextButton(
                    onClick = { confirmRemove = true },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Icon(AppIcons.Delete, null, Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.remove_lamp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (onSave(name, ip)) onDismiss() else badAddress = true }, enabled = name.isNotBlank()) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

sealed interface ImportOutcome {
    data class Done(val summary: ImportSummary) : ImportOutcome
    data class Failed(val reason: String) : ImportOutcome
}

@Composable
fun ImportResultDialog(outcome: ImportOutcome, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (outcome is ImportOutcome.Done) R.string.import_done_title else R.string.import_failed_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (outcome) {
                    is ImportOutcome.Failed -> Text(outcome.reason)
                    is ImportOutcome.Done -> {
                        val summary = outcome.summary
                        if (summary.added.isNotEmpty()) Text(stringResource(R.string.import_added, summary.added.joinToString()))
                        if (summary.updated.isNotEmpty()) Text(stringResource(R.string.import_updated, summary.updated.joinToString()))
                        if (summary.added.isEmpty() && summary.updated.isEmpty()) Text(stringResource(R.string.import_nothing))
                        for (skipped in summary.skipped) {
                            Text(
                                stringResource(
                                    when (skipped.reason) {
                                        SkipReason.NOT_A_LIGHT -> R.string.import_skipped_not_light
                                        SkipReason.UNSUPPORTED_PROTOCOL -> R.string.import_skipped_protocol
                                        SkipReason.NO_KEY -> R.string.import_skipped_no_key
                                    },
                                    skipped.name,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(stringResource(R.string.import_delete_hint), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) } },
    )
}
