package com.spends.app.data.backup

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds the backup **data key (DEK)** for this device. The DEK is generated once and stored at rest
 * encrypted by a key that lives in the Android hardware keystore (TEE/StrongBox) and never leaves it,
 * so normal backups/restores on this phone need no password — yet a stolen prefs/file is useless.
 *
 * Alongside the DEK we keep the passphrase-wrapped copy (salt/iv/wrappedDek) produced when the user
 * sets a recovery password; that copy is embedded in every backup so a NEW phone can recover the DEK
 * from the password alone (see [BackupCrypto]).
 */
@Singleton
class SecureKeyStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences("spends_secure", Context.MODE_PRIVATE)

    /**
     * True when this device holds a DEK and can therefore decrypt its OWN encrypted backups (via the
     * hardware-wrapped DEK) — independent of whether a recovery password is set. Embedding the recovery
     * bundle so a NEW phone can recover still needs a password (see [wrapBundle]). Keeping this gated only
     * on the DEK means removing the password (#8) doesn't lock the user out of their own earlier backups.
     */
    fun isReady(): Boolean = hasDek()

    /** True once the user has set a recovery password (a wrap bundle exists). */
    fun hasPassword(): Boolean = prefs.contains(KEY_WRAP_SALT)

    /**
     * Remove the recovery password so future backups are written unencrypted (#8). The DEK is kept, so any
     * encrypted backup already made on THIS device still opens here.
     */
    fun clearPassword() {
        prefs.edit()
            .remove(KEY_WRAP_SALT)
            .remove(KEY_WRAP_IV)
            .remove(KEY_WRAPPED_DEK)
            .remove(KEY_WRAP_ITER)
            .apply()
    }

    private fun hasDek(): Boolean = prefs.contains(KEY_DEK_BLOB)

    /** Set/replace the recovery password, keeping the same DEK so existing same-device backups still open. */
    fun setPassword(password: CharArray) {
        val dek = ensureDek()
        val bundle = BackupCrypto.wrap(dek, password)
        prefs.edit()
            .putString(KEY_WRAP_SALT, bundle.salt.b64())
            .putString(KEY_WRAP_IV, bundle.iv.b64())
            .putString(KEY_WRAPPED_DEK, bundle.wrappedDek.b64())
            .putInt(KEY_WRAP_ITER, bundle.iterations)
            .apply()
    }

    /** The current DEK (creating one if needed). */
    fun dek(): ByteArray = ensureDek()

    /** The stored wrap bundle to embed in a backup; null if no password set yet. */
    fun wrapBundle(): BackupCrypto.WrapBundle? {
        if (!hasPassword()) return null
        return BackupCrypto.WrapBundle(
            salt = prefs.getString(KEY_WRAP_SALT, null)!!.unb64(),
            iv = prefs.getString(KEY_WRAP_IV, null)!!.unb64(),
            wrappedDek = prefs.getString(KEY_WRAPPED_DEK, null)!!.unb64(),
            iterations = prefs.getInt(KEY_WRAP_ITER, BackupCrypto.PBKDF2_ITERATIONS),
        )
    }

    // ---- Groq API key (BYOK) — encrypted at rest, device-local, NEVER in the backup snapshot ----

    /**
     * Store the user's Groq API key encrypted by the same hardware-wrapped master key the DEK uses, in
     * the device-local `spends_secure` prefs. Like the DEK it never leaves the device and is NOT part of
     * any backup snapshot. A blank key clears it. See [AI-RESEARCH.md] §2.5.
     */
    fun setApiKey(key: String) {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) {
            clearApiKey()
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, masterKey())
        val ct = cipher.doFinal(trimmed.toByteArray(Charsets.UTF_8))
        val blob = cipher.iv + ct
        prefs.edit().putString(KEY_API_KEY_BLOB, blob.b64()).apply()
    }

    /** The stored Groq API key, or null if none is set or it can't be decrypted (fail-closed). */
    fun apiKey(): String? {
        val blob = prefs.getString(KEY_API_KEY_BLOB, null)?.unb64() ?: return null
        return runCatching {
            val iv = blob.copyOfRange(0, IV_LEN)
            val ct = blob.copyOfRange(IV_LEN, blob.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, masterKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    /** True when a Groq API key is stored (presence only — the actual call fails closed if it can't decrypt). */
    fun hasApiKey(): Boolean = prefs.contains(KEY_API_KEY_BLOB)

    /** Remove the stored Groq API key. */
    fun clearApiKey() {
        prefs.edit().remove(KEY_API_KEY_BLOB).apply()
    }

    /** Adopt a DEK + wrap bundle recovered from a password-restore on a new device (re-arms zero-hassle). */
    fun importRecovered(dek: ByteArray, bundle: BackupCrypto.WrapBundle) {
        storeDek(dek)
        prefs.edit()
            .putString(KEY_WRAP_SALT, bundle.salt.b64())
            .putString(KEY_WRAP_IV, bundle.iv.b64())
            .putString(KEY_WRAPPED_DEK, bundle.wrappedDek.b64())
            .putInt(KEY_WRAP_ITER, bundle.iterations)
            .apply()
    }

    // ---- DEK at-rest, wrapped by the hardware keystore key ----

    private fun ensureDek(): ByteArray {
        readDek()?.let { return it }
        val dek = BackupCrypto.newDek()
        storeDek(dek)
        return dek
    }

    private fun readDek(): ByteArray? {
        val blob = prefs.getString(KEY_DEK_BLOB, null)?.unb64() ?: return null
        val iv = blob.copyOfRange(0, IV_LEN)
        val ct = blob.copyOfRange(IV_LEN, blob.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, masterKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(ct)
    }

    private fun storeDek(dek: ByteArray) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, masterKey())
        val ct = cipher.doFinal(dek)
        val blob = cipher.iv + ct
        prefs.edit().putString(KEY_DEK_BLOB, blob.b64()).apply()
    }

    private fun masterKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(MASTER_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                MASTER_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun ByteArray.b64(): String = Base64.encodeToString(this, Base64.NO_WRAP)
    private fun String.unb64(): ByteArray = Base64.decode(this, Base64.NO_WRAP)

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val MASTER_ALIAS = "spends_backup_master"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LEN = 12

        const val KEY_DEK_BLOB = "dek_blob"
        const val KEY_API_KEY_BLOB = "groq_api_key_blob"
        const val KEY_WRAP_SALT = "wrap_salt"
        const val KEY_WRAP_IV = "wrap_iv"
        const val KEY_WRAPPED_DEK = "wrapped_dek"
        const val KEY_WRAP_ITER = "wrap_iter"
    }
}
