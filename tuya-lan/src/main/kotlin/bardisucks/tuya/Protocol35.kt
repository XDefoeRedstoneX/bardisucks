package bardisucks.tuya

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** Payload formats and session-key math for protocol 3.5 (ported from TinyTuya). */
internal object Protocol35 {
    val VERSION_HEADER: ByteArray = "3.5".toByteArray() + ByteArray(12)

    private val NO_VERSION_HEADER = setOf(
        TuyaCommand.DP_QUERY, TuyaCommand.DP_QUERY_NEW, TuyaCommand.UPDATEDPS, TuyaCommand.HEART_BEAT,
        TuyaCommand.SESS_KEY_NEG_START, TuyaCommand.SESS_KEY_NEG_RESP, TuyaCommand.SESS_KEY_NEG_FINISH,
    )

    /** Devices ignore JSON with spaces, so always encode compactly. */
    private val json = Json { encodeDefaults = true }

    fun payload(cmd: Int, body: JsonObject): ByteArray {
        val bytes = json.encodeToString(JsonObject.serializer(), body).toByteArray()
        return if (cmd in NO_VERSION_HEADER) bytes else VERSION_HEADER + bytes
    }

    fun control(dps: JsonObject, timeSeconds: Long): JsonObject = buildJsonObject {
        put("protocol", 5)
        put("t", timeSeconds)
        put("data", buildJsonObject { put("dps", dps) })
    }

    fun heartbeat(deviceId: String): JsonObject = buildJsonObject {
        put("gwId", deviceId)
        put("devId", deviceId)
    }

    /** Parses a device payload (version header optional). Returns null for empty acks. */
    fun decode(payload: ByteArray): JsonObject? {
        var body = payload
        if (body.size >= VERSION_HEADER.size && body.copyOfRange(0, 3).contentEquals(VERSION_HEADER.copyOfRange(0, 3))) {
            body = body.copyOfRange(VERSION_HEADER.size, body.size)
        }
        val text = body.toString(Charsets.UTF_8).trim()
        if (text.isEmpty()) return null
        return try {
            json.parseToJsonElement(text).jsonObject
        } catch (e: IllegalArgumentException) {
            throw TuyaException("Device sent something that isn't JSON: $text", e)
        }
    }

    /** DPs live at `dps` (older replies) or `data.dps` (3.4+ replies). */
    fun dps(body: JsonObject?): JsonObject? =
        (body?.get("dps") ?: (body?.get("data") as? JsonObject)?.get("dps")) as? JsonObject

    // Session-key handshake: we send a nonce, the device answers with its nonce + HMAC(localKey, ourNonce),
    // we answer HMAC(localKey, deviceNonce). Session key = AES-GCM(localKey, iv = ourNonce[0..12], ourNonce xor deviceNonce).
    fun deviceProof(localKey: ByteArray, localNonce: ByteArray) = Crypto.hmacSha256(localKey, localNonce)

    fun clientProof(localKey: ByteArray, remoteNonce: ByteArray) = Crypto.hmacSha256(localKey, remoteNonce)

    fun sessionKey(localKey: ByteArray, localNonce: ByteArray, remoteNonce: ByteArray): ByteArray {
        val xored = ByteArray(16) { (localNonce[it].toInt() xor remoteNonce[it].toInt()).toByte() }
        return Crypto.gcmEncrypt(localKey, localNonce.copyOfRange(0, 12), xored).copyOfRange(0, 16)
    }
}
