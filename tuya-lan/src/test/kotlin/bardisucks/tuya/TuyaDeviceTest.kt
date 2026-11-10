package bardisucks.tuya

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
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
    fun `wrong local key fails the handshake`() = runBlocking {
        lamp(key = "ffffffffffffffff").use { lamp ->
            assertFailsWith<TuyaException> { lamp.connect() }
            assertEquals(TuyaDevice.State.DISCONNECTED, lamp.state.value)
        }
    }
}

/** Minimal device side of protocol 3.5 for tests (the Python tools/dev/fake_lamp.py is the TinyTuya-checked one). */
private class FakeLamp : AutoCloseable {
    companion object {
        const val ID = "fake0lamp0000000035a"
        const val KEY = "0123456789abcdef"
    }

    private val server = ServerSocket(0)
    val port: Int = server.localPort
    private val dps = ConcurrentHashMap<String, JsonElement>(mapOf("20" to JsonPrimitive(true), "21" to JsonPrimitive("white")))
    private val sessions = CopyOnWriteArrayList<Session>()
    private var seq = 0

    init {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (e: IOException) { break }
                Session(socket).also { sessions += it; thread(isDaemon = true) { it.run() } }
            }
        }
    }

    override fun close() {
        server.close()
        sessions.forEach { it.socket.close() }
    }

    private inner class Session(val socket: Socket) {
        private var key = KEY.toByteArray()
        private var ready = false

        fun send(cmd: Int, payload: ByteArray) = synchronized(this) {
            socket.getOutputStream().write(Frame6699.encode(Frame(++seq, cmd, payload, retcode = 0), key))
        }

        fun run() {
            val input = socket.getInputStream()
            var localNonce = ByteArray(0)
            var remoteNonce = ByteArray(0)
            try {
                while (true) {
                    val header = input.readNBytes(Frame6699.HEADER_SIZE)
                    if (header.size < Frame6699.HEADER_SIZE) return
                    val rest = input.readNBytes(Frame6699.totalLength(header) - Frame6699.HEADER_SIZE)
                    val frame = Frame6699.decode(header + rest, key, hasRetcode = false)
                    when (frame.cmd) {
                        TuyaCommand.SESS_KEY_NEG_START -> {
                            localNonce = frame.payload.copyOf(16)
                            remoteNonce = Crypto.randomBytes(16)
                            send(TuyaCommand.SESS_KEY_NEG_RESP, remoteNonce + Protocol35.deviceProof(key, localNonce))
                        }
                        TuyaCommand.SESS_KEY_NEG_FINISH -> {
                            key = Protocol35.sessionKey(KEY.toByteArray(), localNonce, remoteNonce)
                            ready = true
                        }
                        TuyaCommand.DP_QUERY_NEW -> send(frame.cmd, statusJson(dps).toByteArray())
                        TuyaCommand.CONTROL_NEW -> {
                            val body = Json.parseToJsonElement(frame.payload.decodeToString().substring(15)).jsonObject
                            val changed = Protocol35.dps(body)!!
                            dps.putAll(changed)
                            send(frame.cmd, ByteArray(0))
                            sessions.filter { it.ready }.forEach {
                                runCatching { it.send(TuyaCommand.STATUS, Protocol35.VERSION_HEADER + statusJson(changed).toByteArray()) }
                            }
                        }
                        else -> send(frame.cmd, ByteArray(0))
                    }
                }
            } catch (e: Exception) {
                socket.close() // wrong key etc.: drop the connection like a real device
            }
        }
    }

    private fun statusJson(values: Map<String, JsonElement>) = buildJsonObject {
        put("protocol", 4)
        put("data", buildJsonObject { put("dps", JsonObject(values)) })
    }.toString()
}
