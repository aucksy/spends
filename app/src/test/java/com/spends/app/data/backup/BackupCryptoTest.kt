package com.spends.app.data.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

class BackupCryptoTest {

    private val plaintext = "the quick brown fox jumps over ₹12,34,567.00".toByteArray(Charsets.UTF_8)

    @Test fun sameDeviceRoundTrip() {
        val dek = BackupCrypto.newDek()
        val bundle = BackupCrypto.wrap(dek, "correct horse".toCharArray())
        val container = BackupCrypto.seal(plaintext, dek, bundle)

        assertTrue(BackupCrypto.isEncrypted(container))
        assertArrayEquals(plaintext, BackupCrypto.openWithDek(container, dek))
    }

    @Test fun passwordRecoveryOnNewDevice() {
        val dek = BackupCrypto.newDek()
        val password = "correct horse battery".toCharArray()
        val bundle = BackupCrypto.wrap(dek, password)
        val container = BackupCrypto.seal(plaintext, dek, bundle)

        // Simulate a fresh phone: only the file + the password, no device DEK.
        val recovered = BackupCrypto.openWithPassword(container, "correct horse battery".toCharArray())
        assertArrayEquals(plaintext, recovered.plaintext)
        assertArrayEquals(dek, recovered.dek) // same DEK recovered, so future backups stay consistent
    }

    @Test fun wrongPasswordThrows() {
        val dek = BackupCrypto.newDek()
        val container = BackupCrypto.seal(plaintext, dek, BackupCrypto.wrap(dek, "right".toCharArray()))
        assertThrows(WrongBackupPasswordException::class.java) {
            BackupCrypto.openWithPassword(container, "wrong".toCharArray())
        }
    }

    @Test fun tamperedPayloadFailsToDecrypt() {
        val dek = BackupCrypto.newDek()
        val container = BackupCrypto.seal(plaintext, dek, BackupCrypto.wrap(dek, "pw".toCharArray()))
        container[container.size - 1] = (container[container.size - 1] + 1).toByte() // flip a payload byte
        assertThrows(Exception::class.java) { BackupCrypto.openWithDek(container, dek) }
    }

    @Test fun legacyGzipIsNotDetectedAsEncrypted() {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write("{\"schemaVersion\":2}".toByteArray()) }
        assertFalse(BackupCrypto.isEncrypted(out.toByteArray()))
    }

    @Test fun eachSealUsesAFreshNonce() {
        val dek = BackupCrypto.newDek()
        val bundle = BackupCrypto.wrap(dek, "pw".toCharArray())
        val a = BackupCrypto.seal(plaintext, dek, bundle)
        val b = BackupCrypto.seal(plaintext, dek, bundle)
        assertFalse(a.contentEquals(b)) // same plaintext + DEK must not yield identical ciphertext
    }
}
