package bardisucks.core.home

import bardisucks.core.LightMode
import bardisucks.tuya.FakeLamp
import bardisucks.tuya.TuyaDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Scenes end to end, against two fake bulbs speaking the real protocol on localhost. */
class ScenesTest {
    private val kamar = FakeLamp(id = "lampkamar00000000001", key = "aaaaaaaaaaaaaaaa")
    private val meja = FakeLamp(
        id = "lampmeja000000000002", key = "bbbbbbbbbbbbbbbb",
        initial = FakeLamp.DEFAULT_DPS + mapOf("21" to "colour", "24" to "001703e801f4"),
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val file = File(Files.createTempDirectory("bardisucks-scenes").toFile(), "home.json")
    private val store = ConfigStore(file)
    private val controller = HomeController(
        store, PlainVault, scope,
        HomeRuntime(scope, connect = { TuyaDevice(it.id, it.host, it.localKey, it.port, timeoutMs = 800, keepAliveMs = 300) }),
    )

    private val all = Target.Group(HomeController.ALL_LAMPS).encode()
    private val kamarRef = Target.Light(kamar.id).encode()
    private val mejaRef = Target.Light(meja.id).encode()

    @AfterTest
    fun tearDown() {
        controller.stop()
        scope.cancel()
        kamar.close()
        meja.close()
    }

    private fun setUp(connect: Boolean = true) = runBlocking {
        controller.importDevices(
            """{"devices": [
                {"id": "${kamar.id}", "name": "Kamar", "local_key": "${kamar.key}", "category": "dj", "lan_ip": "127.0.0.1"},
                {"id": "${meja.id}", "name": "Meja", "local_key": "${meja.key}", "category": "dj", "lan_ip": "127.0.0.1"}
            ]}""",
        )
        store.update { c -> c.copy(devices = c.devices.map { it.copy(port = if (it.id == kamar.id) kamar.port else meja.port) }) }
        if (connect) {
            controller.start()
            await { it.lights.size == 2 && it.lights.all { lamp -> lamp.online } }
        }
    }

    private suspend fun await(what: (HomeState) -> Boolean): HomeState = withTimeout(5_000) { controller.state.first(what) }

    private suspend fun awaitLamp(lamp: FakeLamp, what: (FakeLamp) -> Boolean) = withTimeout(5_000) {
        while (!what(lamp)) delay(20)
    }

    private fun scene(name: String, style: SceneStyle, vararg looks: List<SceneAction>) =
        controller.saveScene(SceneConfig("", name, style, looks.map { SceneLook(actions = it) }))

    @Test
    fun `a scene sets each lamp in one frame, with its own timer`(): Unit = runBlocking {
        setUp()
        val id = scene(
            "Night", SceneStyle.NORMAL,
            listOf(
                SceneAction(kamarRef, brightness = 10, white = 0, offAfterMinutes = 30),
                SceneAction(mejaRef, on = false),
            ),
        )
        assertEquals(emptyList(), controller.runScene(id).await())
        awaitLamp(kamar) { it.dp("22") == 100 }
        awaitLamp(meja) { it.dp("20") == false }
        val frame = kamar.received.single()
        assertEquals(setOf("20", "21", "22", "23", "26"), frame.keys, "power, mode, level, tone and timer together")
        assertEquals(1800, kamar.dp("26"))
        assertEquals(setOf("20"), meja.received.single().keys)
    }

    @Test
    fun `a group step reaches every lamp, and a later lamp step overrides it`(): Unit = runBlocking {
        setUp()
        val id = scene(
            "Mixed", SceneStyle.NORMAL,
            listOf(SceneAction(all, brightness = 50, colour = Swatch(200, 100)), SceneAction(mejaRef, white = 30)),
        )
        controller.runScene(id).await()
        awaitLamp(kamar) { it.dp("24") == "00c803e801f4" }
        awaitLamp(meja) { it.dp("21") == "white" }
        assertEquals(500, kamar.dp("24").toString().takeLast(4).toInt(16))
        assertEquals(300, meja.dp("23"))
    }

    @Test
    fun `steps that leave things out keep what the lamp has`(): Unit = runBlocking {
        setUp()
        // Meja shows colour h=23 at 50 %: a brightness-only step keeps the colour.
        controller.runScene(scene("Dim", SceneStyle.NORMAL, listOf(SceneAction(mejaRef, brightness = 20)))).await()
        awaitLamp(meja) { it.dp("24") == "001703e800c8" }
        assertEquals("colour", meja.dp("21"))
    }

    @Test
    fun `a toggle scene puts everything back on the second tap`(): Unit = runBlocking {
        setUp()
        val before = controller.state.value.lights.associate { it.id to it.state }
        val id = scene("Movie", SceneStyle.TOGGLE, listOf(SceneAction(all, brightness = 15, colour = Swatch(265, 85))))

        controller.runScene(id).await()
        awaitLamp(kamar) { it.dp("21") == "colour" }
        assertTrue(await { it.scenes.single().toggledOn }.scenes.single().toggledOn)

        controller.runScene(id).await()
        awaitLamp(kamar) { it.dp("21") == "white" && it.dp("22") == 1000 }
        awaitLamp(meja) { it.dp("24") == "001703e801f4" }
        val after = await { s -> s.lights.all { it.state == before[it.id] } }
        assertFalse(after.scenes.single().toggledOn)
    }

    @Test
    fun `running another scene forgets a toggle, so its next tap applies again`(): Unit = runBlocking {
        setUp()
        val movie = scene("Movie", SceneStyle.TOGGLE, listOf(SceneAction(all, brightness = 15, colour = Swatch(265, 85))))
        val off = scene("Off", SceneStyle.NORMAL, listOf(SceneAction(all, on = false)))
        controller.runScene(movie).await()
        controller.runScene(off).await()
        awaitLamp(kamar) { it.dp("20") == false }
        assertFalse(await { s -> s.scenes.none { it.toggledOn } }.scenes.any { it.toggledOn })
        controller.runScene(movie).await()
        awaitLamp(kamar) { it.dp("20") == true && it.dp("21") == "colour" }
    }

    @Test
    fun `a cycle steps through its looks and wraps around`(): Unit = runBlocking {
        setUp()
        val id = scene(
            "Cycle", SceneStyle.CYCLE,
            listOf(SceneAction(kamarRef, brightness = 100, white = 70)),
            listOf(SceneAction(kamarRef, brightness = 5, white = 0)),
            listOf(SceneAction(kamarRef, on = false)),
        )
        val levels = mutableListOf<Any?>()
        await { it.scenes.size == 1 }
        repeat(4) { tap ->
            assertEquals(tap % 3, controller.state.value.scenes.single().nextLook)
            controller.runScene(id).await()
            awaitLamp(kamar) { it.received.size == tap + 1 }
            levels += if (kamar.dp("20") == false) "off" else kamar.dp("22")
            await { it.scenes.single().nextLook == (tap + 1) % 3 }
        }
        assertEquals(listOf<Any?>(1000, 50, "off", 1000), levels)
    }

    @Test
    fun `capturing the lamps gives a scene that brings the same look back`(): Unit = runBlocking {
        setUp()
        val look = controller.captureLook()
        assertEquals(
            listOf(
                SceneAction(kamarRef, brightness = 100, white = 100),
                SceneAction(mejaRef, brightness = 50, colour = Swatch(23, 100)),
            ),
            look.actions,
        )
        val id = controller.saveScene(SceneConfig("", "Now", looks = listOf(look)))
        kamar.change(mapOf("20" to false))
        meja.change(mapOf("21" to "white"))
        await { s -> s.lights.first { it.id == meja.id }.state?.mode == LightMode.WHITE }
        controller.runScene(id).await()
        awaitLamp(kamar) { it.dp("20") == true }
        awaitLamp(meja) { it.dp("21") == "colour" && it.dp("24") == "001703e801f4" }
    }

    @Test
    fun `lamps that can't be reached are named`(): Unit = runBlocking {
        setUp()
        kamar.silent = true
        // Noticed after two unanswered heartbeats plus a failed reconnect: a few seconds.
        withTimeout(15_000) { controller.state.first { s -> s.lights.first { it.id == kamar.id }.unreachable } }
        val id = scene("All", SceneStyle.NORMAL, listOf(SceneAction(all, brightness = 30)))
        assertEquals(listOf("Kamar"), controller.runScene(id).await())
        // Meja shows a colour, so its brightness is the V of DP 24 (300 = 0x012c).
        awaitLamp(meja) { it.dp("24") == "001703e8012c" }
    }

    @Test
    fun `a tap from outside the app connects first, then runs`(): Unit = runBlocking {
        setUp(connect = false)
        val id = scene("Off", SceneStyle.NORMAL, listOf(SceneAction(all, on = false)))
        assertEquals(emptyList(), controller.runWhenReady(Action.RunScene(id)))
        awaitLamp(kamar) { it.dp("20") == false }
        awaitLamp(meja) { it.dp("20") == false }
        assertEquals(emptyList(), controller.runWhenReady(Action.Switch(Target.Light(kamar.id))))
        awaitLamp(kamar) { it.dp("20") == true }
    }

    @Test
    fun `starter scenes, tiles and clean-up when things are removed`(): Unit = runBlocking {
        setUp()
        controller.addStarterScenes()
        val scenes = await { it.scenes.size == 6 }.scenes
        assertEquals(listOf("Bright", "Evening", "Night light", "Movie", "All off", "Cycle"), scenes.map { it.name })
        assertTrue(scenes.all { s -> s.config.looks.all { look -> look.actions.all { it.target == all } } })
        assertEquals(30, scenes[2].config.looks.single().actions.single().offAfterMinutes)

        val cycle = scenes.last()
        controller.bindTile(1, Action.RunScene(cycle.id))
        controller.bindTile(2, Action.Switch(Target.Light(meja.id)))
        val described = controller.describe(Action.RunScene(cycle.id), "All lamps")!!
        assertEquals(ActionInfo("Cycle", active = false, detail = "Bright"), described)
        assertEquals(ActionInfo("Meja", active = true), controller.describe(Action.Switch(Target.Light(meja.id)), "All lamps"))
        await { it.tiles == mapOf(1 to Action.RunScene(cycle.id), 2 to Action.Switch(Target.Light(meja.id))) }

        controller.removeLight(meja.id)
        controller.deleteScene(cycle.id)
        val state = await { it.tiles.isEmpty() && it.scenes.size == 5 }
        assertNull(controller.describe(Action.RunScene(cycle.id), "All lamps"))
        assertEquals(state.scenes.map { it.config }, ConfigStore(file).config.value.scenes, "saved to disk")
    }

    @Test
    fun `actions survive being written as text`() {
        for (action in listOf(Action.RunScene("s1"), Action.Switch(Target.Light("abc")), Action.Switch(Target.Group("all")))) {
            assertEquals(action, Action.decode(action.encode()))
        }
        assertNull(Action.decode("scene:"))
        assertNull(Action.decode("nonsense"))
    }

    private object PlainVault : KeyVault {
        override fun seal(secret: String) = secret.reversed()
        override fun open(sealed: String) = sealed.reversed()
    }
}
