package bardisucks.core

enum class LightMode(val tuya: String) { WHITE("white"), COLOUR("colour"), SCENE("scene"), MUSIC("music") }

/** What a lamp last reported, in Tuya's own scales. */
data class LightState(
    val on: Boolean,
    val mode: LightMode,
    /** bright_value_v2, 10–1000: the brightness while in white mode. */
    val whiteLevel: Int,
    /** temp_value_v2, 0–1000: 0 is the warmest white. */
    val temp: Int,
    /** colour_data_v2. In colour mode its V is the brightness. */
    val colour: Hsv,
    /** Seconds until the on-device countdown flips the power; 0 = none pending. */
    val countdown: Int = 0,
) {
    /** 1–100, read from whichever DP is live in the current mode. */
    val brightness: Int get() = LightCodec.levelToPercent(if (mode == LightMode.COLOUR) colour.v else whiteLevel)

    /** 0–100: 0 is the warmest white, 100 the coolest. */
    val tempPercent: Int get() = temp / 10

    /** 0–100. */
    val saturation: Int get() = colour.s / 10
}

/** Something the user asks of a lamp, independent of how the lamp encodes it. */
sealed interface LightCommand {
    data class Power(val on: Boolean) : LightCommand

    /** Brightness 1–100, keeping the lamp in its current white/colour mode. */
    data class Brightness(val percent: Int) : LightCommand

    /** Switch to white. Null fields keep the lamp's current value. */
    data class White(val percent: Int? = null, val tempPercent: Int? = null) : LightCommand

    /** Switch to colour: hue 0–360, saturation 0–100. A null [percent] keeps the current brightness. */
    data class Colour(val hue: Int, val saturation: Int, val percent: Int? = null) : LightCommand
}

/**
 * Maps [LightState] and [LightCommand]s to the DPs of a Tuya "v2" RGBCW light.
 * The defaults are the BARDI 12 W bulb's layout (docs/devices/bardi-bulb-12w-rgbww.md).
 */
class LightProfile(
    val power: String = "20",
    val mode: String = "21",
    val bright: String = "22",
    val temp: String = "23",
    val colour: String = "24",
    val countdown: String = "26",
    val live: String = "28",
) {
    /** Null if [dps] doesn't look like this kind of light (no power DP). */
    fun read(dps: Map<String, Any?>): LightState? {
        val on = dps[power] as? Boolean ?: return null
        return LightState(
            on = on,
            mode = LightMode.entries.firstOrNull { it.tuya == dps[mode] } ?: LightMode.WHITE,
            whiteLevel = (dps[bright] as? Number)?.toInt()?.coerceIn(10, 1000) ?: 1000,
            temp = (dps[temp] as? Number)?.toInt()?.coerceIn(0, 1000) ?: 1000,
            colour = (dps[colour] as? String)?.let { runCatching { LightCodec.decodeColour(it) }.getOrNull() }
                ?: Hsv(0, 1000, 1000),
            countdown = (dps[countdown] as? Number)?.toInt() ?: 0,
        )
    }

    /**
     * The DPs to send for [command], all in one frame. Mode and value always travel together, because a lamp
     * in white mode ignores a colour that arrives without the mode switch (and the other way round).
     */
    fun dpsFor(command: LightCommand, current: LightState?): Map<String, Any?> {
        val dps = LinkedHashMap<String, Any?>()
        when (command) {
            is LightCommand.Power -> dps[power] = command.on
            is LightCommand.Brightness -> {
                dps[power] = true
                val level = LightCodec.percentToLevel(command.percent)
                if (current?.mode == LightMode.COLOUR) {
                    dps[colour] = LightCodec.encodeColour(current.colour.copy(v = level))
                } else {
                    dps[mode] = LightMode.WHITE.tuya
                    dps[bright] = level
                }
            }
            is LightCommand.White -> {
                dps[power] = true
                dps[mode] = LightMode.WHITE.tuya
                command.percent?.let { dps[bright] = LightCodec.percentToLevel(it) }
                command.tempPercent?.let { dps[temp] = LightCodec.percentToTemp(it) }
            }
            is LightCommand.Colour -> {
                dps[power] = true
                dps[mode] = LightMode.COLOUR.tuya
                dps[colour] = LightCodec.encodeColour(colourFor(command, current))
            }
        }
        // The bulb's countdown flips the power when it ends, so a pending one must not outlive a power change we make.
        if (current != null && current.countdown > 0 && dps[power] != current.on) dps[countdown] = 0
        return dps
    }

    /**
     * Live-preview variant of [command] (DP 28: shown at once, not saved by the bulb), for streaming while a
     * slider or the colour wheel is dragged. Null when the lamp first needs a real command (it is off or not in
     * colour mode), or when the command has no live form.
     */
    fun previewFor(command: LightCommand, current: LightState?): Map<String, Any?>? {
        if (current == null || !current.on || current.mode != LightMode.COLOUR) return null
        val hsv = when (command) {
            is LightCommand.Colour -> colourFor(command, current)
            is LightCommand.Brightness -> current.colour.copy(v = LightCodec.percentToLevel(command.percent))
            else -> return null
        }
        return mapOf(live to LightCodec.encodeLive(hsv))
    }

    private fun colourFor(command: LightCommand.Colour, current: LightState?): Hsv {
        val value = command.percent?.let(LightCodec::percentToLevel)
            ?: current?.let { if (it.mode == LightMode.COLOUR) it.colour.v else LightCodec.percentToLevel(it.brightness) }
            ?: 1000
        return Hsv(command.hue.coerceIn(0, 360), (command.saturation * 10).coerceIn(0, 1000), value)
    }
}
