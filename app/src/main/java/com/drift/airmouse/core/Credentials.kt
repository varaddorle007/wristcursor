package com.drift.airmouse.core

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Mac sign-ins for Screen Sharing, kept on the watch only and encrypted with a key that
 * lives in the watch's hardware keystore (it can't be copied off the device).
 */
object Credentials {
    data class Login(val user: String, val password: String)

    private const val ALIAS = "drift-logins"
    private const val FILE = "drift_logins"

    fun save(context: Context, computerName: String, login: Login) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = cipher.iv + cipher.doFinal("${login.user}\u0000${login.password}".toByteArray(Charsets.UTF_8))
        store(context).edit { putString(computerName, Base64.encodeToString(sealed, Base64.NO_WRAP)) }
    }

    fun load(context: Context, computerName: String): Login? = runCatching {
        val sealed = Base64.decode(store(context).getString(computerName, null) ?: return null, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed, 0, 12))
        val text = String(cipher.doFinal(sealed, 12, sealed.size - 12), Charsets.UTF_8)
        Login(text.substringBefore('\u0000'), text.substringAfter('\u0000'))
    }.getOrNull()

    fun forget(context: Context, computerName: String) {
        store(context).edit { remove(computerName) }
    }

    private fun store(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val keystore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keystore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }
}
