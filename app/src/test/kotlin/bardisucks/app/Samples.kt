package bardisucks.app

import bardisucks.app.ui.HomeEdits
import bardisucks.app.ui.LightActions
import bardisucks.core.Hsv
import bardisucks.core.LightMode
import bardisucks.core.LightState
import bardisucks.core.home.GroupItem
import bardisucks.core.home.HomeConfig
import bardisucks.core.home.HomeController
import bardisucks.core.home.HomeState
import bardisucks.core.home.LightItem
import bardisucks.core.home.SceneAction
import bardisucks.core.home.SceneConfig
import bardisucks.core.home.SceneItem
import bardisucks.core.home.SceneLook
import bardisucks.core.home.SceneStyle
import bardisucks.core.home.Swatch
import bardisucks.core.home.Action
import bardisucks.core.home.Target
import bardisucks.core.home.encode

/** A made-up home for screenshots and UI tests. */
object Samples {
    val kamar = LightItem(
        "kamar", "Lampu Kamar", "192.168.100.133", online = true,
        state = LightState(on = true, mode = LightMode.COLOUR, whiteLevel = 1000, temp = 200, colour = Hsv(275, 800, 700)),
    )
    val meja = LightItem(
        "meja", "Lampu Meja", "192.168.100.134", online = true,
        state = LightState(on = true, mode = LightMode.WHITE, whiteLevel = 400, temp = 150, colour = Hsv(30, 1000, 1000)),
    )
    val teras = LightItem(
        "teras", "Teras", "192.168.100.135", online = false, unreachable = true,
        state = LightState(on = true, mode = LightMode.WHITE, whiteLevel = 1000, temp = 900, colour = Hsv(0, 1000, 1000)),
    )
    val tidur = LightItem(
        "tidur", "Lampu Tidur", "192.168.100.136", online = true,
        state = LightState(on = false, mode = LightMode.COLOUR, whiteLevel = 300, temp = 0, colour = Hsv(20, 1000, 300)),
    )

    private val lights = listOf(kamar, meja, tidur, teras)
    val all = GroupItem(HomeController.ALL_LAMPS, "", lights, relativeBrightness = false, builtIn = true)
    val desk = GroupItem("desk", "Desk", listOf(kamar, meja), relativeBrightness = true)

    private val allRef = Target.Group(HomeController.ALL_LAMPS).encode()
    val movie = SceneConfig(
        "movie", "Movie", SceneStyle.TOGGLE,
        listOf(SceneLook(actions = listOf(SceneAction(allRef, brightness = 15, colour = Swatch(265, 85))))),
    )
    val night = SceneConfig(
        "night", "Night light", SceneStyle.NORMAL,
        listOf(
            SceneLook(
                actions = listOf(
                    SceneAction(Target.Light(tidur.id).encode(), brightness = 5, white = 0, offAfterMinutes = 30),
                    SceneAction(Target.Light(kamar.id).encode(), on = false),
                ),
            ),
        ),
    )
    val cycle = SceneConfig(
        "cycle", "Cycle", SceneStyle.CYCLE,
        listOf(
            SceneLook("Bright", listOf(SceneAction(allRef, brightness = 100, white = 70))),
            SceneLook("Warm", listOf(SceneAction(allRef, brightness = 50, white = 15))),
            SceneLook("Party", listOf(SceneAction(Target.Light(kamar.id).encode(), colour = Swatch(320, 100)), SceneAction(Target.Light(meja.id).encode(), colour = Swatch(190, 100)))),
            SceneLook("Off", listOf(SceneAction(allRef, on = false))),
        ),
    )
    val allOff = SceneConfig("off", "All off", looks = listOf(SceneLook(actions = listOf(SceneAction(allRef, on = false)))))

    val home = HomeState(
        lights, listOf(all, desk), HomeConfig.DEFAULT_SWATCHES,
        scenes = listOf(SceneItem(movie, toggledOn = true), SceneItem(night), SceneItem(cycle, nextLook = 1), SceneItem(allOff)),
        tiles = mapOf(1 to Action.RunScene("cycle"), 2 to Action.Switch(Target.Group(HomeController.ALL_LAMPS))),
    )
    val noScenes = home.copy(scenes = emptyList(), tiles = emptyMap())
    val twoLamps = HomeState(
        listOf(kamar, meja),
        listOf(GroupItem(HomeController.ALL_LAMPS, "", listOf(kamar, meja), relativeBrightness = false, builtIn = true)),
        HomeConfig.DEFAULT_SWATCHES,
    )

    val noActions = LightActions(toggle = {}, apply = { _, _ -> }, preview = { _, _ -> })
    val noEdits = HomeEdits({ _, _, _, _ -> "" }, {}, { _, _ -> }, { _, _ -> true }, {}, {}, {})
}
