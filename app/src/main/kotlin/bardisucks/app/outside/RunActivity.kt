package bardisucks.app.outside

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import bardisucks.app.BardiApp
import bardisucks.app.R
import bardisucks.core.home.Action

/**
 * Invisible: runs the action in its link (from a home-screen or launcher shortcut) and closes at once,
 * so a tap on a shortcut doesn't open the app.
 */
class RunActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as BardiApp
        val action = intent?.data?.let(::actionOf)
        if (action == null || app.controller.describe(action, getString(R.string.all_lamps)) == null) {
            Toast.makeText(app, R.string.shortcut_missing, Toast.LENGTH_SHORT).show()
        } else {
            app.runFromOutside(action) { failed ->
                if (failed.isNotEmpty()) {
                    Toast.makeText(app, app.getString(R.string.no_response, failed.joinToString()), Toast.LENGTH_LONG).show()
                }
            }
        }
        finish()
    }

    companion object {
        /** `bardisucks://run/<action>`, e.g. `bardisucks://run/scene:s1a2b3c4`. */
        fun link(action: Action): Uri = Uri.Builder().scheme("bardisucks").authority("run").appendPath(action.encode()).build()

        fun actionOf(link: Uri): Action? =
            link.takeIf { it.scheme == "bardisucks" && it.authority == "run" }?.lastPathSegment?.let(Action::decode)
    }
}
