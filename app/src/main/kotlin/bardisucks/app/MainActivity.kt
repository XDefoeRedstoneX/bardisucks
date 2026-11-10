package bardisucks.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import bardisucks.app.ui.AppRoot
import bardisucks.app.ui.BardiTheme

class MainActivity : ComponentActivity() {
    private val app get() = application as BardiApp

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val version = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
        setContent {
            BardiTheme { AppRoot(app.controller, version) }
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
}
