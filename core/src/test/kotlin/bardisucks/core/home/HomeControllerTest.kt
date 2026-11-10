package bardisucks.core.home

import bardisucks.core.LightCommand
import bardisucks.core.LightMode
import bardisucks.tuya.FakeLamp
import bardisucks.tuya.TuyaDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.nio.file.Files
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The app's logic end to end, against two fake bulbs speaking the real protocol on localhost. */
class HomeControllerTest {
    private val kamar = FakeLamp(id = "lampkamar00000000001", key = "aaaaaaaaaaaaaaaa")
    private val meja = FakeLamp(id = "lampmeja000000000002", key = "bbbbbbbbbbbbbbbb", initial = FakeLamp.DEFAULT_DPS + ("22" to 400))

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val file = File(Files.createTempDirectory("bardisucks-test").toFile(), "home.json")
    private val store = ConfigStore(file)
    private val controller = HomeController(
        store, TestVault, scope,
        HomeRuntime(scope, connect = { TuyaDevice(it.id, it.host, it.localKey, it.port, timeoutMs = 800, keepAliveMs = 300) }),
    )

    private val all = Target.Group(HomeController.ALL_LAMPS)
    private val kamarLight = Target.Light(kamar.id)

    @AfterTest
    fun tearDown() {
        controller.stop()
        scope.cancel()
        kamar.close()
        meja.close()
    }

    /** Imports both lamps the way the app does, then points them at the fakes' ports. */
    private fun importAndConnect() = runBlocking {
        val summary = controller.importDevices(
            """{"devices": [
                {"id": "${kamar.id}", "name": "Kamar", "local_key": "${kamar.key}", "category": "dj", "lan_ip": "127.0.0.1", "version": "3.5"},
                {"id": "${meja.id}", "name": "Meja", "local_key": "${meja.key}", "category": "dj", "lan_ip": "127.0.0.1", "version": "3.5"}
            ]}""",
        )
        assertEquals(listOf("Kamar", "Meja"), summary.added)
        store.update { config ->
            config.copy(devices = config.devices.map { it.copy(port = if (it.id == kamar.id) kamar.port else meja.port) })
        }
        controller.start()
        await { it.lights.size == 2 && it.lights.all { lamp -> lamp.online } }
    }

    private suspend fun await(what: (HomeState) -> Boolean): HomeState = withTimeout(5_000) { controller.state.first(what) }

    private suspend fun awaitLamp(lamp: FakeLamp, what: (FakeLamp) -> Boolean) = withTimeout(5_000) {
        while (!what(lamp)) delay(20)
    }

    private fun HomeState.light(lamp: FakeLamp) = lights.first { it.id == lamp.id }

    @Test
    fun `imported lamps connect and show their state`(): Unit = runBlocking {
        importAndConnect()
        val state = controller.state.value
        assertEquals(listOf("Kamar", "Meja"), state.lights.map { it.name })
        assertEquals(100, state.light(kamar).state!!.brightness)
        assertEquals(40, state.light(meja).state!!.brightness)
        val group = state.groups.single()
        assertTrue(group.builtIn)
        assertEquals(40..100, group.brightnessRange)
        assertFalse(file.readText().contains(kamar.key), "local keys must never be stored in plain text")
    }

    @Test
    fun `a group colour reaches every lamp, each in a single frame`(): Unit = runBlocking {
        importAndConnect()
        controller.apply(all, LightCommand.Colour(hue = 200, saturation = 80))
        awaitLamp(kamar) { it.dp("21") == "colour" }
        awaitLamp(meja) { it.dp("21") == "colour" }
        // Power, mode and colour together (what BARDI's groups don't do); each lamp keeps its own brightness.
        assertEquals(setOf("20", "21", "24"), kamar.received.single().keys)
        assertEquals("00c8032003e8", kamar.dp("24"))
        assertEquals("00c803200190", meja.dp("24"))
        val state = await { it.lights.all { lamp -> lamp.state?.mode == LightMode.COLOUR } }
        assertEquals(200, state.groups.single().lead!!.colour.h)
    }

    @Test
    fun `group brightness can keep the ratio between lamps`(): Unit = runBlocking {
        importAndConnect()
        controller.saveGroup(HomeController.ALL_LAMPS, "", emptyList(), relativeBrightness = true)
        await { it.groups.single().relativeBrightness }
        // Dragging from 100 down to 50: Kamar (100 %) follows the slider, Meja (40 %) keeps its 40 % of that.
        controller.preview(all, LightCommand.Brightness(80))
        controller.preview(all, LightCommand.Brightness(60))
        controller.apply(all, LightCommand.Brightness(50))
        awaitLamp(kamar) { it.dp("22") == 500 }
        awaitLamp(meja) { it.dp("22") == 200 }
    }

    @Test
    fun `absolute group brightness sets every lamp to the same level`(): Unit = runBlocking {
        importAndConnect()
        controller.apply(all, LightCommand.Brightness(50))
        awaitLamp(kamar) { it.dp("22") == 500 }
        awaitLamp(meja) { it.dp("22") == 500 }
    }

