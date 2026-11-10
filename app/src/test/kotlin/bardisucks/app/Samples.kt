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

    val home = HomeState(lights, listOf(all, desk), HomeConfig.DEFAULT_SWATCHES)
    val twoLamps = HomeState(
        listOf(kamar, meja),
        listOf(GroupItem(HomeController.ALL_LAMPS, "", listOf(kamar, meja), relativeBrightness = false, builtIn = true)),
        HomeConfig.DEFAULT_SWATCHES,
    )

    val noActions = LightActions(toggle = {}, apply = { _, _ -> }, preview = { _, _ -> })
    val noEdits = HomeEdits({ _, _, _, _ -> "" }, {}, { _, _ -> }, { _, _ -> true }, {}, {}, {})
}
