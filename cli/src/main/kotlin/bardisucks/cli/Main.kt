package bardisucks.cli

import bardisucks.core.Hsv
import bardisucks.core.LightCodec
import bardisucks.tuya.TuyaDevice
import bardisucks.tuya.TuyaException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.time.LocalTime
import kotlin.system.exitProcess

private const val USAGE = """
Usage: bardi [options] <command> <device> [args]

<device> is part of a device name, a device id, or "all". Commands run on every
matching device at once, so "bardi color all 200" colours both lamps together.

Commands:
  list                                   devices in the devices file (no keys shown)
  status     <device>                    print every DP
  on | off | toggle <device>             toggle: if any is on, all go off
  brightness <device> <1-100>            keeps the current white/colour mode
  white      <device> <1-100> <warmth 0-100>
  color      <device> <hue 0-360> [saturation 0-100] [brightness 1-100]
  sweep      <device>                    live-preview rainbow (DP 28), then restore
  effect     <device> <scene hex>        run an on-bulb effect (DP 25)
  watch      <device>                    print status pushes until Ctrl+C

Options:
  --devices <file>   default: secrets/devices.json (from tools/phase0/get_keys.py)
  --ip <address>     LAN IP, if the devices file doesn't have one (single device only)
  --id <id> --key <local key> [--ip ...]   use a device that isn't in the file
"""

private data class Target(val name: String, val id: String, val ip: String?, val key: String, val version: String)

fun main(args: Array<String>) {
    val opts = mutableMapOf<String, String>()
    val rest = mutableListOf<String>()
    var i = 0
    while (i < args.size) {
        val a = args[i]
        if (a.startsWith("--")) {
            opts[a.removePrefix("--")] = args.getOrNull(i + 1) ?: fail("Missing value for $a")
            i += 2
        } else {
            rest += a
            i += 1
        }
    }
    val command = rest.getOrNull(0) ?: fail(USAGE)
    val targets = loadTargets(opts)
    if (command == "list") {
        targets.forEach { println("${it.name.padEnd(32)} id=${it.id.take(4)}...  ip=${it.ip ?: "?"}  v${it.version}") }
        return
    }
    val selector = rest.getOrNull(1) ?: fail("Which device? $USAGE")
    // With --id there is exactly one device, so the <device> word doesn't need to match it.
    val chosen = select(targets, if ("id" in opts) "all" else selector, opts["ip"])
    val params = rest.drop(2)
    val ok = runBlocking { run(command, chosen, params) }
    exitProcess(if (ok) 0 else 1)
}

private fun loadTargets(opts: Map<String, String>): List<Target> {
    opts["id"]?.let { id ->
        val key = opts["key"] ?: fail("--id needs --key")
        return listOf(Target(opts["name"] ?: id, id, opts["ip"], key, opts["version"] ?: "3.5"))
    }
    val file = File(opts["devices"] ?: "secrets/devices.json")
    if (!file.exists()) fail("${file.path} not found. Run tools/phase0/get_keys.py first, or pass --id/--key/--ip.")
    val root = Json.parseToJsonElement(file.readText())
    val list = if (root is JsonObject) root["devices"]!!.jsonArray else root.jsonArray
    return list.map { it.jsonObject }.mapNotNull { d ->
        val key = (d["local_key"] ?: d["key"])?.jsonPrimitive?.content?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        Target(
            name = d["name"]?.jsonPrimitive?.content ?: "?",
            id = d["id"]!!.jsonPrimitive.content,
            ip = d["lan_ip"]?.jsonPrimitive?.content,
            key = key,
            version = d["version"]?.jsonPrimitive?.content ?: "3.5",
        )
    }
}

private fun select(targets: List<Target>, selector: String, ip: String?): List<Target> {
    val chosen = if (selector == "all") targets else targets.filter {
        it.id == selector || it.name.contains(selector, ignoreCase = true)
    }
    if (chosen.isEmpty()) fail("No device matches '$selector'. Try: bardi list")
    if (ip != null) {
        if (chosen.size > 1) fail("--ip only works with a single device ('$selector' matched ${chosen.size})")
        return listOf(chosen.single().copy(ip = ip))
    }
    chosen.filter { it.ip == null }.takeIf { it.isNotEmpty() }?.let { missing ->
        fail("No LAN IP for ${missing.joinToString { it.name }}. Pass --ip, or run tools/phase0/probe.py --no-tests to save IPs.")
    }
    chosen.filter { it.version != "3.5" }.takeIf { it.isNotEmpty() }?.let { old ->
        fail("Protocol ${old.first().version} isn't supported yet (only 3.5): ${old.joinToString { it.name }}")
    }
    return chosen
}

