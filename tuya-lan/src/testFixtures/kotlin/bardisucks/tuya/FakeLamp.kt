package bardisucks.tuya

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

/**
 * Device side of protocol 3.5 for tests: a BARDI-like RGBCW bulb on a random localhost port.
 * Like the real bulbs it accepts several connections and pushes every change to all of them.
 * (tools/dev/fake_lamp.py is the Python twin that is checked against TinyTuya.)
 */
class FakeLamp(
    val id: String = ID,
    val key: String = KEY,
    initial: Map<String, Any> = DEFAULT_DPS,
) : AutoCloseable {
    companion object {
        const val ID = "fake0lamp0000000035a"
        const val KEY = "0123456789abcdef"
        val DEFAULT_DPS: Map<String, Any> = mapOf(
            "20" to true, "21" to "white", "22" to 1000, "23" to 1000, "24" to "000003e803e8",
            "25" to "000e0d0000000000000000c80000", "26" to 0, "34" to false,
        )

        /** DPs the bulb acts on but never stores or reports (music data, live preview). */
        private val TRANSIENT = setOf("27", "28")
    }

    private val server = ServerSocket(0)
    val port: Int = server.localPort

    private val state = ConcurrentHashMap<String, JsonElement>(initial.mapValues { (_, v) -> primitive(v) })
    private val sessions = CopyOnWriteArrayList<Session>()
    private var seq = 0

    /** Every control frame received, in order: the DPs it carried. */
    val received = CopyOnWriteArrayList<Map<String, JsonElement>>()

    /** When true the lamp keeps connections open but stops answering, like a bulb switched off at the wall. */
    @Volatile var silent = false

    val dps: Map<String, JsonElement> get() = HashMap(state)

    fun dp(id: String): Any? = state[id]?.let { p ->
        (p as JsonPrimitive).run { if (isString) content else content.toBooleanStrictOrNull() ?: content.toInt() }
    }

    init {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (e: IOException) { break }
                Session(socket).also { sessions += it; thread(isDaemon = true) { it.run() } }
            }
        }
    }

    /** Changes DPs as if someone used the wall switch or another app, and pushes the change. */
    fun change(changes: Map<String, Any>) {
        val json = changes.mapValues { (_, v) -> primitive(v) }
        state.putAll(json)
        push(json)
    }

    /** Closes every open connection (the server keeps accepting new ones). */
    fun dropConnections() = sessions.forEach { it.socket.close() }

    override fun close() {
        server.close()
        dropConnections()
    }

    private fun push(changed: Map<String, JsonElement>) {
        if (changed.isEmpty()) return
        sessions.filter { it.ready }.forEach {
            runCatching { it.send(TuyaCommand.STATUS, Protocol35.VERSION_HEADER + statusJson(changed).toByteArray()) }
        }
    }

    private inner class Session(val socket: Socket) {
        private var sessionKey = key.toByteArray()
        @Volatile var ready = false

        fun send(cmd: Int, payload: ByteArray) = synchronized(this@FakeLamp) {
            socket.getOutputStream().write(Frame6699.encode(Frame(++seq, cmd, payload, retcode = 0), sessionKey))
        }

        fun run() {
            val input = socket.getInputStream()
            var localNonce = ByteArray(0)
            var remoteNonce = ByteArray(0)
            try {
                while (true) {
                    val header = input.readExactly(Frame6699.HEADER_SIZE)
                    if (header.size < Frame6699.HEADER_SIZE) return
                    val rest = input.readExactly(Frame6699.totalLength(header) - Frame6699.HEADER_SIZE)
                    val frame = Frame6699.decode(header + rest, sessionKey, hasRetcode = false)
                    if (silent) continue
                    when (frame.cmd) {
                        TuyaCommand.SESS_KEY_NEG_START -> {
                            localNonce = frame.payload.copyOf(16)
                            remoteNonce = Crypto.randomBytes(16)
                            send(TuyaCommand.SESS_KEY_NEG_RESP, remoteNonce + Protocol35.deviceProof(sessionKey, localNonce))
                        }
                        TuyaCommand.SESS_KEY_NEG_FINISH -> {
                            sessionKey = Protocol35.sessionKey(key.toByteArray(), localNonce, remoteNonce)
                            ready = true
                        }
                        TuyaCommand.DP_QUERY_NEW -> send(frame.cmd, statusJson(state).toByteArray())
                        TuyaCommand.CONTROL_NEW -> {
                            val body = Json.parseToJsonElement(frame.payload.decodeToString().substring(15)).jsonObject
                            val requested = Protocol35.dps(body)!!
                            received += requested
                            val stored = requested.filterKeys { it !in TRANSIENT }
                            state.putAll(stored)
                            send(frame.cmd, ByteArray(0))
                            push(stored)
                        }
                        else -> send(frame.cmd, ByteArray(0))
                    }
                }
            } catch (e: Exception) {
                // wrong key, closed socket...: drop the connection like a real device
            } finally {
                socket.close()
                sessions -= this
            }
        }
    }

    private fun statusJson(values: Map<String, JsonElement>) = buildJsonObject {
        put("protocol", 4)
        put("data", buildJsonObject { put("dps", JsonObject(values)) })
    }.toString()

    private fun primitive(v: Any): JsonElement = when (v) {
        is Boolean -> JsonPrimitive(v)
        is Number -> JsonPrimitive(v)
        else -> JsonPrimitive(v.toString())
    }
}
