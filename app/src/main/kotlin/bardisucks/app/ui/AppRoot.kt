package bardisucks.app.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import bardisucks.app.R
import bardisucks.core.home.HomeController
import bardisucks.core.home.HomeEvent
import bardisucks.core.home.HomeState
import bardisucks.core.home.ImportException
import bardisucks.core.home.Swatch
import bardisucks.core.home.Target
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Everything the screens can change besides switching lamps. Implemented by [HomeController]. */
class HomeEdits(
    val saveGroup: (id: String?, name: String, members: List<String>, relativeBrightness: Boolean) -> String,
    val deleteGroup: (id: String) -> Unit,
    val renameLight: (id: String, name: String) -> Unit,
    val setLightAddress: (id: String, ip: String) -> Boolean,
    val removeLight: (id: String) -> Unit,
    val addSwatch: (Swatch) -> Unit,
    val removeSwatch: (Swatch) -> Unit,
)

private sealed interface Screen {
    data class Light(val target: Target) : Screen
    data class EditGroup(val id: String?) : Screen
    data object Settings : Screen
}

/** From this width on (tablets, landscape) the home screen stays visible next to whatever is opened. */
private val TWO_PANE_WIDTH = 840.dp
private val HOME_PANE_WIDTH = 400.dp

private const val MAX_IMPORT_BYTES = 2_000_000

/** The app wired to its [controller]: live state, the file picker, and messages. */
@Composable
fun AppRoot(controller: HomeController, version: String) {
    val state by controller.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var importOutcome by remember { mutableStateOf<ImportOutcome?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { importOutcome = importDevices(context, uri, controller) }
    }
    val noResponse = stringResource(R.string.no_response)
    LaunchedEffect(controller) {
        controller.events.collect { event ->
            when (event) {
                is HomeEvent.NoResponse -> snackbar.showSnackbar(noResponse.format(event.names.joinToString()))
            }
        }
    }

    AppContent(
        state = state,
        actions = remember(controller) { LightActions(controller::toggle, controller::apply, controller::preview) },
        edits = remember(controller) {
            HomeEdits(
                controller::saveGroup, controller::deleteGroup, controller::renameLight, controller::setLightAddress,
                controller::removeLight, controller::addSwatch, controller::removeSwatch,
            )
        },
        version = version,
        snackbar = snackbar,
        // Some file managers label .json files oddly, so don't filter by type.
        onImport = { picker.launch(arrayOf("*/*")) },
    )
    importOutcome?.let { ImportResultDialog(it, onDismiss = { importOutcome = null }) }
}

private suspend fun importDevices(context: Context, uri: Uri, controller: HomeController): ImportOutcome =
    withContext(Dispatchers.IO) {
        try {
            val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(MAX_IMPORT_BYTES + 1)
                var size = 0
                while (size < buffer.size) {
                    val read = input.read(buffer, size, buffer.size - size)
                    if (read < 0) break
                    size += read
                }
                buffer.copyOf(size)
            }
            when {
                bytes == null -> ImportOutcome.Failed(context.getString(R.string.import_unreadable))
                bytes.size > MAX_IMPORT_BYTES -> ImportOutcome.Failed(context.getString(R.string.import_too_big))
                else -> ImportOutcome.Done(controller.importDevices(bytes.decodeToString()))
            }
        } catch (e: ImportException) {
            ImportOutcome.Failed(e.message.orEmpty())
        } catch (e: Exception) {
            // Name the cause: on an unfamiliar phone this is how a keystore or file-access problem gets diagnosed.
            ImportOutcome.Failed(context.getString(R.string.import_unreadable) + "\n(${e.javaClass.simpleName}: ${e.message})")
        }
    }

