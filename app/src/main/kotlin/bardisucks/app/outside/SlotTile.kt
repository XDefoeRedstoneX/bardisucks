package bardisucks.app.outside

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import bardisucks.app.BardiApp
import bardisucks.app.MainActivity
import bardisucks.app.R
import bardisucks.core.home.Action
import bardisucks.core.home.ActionInfo
import bardisucks.core.home.SceneStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * One of six Quick Settings tiles. Each runs what it is bound to in Settings: a scene, or switching a lamp or
 * group. Android needs every tile declared up front, hence the numbered subclasses below.
 */
abstract class SlotTile(private val slot: Int) : TileService() {
    private var listening: CoroutineScope? = null
    private val app get() = application as BardiApp

    override fun onStartListening() {
        super.onStartListening()
        // While the panel is open, keep the tile live; and connect, so the state is right and a tap is instant.
        listening?.cancel()
        val scope = CoroutineScope(Job() + Dispatchers.Main.immediate)
        listening = scope
        if (action() != null) app.connectBriefly()
        scope.launch { app.controller.state.collect { render() } }
    }

    override fun onStopListening() {
        listening?.cancel()
        listening = null
        super.onStopListening()
    }

    override fun onClick() {
        val action = action()
        if (action == null) {
            openApp()
            return
        }
        // Optimistic for things that are on or off: flip the tile at once; the real state follows when the
        // lamps answer. Normal and cycle scenes have no on/off, so their tile stays as it is.
        val flips = action is Action.Switch ||
            app.controller.state.value.scenes.firstOrNull { it.id == (action as Action.RunScene).sceneId }?.config?.style == SceneStyle.TOGGLE
        qsTile?.takeIf { flips }?.let { tile ->
            tile.state = if (tile.state == Tile.STATE_ACTIVE) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
            tile.updateTile()
        }
        app.runFromOutside(action) { render() }
    }

    /** The bound action, or null if unbound or pointing at something that was deleted. */
    private fun action(): Action? =
        app.controller.state.value.tiles[slot]?.takeIf { describe(it) != null }

    private fun describe(action: Action): ActionInfo? = app.controller.describe(action, getString(R.string.all_lamps))

    private fun render() {
        val tile = qsTile ?: return
        val action = action()
        val info = action?.let(::describe)
        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile)
        if (info == null) {
            tile.label = getString(R.string.tile_label_unused, slot)
            tile.state = Tile.STATE_INACTIVE
            setSubtitle(tile, getString(R.string.tile_setup))
        } else {
            tile.label = info.label
            tile.state = if (info.active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            setSubtitle(tile, info.detail ?: if (app.controller.isReachable(action)) null else getString(R.string.tile_offline))
        }
        tile.updateTile()
    }

    private fun setSubtitle(tile: Tile, text: String?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = text
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN_SETTINGS, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, slot, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }

    companion object {
        val SLOTS: List<Class<out SlotTile>> = listOf(
            Tile1::class.java, Tile2::class.java, Tile3::class.java, Tile4::class.java, Tile5::class.java, Tile6::class.java,
        )

        /** Asks Android to redraw every tile that is currently showing. */
        fun refreshAll(context: Context) {
            for (cls in SLOTS) runCatching { requestListeningState(context, ComponentName(context, cls)) }
        }
    }
}

class Tile1 : SlotTile(1)
class Tile2 : SlotTile(2)
class Tile3 : SlotTile(3)
class Tile4 : SlotTile(4)
class Tile5 : SlotTile(5)
class Tile6 : SlotTile(6)