    @Test
    fun `dragging the colour wheel streams live frames and the release saves the colour`(): Unit = runBlocking {
        importAndConnect()
        // The lamp is white: the first frame must be a real mode switch, the rest are DP 28 previews.
        for (hue in 0..300 step 20) {
            controller.preview(kamarLight, LightCommand.Colour(hue, 100))
            delay(60)
        }
        controller.apply(kamarLight, LightCommand.Colour(320, 100))
        awaitLamp(kamar) { it.dp("24") == "014003e803e8" }

        val frames = kamar.received.map { it.keys }
        assertEquals(setOf("20", "21", "24"), frames.first(), "mode switch first")
        assertTrue(frames.count { it == setOf("28") } >= 3, "live previews in between: $frames")
        assertEquals(setOf("20", "21", "24"), frames.last(), "the release is a real, saved command")
        assertTrue(frames.size < 16, "previews are throttled, not one frame per touch event: ${frames.size}")
        assertEquals(320, await { it.light(kamar).state?.colour?.h == 320 }.light(kamar).state!!.colour.h)
    }

    @Test
    fun `toggling a group turns everything off if anything is on`(): Unit = runBlocking {
        importAndConnect()
        kamar.change(mapOf("20" to false))
        await { it.light(kamar).state?.on == false }
        controller.toggle(all) // Meja is still on, so: all off
        awaitLamp(meja) { it.dp("20") == false }
        await { state -> state.lights.all { it.state?.on == false } }
        controller.toggle(all) // nothing on, so: all on
        awaitLamp(kamar) { it.dp("20") == true }
        awaitLamp(meja) { it.dp("20") == true }
    }

    @Test
    fun `changes made at the lamp or by another app show up`(): Unit = runBlocking {
        importAndConnect()
        meja.change(mapOf("21" to "colour", "24" to "007803e801f4"))
        val lamp = await { it.light(meja).state?.mode == LightMode.COLOUR }.light(meja).state!!
        assertEquals(120, lamp.colour.h)
        assertEquals(50, lamp.brightness)
    }

    @Test
    fun `switching a lamp cancels its pending countdown`(): Unit = runBlocking {
        importAndConnect()
        kamar.change(mapOf("26" to 600))
        await { it.light(kamar).state?.countdown == 600 }
        controller.toggle(kamarLight)
        awaitLamp(kamar) { it.dp("20") == false }
        assertEquals(mapOf("20" to JsonPrimitive(false), "26" to JsonPrimitive(0)), kamar.received.last())
    }

    @Test
    fun `a dropped connection is re-made`(): Unit = runBlocking {
        importAndConnect()
        val wentOffline = async(start = CoroutineStart.UNDISPATCHED) { await { !it.light(kamar).online } }
        kamar.dropConnections()
        wentOffline.await()
        await { it.light(kamar).online }
        controller.toggle(kamarLight)
        awaitLamp(kamar) { it.dp("20") == false }
    }

    @Test
    fun `a lamp switched off at the wall is reported and shown offline`(): Unit = runBlocking {
        importAndConnect()
        val event = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(5_000) { controller.events.first() } }
        kamar.silent = true
        controller.toggle(kamarLight)
        assertEquals(HomeEvent.NoResponse(listOf("Kamar")), event.await())
        await { !it.light(kamar).online }
        // The other lamp is unaffected, and the group simply works with what is reachable.
        controller.apply(all, LightCommand.Brightness(10))
        awaitLamp(meja) { it.dp("22") == 100 }
    }

    @Test
    fun `renaming, grouping and removing are remembered`(): Unit = runBlocking {
        importAndConnect()
        controller.renameLight(kamar.id, "  Lampu Kamar ")
        val groupId = controller.saveGroup(null, "Desk", listOf(meja.id, kamar.id), relativeBrightness = true)
        controller.addSwatch(Swatch(300, 50))
        assertTrue(controller.setLightAddress(meja.id, "192.168.100.134"))
        assertFalse(controller.setLightAddress(meja.id, "192.168.1"))

        val reloaded = ConfigStore(file).config.value
        assertEquals(store.config.value, reloaded)
        assertEquals("Lampu Kamar", reloaded.devices.first().name)
        assertEquals("192.168.100.134", reloaded.devices.last().ip)
        assertEquals(GroupConfig(groupId, "Desk", listOf(meja.id, kamar.id), relativeBrightness = true), reloaded.groups.single())
        assertEquals(Swatch(300, 50), reloaded.swatches.last())

        controller.removeLight(kamar.id)
        val state = await { it.lights.size == 1 }
        assertEquals(listOf("Meja"), state.groups.single().members.map { it.name })
        assertFalse(state.groups.single().builtIn, "one lamp left: no 'all lamps' group")
    }

    @Test
    fun `importing again refreshes keys without duplicating lamps`(): Unit = runBlocking {
        importAndConnect()
        controller.renameLight(kamar.id, "Lampu Kamar")
        val summary = controller.importDevices("""[{"id": "${kamar.id}", "name": "Kamar", "key": "${kamar.key}", "category": "dj"}]""")
        assertEquals(listOf("Lampu Kamar"), summary.updated)
        assertEquals(2, controller.state.value.lights.size)
        assertEquals("127.0.0.1", controller.state.value.light(kamar).ip, "a file without an address keeps the known one")
    }

    private object TestVault : KeyVault {
        override fun seal(secret: String): String = "sealed:" + Base64.getEncoder().encodeToString(secret.toByteArray())
        override fun open(sealed: String): String = String(Base64.getDecoder().decode(sealed.removePrefix("sealed:")))
    }
}
