package bardisucks.app

import android.app.Application
import android.os.SystemClock
import android.util.Log
import bardisucks.app.outside.Shortcuts
import bardisucks.app.outside.SlotTile
import bardisucks.core.home.Action
import bardisucks.core.home.ConfigStore
import bardisucks.core.home.HomeController
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class BardiApp : Application() {
    // A failed background task (a lamp misbehaving on the network) must never take the whole app down.
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> Log.e("bardisucks", "Background task failed", e) },
    )

    // Lifecycle bookkeeping below is only touched on the main thread.
    private var disconnect: Job? = null
    private var leftAt = 0L
    private var visible = false

    lateinit var controller: HomeController
        private set

    override fun onCreate() {
        super.onCreate()
        controller = HomeController(ConfigStore(File(filesDir, "home.json")), AndroidKeyVault(), scope)
        // Launcher shortcuts follow the scenes; tiles redraw when what they're bound to changes.
        scope.launch {
            controller.state.map { s -> s.scenes.map { it.config } }.distinctUntilChanged().collect { Shortcuts.sync(this@BardiApp, it) }
        }
        scope.launch {
            controller.state.map { s -> s.tiles to s.scenes.map { Triple(it.name, it.toggledOn, it.nextLook) } }
                .distinctUntilChanged().drop(1).collect { SlotTile.refreshAll(this@BardiApp) }
        }
    }

    /** The app is on screen: (re)connect to the lamps. */
    fun onForeground() {
        visible = true
        disconnect?.cancel()
        disconnect = null
        // After a longer absence the old connections may be dead without anyone having noticed (Android freezes
        // background apps, so even our own delayed disconnect may not have run). Start afresh.
        val away = SystemClock.elapsedRealtime() - leftAt
        if (leftAt != 0L && away > BACKGROUND_GRACE_MS) controller.stop()
        leftAt = 0L
        controller.start()
    }

    /**
     * The app left the screen: let go of the lamps after a moment, so a quick trip to the file picker or
     * another app doesn't cost a reconnect, and nothing is held (or drains battery) while we're away.
     */
    fun onBackground() {
        visible = false
        leftAt = SystemClock.elapsedRealtime()
        scheduleDisconnect()
    }

    /** Quick Settings panel opened: connect so tiles show the real state and a tap is instant. */
    fun connectBriefly() {
        controller.start()
        if (!visible) scheduleDisconnect()
    }

    /** A tap on a tile or shortcut while the app may not be running: connect, run, let go again later. */
    fun runFromOutside(action: Action, onDone: (failed: List<String>) -> Unit = {}) {
        disconnect?.cancel()
        scope.launch {
            val failed = controller.runWhenReady(action)
            withContext(Dispatchers.Main) {
                onDone(failed)
                if (!visible) scheduleDisconnect()
            }
        }
    }

    private fun scheduleDisconnect() {
        disconnect?.cancel()
        disconnect = scope.launch {
            delay(BACKGROUND_GRACE_MS)
            controller.stop()
        }
    }

    private companion object {
        const val BACKGROUND_GRACE_MS = 20_000L
    }
}