/** Navigation between the screens; on wide windows the home screen stays on the left. */
@Composable
fun AppContent(
    state: HomeState,
    actions: LightActions,
    edits: HomeEdits,
    version: String,
    snackbar: SnackbarHostState,
    onImport: () -> Unit,
    /** Which lamp or group to open at start (used by previews and tests). */
    initial: Target? = null,
) {
    val stack = remember { mutableStateListOf<Screen>().apply { if (initial != null) add(Screen.Light(initial)) } }
    var editingLight by remember { mutableStateOf<String?>(null) }
    fun back() {
        if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
    }
    BackHandler(enabled = stack.isNotEmpty()) { back() }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val twoPane = maxWidth >= TWO_PANE_WIDTH
            val top = stack.lastOrNull()

            @Composable
            fun Home(modifier: Modifier = Modifier) = HomeScreen(
                state = state,
                selected = (top as? Screen.Light)?.target.takeIf { twoPane },
                onOpen = { target ->
                    if (twoPane) stack.clear()
                    stack += Screen.Light(target)
                },
                onToggle = actions.toggle,
                onNewGroup = {
                    if (twoPane) stack.clear()
                    stack += Screen.EditGroup(null)
                },
                onSettings = {
                    if (twoPane) stack.clear()
                    stack += Screen.Settings
                },
                onImport = onImport,
                modifier = modifier,
            )

            @Composable
            fun Detail(screen: Screen, modifier: Modifier = Modifier) {
                when (screen) {
                    is Screen.Light -> {
                        val light = state.controlled(screen.target)
                        if (light == null) {
                            LaunchedEffect(screen) { stack.remove(screen) } // the lamp or group was removed
                        } else {
                            LightScreen(
                                light = light,
                                swatches = state.swatches,
                                actions = actions,
                                onBack = if (twoPane && stack.size == 1) null else ::back,
                                onEdit = {
                                    when (val target = screen.target) {
                                        is Target.Light -> editingLight = target.id
                                        is Target.Group -> stack += Screen.EditGroup(target.id)
                                    }
                                },
                                onOpenMember = { stack += Screen.Light(it) },
                                onAddSwatch = edits.addSwatch,
                                onRemoveSwatch = edits.removeSwatch,
                                modifier = modifier,
                            )
                        }
                    }
                    is Screen.EditGroup -> {
                        val group = state.groups.firstOrNull { it.id == screen.id }
                        GroupEditorScreen(
                            group = group,
                            lights = state.lights,
                            onSave = { name, members, relative ->
                                val id = edits.saveGroup(screen.id, name, members, relative)
                                stack.remove(screen)
                                if (screen.id == null) stack += Screen.Light(Target.Group(id))
                            },
                            onDelete = if (group != null && !group.builtIn) {
                                {
                                    edits.deleteGroup(group.id)
                                    stack.removeAll { it == screen || it == Screen.Light(Target.Group(group.id)) }
                                }
                            } else {
                                null
                            },
                            onBack = ::back,
                            modifier = modifier,
                        )
                    }
                    Screen.Settings -> SettingsScreen(
                        lights = state.lights,
                        version = version,
                        onImport = onImport,
                        onEditLight = { editingLight = it.id },
                        onBack = ::back,
                        modifier = modifier,
                    )
                }
            }

            if (twoPane) {
                Row(Modifier.fillMaxSize()) {
                    Home(Modifier.width(HOME_PANE_WIDTH).fillMaxHeight())
                    VerticalDivider()
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        if (top != null) {
                            Detail(top)
                        } else {
                            Text(
                                stringResource(R.string.pick_a_lamp),
                                Modifier.align(Alignment.Center),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            } else if (top != null) {
                Detail(top)
            } else {
                Home()
            }

            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 8.dp))
        }
    }

    state.lights.firstOrNull { it.id == editingLight }?.let { lamp ->
        LightEditDialog(
            lamp = lamp,
            onSave = { name, ip ->
                edits.renameLight(lamp.id, name)
                // A lamp imported without an address may keep none; anything typed has to be a valid one.
                ip == lamp.ip || edits.setLightAddress(lamp.id, ip)
            },
            onRemove = {
                editingLight = null
                edits.removeLight(lamp.id)
            },
            onDismiss = { editingLight = null },
        )
    }
}
