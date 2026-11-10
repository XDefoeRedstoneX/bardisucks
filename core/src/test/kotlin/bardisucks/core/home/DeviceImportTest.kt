package bardisucks.core.home

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class DeviceImportTest {
    @Test
    fun `reads the file written by get_keys and probe`() {
        val file = DeviceImport.parse(
            """
            {"devices": [
              {"id": "a3f6aaaaaaaaaaaaaa55", "name": "BARDI Light Bulb 12W RGBWW", "home": "Rumah", "local_key": "0123456789abcdef",
               "category": "dj", "product_id": "omurhjizrsia38ec", "product_name": "BARDI Light Bulb 12W RGBWW",
               "online": true, "status": {"switch_led": true}, "dp_map": {"20": "switch_led"},
               "lan_ip": "192.168.100.133", "version": "3.5"},
              {"id": "strip000000000000001", "name": "Power strip", "local_key": "fedcba9876543210", "category": "pc", "lan_ip": "192.168.100.140", "version": "3.4"},
              {"id": "old00000000000000033", "name": "Old bulb", "local_key": "fedcba9876543210", "category": "dj", "version": "3.3"},
              {"id": "ble00000000000000001", "name": "Bluetooth thing", "local_key": ""}
            ]}
            """,
        )
        val lamp = file.devices.single()
        assertEquals("BARDI Light Bulb 12W RGBWW", lamp.name)
        assertEquals("0123456789abcdef", lamp.localKey)
        assertEquals("192.168.100.133", lamp.ip)
        assertEquals(
            listOf(
                SkippedDevice("Power strip", SkipReason.NOT_A_LIGHT),
                SkippedDevice("Old bulb", SkipReason.UNSUPPORTED_PROTOCOL),
                SkippedDevice("Bluetooth thing", SkipReason.NO_KEY),
            ),
            file.skipped,
        )
    }

    @Test
    fun `reads TinyTuya's devices list but ignores its public IP`() {
        val file = DeviceImport.parse(
            """[{"name": "Lamp", "id": "bf00000000000000abcd", "key": "0123456789abcdef", "ip": "36.71.12.34", "version": 3.5},
                {"name": "Lamp 2", "id": "bf00000000000000abce", "key": "0123456789abcdef", "ip": "10.0.0.7"}]""",
        )
        assertNull(file.devices[0].ip)
        assertEquals("10.0.0.7", file.devices[1].ip)
    }

    @Test
    fun `explains what is wrong with other files`() {
        assertFailsWith<ImportException> { DeviceImport.parse("hello") }
        assertFailsWith<ImportException> { DeviceImport.parse("""{"something": "else"}""") }
        assertFailsWith<ImportException> { DeviceImport.parse("[]") }
    }
}
