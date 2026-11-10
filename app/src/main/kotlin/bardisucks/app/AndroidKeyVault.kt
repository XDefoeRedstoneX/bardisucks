package bardisucks.app

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import bardisucks.core.home.KeyVault
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Seals the lamps' local keys with an AES key that lives in the Android Keystore and can't be exported.
 * A copy of the app's files is therefore useless on another phone (the lamps then show "import again").
 */
class AndroidKeyVault(private val alias: String = "bardisucks.device-keys") : KeyVault {

    @Synchronized
    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply { init(spec) }.generateKey()
    }

    /** Result: one byte IV length, the IV the keystore chose, then ciphertext + tag; Base64. */
    override fun seal(secret: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = cipher.doFinal(secret.toByteArray())
        return Base64.encodeToString(byteArrayOf(cipher.iv.size.toByte()) + cipher.iv + sealed, Base64.NO_WRAP)
    }

    override fun open(sealed: String): String {
        val bytes = Base64.decode(sealed, Base64.NO_WRAP)
        val ivSize = bytes[0].toInt()
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, bytes, 1, ivSize))
        }
        return String(cipher.doFinal(bytes, 1 + ivSize, bytes.size - 1 - ivSize))
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
    }
}
