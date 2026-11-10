package bardisucks.tuya

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TuyaDeviceTest {
    private val fake = FakeLamp()

    @AfterTest
    fun stop() = fake.close()

    private fun lamp(key: String = FakeLamp.KEY) =
        TuyaDevice(FakeLamp.ID, "127.0.0.1", key, port = fake.port, timeoutMs = 2_000, keepAliveMs = null)

    @Test
    fun `handshake then status`() = runBlocking {
        lamp().use { lamp ->
            lamp.connect()
            assertEquals(TuyaDevice.State.CONNECTED, lamp.state.value)
            val dps = lamp.status()
            assertEquals(JsonPrimitive(true), dps["20"])
            assertEquals(JsonPrimitive("white"), dps["21"])
        }
    }

    @Test
    fun `set changes the device and the push arrives on updates`() = runBlocking {
        lamp().use { lamp ->
            lamp.connect()
            val pushed = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(2_000) { lamp.updates.first() } }
            lamp.set(mapOf("20" to true, "21" to "colour", "24" to "007803e803e8"))
            val push = pushed.await()
            assertEquals(JsonPrimitive("colour"), push["21"])
            assertEquals(JsonPrimitive("007803e803e8"), lamp.status()["24"])
            lamp.heartbeat()
        }
    }

    @Test
    fun `two connections at once, like the real bulbs`() = runBlocking {
        lamp().use { a ->
            lamp().use { b ->
                a.connect()
                b.connect()
                val seen = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(2_000) { b.updates.first() } }
                a.set(mapOf("20" to false))
                val seenByB = seen.await()
                assertEquals(JsonPrimitive(false), seenByB["20"])
                assertEquals(JsonPrimitive(false), b.status()["20"])
            }
        }
    }

    @Test
    fun `a lamp that stops answering heartbeats is reported as disconnected`(): Unit = runBlocking {
        TuyaDevice(FakeLamp.ID, "127.0.0.1", FakeLamp.KEY, port = fake.port, timeoutMs = 200, keepAliveMs = 100).use { lamp ->
            lamp.connect()
            fake.silent = true // socket stays open, like a bulb switched off at the wall
            withTimeout(3_000) { lamp.state.first { it == TuyaDevice.State.DISCONNECTED } }
        }
    }

    @Test
    fun `whatever breaks on the wire surfaces as a TuyaException`(): Unit = runBlocking {
        // Any other exception type (a raw IOException, say) would fail this test, and crash an app.
        repeat(25) {
            lamp().use { lamp ->
                lamp.connect()
                fake.dropConnections()
                try {
                    lamp.set(mapOf("20" to true))
                    lamp.setNoWait(mapOf("28" to "0000003e803e800000000"))
                    lamp.set(mapOf("20" to false))
                } catch (e: TuyaException) {
                    // expected: the lamp hung up
                }
            }
        }
    }

    @Test
    fun `wrong local key fails the handshake`() = runBlocking {
        lamp(key = "ffffffffffffffff").use { lamp ->
            assertFailsWith<TuyaException> { lamp.connect() }
            assertEquals(TuyaDevice.State.DISCONNECTED, lamp.state.value)
        }
    }
}
