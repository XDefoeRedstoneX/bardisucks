package bardisucks.core.home

import bardisucks.core.LightCommand
import bardisucks.core.LightState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

enum class LightProblem {
    /** No LAN address yet. */
    NO_ADDRESS,

    /** The stored key can't be opened on this phone (app data moved from another phone): import the devices again. */
    KEY_UNREADABLE,
}

data class LightItem(
    val id: String,
    val name: String,
    val ip: String,
    val online: Boolean,
    /** Last values the lamp reported; kept while it is offline. Null until it has been reached once. */
    val state: LightState?,
    val problem: LightProblem? = null,
    /** The last connection attempt failed. Not [online] and not [unreachable] means still connecting. */
    val unreachable: Boolean = false,
)

data class GroupItem(
    val id: String,
    /** Empty for the built-in "all lamps" group, which the UI names itself. */
    val name: String,
    val members: List<LightItem>,
    val relativeBrightness: Boolean,
    val builtIn: Boolean = false,
) {
    /** Members we can talk to right now. */
    val reachable: List<LightItem> get() = members.filter { it.online && it.state != null }
    val online: Boolean get() = reachable.isNotEmpty()
    val anyOn: Boolean get() = reachable.any { it.state!!.on }

    /** The lamp whose values the group's controls show: the first one that is on, else the first reachable one. */
    val lead: LightState? get() = (reachable.firstOrNull { it.state!!.on } ?: reachable.firstOrNull())?.state

    /** Lowest and highest brightness among the lamps that are on (among all reachable lamps if none is on). */
    val brightnessRange: IntRange?
        get() {
            val levels = reachable.filter { it.state!!.on }.ifEmpty { reachable }.map { it.state!!.brightness }
            return if (levels.isEmpty()) null else levels.min()..levels.max()
        }
}

data class HomeState(
    val lights: List<LightItem> = emptyList(),
    val groups: List<GroupItem> = emptyList(),
    val swatches: List<Swatch> = emptyList(),
    val scenes: List<SceneItem> = emptyList(),
    /** Quick Settings tile slot → what it does. */
    val tiles: Map<Int, Action> = emptyMap(),
)

/** What a command is aimed at. */
sealed interface Target {
    val id: String

    data class Light(override val id: String) : Target
    data class Group(override val id: String) : Target

    companion object
}

/** How an [Action] looks on a Quick Settings tile or in a message. */
data class ActionInfo(val label: String, val active: Boolean, val detail: String? = null)

/** Where toggle and cycle scenes stand. Kept in memory only: after a restart every scene starts afresh. */
private data class SceneRuns(
    /** Toggle scene id → how its lamps looked before it was applied. At most one at a time. */
    val toggled: Map<String, Map<String, LightState>> = emptyMap(),
    /** Cycle scene id → index of the look the next tap applies. */
    val cycle: Map<String, Int> = emptyMap(),
)

sealed interface HomeEvent {
    /** These lamps didn't take a command. */
    data class NoResponse(val names: List<String>) : HomeEvent
}

data class ImportSummary(val added: List<String>, val updated: List<String>, val skipped: List<SkippedDevice>)

/**
 * The app's logic, free of Android: one home's lamps and groups, their live state, and the commands on them.
 * UI layers observe [state] and call the functions; the future hub can reuse the same class.
 */
