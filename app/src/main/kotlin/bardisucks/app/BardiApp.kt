package bardisucks.app

import android.app.Application
import android.os.SystemClock
import android.util.Log
import bardisucks.core.home.ConfigStore
import bardisucks.core.home.HomeController
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

class BardiApp : Application() {
    // A failed background task (a lamp misbehaving on the network) must never take the whole app down.
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> Log.e("bardisucks", "Background task failed", e) },
    )
    private var disconnect: Job? = null
    private var leftAt = 0L

    lateinit var controller: HomeController
        private set

    override fun onCreate() {
        super.onCreate()
        controller = HomeController(ConfigStore(File(filesDir, "home.json")), AndroidKeyVault(), scope)
    }

    /** The app is on screen: (re)connect to the lamps. */
    fun onForeground() {
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
        leftAt = SystemClock.elapsedRealtime()
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
