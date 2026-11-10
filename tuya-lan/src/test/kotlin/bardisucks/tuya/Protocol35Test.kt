package bardisucks.tuya

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class Protocol35Test {
    @Test
    fun `control payload is compact JSON behind the version header, like TinyTuya`() {
        val dps = buildJsonObject {
            put("20", true)
            put("21", "colour")
            put("24", "007803e803e8")
        }
        val payload = Protocol35.payload(TuyaCommand.CONTROL_NEW, Protocol35.control(dps, 1_700_000_000))
        assertContentEquals(Vectors.frames.single { it.name == "client_control" }.payload, payload)
    }

    @Test
    fun `query and heartbeat have no version header`() {
        val query = Protocol35.payload(TuyaCommand.DP_QUERY_NEW, buildJsonObject { })
        assertEquals("{}", query.decodeToString())
        val hb = Protocol35.payload(TuyaCommand.HEART_BEAT, Protocol35.heartbeat("abc"))
        assertContentEquals(Vectors.frames.single { it.name == "client_heartbeat" }.payload, hb)
    }

    @Test
    fun `reads dps from pushes and replies`() {
        val push = Vectors.frames.single { it.name == "device_status_push" }.payload
        assertEquals(JsonObject(mapOf("20" to JsonPrimitive(false), "26" to JsonPrimitive(0))), Protocol35.dps(Protocol35.decode(push)))
        val reply = Vectors.frames.single { it.name == "device_query_reply" }.payload
        assertEquals(JsonPrimitive(500), Protocol35.dps(Protocol35.decode(reply))!!["22"])
        assertNull(Protocol35.decode(ByteArray(0)))
    }
}
