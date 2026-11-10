package bardisucks.core.home

import bardisucks.core.LightCommand
import bardisucks.core.LightProfile
import bardisucks.core.LightState
import bardisucks.tuya.TuyaDevice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Where and how to reach one device on the LAN. */
data class DeviceEndpoint(val id: String, val host: String, val port: Int, val localKey: String)

/**
 * What we currently know about one device. Not [online] and not [unreachable] means a connection attempt is
 * still under way; [unreachable] means the last attempt failed (it keeps being retried).
 */
data class DeviceSnapshot(val online: Boolean = false, val light: LightState? = null, val unreachable: Boolean = false)

/**
 * Holds the LAN connections to a home's lamps while the app is in use, and fans commands out to them.
 *
 * - [snapshots] is the live state of every lamp (connection + last reported values).
 * - [apply] sends a command to several lamps at the same moment, which is what makes groups work.
 * - [preview] streams intermediate values while a slider or the colour wheel is being dragged.
 */
class HomeRuntime(
    private val scope: CoroutineScope,
    private val profile: LightProfile = LightProfile(),
    private val connect: (DeviceEndpoint) -> TuyaDevice = { TuyaDevice(it.id, it.host, it.localKey, it.port, timeoutMs = 3_000, keepAliveMs = 10_000) },
) {
    private val sessions = LinkedHashMap<String, DeviceSession>()
    private var running = false

    private val _snapshots = MutableStateFlow<Map<String, DeviceSnapshot>>(emptyMap())
    val snapshots: StateFlow<Map<String, DeviceSnapshot>> = _snapshots

    /** Replaces the set of devices. Unchanged devices keep their connection. */
    @Synchronized
    fun setDevices(endpoints: List<DeviceEndpoint>) {
        val wanted = endpoints.associateBy { it.id }
        sessions.entries.removeAll { (id, session) ->
            (wanted[id] != session.endpoint).also { stale -> if (stale) session.retire() }
        }
        val added = endpoints.filter { it.id !in sessions }.map { endpoint ->
            DeviceSession(endpoint, profile, scope, connect, ::onSnapshot).also { sessions[endpoint.id] = it }
        }
        // Publish the new device list before any new session can report, so no first report gets lost.
        val addedIds = added.map { it.endpoint.id }.toSet()
        _snapshots.update { current ->
            wanted.keys.associateWith { id -> current[id]?.takeUnless { id in addedIds } ?: DeviceSnapshot() }
        }
        if (running) added.forEach { it.start() }
    }

    private fun onSnapshot(session: DeviceSession, snapshot: DeviceSnapshot) {
        if (!session.retired) _snapshots.update { it + (session.endpoint.id to snapshot) }
    }

    /** Connects to every device and keeps reconnecting until [stop]. */
    @Synchronized
    fun start() {
        running = true
        sessions.values.forEach { it.start() }
    }

    /** Drops all connections (the lamps keep their state). */
    @Synchronized
    fun stop() {
        running = false
        sessions.values.forEach { it.stop() }
    }

    /**
     * Sends each lamp its command, all at the same moment. The local state changes immediately; the result is
     * the set of device ids that could not be reached or didn't confirm.
     */
    fun apply(commands: Map<String, LightCommand>): Deferred<Set<String>> {
        val prepared = commands.map { (id, command) ->
            val session = session(id)?.takeIf { it.online }
            val dps = session?.let { profile.dpsFor(command, it.light) }
            if (session != null && dps != null) session.expect(dps)
            Triple(id, session, dps)
        }
        return scope.async {
            prepared.map { (id, session, dps) ->
                async { id.takeUnless { session != null && dps != null && session.send(dps) } }
            }.awaitAll().filterNotNull().toSet()
        }
    }

    /** Shows [commands] on the lamps right away without waiting for confirmation. Follow up with [apply]. */
    fun preview(commands: Map<String, LightCommand>) {
        for ((id, command) in commands) {
            val session = session(id)?.takeIf { it.online } ?: continue
            val live = profile.previewFor(command, session.light)
            if (live != null) session.previewLive(live) else session.previewReal(profile.dpsFor(command, session.light))
        }
    }

    @Synchronized
    private fun session(id: String) = sessions[id]
}

