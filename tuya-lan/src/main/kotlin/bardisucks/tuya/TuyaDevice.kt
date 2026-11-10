package bardisucks.tuya

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference

/**
 * One encrypted LAN connection to a Tuya device speaking protocol 3.5 (the BARDI 12 W bulbs).
 *
 * ```
 * TuyaDevice(id, "192.168.100.133", localKey).use { lamp ->
 *     lamp.connect()
 *     println(lamp.status())          // {"20":true,"21":"colour",...}
 *     lamp.set(mapOf("20" to false))
 * }
 * ```
 * Status changes the device pushes on its own (switch pressed, countdown fired, another app)
 * arrive on [updates]. Not thread-confined: calls are serialized internally.
 */
class TuyaDevice(
    val id: String,
    val host: String,
    localKey: String,
    val port: Int = 6668,
    private val timeoutMs: Long = 5_000,
    /** Heartbeat interval; two unanswered heartbeats in a row close the connection. null = no automatic heartbeat. */
    private val keepAliveMs: Long? = 12_000,
) : AutoCloseable {

    enum class State { DISCONNECTED, CONNECTING, CONNECTED }

    private val localKey = localKey.toByteArray(Charsets.UTF_8).also {
        require(it.size == 16) { "local key must be 16 characters, got ${it.size}" }
    }

    @Volatile private var key = this.localKey
    @Volatile private var socket: Socket? = null
    private var seq = 0
    private var connectionJob: Job? = null
    private val requestLock = Mutex()
    private val waiting = AtomicReference<Waiter?>(null)

    private val _state = MutableStateFlow(State.DISCONNECTED)
    val state: StateFlow<State> = _state

    private val _updates = MutableSharedFlow<JsonObject>(extraBufferCapacity = 64)
    /** DP changes pushed by the device, e.g. `{"20":false,"26":0}` when a countdown fires. */
    val updates: SharedFlow<JsonObject> = _updates

    private class Waiter(val cmds: Set<Int>, val reply: CompletableDeferred<Frame>)

    private companion object {
        const val MAX_MISSED_HEARTBEATS = 2
    }

    suspend fun connect() {
        close()
        _state.value = State.CONNECTING
        val s = Socket()
        try {
            withContext(Dispatchers.IO) {
                s.connect(InetSocketAddress(host, port), timeoutMs.toInt())
                s.tcpNoDelay = true
                s.soTimeout = timeoutMs.toInt()
                handshake(s)
                s.soTimeout = 0 // from now on the reader blocks until data arrives or the socket closes
            }
        } catch (e: Exception) {
            s.close()
            _state.value = State.DISCONNECTED
            throw if (e is TuyaException) e else TuyaException("Can't connect to $host:$port: ${e.message}", e)
        }
        socket = s
        _state.value = State.CONNECTED
        val job = SupervisorJob()
        connectionJob = job
        val scope = CoroutineScope(Dispatchers.IO + job)
        scope.launch { readLoop(s) }
        keepAliveMs?.let { interval ->
            scope.launch {
                var missed = 0
                while (isActive) {
                    delay(interval)
                    missed = if (runCatching { heartbeat() }.isSuccess) 0 else missed + 1
                    // A lamp switched off at the wall never closes its socket; unanswered heartbeats are the only sign.
                    if (missed >= MAX_MISSED_HEARTBEATS) close()
                }
            }
        }
    }

    /** Current value of every DP, keyed by DP id ("20", "21", ...). */
    suspend fun status(): JsonObject {
        val reply = request(
            TuyaCommand.DP_QUERY_NEW,
            Protocol35.payload(TuyaCommand.DP_QUERY_NEW, JsonObject(emptyMap())),
            expect = setOf(TuyaCommand.DP_QUERY_NEW, TuyaCommand.DP_QUERY),
        )
        return Protocol35.dps(Protocol35.decode(reply.payload)) ?: throw TuyaException("Status reply contained no DPs")
    }

    /** Sets several DPs in one frame, e.g. `mapOf("20" to true, "21" to "colour", "24" to "007803e803e8")`. */
    suspend fun set(dps: Map<String, Any?>) {
        request(
            TuyaCommand.CONTROL_NEW,
            Protocol35.payload(TuyaCommand.CONTROL_NEW, Protocol35.control(toJson(dps), nowSeconds())),
            expect = setOf(TuyaCommand.CONTROL_NEW, TuyaCommand.STATUS),
        )
    }

    /** Sends without waiting for the reply. Meant for high-rate live preview (DP 28) while a slider moves. */
    suspend fun setNoWait(dps: Map<String, Any?>) {
        val s = socket ?: throw TuyaException("Not connected")
        withContext(Dispatchers.IO) {
            send(s, TuyaCommand.CONTROL_NEW, Protocol35.payload(TuyaCommand.CONTROL_NEW, Protocol35.control(toJson(dps), nowSeconds())))
        }
    }

    suspend fun heartbeat() {
        request(
            TuyaCommand.HEART_BEAT,
            Protocol35.payload(TuyaCommand.HEART_BEAT, Protocol35.heartbeat(id)),
            expect = setOf(TuyaCommand.HEART_BEAT),
        )
    }

    override fun close() {
        connectionJob?.cancel()
        connectionJob = null
        socket?.close()
        socket = null
        waiting.getAndSet(null)?.reply?.completeExceptionally(TuyaException("Connection closed"))
        _state.value = State.DISCONNECTED
    }

    // --- internals ---

    private fun handshake(s: Socket) {
        key = localKey
        val localNonce = Crypto.randomBytes(16)
        send(s, TuyaCommand.SESS_KEY_NEG_START, localNonce)
        val reply = try {
            readFrame(s.getInputStream())
        } catch (e: IOException) {
            throw TuyaException("No handshake reply from $host (wrong local key, or not a protocol 3.5 device?)", e)
        }
        if (reply.cmd != TuyaCommand.SESS_KEY_NEG_RESP || reply.payload.size < 48) {
            throw TuyaException("Unexpected handshake reply (cmd 0x${reply.cmd.toString(16)}, ${reply.payload.size} bytes)")
        }
        val remoteNonce = reply.payload.copyOfRange(0, 16)
        if (!reply.payload.copyOfRange(16, 48).contentEquals(Protocol35.deviceProof(localKey, localNonce))) {
            throw TuyaException("Handshake proof mismatch (wrong local key?)")
        }
        send(s, TuyaCommand.SESS_KEY_NEG_FINISH, Protocol35.clientProof(localKey, remoteNonce))
        key = Protocol35.sessionKey(localKey, localNonce, remoteNonce)
    }

    private suspend fun request(cmd: Int, payload: ByteArray, expect: Set<Int>): Frame = requestLock.withLock {
        val s = socket ?: throw TuyaException("Not connected")
        val waiter = Waiter(expect, CompletableDeferred())
        waiting.set(waiter)
        try {
            withContext(Dispatchers.IO) { send(s, cmd, payload) }
            withTimeoutOrNull(timeoutMs) { waiter.reply.await() }
                ?: throw TuyaException("No reply to command 0x${cmd.toString(16)} within $timeoutMs ms")
        } finally {
            waiting.compareAndSet(waiter, null)
        }
    }

    private fun send(s: Socket, cmd: Int, payload: ByteArray) = synchronized(this) {
        seq += 1
        s.getOutputStream().apply {
            write(Frame6699.encode(Frame(seq, cmd, payload), key))
            flush()
        }
    }

    private fun readFrame(input: InputStream): Frame {
        val header = input.readExactly(Frame6699.HEADER_SIZE)
        if (header.size < Frame6699.HEADER_SIZE) throw IOException("Connection closed by device")
        val total = Frame6699.totalLength(header)
        val rest = input.readExactly(total - Frame6699.HEADER_SIZE)
        if (rest.size < total - Frame6699.HEADER_SIZE) throw IOException("Connection closed mid-frame")
        return Frame6699.decode(header + rest, key, hasRetcode = true)
    }

    private fun readLoop(s: Socket) {
        val input = s.getInputStream()
        try {
            while (!s.isClosed) {
                val frame = readFrame(input)
                waiting.get()?.let { w ->
                    if (frame.cmd in w.cmds && waiting.compareAndSet(w, null)) w.reply.complete(frame)
                }
                if (frame.cmd == TuyaCommand.STATUS || frame.cmd == TuyaCommand.CONTROL_NEW) {
                    Protocol35.dps(Protocol35.decode(frame.payload))?.let { _updates.tryEmit(it) }
                }
            }
        } catch (e: Exception) {
            waiting.getAndSet(null)?.reply?.completeExceptionally(TuyaException("Connection lost: ${e.message}", e))
        } finally {
            if (socket === s) {
                socket = null
                _state.value = State.DISCONNECTED
            }
            s.close()
        }
    }

    private fun nowSeconds() = System.currentTimeMillis() / 1000

    private fun toJson(dps: Map<String, Any?>) = JsonObject(dps.mapValues { (dp, v) -> jsonValue(dp, v) })

    private fun jsonValue(dp: String, v: Any?): JsonElement = when (v) {
        null -> JsonNull
        is JsonElement -> v
        is Boolean -> JsonPrimitive(v)
        is Number -> JsonPrimitive(v)
        is String -> JsonPrimitive(v)
        else -> throw IllegalArgumentException("DP $dp: unsupported value type ${v::class.simpleName}")
    }
}
