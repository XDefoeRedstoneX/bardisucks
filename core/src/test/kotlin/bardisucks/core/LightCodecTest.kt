package bardisucks.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LightCodecTest {
    @Test
    fun `colour matches the values read from the BARDI bulbs`() {
        assertEquals(Hsv(23, 1000, 1000), LightCodec.decodeColour("001703e803e8"))
        assertEquals(Hsv(29, 1000, 1000), LightCodec.decodeColour("001d03e803e8"))
        assertEquals("007803e803e8", LightCodec.encodeColour(Hsv(120, 1000, 1000)))
    }

    @Test
    fun `live preview string is 21 chars like the sweep that worked in phase 0`() {
        assertEquals("0007803e803e800000000", LightCodec.encodeLive(Hsv(120, 1000, 1000)))
        assertEquals(21, LightCodec.encodeLive(Hsv(360, 1000, 1000), gradient = true).length)
    }

    @Test
    fun `BARDI night preset round-trips`() {
        val night = "000e0d0000000000000000c80000"
        val scene = LightCodec.decodeScene(night)
        assertEquals(0, scene.number)
        val unit = scene.units.single()
        assertEquals(ChangeMode.STATIC, unit.mode)
        assertEquals(14, unit.switchInterval)
        assertEquals(13, unit.changeTime)
        assertEquals(200, unit.bright)
        assertEquals(night, LightCodec.encodeScene(scene))
    }

    @Test
    fun `multi-unit effect round-trips`() {
        val soft = "04464602007803e803e800000000464602007803e8000a00000000"
        assertEquals(soft, LightCodec.encodeScene(LightCodec.decodeScene(soft)))
        assertEquals(2, LightCodec.decodeScene(soft).units.size)
    }

    @Test
    fun `percent mapping covers the bulb range`() {
        assertEquals(10, LightCodec.percentToLevel(1))
        assertEquals(1000, LightCodec.percentToLevel(100))
        assertEquals(100, LightCodec.levelToPercent(1000))
        assertEquals(1, LightCodec.levelToPercent(10))
        assertEquals(500, LightCodec.percentToTemp(50))
    }

    @Test
    fun `rejects bad input`() {
        assertFailsWith<IllegalArgumentException> { Hsv(400, 0, 0) }
        assertFailsWith<IllegalArgumentException> { LightCodec.decodeColour("abc") }
        assertFailsWith<IllegalArgumentException> { LightCodec.encodeScene(Scene(1, emptyList())) }
    }
}