/** One device: a connection that is re-established whenever it drops, plus the last known DP values. */
internal class DeviceSession(
    val endpoint: DeviceEndpoint,
    private val profile: LightProfile,
    private val scope: CoroutineScope,
    private val connect: (DeviceEndpoint) -> TuyaDevice,
    private val publish: (DeviceSession, DeviceSnapshot) -> Unit,
) {
    /** Set once this session has been replaced or removed; it then stops reporting. */
    @Volatile var retired = false
        private set

    private val lock = Any()
    private var job: Job? = null
    @Volatile private var device: TuyaDevice? = null
    @Volatile private var unreachable = false
    private var dps: Map<String, Any?> = emptyMap()

    // Values we just sent, and until when to prefer them over (possibly older) pushes still in flight.
    private var expected: Map<String, Any?> = emptyMap()
    private var expectedUntil = 0L

    // Latest not-yet-sent preview values. Real DPs (mode switches, white levels) are merged so none gets lost;
    // live DP 28 frames simply replace each other.
    private var pendingReal: Map<String, Any?> = emptyMap()
    private var pendingLive: Map<String, Any?>? = null
    private val previewSignal = Channel<Unit>(Channel.CONFLATED)

    val online: Boolean get() = device != null
    val light: LightState? get() = synchronized(lock) { profile.read(dps) }

    fun start() = synchronized(lock) {
        if (job?.isActive != true) {
            unreachable = false
            job = scope.launch { run() }
        }
    }

    fun stop() {
        synchronized(lock) {
            job?.cancel()
            job = null
        }
        device?.close()
        device = null
        unreachable = false
        emit()
    }

    fun retire() {
        retired = true
        stop()
    }

    /** Records [sent] as the lamp's state before the lamp confirms it, and drops previews it supersedes. */
    fun expect(sent: Map<String, Any?>) {
        synchronized(lock) {
            val stored = sent - profile.live
            dps = dps + stored
            expected = stored
            expectedUntil = System.nanoTime() + EXPECT_WINDOW_NS
            pendingReal = emptyMap()
            pendingLive = null
        }
        emit()
    }

    /** False if the lamp didn't take it; the connection is then re-made, which also re-reads the real state. */
    suspend fun send(sent: Map<String, Any?>): Boolean {
        val d = device ?: return false
        return try {
            d.set(sent)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            d.close()
            false
        }
    }

    fun previewLive(frame: Map<String, Any?>) {
        synchronized(lock) { pendingLive = frame }
        previewSignal.trySend(Unit)
    }

    fun previewReal(sent: Map<String, Any?>) {
        synchronized(lock) {
            pendingReal = pendingReal + sent
            dps = dps + sent
        }
        emit()
        previewSignal.trySend(Unit)
    }

    private suspend fun run() {
        var retryMs = FIRST_RETRY_MS
        while (true) {
            if (endpoint.host.isNotBlank()) {
                var opened: TuyaDevice? = null
                try {
                    val d = connect(endpoint).also { opened = it }
                    coroutineScope {
                        // Listen before connecting so no push between "connected" and "status read" is missed.
                        val pushes = launch(start = CoroutineStart.UNDISPATCHED) { d.updates.collect { merge(it.toPlainMap()) } }
                        d.connect()
                        val status = d.status().toPlainMap()
                        synchronized(lock) { dps = status }
                        device = d
                        unreachable = false
                        emit()
                        retryMs = FIRST_RETRY_MS
                        val previews = launch { sendPreviews(d) }
                        d.state.first { it == TuyaDevice.State.DISCONNECTED }
                        pushes.cancel()
                        previews.cancel()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // No route, wrong key, timed out...: say so, and try again below.
                    unreachable = true
                } finally {
                    device = null
                    opened?.close()
                    emit()
                }
            }
            delay(retryMs)
            retryMs = (retryMs * 2).coerceAtMost(MAX_RETRY_MS)
        }
    }

    private suspend fun sendPreviews(d: TuyaDevice) {
        for (ignored in previewSignal) {
            val (real, live) = synchronized(lock) {
                (pendingReal to pendingLive).also {
                    pendingReal = emptyMap()
                    pendingLive = null
                }
            }
            try {
                if (real.isNotEmpty()) d.setNoWait(real)
                if (live != null) d.setNoWait(live)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
            }
            delay(PREVIEW_INTERVAL_MS)
        }
    }

    private fun merge(pushed: Map<String, Any?>) {
        synchronized(lock) {
            val fresh = if (System.nanoTime() < expectedUntil) pushed - expected.keys else pushed
            dps = dps + fresh
        }
        emit()
    }

    private fun emit() = publish(this, DeviceSnapshot(online, light, unreachable && !online))

    private companion object {
        const val FIRST_RETRY_MS = 500L
        const val MAX_RETRY_MS = 8_000L

        /** ~6 updates a second: smooth to the eye, and well within what the bulbs accept. */
        const val PREVIEW_INTERVAL_MS = 160L
        const val EXPECT_WINDOW_NS = 800_000_000L
    }
}