private suspend fun run(command: String, targets: List<Target>, params: List<String>): Boolean = coroutineScope {
    val devices = targets.map { TuyaDevice(it.id, it.ip!!, it.key, keepAliveMs = if (command == "watch") 12_000 else null) }
    try {
        // Connect to all at once so group commands land together.
        val connected = devices.zip(targets).map { (d, t) ->
            async { runCatching { d.connect() }.onFailure { println("${t.name}: ${it.message}") }.isSuccess }
        }.awaitAll()
        val live = devices.zip(targets).filterIndexed { idx, _ -> connected[idx] }
        if (live.isEmpty()) return@coroutineScope false

        if (command == "toggle") {
            val anyOn = live.map { (d, _) -> async { d.status()["20"]?.jsonPrimitive?.boolean == true } }.awaitAll().any { it }
            return@coroutineScope each(live) { d, _ -> d.set(mapOf("20" to !anyOn)); if (anyOn) "off" else "on" }
        }
        if (command == "watch") return@coroutineScope watch(live)

        each(live) { d, _ ->
            when (command) {
                "status" -> d.status().entries.joinToString("\n", prefix = "\n") { (k, v) -> "  $k = $v" }
                "on", "off" -> { d.set(mapOf("20" to (command == "on"))); command }
                "brightness" -> brightness(d, intParam(params, 0, "brightness", 1..100))
                "white" -> {
                    val level = LightCodec.percentToLevel(intParam(params, 0, "brightness", 1..100))
                    val temp = LightCodec.percentToTemp(intParam(params, 1, "warmth", 0..100))
                    d.set(mapOf("20" to true, "21" to "white", "22" to level, "23" to temp))
                    "white $level/$temp"
                }
                "color", "colour" -> {
                    val hsv = Hsv(
                        intParam(params, 0, "hue", 0..360),
                        (params.getOrNull(1)?.let { intParam(params, 1, "saturation", 0..100) } ?: 100) * 10,
                        LightCodec.percentToLevel(params.getOrNull(2)?.let { intParam(params, 2, "brightness", 1..100) } ?: 100),
                    )
                    d.set(mapOf("20" to true, "21" to "colour", "24" to LightCodec.encodeColour(hsv)))
                    "colour ${LightCodec.encodeColour(hsv)}"
                }
                "sweep" -> sweep(d)
                "effect" -> {
                    val scene = params.getOrNull(0) ?: fail("effect needs a scene hex string")
                    LightCodec.decodeScene(scene) // validates
                    d.set(mapOf("20" to true, "21" to "scene", "25" to scene))
                    "effect set"
                }
                else -> fail("Unknown command '$command'.$USAGE")
            }
        }
    } finally {
        devices.forEach { it.close() }
    }
}

/** Runs [action] on every device in parallel and prints one line per device. */
private suspend fun each(live: List<Pair<TuyaDevice, Target>>, action: suspend (TuyaDevice, Target) -> String): Boolean =
    coroutineScope {
        live.map { (d, t) ->
            async {
                try {
                    println("${t.name}: ${action(d, t)}")
                    true
                } catch (e: TuyaException) {
                    println("${t.name}: ERROR ${e.message}")
                    false
                }
            }
        }.awaitAll().all { it }
    }

/** Brightness lives in DP 22 in white mode but in the V of DP 24 in colour mode. */
private suspend fun brightness(d: TuyaDevice, percent: Int): String {
    val level = LightCodec.percentToLevel(percent)
    val dps = d.status()
    return if (dps["21"]?.jsonPrimitive?.content == "colour") {
        val hsv = LightCodec.decodeColour(dps["24"]!!.jsonPrimitive.content)
        d.set(mapOf("20" to true, "24" to LightCodec.encodeColour(hsv.copy(v = level))))
        "colour brightness $level"
    } else {
        d.set(mapOf("20" to true, "21" to "white", "22" to level))
        "white brightness $level"
    }
}

private suspend fun sweep(d: TuyaDevice): String {
    val saved = d.status().filterKeys { it in setOf("20", "21", "22", "23", "24") }
    d.set(mapOf("20" to true, "21" to "colour"))
    for (h in 0..360 step 12) {
        d.setNoWait(mapOf("28" to LightCodec.encodeLive(Hsv(h, 1000, 1000))))
        delay(200)
    }
    d.set(saved)
    return "swept and restored"
}

private suspend fun watch(live: List<Pair<TuyaDevice, Target>>): Boolean = coroutineScope {
    live.forEach { (d, t) ->
        println("${t.name}: ${d.status()}")
        launch { d.updates.collect { println("${LocalTime.now().withNano(0)} ${t.name}: $it") } }
    }
    println("Watching for changes. Flip a switch or use another app. Ctrl+C to stop.")
    while (live.any { it.first.state.value == TuyaDevice.State.CONNECTED }) delay(1_000)
    println("Disconnected.")
    true
}

private fun intParam(params: List<String>, index: Int, name: String, range: IntRange): Int {
    val v = params.getOrNull(index)?.toIntOrNull() ?: fail("Missing or invalid $name (${range.first}–${range.last})")
    if (v !in range) fail("$name must be ${range.first}–${range.last}")
    return v
}

private fun fail(message: String): Nothing {
    System.err.println(message.trim())
    exitProcess(2)
}
