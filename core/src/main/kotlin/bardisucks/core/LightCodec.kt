package bardisucks.core

/** Hue 0–360, saturation and value 0–1000 (Tuya's scale). */
data class Hsv(val h: Int, val s: Int, val v: Int) {
    init {
        require(h in 0..360 && s in 0..1000 && v in 0..1000) { "HSV out of range: $this" }
    }
}

enum class ChangeMode(val code: Int) { STATIC(0), JUMP(1), GRADIENT(2) }

/** One step of an on-bulb effect (scene_data, DP 25). */
data class SceneUnit(
    val mode: ChangeMode,
    /** 0–100: time between steps. */
    val switchInterval: Int,
    /** 0–100: how long the change itself takes. */
    val changeTime: Int,
    val hsv: Hsv,
    /** White brightness 0–1000 (used when hsv is all zero). */
    val bright: Int = 0,
    /** White temperature 0–1000. */
    val temp: Int = 0,
)

data class Scene(val number: Int, val units: List<SceneUnit>)

/**
 * Encoders for the hex-string DPs of Tuya v2 lights, as used by the BARDI 12 W bulbs
 * (formats in docs/research/tuya-platform.md §2.3, verified against the bulbs in phase 0).
 */
object LightCodec {
    /** colour_data (DP 24): `HHHHSSSSVVVV`. */
    fun encodeColour(hsv: Hsv): String = hex4(hsv.h) + hex4(hsv.s) + hex4(hsv.v)

    fun decodeColour(value: String): Hsv {
        require(value.length == 12) { "colour_data must be 12 hex chars, got '$value'" }
        return Hsv(value.substring(0, 4).toInt(16), value.substring(4, 8).toInt(16), value.substring(8, 12).toInt(16))
    }

    /** control_data (DP 28): live preview while dragging, not saved by the bulb. `M HHHH SSSS VVVV BBBB KKKK`. */
    fun encodeLive(hsv: Hsv, gradient: Boolean = false, bright: Int = 0, temp: Int = 0): String =
        (if (gradient) "1" else "0") + encodeColour(hsv) + hex4(bright) + hex4(temp)

    /** scene_data (DP 25): `SS` + up to 8 units of `II TT MM HHHH SSSS VVVV BBBB KKKK`. */
    fun encodeScene(scene: Scene): String {
        require(scene.units.size in 1..8) { "A scene needs 1–8 units" }
        return hex2(scene.number) + scene.units.joinToString("") { u ->
            hex2(u.switchInterval) + hex2(u.changeTime) + hex2(u.mode.code) + encodeColour(u.hsv) + hex4(u.bright) + hex4(u.temp)
        }
    }

    fun decodeScene(value: String): Scene {
        require(value.length >= 28 && (value.length - 2) % 26 == 0) { "Not a scene_data string: '$value'" }
        val units = value.substring(2).chunked(26).map { u ->
            SceneUnit(
                mode = ChangeMode.entries.first { it.code == u.substring(4, 6).toInt(16) },
                switchInterval = u.substring(0, 2).toInt(16),
                changeTime = u.substring(2, 4).toInt(16),
                hsv = decodeColour(u.substring(6, 18)),
                bright = u.substring(18, 22).toInt(16),
                temp = u.substring(22, 26).toInt(16),
            )
        }
        return Scene(value.substring(0, 2).toInt(16), units)
    }

    /** UI percent (1–100) → bright_value_v2 / V (10–1000). */
    fun percentToLevel(percent: Int): Int = 10 + (percent.coerceIn(1, 100) - 1) * 990 / 99

    fun levelToPercent(level: Int): Int = 1 + (level.coerceIn(10, 1000) - 10) * 99 / 990

    /** UI warmth 0–100 (0 = warmest) → temp_value_v2 0–1000. */
    fun percentToTemp(percent: Int): Int = percent.coerceIn(0, 100) * 10

    private fun hex2(n: Int) = n.toString(16).padStart(2, '0').also { require(it.length == 2) { "$n doesn't fit in 2 hex digits" } }

    private fun hex4(n: Int) = n.toString(16).padStart(4, '0').also { require(it.length == 4) { "$n doesn't fit in 4 hex digits" } }
}
