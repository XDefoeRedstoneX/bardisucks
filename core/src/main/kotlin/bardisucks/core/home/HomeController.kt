package bardisucks.core.home

import bardisucks.core.LightCommand
import bardisucks.core.LightState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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
)

/** What a command is aimed at. */
sealed interface Target {
    val id: String

    data class Light(override val id: String) : Target
    data class Group(override val id: String) : Target
}

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

    val state: StateFlow<HomeState> = combine(store.config, runtime.snapshots, ::buildState)
        .stateIn(scope, SharingStarted.Eagerly, buildState(store.config.value, runtime.snapshots.value))

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
        val on = when (target) {
            is Target.Light -> state.value.lights.firstOrNull { it.id == target.id }?.state?.on == true
            is Target.Group -> state.value.groups.firstOrNull { it.id == target.id }?.anyOn == true
        }
        apply(target, LightCommand.Power(!on))
    }

    /** Sends [command] to the lamp, or to every reachable lamp of the group at the same moment. */
    @Synchronized
    fun apply(target: Target, command: LightCommand) {
        val perLamp = resolve(target, command)
        dragBase = null
        if (perLamp.isEmpty()) {
            _events.tryEmit(HomeEvent.NoResponse(namesOf(target)))
            return
        }
        val pending = runtime.apply(perLamp)
        scope.launch {
            val failed = pending.await()
            if (failed.isNotEmpty()) _events.emit(HomeEvent.NoResponse(state.value.lights.filter { it.id in failed }.map { it.name }))
        }
    }

    /** Shows [command] on the lamps while a control is being dragged. Finish the drag with [apply]. */
    @Synchronized
    fun preview(target: Target, command: LightCommand) = runtime.preview(resolve(target, command))

    private fun resolve(target: Target, command: LightCommand): Map<String, LightCommand> {
        val now = state.value
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
        is Target.Light -> state.value.lights.filter { it.id == target.id }.map { it.name }
        is Target.Group -> state.value.groups.firstOrNull { it.id == target.id }?.members.orEmpty().map { it.name }
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
        store.update { config ->
            config.copy(
                devices = config.devices.filterNot { it.id == id },
                groups = config.groups.map { it.copy(members = it.members - id) },
            )
        }
    }

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
        val group = GroupConfig(id ?: newId(), name.trim(), members.distinct(), relativeBrightness)
        store.update { config ->
            val groups = if (config.groups.any { it.id == group.id }) config.groups.map { if (it.id == group.id) group else it } else config.groups + group
            config.copy(groups = groups)
        }
        return group.id
    }

    fun deleteGroup(id: String) {
        store.update { config -> config.copy(groups = config.groups.filterNot { it.id == id }) }
    }

    fun addSwatch(swatch: Swatch) {
        store.update { config -> if (swatch in config.swatches) config else config.copy(swatches = (config.swatches + swatch).takeLast(MAX_SWATCHES)) }
    }

    fun removeSwatch(swatch: Swatch) {
        store.update { config -> config.copy(swatches = config.swatches - swatch) }
    }

    // --- state ---

    private fun buildState(config: HomeConfig, snapshots: Map<String, DeviceSnapshot>): HomeState {
        val lights = config.devices.filter { it.category == LIGHT }.map { device ->
            val snapshot = snapshots[device.id]
            val problem = when {
                openKey(device.sealedKey) == null -> LightProblem.KEY_UNREADABLE
                device.ip.isBlank() -> LightProblem.NO_ADDRESS
                else -> null
            }
            LightItem(device.id, device.name, device.ip, snapshot?.online == true, snapshot?.light, problem)
        }
        val byId = lights.associateBy { it.id }
        val groups = buildList {
            if (lights.size >= 2) add(GroupItem(ALL_LAMPS, "", lights, config.allLampsRelativeBrightness, builtIn = true))
            for (group in config.groups) {
                add(GroupItem(group.id, group.name, group.members.mapNotNull(byId::get), group.relativeBrightness))
            }
        }
        return HomeState(lights, groups, config.swatches)
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

    private fun newId() = "g" + java.util.UUID.randomUUID().toString().take(8)

    companion object {
        /** Id of the built-in group that always contains every lamp. */
        const val ALL_LAMPS = "all"
        private const val LIGHT = "dj"
        private const val MAX_SWATCHES = 16
    }
}
