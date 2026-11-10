package bardisucks.tuya

import java.io.InputStream
import java.nio.ByteBuffer

/** One protocol message. Frames from a device carry a [retcode]; frames we send don't. */
class Frame(
    val seq: Int,
    val cmd: Int,
    val payload: ByteArray,
    val retcode: Int? = null,
)

/**
 * The 0x6699 frame format used by protocol 3.5:
 *
 * ```
 * 00006699 | 0000 | seq u32 | cmd u32 | length u32 | iv[12] | AES-GCM(retcode? + payload) | tag[16] | 00009966
 * ```
 * `length` counts iv + ciphertext + tag. The 14 header bytes after the prefix are the GCM associated data.
 */
object Frame6699 {
    const val PREFIX = 0x00006699
    const val SUFFIX = 0x00009966
    const val HEADER_SIZE = 18
    private const val IV_SIZE = 12
    private const val TAG_SIZE = 16
    private const val MAX_LENGTH = 64 * 1024

    fun encode(frame: Frame, key: ByteArray, iv: ByteArray = Crypto.randomBytes(IV_SIZE)): ByteArray {
        require(iv.size == IV_SIZE) { "IV must be $IV_SIZE bytes" }
        val plaintext = frame.retcode?.let { ByteBuffer.allocate(4).putInt(it).array() + frame.payload } ?: frame.payload
        val header = ByteBuffer.allocate(HEADER_SIZE)
            .putInt(PREFIX)
            .putShort(0)
            .putInt(frame.seq)
            .putInt(frame.cmd)
            .putInt(IV_SIZE + plaintext.size + TAG_SIZE)
            .array()
        val sealed = Crypto.gcmEncrypt(key, iv, plaintext, aad = header.copyOfRange(4, HEADER_SIZE))
        return header + iv + sealed + ByteBuffer.allocate(4).putInt(SUFFIX).array()
    }

    /** Total frame size in bytes, read from its first [HEADER_SIZE] bytes. */
    fun totalLength(header: ByteArray): Int {
        val buf = ByteBuffer.wrap(header, 0, HEADER_SIZE)
        if (buf.getInt() != PREFIX) throw TuyaException("Not a 0x6699 frame (unsupported protocol version?)")
        val length = buf.getInt(14)
        if (length < IV_SIZE + TAG_SIZE || length > MAX_LENGTH) throw TuyaException("Implausible frame length $length")
        return HEADER_SIZE + length + 4
    }

    /** Decrypts a complete frame. Throws [TuyaException] if the key is wrong (GCM tag mismatch). */
    fun decode(bytes: ByteArray, key: ByteArray, hasRetcode: Boolean): Frame {
        val total = totalLength(bytes)
        if (bytes.size < total) throw TuyaException("Truncated frame: ${bytes.size} of $total bytes")
        val buf = ByteBuffer.wrap(bytes)
        val seq = buf.getInt(6)
        val cmd = buf.getInt(10)
        if (buf.getInt(total - 4) != SUFFIX) throw TuyaException("Bad frame suffix")
        val iv = bytes.copyOfRange(HEADER_SIZE, HEADER_SIZE + IV_SIZE)
        val sealed = bytes.copyOfRange(HEADER_SIZE + IV_SIZE, total - 4)
        val plaintext = try {
            Crypto.gcmDecrypt(key, iv, sealed, aad = bytes.copyOfRange(4, HEADER_SIZE))
        } catch (e: javax.crypto.AEADBadTagException) {
            throw TuyaException("Frame failed to decrypt (wrong local key?)", e)
        }
        return if (hasRetcode && plaintext.size >= 4) {
            Frame(seq, cmd, plaintext.copyOfRange(4, plaintext.size), ByteBuffer.wrap(plaintext).getInt(0))
        } else {
            Frame(seq, cmd, plaintext)
        }
    }
}

class TuyaException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Like InputStream.readNBytes (which needs Android 13). Returns fewer bytes only at end of stream. */
internal fun InputStream.readExactly(count: Int): ByteArray {
    val buffer = ByteArray(count)
    var filled = 0
    while (filled < count) {
        val n = read(buffer, filled, count - filled)
        if (n < 0) return buffer.copyOf(filled)
        filled += n
    }
    return buffer
}
