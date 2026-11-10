package bardisucks.tuya

import bardisucks.tuya.Vectors.str
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class Frame6699Test {
    @Test
    fun `encodes byte-for-byte like TinyTuya`() {
        for (v in Vectors.frames) {
            val encoded = Frame6699.encode(Frame(v.seq, v.cmd, v.payload, v.retcode), Vectors.key, Vectors.iv)
            assertContentEquals(v.frame, encoded, v.name)
        }
    }

    @Test
    fun `decodes TinyTuya frames`() {
        for (v in Vectors.frames) {
            val frame = Frame6699.decode(v.frame, Vectors.key, hasRetcode = v.retcode != null)
            assertEquals(v.seq, frame.seq, v.name)
            assertEquals(v.cmd, frame.cmd, v.name)
            assertEquals(v.retcode, frame.retcode, v.name)
            assertContentEquals(v.payload, frame.payload, v.name)
            assertEquals(v.frame.size, Frame6699.totalLength(v.frame), v.name)
        }
    }

    @Test
    fun `wrong key is reported, not garbage`() {
        val wrongKey = "fedcba9876543210".toByteArray()
        assertFailsWith<TuyaException> { Frame6699.decode(Vectors.frames.first().frame, wrongKey, hasRetcode = false) }
    }

    @Test
    fun `session key matches TinyTuya`() {
        val s = Vectors.session
        val localKey = Vectors.hex(s.str("local_key"))
        val localNonce = Vectors.hex(s.str("local_nonce"))
        val remoteNonce = Vectors.hex(s.str("remote_nonce"))
        assertContentEquals(Vectors.hex(s.str("device_proof")), Protocol35.deviceProof(localKey, localNonce))
        assertContentEquals(Vectors.hex(s.str("client_proof")), Protocol35.clientProof(localKey, remoteNonce))
        val sessionKey = Protocol35.sessionKey(localKey, localNonce, remoteNonce)
        assertContentEquals(Vectors.hex(s.str("session_key")), sessionKey)
        val frame = Frame6699.decode(Vectors.hex(s.str("session_frame")), sessionKey, hasRetcode = false)
        assertEquals("{}", frame.payload.decodeToString())
    }
}
