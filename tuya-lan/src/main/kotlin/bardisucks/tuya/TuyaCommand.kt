package bardisucks.tuya

/** Tuya LAN command ids (same numbering as TinyTuya's command_types.py). */
object TuyaCommand {
    const val SESS_KEY_NEG_START = 0x03
    const val SESS_KEY_NEG_RESP = 0x04
    const val SESS_KEY_NEG_FINISH = 0x05
    const val CONTROL = 0x07
    const val STATUS = 0x08
    const val HEART_BEAT = 0x09
    const val DP_QUERY = 0x0A
    const val CONTROL_NEW = 0x0D
    const val DP_QUERY_NEW = 0x10
    const val UPDATEDPS = 0x12
}
