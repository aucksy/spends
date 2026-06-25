package com.spends.app.data.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import javax.crypto.SecretKeyFactory

/** Thrown when a backup is opened with the wrong recovery password (GCM tag mismatch). */
class WrongBackupPasswordException : Exception("That backup password didn't work.")

/**
 * Pure (Android-free, JVM-testable) crypto for encrypted backups.
 *
 * Design (so a leaked backup file is useless, yet you can still restore on a brand-new phone):
 *  - Every backup is encrypted with a random 256-bit **data key (DEK)** using AES-256-GCM. The DEK
 *    lives on the device wrapped by the hardware keystore (see [SecureKeyStore]) — so normal backups
 *    and restores need no password at all.
 *  - The DEK is ALSO wrapped by a key derived from a user **recovery password** (PBKDF2-HMAC-SHA256)
 *    and that wrapped copy is embedded in every backup file. On a new phone you enter the password
 *    once; we unwrap the DEK and can read the file (and re-arm the keystore for zero-hassle after).
 *
 * Container layout (binary, opaque — not openable in a text editor):
 *   "SPNDBAK" + version(1) | kdfIterations(int) | salt | wrapIv | wrappedDek | payloadIv | payload
 * lengths are length-prefixed (short/int, big-endian). [payload] = AES-GCM(DEK, gzip-json).
 */
object BackupCrypto {

    private val MAGIC = byteArrayOf('S'.code.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'D'.code.toByte(), 'B'.code.toByte(), 'A'.code.toByte(), 'K'.code.toByte())
    private const val VERSION = 1
    private const val GCM_TAG_BITS = 128
    private const val IV_LEN = 12
    private const val DEK_LEN = 32
    const val PBKDF2_ITERATIONS = 120_000

    /** The passphrase-wrapped copy of the DEK + the params needed to unwrap it. Embedded in each file. */
    data class WrapBundle(val salt: ByteArray, val iv: ByteArray, val wrappedDek: ByteArray, val iterations: Int)

    /** Result of opening a file with the recovery password: the plaintext + the recovered DEK to re-arm the device. */
    data class Recovered(val plaintext: ByteArray, val dek: ByteArray, val bundle: WrapBundle)

    private val rng = SecureRandom()

    fun newDek(): ByteArray = ByteArray(DEK_LEN).also { rng.nextBytes(it) }

    fun isEncrypted(bytes: ByteArray): Boolean =
        bytes.size > MAGIC.size && MAGIC.indices.all { bytes[it] == MAGIC[it] }

    /** Derive a 256-bit key-encryption-key from the recovery password. */
    private fun deriveKek(password: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(password, salt, iterations, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    /** Wrap [dek] under [password], producing a fresh salt/iv. Used when (re)setting the recovery password. */
    fun wrap(dek: ByteArray, password: CharArray): WrapBundle {
        val salt = ByteArray(16).also { rng.nextBytes(it) }
        val kek = deriveKek(password, salt, PBKDF2_ITERATIONS)
        val (iv, wrapped) = gcmEncrypt(kek, dek)
        return WrapBundle(salt, iv, wrapped, PBKDF2_ITERATIONS)
    }

    /** Encrypt [plaintext] (gzip-json) with [dek], embedding [bundle] so the file is self-recoverable. */
    fun seal(plaintext: ByteArray, dek: ByteArray, bundle: WrapBundle): ByteArray {
        val (payloadIv, payload) = gcmEncrypt(dek, plaintext)
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { d ->
            d.write(MAGIC)
            d.writeByte(VERSION)
            d.writeInt(bundle.iterations)
            d.writeChunk(bundle.salt)
            d.writeChunk(bundle.iv)
            d.writeChunk(bundle.wrappedDek)
            d.writeChunk(payloadIv)
            d.writeChunk(payload)
        }
        return out.toByteArray()
    }

    /** Same-device open: decrypt the payload directly with the device [dek]. */
    fun openWithDek(container: ByteArray, dek: ByteArray): ByteArray {
        val p = parse(container)
        return gcmDecrypt(dek, p.payloadIv, p.payload)
    }

    /** New-device open: derive the KEK from [password], unwrap the DEK, decrypt. Throws on a wrong password. */
    fun openWithPassword(container: ByteArray, password: CharArray): Recovered {
        val p = parse(container)
        val kek = deriveKek(password, p.salt, p.iterations)
        val dek = try {
            gcmDecrypt(kek, p.wrapIv, p.wrappedDek)
        } catch (e: Exception) {
            throw WrongBackupPasswordException()
        }
        val plaintext = gcmDecrypt(dek, p.payloadIv, p.payload)
        return Recovered(plaintext, dek, WrapBundle(p.salt, p.wrapIv, p.wrappedDek, p.iterations))
    }

    // ---- internals ----

    private data class Parsed(
        val iterations: Int,
        val salt: ByteArray,
        val wrapIv: ByteArray,
        val wrappedDek: ByteArray,
        val payloadIv: ByteArray,
        val payload: ByteArray,
    )

    private fun parse(container: ByteArray): Parsed {
        require(isEncrypted(container)) { "Not an encrypted Spends backup." }
        DataInputStream(ByteArrayInputStream(container)).use { d ->
            d.skipBytes(MAGIC.size)
            d.readUnsignedByte() // version
            val iterations = d.readInt()
            val salt = d.readChunk()
            val wrapIv = d.readChunk()
            val wrappedDek = d.readChunk()
            val payloadIv = d.readChunk()
            val payload = d.readChunk()
            return Parsed(iterations, salt, wrapIv, wrappedDek, payloadIv, payload)
        }
    }

    private fun gcmEncrypt(key: ByteArray, plaintext: ByteArray): Pair<ByteArray, ByteArray> {
        val iv = ByteArray(IV_LEN).also { rng.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        return iv to cipher.doFinal(plaintext)
    }

    private fun gcmDecrypt(key: ByteArray, iv: ByteArray, ct: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ct)
    }

    private fun DataOutputStream.writeChunk(bytes: ByteArray) {
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readChunk(): ByteArray {
        val len = readInt()
        require(len in 0..(64 * 1024 * 1024)) { "Corrupt backup (bad chunk length)." }
        val buf = ByteArray(len)
        readFully(buf)
        return buf
    }
}
