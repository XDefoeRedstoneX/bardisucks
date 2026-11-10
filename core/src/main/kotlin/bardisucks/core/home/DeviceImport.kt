package bardisucks.core.home

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

data class ImportedDevice(
    val id: String,
    val name: String,
    val localKey: String,
    /** LAN address, or null if the file doesn't have one. */
    val ip: String?,
    val category: String,
    val product: String,
)

enum class SkipReason { NO_KEY, NOT_A_LIGHT, UNSUPPORTED_PROTOCOL }

data class SkippedDevice(val name: String, val reason: SkipReason)

data class ImportedFile(val devices: List<ImportedDevice>, val skipped: List<SkippedDevice>)

class ImportException(message: String) : Exception(message)

/**
 * Reads the devices file written by tools/phase0/get_keys.py + probe.py (`{"devices": [...]}` with `local_key`
 * and `lan_ip`), or TinyTuya's devices.json (a bare list with `key`).
 */
object DeviceImport {
    private const val SUPPORTED_PROTOCOL = "3.5"
    private const val LIGHT = "dj"
    private const val KEY_LENGTH = 16

    fun parse(text: String): ImportedFile {
        val root = try {
            Json.parseToJsonElement(text)
        } catch (e: Exception) {
            throw ImportException("This isn't a devices file (not valid JSON).")
        }
        val entries = when (root) {
            is JsonArray -> root
            is JsonObject -> root["devices"] as? JsonArray
            else -> null
        } ?: throw ImportException("This isn't a devices file (no device list inside).")

        val devices = mutableListOf<ImportedDevice>()
        val skipped = mutableListOf<SkippedDevice>()
        for (entry in entries) {
            val d = entry as? JsonObject ?: continue
            val id = d.text("id") ?: continue
            val name = d.text("name") ?: id
            val key = d.text("local_key") ?: d.text("key")
            val category = d.text("category")
            val version = d.text("version")
            when {
                key == null || key.length != KEY_LENGTH -> skipped += SkippedDevice(name, SkipReason.NO_KEY)
                category != null && category != LIGHT -> skipped += SkippedDevice(name, SkipReason.NOT_A_LIGHT)
                version != null && version != SUPPORTED_PROTOCOL -> skipped += SkippedDevice(name, SkipReason.UNSUPPORTED_PROTOCOL)
                else -> devices += ImportedDevice(
                    id = id,
                    name = name,
                    localKey = key,
                    // TinyTuya's "ip" can be the home's public address as seen by the cloud; only trust LAN ranges.
                    ip = d.text("lan_ip") ?: d.text("ip")?.takeIf(::isLanAddress),
                    category = category ?: LIGHT,
                    product = d.text("product_name") ?: "",
                )
            }
        }
        if (devices.isEmpty() && skipped.isEmpty()) throw ImportException("The file has no devices in it.")
        return ImportedFile(devices, skipped)
    }

    fun isLanAddress(ip: String): Boolean {
        val parts = ip.split(".").map { it.toIntOrNull() ?: return false }
        if (parts.size != 4 || parts.any { it !in 0..255 }) return false
        return parts[0] == 10 || (parts[0] == 172 && parts[1] in 16..31) || (parts[0] == 192 && parts[1] == 168)
    }

    private fun JsonObject.text(name: String): String? =
        (this[name] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content?.takeIf { it.isNotBlank() }
}

/** DP values as plain Kotlin values (Boolean, Int, String), which is what [bardisucks.core.LightProfile] reads. */
internal fun Map<String, JsonElement>.toPlainMap(): Map<String, Any?> = mapValues { (_, v) ->
    val p = (v as? JsonPrimitive)?.takeUnless { it is JsonNull }
    when {
        p == null -> null
        p.isString -> p.content
        else -> p.booleanOrNull ?: p.intOrNull ?: p.doubleOrNull ?: p.content
    }
}
