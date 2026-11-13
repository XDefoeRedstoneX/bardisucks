package bardisucks.app

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import bardisucks.app.ui.AppRoot
import bardisucks.app.ui.BardiTheme
import bardisucks.core.home.ConfigStore
import bardisucks.core.home.HomeController
import bardisucks.core.home.HomeRuntime
import bardisucks.core.home.KeyVault
import bardisucks.tuya.FakeLamp
import bardisucks.tuya.TuyaDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.file.Files

/**
 * The real screens on the real logic, talking the real protocol to two fake bulbs on localhost.
 * Only the phone itself (Android keystore, file picker) is left out.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-normal-long-notround-any-420dpi-keyshidden-nonav")
class AppFlowTest {
    @get:Rule
    val compose = createComposeRule()

    private val kamar = FakeLamp(id = "lampkamar00000000001", key = "aaaaaaaaaaaaaaaa")
    private val meja = FakeLamp(id = "lampmeja000000000002", key = "bbbbbbbbbbbbbbbb", initial = FakeLamp.DEFAULT_DPS + ("22" to 400))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val store = ConfigStore(File(Files.createTempDirectory("bardisucks-app-test").toFile(), "home.json"))
    private val controller = HomeController(
        store, PlainVault, scope,
        HomeRuntime(scope, connect = { TuyaDevice(it.id, it.host, it.localKey, it.port, timeoutMs = 1_000, keepAliveMs = 500) }),
    )

    @After
    fun tearDown() {
        controller.stop()
        scope.cancel()
        kamar.close()
        meja.close()
    }

    private fun waitFor(what: String, condition: () -> Boolean) = try {
        compose.waitUntil(8_000, condition)
    } catch (e: Throwable) {
        throw AssertionError("Timed out waiting for: $what", e)
    }

    @Test
    fun lampsCanBeSwitchedColouredAndDimmedAsAGroup() {
        controller.importDevices(
            """{"devices": [
                {"id": "${kamar.id}", "name": "Kamar", "local_key": "${kamar.key}", "category": "dj", "lan_ip": "127.0.0.1", "version": "3.5"},
                {"id": "${meja.id}", "name": "Meja", "local_key": "${meja.key}", "category": "dj", "lan_ip": "127.0.0.1", "version": "3.5"}
            ]}""",
        )
        store.update { c -> c.copy(devices = c.devices.map { it.copy(port = if (it.id == kamar.id) kamar.port else meja.port) }) }
        controller.start()
        compose.setContent { BardiTheme(dynamicColor = false) { AppRoot(controller, "test") } }
        waitFor("both lamps online") { controller.state.value.lights.let { it.size == 2 && it.all { lamp -> lamp.online } } }

        // Home: one lamp's power button.
        compose.onNodeWithTag("power:Kamar").performClick()
        waitFor("Kamar off") { kamar.dp("20") == false }
        assertEquals(true, meja.dp("20"))

        // Open the group of all lamps and switch it to colour: both lamps follow, Kamar turns back on.
        compose.onNodeWithTag("tile:All lamps").performClick()
        compose.onNodeWithText("Colour").performClick()
        waitFor("both lamps in colour mode") { kamar.dp("21") == "colour" && meja.dp("21") == "colour" }
        assertEquals(true, kamar.dp("20"))

        // Drag the group's brightness down: in colour mode that lands in the V of DP 24 on both lamps.
        compose.onNodeWithTag("slider:Brightness").performTouchInput { swipeLeft(startX = width * 0.9f, endX = width * 0.3f) }
        waitFor("both lamps dimmed to the same level") {
            val a = (kamar.dp("24") as String).takeLast(4).toInt(16)
            val b = (meja.dp("24") as String).takeLast(4).toInt(16)
            a == b && a in 250..350
        }

        // The power row of the group turns everything off.
        compose.onNodeWithTag("powerRow").performClick()
        waitFor("both lamps off") { kamar.dp("20") == false && meja.dp("20") == false }
    }

    @Test
    fun scenesRunWithOneTapAndToggleBack() {
        importAndStart()
        controller.addStarterScenes()
        compose.setContent { BardiTheme(dynamicColor = false) { AppRoot(controller, "test") } }
        waitFor("starter scenes shown") { controller.state.value.scenes.size == 6 }
        compose.waitForIdle()

        compose.onNodeWithTag("scene:All off").performClick()
        waitFor("both lamps off") { kamar.dp("20") == false && meja.dp("20") == false }

        // Movie is a toggle: on, then back to exactly how it was (both off).
        compose.onNodeWithTag("scene:Movie").performClick()
        waitFor("movie colours") { kamar.dp("20") == true && kamar.dp("21") == "colour" && meja.dp("21") == "colour" }
        waitFor("movie shown as on") { controller.state.value.scenes.first { it.name == "Movie" }.toggledOn }
        compose.onNodeWithTag("scene:Movie").performClick()
        waitFor("back off") { kamar.dp("20") == false && meja.dp("20") == false }
    }

    @Test
    fun aNewSceneCanBeMadeFromHowTheLampsLookNow() {
        importAndStart()
        compose.setContent { BardiTheme(dynamicColor = false) { AppRoot(controller, "test") } }
        compose.onNodeWithText("Add starter scenes").assertExists()
        compose.onNodeWithText("New scene").performClick()
        compose.onNodeWithTag("sceneName").performTextInput("Reading")
        compose.onNodeWithText("Use how lamps look now").performScrollTo().performClick()
        compose.onNodeWithTag("saveScene").performScrollTo().performClick()
        waitFor("scene saved") { controller.state.value.scenes.any { it.name == "Reading" } }
        val look = controller.state.value.scenes.single().config.looks.single()
        assertEquals(listOf(kamar.id, meja.id).map { "light:$it" }, look.actions.map { it.target })
        assertEquals(listOf(100, 40), look.actions.map { it.brightness })
    }

    private fun importAndStart() {
        controller.importDevices(
            """{"devices": [
                {"id": "${kamar.id}", "name": "Kamar", "local_key": "${kamar.key}", "category": "dj", "lan_ip": "127.0.0.1", "version": "3.5"},
                {"id": "${meja.id}", "name": "Meja", "local_key": "${meja.key}", "category": "dj", "lan_ip": "127.0.0.1", "version": "3.5"}
            ]}""",
        )
        store.update { c -> c.copy(devices = c.devices.map { it.copy(port = if (it.id == kamar.id) kamar.port else meja.port) }) }
        controller.start()
        waitFor("both lamps online") { controller.state.value.lights.let { it.size == 2 && it.all { lamp -> lamp.online } } }
    }

    private object PlainVault : KeyVault {
        override fun seal(secret: String) = secret.reversed()
        override fun open(sealed: String) = sealed.reversed()
    }
}
