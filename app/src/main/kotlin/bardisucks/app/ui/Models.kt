package bardisucks.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import bardisucks.app.R
import bardisucks.core.LightCommand
import bardisucks.core.LightMode
import bardisucks.core.LightState
import bardisucks.core.home.GroupItem
import bardisucks.core.home.HomeState
import bardisucks.core.home.LightItem
import bardisucks.core.home.LightProblem
import bardisucks.core.home.Target

enum class Reach { ONLINE, CONNECTING, OFFLINE }

val LightItem.reach: Reach
    get() = when {
        online -> Reach.ONLINE
        unreachable || problem != null -> Reach.OFFLINE
        else -> Reach.CONNECTING
    }

val GroupItem.reach: Reach
    get() = when {
        online -> Reach.ONLINE
        members.isEmpty() || members.all { it.reach == Reach.OFFLINE } -> Reach.OFFLINE
        else -> Reach.CONNECTING
    }

/** What the control screen shows and steers: one lamp, or a group seen as one lamp. */
data class Controlled(
    val target: Target,
    val name: String,
    val reach: Reach,
    /** For a group: the values of its leading lamp. */
    val state: LightState?,
    val on: Boolean,
    /** Differs from a single value when a group's lamps are at different levels. */
    val brightnessRange: IntRange?,
    val problem: LightProblem? = null,
    val group: GroupItem? = null,
)

@Composable
fun GroupItem.displayName(): String = if (builtIn) stringResource(R.string.all_lamps) else name

@Composable
fun HomeState.controlled(target: Target): Controlled? = when (target) {
    is Target.Light -> lights.firstOrNull { it.id == target.id }?.let { lamp ->
        Controlled(
            target, lamp.name, lamp.reach, lamp.state,
            on = lamp.online && lamp.state?.on == true,
            brightnessRange = lamp.state?.brightness?.let { it..it },
            problem = lamp.problem,
        )
    }
    is Target.Group -> groups.firstOrNull { it.id == target.id }?.let { group ->
        Controlled(target, group.displayName(), group.reach, group.lead, group.anyOn, group.brightnessRange, group = group)
    }
}

/** One line under a lamp's name: "60% · Warm white", "Off", "Offline"... */
@Composable
fun LightItem.statusText(): String = when {
    problem == LightProblem.KEY_UNREADABLE -> stringResource(R.string.status_reimport)
    problem == LightProblem.NO_ADDRESS -> stringResource(R.string.status_no_address)
    reach == Reach.OFFLINE -> stringResource(R.string.status_offline)
    reach == Reach.CONNECTING -> stringResource(R.string.status_connecting)
    else -> state.statusText()
}

@Composable
fun LightState?.statusText(): String = when {
    this == null -> stringResource(R.string.status_connecting)
    !on -> stringResource(R.string.status_off)
    else -> stringResource(R.string.status_on_detail, brightness, modeText())
}

@Composable
private fun LightState.modeText(): String = when (mode) {
    LightMode.COLOUR -> stringResource(R.string.mode_colour)
    LightMode.SCENE -> stringResource(R.string.mode_effect)
    LightMode.MUSIC -> stringResource(R.string.mode_music)
    LightMode.WHITE -> when {
        tempPercent < 34 -> stringResource(R.string.white_warm)
        tempPercent < 67 -> stringResource(R.string.white_neutral)
        else -> stringResource(R.string.white_cool)
    }
}

@Composable
fun GroupItem.statusText(): String {
    val lit = reachable.count { it.state!!.on }
    return when {
        members.isEmpty() -> stringResource(R.string.group_empty)
        reach == Reach.OFFLINE -> stringResource(R.string.status_offline)
        reach == Reach.CONNECTING -> stringResource(R.string.status_connecting)
        lit == 0 -> pluralStringResource(R.plurals.lamps_off, members.size, members.size)
        lit == members.size -> stringResource(R.string.group_all_on, brightnessText(brightnessRange))
        else -> stringResource(R.string.group_some_on, lit, members.size)
    }
}

fun brightnessText(range: IntRange?): String = when {
    range == null -> ""
    range.first == range.last -> "${range.first}%"
    else -> "${range.first}–${range.last}%"
}

/** What the screens can ask for. Implemented by [bardisucks.core.home.HomeController]. */
class LightActions(
    val toggle: (Target) -> Unit,
    val apply: (Target, LightCommand) -> Unit,
    val preview: (Target, LightCommand) -> Unit,
)