class HomeController(
    private val store: ConfigStore,
    private val vault: KeyVault,
    private val scope: CoroutineScope,
    private val runtime: HomeRuntime = HomeRuntime(scope),
) {
    private val openedKeys = ConcurrentHashMap<String, String>()
    private val unreadableKeys = ConcurrentHashMap.newKeySet<String>()
    private val sceneRuns = MutableStateFlow(SceneRuns())

    val state: StateFlow<HomeState> = combine(store.config, runtime.snapshots, sceneRuns, ::buildState)
        .stateIn(scope, SharingStarted.Eagerly, buildState(store.config.value, runtime.snapshots.value, sceneRuns.value))

    /**
     * The same as [state], but computed from the latest sources. [state] reaches observers a moment after a
     * change; decisions made right after one (save a scene, then run it) must not see the old values.
     */
    private fun current(): HomeState = buildState(store.config.value, runtime.snapshots.value, sceneRuns.value)

    private val _events = MutableSharedFlow<HomeEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<HomeEvent> = _events

    // While a group's brightness slider is dragged in "keep ratio" mode: where every lamp stood when the drag began.
    private class DragBase(val groupId: String, val levels: Map<String, Int>, val top: Int)

    private var dragBase: DragBase? = null

    init {
        scope.launch {
            store.config.map(::endpoints).distinctUntilChanged().collect(runtime::setDevices)
        }
    }

    /** Connect to the lamps (app came to the foreground). */
    fun start() = runtime.start()

    /** Drop the connections (app went to the background). */
    fun stop() = runtime.stop()

    // --- commands ---

    /** A lamp flips. A group goes off if any of its lamps is on, otherwise all go on. */
    fun toggle(target: Target) {
        switch(target)
    }

    private fun switch(target: Target): Deferred<List<String>> {
        val on = when (target) {
            is Target.Light -> current().lights.firstOrNull { it.id == target.id }?.state?.on == true
            is Target.Group -> current().groups.firstOrNull { it.id == target.id }?.anyOn == true
        }
        return send(target, LightCommand.Power(!on))
    }

    /** Sends [command] to the lamp, or to every reachable lamp of the group at the same moment. */
    fun apply(target: Target, command: LightCommand) {
        send(target, command)
    }

    @Synchronized
    private fun send(target: Target, command: LightCommand): Deferred<List<String>> {
        val perLamp = resolve(target, command)
        dragBase = null
        if (perLamp.isEmpty()) {
            val names = namesOf(target)
            _events.tryEmit(HomeEvent.NoResponse(names))
            return CompletableDeferred(names)
        }
        return dispatch(perLamp, offline = emptySet())
    }

    /**
     * Sends the commands at the same moment and reports lamps that couldn't take theirs ([offline] ones
     * included). The result lists their names; the same names also go out as a [HomeEvent.NoResponse].
     */
    private fun dispatch(perLamp: Map<String, LightCommand>, offline: Set<String>): Deferred<List<String>> {
        val pending = if (perLamp.isEmpty()) CompletableDeferred(emptySet()) else runtime.apply(perLamp)
        val result = CompletableDeferred<List<String>>()
        scope.launch {
            val failed = pending.await() + offline
            val names = current().lights.filter { it.id in failed }.map { it.name }
            if (names.isNotEmpty()) _events.emit(HomeEvent.NoResponse(names))
            result.complete(names)
        }
        return result
    }

    /** Shows [command] on the lamps while a control is being dragged. Finish the drag with [apply]. */
    @Synchronized
    fun preview(target: Target, command: LightCommand) = runtime.preview(resolve(target, command))

    private fun resolve(target: Target, command: LightCommand): Map<String, LightCommand> {
        val now = current()
        return when (target) {
            is Target.Light -> now.lights.filter { it.id == target.id && it.online }.associate { it.id to command }
            is Target.Group -> {
                val group = now.groups.firstOrNull { it.id == target.id } ?: return emptyMap()
                val lamps = group.reachable
                if (command is LightCommand.Brightness && group.relativeBrightness && lamps.isNotEmpty()) {
                    relativeBrightness(group.id, lamps, command.percent)
                } else {
                    lamps.associate { it.id to command }
                }
            }
        }
    }

    /** The brightest lamp follows the slider; the others keep their distance to it in proportion. */
    private fun relativeBrightness(groupId: String, lamps: List<LightItem>, percent: Int): Map<String, LightCommand> {
        val base = dragBase?.takeIf { it.groupId == groupId } ?: DragBase(
            groupId,
            levels = lamps.associate { it.id to it.state!!.brightness },
            top = lamps.maxOf { it.state!!.brightness },
        ).also { dragBase = it }
        return lamps.associate { lamp ->
            val own = base.levels[lamp.id] ?: base.top
            lamp.id to LightCommand.Brightness((own * percent.toDouble() / base.top).roundToInt().coerceIn(1, 100))
        }
    }

    private fun namesOf(target: Target): List<String> = when (target) {
        is Target.Light -> current().lights.filter { it.id == target.id }.map { it.name }
        is Target.Group -> current().groups.firstOrNull { it.id == target.id }?.members.orEmpty().map { it.name }
    }

    /** Ids of the lamps [target] stands for (all members of a group, reachable or not). */
    private fun lampsOf(target: Target): List<String> = when (target) {
        is Target.Light -> listOf(target.id)
        is Target.Group -> current().groups.firstOrNull { it.id == target.id }?.members.orEmpty().map { it.id }
    }

    // --- scenes ---

    /** Applies a scene: its look, the next look of a cycle, or for a toggle that is on, what was there before. */
    @Synchronized
    fun runScene(id: String): Deferred<List<String>> {
        val scene = store.config.value.scenes.firstOrNull { it.id == id } ?: return CompletableDeferred(emptyList())
        val runs = sceneRuns.value
        val before = runs.toggled[id]
        if (scene.style == SceneStyle.TOGGLE && before != null) {
            sceneRuns.value = runs.copy(toggled = emptyMap())
            return send(before.mapValues { (_, state) -> LightCommand.Restore(state) })
        }
        val index = if (scene.style == SceneStyle.CYCLE) (runs.cycle[id] ?: 0) % scene.looks.size.coerceAtLeast(1) else 0
        val look = scene.looks.getOrNull(index) ?: return CompletableDeferred(emptyList())
        val commands = commandsFor(look)
        val lights = current().lights.associateBy { it.id }
        sceneRuns.value = SceneRuns(
            // Running any scene forgets an earlier toggle, so its next tap doesn't undo something unrelated.
            toggled = if (scene.style == SceneStyle.TOGGLE) {
                mapOf(id to commands.keys.mapNotNull { lamp -> lights[lamp]?.state?.let { lamp to it } }.toMap())
            } else {
                emptyMap()
            },
            cycle = if (scene.style == SceneStyle.CYCLE) runs.cycle + (id to (index + 1) % scene.looks.size) else runs.cycle,
        )
        return send(commands)
    }

    /** Shows [look] on the lamps without touching any toggle or cycle (the editor's "Try it"). */
    fun tryLook(look: SceneLook): Deferred<List<String>> = send(commandsFor(look))

    /** What one tap does: run a scene, or switch a lamp or group. */
    fun run(action: Action): Deferred<List<String>> = when (action) {
        is Action.RunScene -> runScene(action.sceneId)
        is Action.Switch -> switch(action.target)
    }

    /**
     * For a tap from outside the app (Quick Settings, a shortcut): connects, waits until the lamps involved
     * answered or [timeoutMs] passed, then runs [action]. Returns the names of lamps that didn't respond.
     */
    suspend fun runWhenReady(action: Action, timeoutMs: Long = 6_000): List<String> {
        start()
        val needed = lampsFor(action)
        withTimeoutOrNull(timeoutMs) {
            state.first { s -> s.lights.filter { it.id in needed }.all { it.online || it.unreachable || it.problem != null } }
        }
        return run(action).await()
    }

    /** Label and on/off state of [action] for a tile; null if what it points at no longer exists. */
    fun describe(action: Action, allLampsName: String): ActionInfo? {
        val now = current()
        return when (action) {
            is Action.RunScene -> now.scenes.firstOrNull { it.id == action.sceneId }?.let { scene ->
                ActionInfo(
                    scene.name,
                    active = scene.toggledOn,
                    detail = scene.look?.name?.takeIf { scene.config.style == SceneStyle.CYCLE && it.isNotBlank() },
                )
            }
            is Action.Switch -> when (val t = action.target) {
                is Target.Light -> now.lights.firstOrNull { it.id == t.id }?.let { ActionInfo(it.name, it.online && it.state?.on == true) }
                is Target.Group -> now.groups.firstOrNull { it.id == t.id }?.let { ActionInfo(if (it.builtIn) allLampsName else it.name, it.anyOn) }
            }
        }
    }

    /** A look with one step per lamp, matching how [lampIds] (default: every reachable lamp) look right now. */
    fun captureLook(lampIds: List<String>? = null): SceneLook {
        val lamps = current().lights.filter { (lampIds == null || it.id in lampIds) && it.online && it.state != null }
        return SceneLook(actions = lamps.map { captureAction(it.id, it.state!!) })
    }

    /** Creates the scene (blank id) or replaces it; returns its id. */
    fun saveScene(scene: SceneConfig): String {
        val saved = if (scene.id.isBlank()) scene.copy(id = newId("s")) else scene
        store.update { config ->
            val scenes = if (config.scenes.any { it.id == saved.id }) config.scenes.map { if (it.id == saved.id) saved else it } else config.scenes + saved
            config.copy(scenes = scenes)
        }
        // An edited toggle or cycle starts afresh.
        sceneRuns.value = sceneRuns.value.let { it.copy(toggled = it.toggled - saved.id, cycle = it.cycle - saved.id) }
        return saved.id
    }

    fun deleteScene(id: String) {
        store.update { config ->
            config.copy(
                scenes = config.scenes.filterNot { it.id == id },
                tiles = config.tiles.filterValues { it != Action.RunScene(id).encode() },
            )
        }
        sceneRuns.value = sceneRuns.value.let { it.copy(toggled = it.toggled - id, cycle = it.cycle - id) }
    }

    /** Adds a few ready-made scenes for all lamps (or the only lamp). */
    fun addStarterScenes(names: StarterNames = StarterNames()) {
        val now = current()
        val target = when {
            now.groups.any { it.id == ALL_LAMPS } -> Target.Group(ALL_LAMPS)
            now.lights.isNotEmpty() -> Target.Light(now.lights.first().id)
            else -> return
        }
        val starters = starterScenes(target.encode(), names) { newId("s") }
        store.update { config -> config.copy(scenes = config.scenes + starters) }
    }

    /** Binds Quick Settings tile [slot] to [action], or frees it. */
    fun bindTile(slot: Int, action: Action?) {
        store.update { config ->
            config.copy(tiles = if (action == null) config.tiles - slot else config.tiles + (slot to action.encode()))
        }
    }

    /** lamp id → its command; a lamp named by several steps (say, via a group and alone) takes the last. */
    private fun commandsFor(look: SceneLook): Map<String, LightCommand> {
        val commands = LinkedHashMap<String, LightCommand>()
        for (action in look.actions) {
            val target = Target.decode(action.target) ?: continue
            for (lamp in lampsOf(target)) commands[lamp] = action.toCommand()
        }
        return commands
    }

    /** Sends to the reachable lamps; the others are reported as not responding. */
    private fun send(commands: Map<String, LightCommand>): Deferred<List<String>> {
        val online = current().lights.filter { it.online }.map { it.id }.toSet()
        return dispatch(commands.filterKeys { it in online }, offline = commands.keys - online)
    }

    private fun lampsFor(action: Action): Set<String> = when (action) {
        is Action.Switch -> lampsOf(action.target).toSet()
        is Action.RunScene -> store.config.value.scenes.firstOrNull { it.id == action.sceneId }?.looks.orEmpty()
            .flatMap { look -> look.actions.mapNotNull { Target.decode(it.target) } }
            .flatMap(::lampsOf).toSet()
    }

    // --- devices ---

    /** Adds the lamps of a devices file (see [DeviceImport]); lamps already known get their key and address refreshed. */
    fun importDevices(text: String): ImportSummary {
        val file = DeviceImport.parse(text)
        val added = mutableListOf<String>()
        val updated = mutableListOf<String>()
        store.update { config ->
            added.clear()
            updated.clear()
            val devices = LinkedHashMap(config.devices.associateBy { it.id })
            for (d in file.devices) {
                val known = devices[d.id]
                devices[d.id] = if (known == null) {
                    added += d.name
                    DeviceConfig(d.id, d.name, d.ip.orEmpty(), sealedKey = vault.seal(d.localKey), category = d.category, product = d.product)
                } else {
                    updated += known.name
                    known.copy(sealedKey = vault.seal(d.localKey), ip = d.ip ?: known.ip)
                }
            }
            config.copy(devices = devices.values.toList())
        }
        return ImportSummary(added, updated, file.skipped)
    }

    fun renameLight(id: String, name: String) {
        val clean = name.trim().ifEmpty { return }
        updateDevice(id) { it.copy(name = clean) }
    }

    /** False if [ip] isn't an IPv4 address. */
    fun setLightAddress(id: String, ip: String): Boolean {
        val clean = ip.trim()
        val parts = clean.split(".")
        if (parts.size != 4 || parts.any { (it.toIntOrNull() ?: -1) !in 0..255 }) return false
        updateDevice(id) { it.copy(ip = clean) }
        return true
    }

    fun removeLight(id: String) {
        val ref = Target.Light(id).encode()
        store.update { config ->
            config.copy(
                devices = config.devices.filterNot { it.id == id },
                groups = config.groups.map { it.copy(members = it.members - id) },
                scenes = config.scenes.map { it.without(ref) },
                tiles = config.tiles.filterValues { it != ref },
            )
        }
    }

    /** The scene minus every step aimed at [ref]. */
    private fun SceneConfig.without(ref: String) =
        copy(looks = looks.map { look -> look.copy(actions = look.actions.filterNot { it.target == ref }) })

    private fun updateDevice(id: String, change: (DeviceConfig) -> DeviceConfig) {
        store.update { config -> config.copy(devices = config.devices.map { if (it.id == id) change(it) else it }) }
    }

    // --- groups & swatches ---

    /** Creates the group ([id] null) or replaces it. For the built-in group only [relativeBrightness] is kept. */
    fun saveGroup(id: String?, name: String, members: List<String>, relativeBrightness: Boolean): String {
        if (id == ALL_LAMPS) {
            store.update { it.copy(allLampsRelativeBrightness = relativeBrightness) }
            return ALL_LAMPS
        }
        val group = GroupConfig(id ?: newId("g"), name.trim(), members.distinct(), relativeBrightness)
        store.update { config ->
            val groups = if (config.groups.any { it.id == group.id }) config.groups.map { if (it.id == group.id) group else it } else config.groups + group
            config.copy(groups = groups)
        }
        return group.id
    }

    fun deleteGroup(id: String) {
        val ref = Target.Group(id).encode()
        store.update { config ->
            config.copy(
                groups = config.groups.filterNot { it.id == id },
                scenes = config.scenes.map { it.without(ref) },
                tiles = config.tiles.filterValues { it != ref },
            )
        }
    }

    fun addSwatch(swatch: Swatch) {
        store.update { config -> if (swatch in config.swatches) config else config.copy(swatches = (config.swatches + swatch).takeLast(MAX_SWATCHES)) }
    }

    fun removeSwatch(swatch: Swatch) {
        store.update { config -> config.copy(swatches = config.swatches - swatch) }
    }

    // --- state ---

    private fun buildState(config: HomeConfig, snapshots: Map<String, DeviceSnapshot>, runs: SceneRuns): HomeState {
        val lights = config.devices.filter { it.category == LIGHT }.map { device ->
            val snapshot = snapshots[device.id]
            val problem = when {
                openKey(device.sealedKey) == null -> LightProblem.KEY_UNREADABLE
                device.ip.isBlank() -> LightProblem.NO_ADDRESS
                else -> null
            }
            LightItem(device.id, device.name, device.ip, snapshot?.online == true, snapshot?.light, problem, snapshot?.unreachable == true)
        }
        val byId = lights.associateBy { it.id }
        val groups = buildList {
            if (lights.size >= 2) add(GroupItem(ALL_LAMPS, "", lights, config.allLampsRelativeBrightness, builtIn = true))
            for (group in config.groups) {
                add(GroupItem(group.id, group.name, group.members.mapNotNull(byId::get), group.relativeBrightness))
            }
        }
        val scenes = config.scenes.map { scene ->
            SceneItem(
                scene,
                toggledOn = scene.id in runs.toggled,
                nextLook = (runs.cycle[scene.id] ?: 0) % scene.looks.size.coerceAtLeast(1),
            )
        }
        val tiles = config.tiles.mapNotNull { (slot, text) -> Action.decode(text)?.let { slot to it } }.toMap()
        return HomeState(lights, groups, config.swatches, scenes, tiles)
    }

    private fun endpoints(config: HomeConfig): List<DeviceEndpoint> = config.devices.mapNotNull { device ->
        val key = openKey(device.sealedKey) ?: return@mapNotNull null
        DeviceEndpoint(device.id, device.ip, device.port, key)
    }

    /** Opening a key asks the platform keystore, so results are remembered. Null if it can't be opened. */
    private fun openKey(sealed: String): String? {
        openedKeys[sealed]?.let { return it }
        if (sealed in unreadableKeys) return null
        return try {
            vault.open(sealed).also { openedKeys[sealed] = it }
        } catch (e: Exception) {
            unreadableKeys += sealed
            null
        }
    }

    private fun newId(prefix: String) = prefix + java.util.UUID.randomUUID().toString().take(8)

    companion object {
        /** Id of the built-in group that always contains every lamp. */
        const val ALL_LAMPS = "all"
        private const val LIGHT = "dj"
        private const val MAX_SWATCHES = 16
    }
}
