package bardisucks.core.home

import bardisucks.core.LightCommand
import bardisucks.core.LightMode
import bardisucks.core.LightState
import kotlinx.serialization.Serializable

@Serializable
enum class SceneStyle {
    /** A tap applies the scene. */
    NORMAL,

    /** A tap applies it; the next tap puts the lamps back as they were before. */
    TOGGLE,

    /** Each tap applies the next look, round and round. */
    CYCLE,
}

/**
 * What one lamp or group should look like. Fields left null are left as the lamp has them.
 * [white] and [colour] are alternatives: a step sets one or the other (or neither, keeping the mode).
 */
@Serializable
data class SceneAction(
    /** "light:<id>" or "group:<id>" (see [Target.encode]). */
    val target: String,
    /** False switches off; everything else then doesn't matter. */
    val on: Boolean = true,
    /** 1–100. */
    val brightness: Int? = null,
    /** White tone 0–100 (0 warmest): switches to white. */
    val white: Int? = null,
    /** Switches to this colour. */
    val colour: Swatch? = null,
    /** Starts the lamp's own countdown, so it goes off later even if this phone is gone. */
    val offAfterMinutes: Int? = null,
) {
    fun toCommand() = LightCommand.Look(
        on = on,
        percent = brightness,
        tempPercent = white.takeIf { colour == null },
        hue = colour?.hue,
        saturation = colour?.saturation,
        offAfterSeconds = offAfterMinutes?.times(60),
    )
}

@Serializable
data class SceneLook(val name: String = "", val actions: List<SceneAction> = emptyList())

@Serializable
data class SceneConfig(
    val id: String,
    val name: String,
    val style: SceneStyle = SceneStyle.NORMAL,
    /** One look, or for [SceneStyle.CYCLE] the looks in order. */
    val looks: List<SceneLook>,
)

/** A scene as the UI shows it: its settings plus where its toggle or cycle stands. */
data class SceneItem(
    val config: SceneConfig,
    /** A toggle scene that was applied and whose next tap puts things back. */
    val toggledOn: Boolean = false,
    /** For a cycle: index of the look the next tap applies. */
    val nextLook: Int = 0,
) {
    val id: String get() = config.id
    val name: String get() = config.name

    /** The look a tap applies next (for a toggle that is on: the look that is showing). */
    val look: SceneLook? get() = config.looks.getOrNull(if (config.style == SceneStyle.CYCLE) nextLook else 0)
}

/** Something a single tap can trigger, e.g. from a Quick Settings tile or a home-screen shortcut. */
sealed interface Action {
    data class RunScene(val sceneId: String) : Action
    data class Switch(val target: Target) : Action

    fun encode(): String = when (this) {
        is RunScene -> "scene:$sceneId"
        is Switch -> target.encode()
    }

    companion object {
        fun decode(text: String): Action? {
            if (text.startsWith("scene:")) return text.removePrefix("scene:").takeIf { it.isNotEmpty() }?.let(::RunScene)
            return Target.decode(text)?.let(::Switch)
        }
    }
}

fun Target.encode(): String = when (this) {
    is Target.Light -> "light:$id"
    is Target.Group -> "group:$id"
}

fun Target.Companion.decode(text: String): Target? {
    val id = text.substringAfter(':', "").takeIf { it.isNotEmpty() } ?: return null
    return when (text.substringBefore(':')) {
        "light" -> Target.Light(id)
        "group" -> Target.Group(id)
        else -> null
    }
}

/** A step that makes the lamp look like [state] does now. */
fun captureAction(lampId: String, state: LightState): SceneAction = when {
    !state.on -> SceneAction(Target.Light(lampId).encode(), on = false)
    state.mode == LightMode.COLOUR -> SceneAction(
        Target.Light(lampId).encode(),
        brightness = state.brightness,
        colour = Swatch(state.colour.h, state.saturation),
    )
    else -> SceneAction(Target.Light(lampId).encode(), brightness = state.brightness, white = state.tempPercent)
}

/** Names for the starter scenes, so the app can translate them. */
data class StarterNames(
    val bright: String = "Bright",
    val evening: String = "Evening",
    val night: String = "Night light",
    val movie: String = "Movie",
    val allOff: String = "All off",
    val cycle: String = "Cycle",
    val cycleLooks: List<String> = listOf("Bright", "Warm", "Night", "Off"),
)

/** A small set of useful scenes for [target] (normally the group of all lamps), to edit or delete. */
fun starterScenes(target: String, names: StarterNames, newId: () -> String): List<SceneConfig> {
    fun look(name: String = "", action: SceneAction) = SceneLook(name, listOf(action))
    val bright = SceneAction(target, brightness = 100, white = 70)
    val warm = SceneAction(target, brightness = 50, white = 15)
    val night = SceneAction(target, brightness = 5, white = 0)
    val off = SceneAction(target, on = false)
    return listOf(
        SceneConfig(newId(), names.bright, looks = listOf(look(action = bright))),
        SceneConfig(newId(), names.evening, looks = listOf(look(action = warm))),
        SceneConfig(newId(), names.night, looks = listOf(look(action = night.copy(offAfterMinutes = 30)))),
        SceneConfig(newId(), names.movie, SceneStyle.TOGGLE, listOf(look(action = SceneAction(target, brightness = 15, colour = Swatch(265, 85))))),
        SceneConfig(newId(), names.allOff, looks = listOf(look(action = off))),
        SceneConfig(
            newId(), names.cycle, SceneStyle.CYCLE,
            listOf(bright, warm, night, off).mapIndexed { i, action -> look(names.cycleLooks.getOrElse(i) { "" }, action) },
        ),
    )
}
