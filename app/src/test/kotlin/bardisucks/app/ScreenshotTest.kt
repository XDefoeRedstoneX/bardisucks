package bardisucks.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import bardisucks.app.ui.AppContent
import bardisucks.app.ui.AppIcons
import bardisucks.app.ui.BardiTheme
import bardisucks.app.ui.GroupEditorScreen
import bardisucks.app.ui.SettingsScreen
import bardisucks.core.home.HomeState
import bardisucks.core.home.Target
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private const val PHONE = "w411dp-h891dp-normal-long-notround-any-420dpi-keyshidden-nonav"
private const val TABLET = "w1280dp-h800dp-xlarge-notlong-notround-land-240dpi-keyshidden-nonav"

/** Renders the screens to build/outputs/roborazzi/, to look at without a phone. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = PHONE)
class ScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun shoot(name: String, dark: Boolean = true, content: @Composable () -> Unit) {
        compose.setContent { BardiTheme(dark = dark, dynamicColor = false, content = content) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    @Composable
    private fun App(state: HomeState = Samples.home, open: Target? = null) =
        AppContent(state, Samples.noActions, Samples.noEdits, "0.1.0", remember { SnackbarHostState() }, onImport = {}, initial = open)

    @Test fun home() = shoot("home") { App() }

    @Test fun homeLight() = shoot("home_light", dark = false) { App() }

    @Test fun homeEmpty() = shoot("home_empty") { App(HomeState()) }

    @Test fun lampColour() = shoot("lamp_colour") { App(open = Target.Light(Samples.kamar.id)) }

    @Test fun lampWhite() = shoot("lamp_white") { App(open = Target.Light(Samples.meja.id)) }

    @Test fun lampWhiteLight() = shoot("lamp_white_light", dark = false) { App(open = Target.Light(Samples.meja.id)) }

    @Test fun lampOffline() = shoot("lamp_offline") { App(open = Target.Light(Samples.teras.id)) }

    @Test fun group() = shoot("group") { App(open = Target.Group(Samples.desk.id)) }

    @Test fun groupEditor() = shoot("group_editor") {
        GroupEditorScreen(Samples.desk, Samples.home.lights, onSave = { _, _, _ -> }, onDelete = {}, onBack = {})
    }

    @Test fun settings() = shoot("settings") {
        SettingsScreen(Samples.home.lights, "0.1.0", onImport = {}, onEditLight = {}, onBack = {})
    }

    @Test
    @Config(qualifiers = TABLET)
    fun tablet() = shoot("tablet") { App(open = Target.Group(Samples.all.id)) }

    @Test fun icons() = shoot("icons") {
        Surface {
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf(
                    AppIcons.Bulb, AppIcons.Power, AppIcons.Group, AppIcons.Back, AppIcons.Add, AppIcons.Check, AppIcons.Edit,
                    AppIcons.Delete, AppIcons.Settings, AppIcons.Warning, AppIcons.ChevronRight, AppIcons.Upload,
                ).forEach { Icon(it, null) }
            }
        }
    }
}
