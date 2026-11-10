package bardisucks.core.home

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

@Serializable
data class DeviceConfig(
    val id: String,
    val name: String,
    /** LAN address; blank until the user fills it in. */
    val ip: String,
    val port: Int = 6668,
    val version: String = "3.5",
    /** The device's local_key, sealed by the platform [KeyVault]. Never stored in plain text. */
    val sealedKey: String,
    val category: String = "dj",
    val product: String = "",
)

@Serializable
data class GroupConfig(
    val id: String,
    val name: String,
    val members: List<String>,
    /** Group brightness keeps the ratio between the lamps instead of setting them all to the same level. */
    val relativeBrightness: Boolean = false,
)

/** A saved colour: hue 0–360, saturation 0–100. */
@Serializable
data class Swatch(val hue: Int, val saturation: Int)

/** Everything the app remembers about a home. One JSON document, so it doubles as the backup/export format. */
@Serializable
data class HomeConfig(
    val schema: Int = 1,
    val devices: List<DeviceConfig> = emptyList(),
    val groups: List<GroupConfig> = emptyList(),
    /** [GroupConfig.relativeBrightness] for the built-in group of all lamps. */
    val allLampsRelativeBrightness: Boolean = false,
    val swatches: List<Swatch> = DEFAULT_SWATCHES,
) {
    companion object {
        val DEFAULT_SWATCHES = listOf(
            Swatch(0, 100), Swatch(28, 100), Swatch(50, 100), Swatch(120, 100),
            Swatch(180, 100), Swatch(225, 100), Swatch(275, 100), Swatch(325, 80),
        )
    }
}

/** Seals secrets with a key that never leaves the device (Android Keystore in the app). */
interface KeyVault {
    fun seal(secret: String): String

    /** Throws if [sealed] can't be opened, e.g. after the app's data was restored onto another phone. */
    fun open(sealed: String): String
}

/** Keeps [HomeConfig] in a JSON file. Writes are atomic, so a crash can't leave half a file. */
class ConfigStore(private val file: File) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }
    private val _config = MutableStateFlow(load())
    val config: StateFlow<HomeConfig> = _config

    @Synchronized
    fun update(transform: (HomeConfig) -> HomeConfig): HomeConfig {
        val next = transform(_config.value)
        if (next != _config.value) {
            write(next)
            _config.value = next
        }
        return next
    }

    private fun load(): HomeConfig {
        if (!file.exists()) return HomeConfig()
        return try {
            json.decodeFromString(HomeConfig.serializer(), file.readText())
        } catch (e: Exception) {
            // Keep the unreadable file for a later rescue instead of overwriting it with an empty home.
            file.copyTo(File(file.path + ".unreadable"), overwrite = true)
            HomeConfig()
        }
    }

    private fun write(config: HomeConfig) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(json.encodeToString(HomeConfig.serializer(), config))
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}
