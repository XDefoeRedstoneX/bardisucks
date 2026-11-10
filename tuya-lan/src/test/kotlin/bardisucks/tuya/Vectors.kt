package bardisucks.tuya

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Golden vectors produced by TinyTuya (tools/dev/gen_test_vectors.py). */
object Vectors {
    private val root: JsonObject = Json.parseToJsonElement(
        Vectors::class.java.getResource("/tuya35-vectors.json")!!.readText(),
    ).jsonObject

    val key = hex(root.str("key"))
    val iv = hex(root.str("iv"))

    data class FrameVector(val name: String, val seq: Int, val cmd: Int, val payload: ByteArray, val retcode: Int?, val frame: ByteArray)

    val frames = root["frames"]!!.jsonArray.map {
        val o = it.jsonObject
        FrameVector(
            o.str("name"), o["seq"]!!.jsonPrimitive.int, o["cmd"]!!.jsonPrimitive.int,
            hex(o.str("payload")), o["retcode"]!!.jsonPrimitive.intOrNull, hex(o.str("frame")),
        )
    }

    val session: JsonObject = root["session"]!!.jsonObject

    fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    fun JsonObject.str(name: String) = this[name]!!.jsonPrimitive.content
}
