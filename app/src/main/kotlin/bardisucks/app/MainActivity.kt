package bardisucks.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import bardisucks.app.outside.Shortcuts
import bardisucks.app.outside.TileRequest
import bardisucks.app.ui.AppRoot
import bardisucks.app.ui.BardiTheme
import bardisucks.app.ui.PlatformHooks

class MainActivity : ComponentActivity() {
    private val app get() = application as BardiApp

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val version = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
        val platform = PlatformHooks(
            pinScene = if (Shortcuts.canPin(this)) { scene -> Shortcuts.pin(this, scene) } else null,
            addTile = if (TileRequest.supported()) {
                { slot ->
                    val action = app.controller.state.value.tiles[slot]
                    val label = action?.let { app.controller.describe(it, getString(R.string.all_lamps))?.label }
                    TileRequest.request(this, slot, label ?: getString(R.string.tile_label_unused, slot))
                }
            } else {
                null
            },
        )
        val openSettings = savedInstanceState == null && intent.getBooleanExtra(EXTRA_OPEN_SETTINGS, false)
        setContent {
            BardiTheme { AppRoot(app.controller, version, platform, openSettings) }
        }
    }

    override fun onStart() {
        super.onStart()
        app.onForeground()
    }

    override fun onStop() {
        super.onStop()
        app.onBackground()
    }

    companion object {
        /** Opens Settings (where tiles are set up), e.g. from an unused tile. */
        const val EXTRA_OPEN_SETTINGS = "bardisucks.OPEN_SETTINGS"
    }
}
