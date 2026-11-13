package bardisucks.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LightProfileTest {
    private val profile = LightProfile()

    // The first BARDI bulb's status from the phase 0 report.
    private val reported = mapOf<String, Any?>(
        "20" to true, "21" to "colour", "22" to 1000, "23" to 1000, "24" to "001703e803e8",
        "25" to "000e0d0000000000000000c80000", "26" to 0, "34" to false,
    )
    private val colourLamp = profile.read(reported)!!
    private val whiteLamp = colourLamp.copy(mode = LightMode.WHITE, whiteLevel = 400)

    @Test
    fun `reads the bulb's reported status`() {
        assertEquals(LightState(on = true, mode = LightMode.COLOUR, whiteLevel = 1000, temp = 1000, colour = Hsv(23, 1000, 1000)), colourLamp)
        assertEquals(100, colourLamp.brightness)
        assertEquals(40, whiteLamp.brightness)
        assertNull(profile.read(mapOf("1" to true)), "a socket is not a light")
    }

    @Test
    fun `colour always travels with power and mode, so a lamp in white mode obeys`() {
        val dps = profile.dpsFor(LightCommand.Colour(hue = 200, saturation = 80), whiteLamp)
        // The lamp was at 40 % in white mode, so the colour keeps 40 % (v = 400 = 0x0190).
        assertEquals(mapOf<String, Any?>("20" to true, "21" to "colour", "24" to "00c803200190"), dps)
    }

    @Test
    fun `brightness goes to the DP that is live in the lamp's mode`() {
        assertEquals(mapOf<String, Any?>("20" to true, "24" to "001703e801f4"), profile.dpsFor(LightCommand.Brightness(50), colourLamp))
        assertEquals(mapOf<String, Any?>("20" to true, "21" to "white", "22" to 500), profile.dpsFor(LightCommand.Brightness(50), whiteLamp))
    }

    @Test
    fun `white keeps what it isn't told to change`() {
        assertEquals(mapOf<String, Any?>("20" to true, "21" to "white"), profile.dpsFor(LightCommand.White(), colourLamp))
        assertEquals(
            mapOf<String, Any?>("20" to true, "21" to "white", "22" to 1000, "23" to 300),
            profile.dpsFor(LightCommand.White(percent = 100, tempPercent = 30), colourLamp),
        )
    }

    @Test
    fun `a pending countdown is cancelled only when the power really changes`() {
        val counting = colourLamp.copy(countdown = 600)
        assertEquals(mapOf<String, Any?>("20" to false, "26" to 0), profile.dpsFor(LightCommand.Power(false), counting))
        // Still on afterwards: "off in 10 minutes" must survive a brightness change.
        assertEquals(mapOf<String, Any?>("20" to true, "24" to "001703e801f4"), profile.dpsFor(LightCommand.Brightness(50), counting))
        assertEquals(mapOf<String, Any?>("20" to false), profile.dpsFor(LightCommand.Power(false), colourLamp))
    }

    @Test
    fun `a scene look combines power, mode, levels and timer`() {
        assertEquals(
            mapOf<String, Any?>("20" to true, "21" to "white", "22" to 50, "23" to 0, "26" to 1800),
            profile.dpsFor(LightCommand.Look(percent = 5, tempPercent = 0, offAfterSeconds = 1800), colourLamp),
        )
        assertEquals(
            mapOf<String, Any?>("20" to true, "21" to "colour", "24" to "010903520190"),
            profile.dpsFor(LightCommand.Look(hue = 265, saturation = 85), whiteLamp),
        )
        assertEquals(mapOf<String, Any?>("20" to false), profile.dpsFor(LightCommand.Look(on = false, percent = 50, offAfterSeconds = 60), colourLamp))
        assertEquals(mapOf<String, Any?>("20" to true), profile.dpsFor(LightCommand.Look(), colourLamp))
    }

    @Test
    fun `restore brings back every value, but sends only power when the lamp was off`() {
        assertEquals(
            mapOf<String, Any?>("20" to true, "21" to "colour", "22" to 1000, "23" to 1000, "24" to "001703e803e8"),
            profile.dpsFor(LightCommand.Restore(colourLamp), whiteLamp),
        )
        assertEquals(mapOf<String, Any?>("20" to false), profile.dpsFor(LightCommand.Restore(colourLamp.copy(on = false)), whiteLamp))
    }

    @Test
    fun `live preview only when the lamp already shows colour`() {
        assertEquals(mapOf<String, Any?>("28" to "0007803e803e800000000"), profile.previewFor(LightCommand.Colour(120, 100), colourLamp))
        assertEquals(mapOf<String, Any?>("28" to "0001703e801f400000000"), profile.previewFor(LightCommand.Brightness(50), colourLamp))
        assertNull(profile.previewFor(LightCommand.Colour(120, 100), whiteLamp), "needs the mode switch first")
        assertNull(profile.previewFor(LightCommand.Colour(120, 100), colourLamp.copy(on = false)))
        assertNull(profile.previewFor(LightCommand.White(percent = 50), colourLamp))
    }
}
