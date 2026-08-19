package com.personal.bubuprotect.core.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import com.personal.bubuprotect.core.crypto.Argon2Kdf
import org.junit.Test

/**
 * The envelope is the only thing standing between a file in someone's cloud storage and their entire
 * vault, so every failure mode gets pinned rather than reasoned about.
 *
 * These run the real KDF at its real cost, which is the point - but it means each `seal`/`open` is a
 * deliberate fraction of a second. The suite is kept small for that reason.
 */
class VaultBackupEnvelopeTest {

    private val passphrase get() = "correct horse battery staple".toCharArray()

    @Test
    fun `round trips a payload`() {
        val secret = """{"e":[{"id":"1","s":"hunter2"}]}""".toByteArray()

        val file = VaultBackupEnvelope.seal(secret.copyOf(), passphrase)
        val opened = VaultBackupEnvelope.open(file, passphrase)

        assertArrayEquals(secret, opened)
    }

    @Test
    fun `the plaintext buffer is wiped by seal`() {
        val plaintext = "a password".toByteArray()

        VaultBackupEnvelope.seal(plaintext, passphrase)

        // The caller's array is zeroed in place, so the secret does not linger in a buffer the
        // caller forgot about.
        assertArrayEquals(ByteArray(plaintext.size), plaintext)
    }

    @Test
    fun `the vault contents never appear in the file`() {
        val marker = "SUPER-SECRET-MARKER"
        val file = VaultBackupEnvelope.seal(marker.toByteArray(), passphrase)

        assertFalse(String(file, Charsets.ISO_8859_1).contains(marker))
    }

    @Test
    fun `two exports of identical data produce different files`() {
        val a = VaultBackupEnvelope.seal("same".toByteArray(), passphrase)
        val b = VaultBackupEnvelope.seal("same".toByteArray(), passphrase)

        // Fresh salt and a fresh content key per export. Identical files would leak that nothing
        // changed between two backups.
        assertNotEquals(String(a, Charsets.ISO_8859_1), String(b, Charsets.ISO_8859_1))
    }

    @Test
    fun `a wrong passphrase is rejected`() {
        val file = VaultBackupEnvelope.seal("payload".toByteArray(), passphrase)

        assertThrows(WrongBackupPassphraseException::class.java) {
            VaultBackupEnvelope.open(file, "not the passphrase".toCharArray())
        }
    }

    @Test
    fun `a flipped byte in the payload is caught`() {
        val file = VaultBackupEnvelope.seal("payload".toByteArray(), passphrase)
        file[file.size - 1] = (file[file.size - 1].toInt() xor 0x01).toByte()

        assertThrows(CorruptBackupException::class.java) {
            VaultBackupEnvelope.open(file, passphrase)
        }
    }

    @Test
    fun `downgrading the memory cost in the header is refused, not honoured`() {
        val file = VaultBackupEnvelope.seal("payload".toByteArray(), passphrase)

        // Bytes 10..13 are the big-endian Argon2 memory cost in KiB. Rewriting it to 8 KiB would,
        // if honoured, derive a key an attacker could reproduce for almost nothing - so the floor
        // in Argon2Kdf has to reject it rather than the AAD merely making it undecryptable.
        file[10] = 0
        file[11] = 0
        file[12] = 0
        file[13] = 8

        assertThrows(CorruptBackupException::class.java) {
            VaultBackupEnvelope.open(file, passphrase)
        }
    }

    @Test
    fun `an absurd memory demand in the header is refused without allocating it`() {
        val file = VaultBackupEnvelope.seal("payload".toByteArray(), passphrase)

        // ~1 TiB of memory. Read before anything is authenticated, so honouring it would let a
        // crafted file kill the process on sight.
        file[10] = 0x40
        file[11] = 0
        file[12] = 0
        file[13] = 0

        assertThrows(CorruptBackupException::class.java) {
            VaultBackupEnvelope.open(file, passphrase)
        }
    }

    @Test
    fun `a future format version is refused rather than guessed at`() {
        val file = VaultBackupEnvelope.seal("payload".toByteArray(), passphrase)
        file[8] = (VaultBackupEnvelope.FORMAT_VERSION + 1).toByte()

        val thrown = assertThrows(UnsupportedBackupVersionException::class.java) {
            VaultBackupEnvelope.open(file, passphrase)
        }
        assertEquals(VaultBackupEnvelope.FORMAT_VERSION + 1, thrown.version)
    }

    @Test
    fun `a foreign file is rejected before the passphrase is used`() {
        val notABackup = "PK this is a zip file".toByteArray()

        assertFalse(VaultBackupEnvelope.looksLikeBackup(notABackup))
        assertThrows(CorruptBackupException::class.java) {
            VaultBackupEnvelope.open(notABackup, passphrase)
        }
    }

    @Test
    fun `truncation is caught rather than read past the end`() {
        val file = VaultBackupEnvelope.seal("payload".toByteArray(), passphrase)

        assertThrows(CorruptBackupException::class.java) {
            VaultBackupEnvelope.open(file.copyOfRange(0, file.size - 8), passphrase)
        }
        assertThrows(CorruptBackupException::class.java) {
            VaultBackupEnvelope.open(file.copyOfRange(0, 20), passphrase)
        }
    }

    @Test
    fun `an absurd declared payload length is refused without allocating it`() {
        val file = VaultBackupEnvelope.seal("payload".toByteArray(), passphrase)

        // The length prefixes are read before anything is authenticated, so they are attacker
        // controlled. Claiming ~2GB must be a clean refusal, not an OutOfMemoryError.
        val payloadLengthAt = file.size - "payload".toByteArray().size - 12 - 16 - 4
        file[payloadLengthAt] = 0x7f
        file[payloadLengthAt + 1] = 0xff.toByte()
        file[payloadLengthAt + 2] = 0xff.toByte()
        file[payloadLengthAt + 3] = 0xff.toByte()

        assertThrows(CorruptBackupException::class.java) {
            VaultBackupEnvelope.open(file, passphrase)
        }
    }

    @Test
    fun `backups are written with Argon2id and record their own parameters`() {
        val file = VaultBackupEnvelope.seal("payload".toByteArray(), passphrase)

        fun intAt(index: Int) = ((file[index].toInt() and 0xff) shl 24) or
            ((file[index + 1].toInt() and 0xff) shl 16) or
            ((file[index + 2].toInt() and 0xff) shl 8) or
            (file[index + 3].toInt() and 0xff)

        assertEquals(VaultBackupEnvelope.KDF_ARGON2ID, file[9].toInt() and 0xff)
        assertEquals(Argon2Kdf.MEMORY_KIB, intAt(10))
        assertEquals(Argon2Kdf.ITERATIONS, intAt(14))
        assertEquals(Argon2Kdf.PARALLELISM, intAt(18))

        // Memory-hardness is the whole reason this KDF was chosen over the vault's PBKDF2: it is
        // what stops a GPU running tens of thousands of guesses in parallel.
        assertTrue("backup KDF must be memory-hard", intAt(10) >= 46 * 1024)
    }

    @Test
    fun `the payload key is independent of the passphrase-derived key`() {
        // Two exports under the same passphrase and the same salt would still differ, because the
        // content key is random per file. Asserted via the wrapped-key block rather than the whole
        // file so a shared salt cannot be what makes them differ.
        val a = VaultBackupEnvelope.seal("same".toByteArray(), passphrase)
        val b = VaultBackupEnvelope.seal("same".toByteArray(), passphrase)

        val wrappedA = a.copyOfRange(HEADER_LENGTH, HEADER_LENGTH + 62)
        val wrappedB = b.copyOfRange(HEADER_LENGTH, HEADER_LENGTH + 62)
        assertFalse(wrappedA.contentEquals(wrappedB))
    }

    private companion object {
        /** magic 8 + version 1 + kdfId 1 + params 12 + saltLen 1 + salt 32 */
        const val HEADER_LENGTH = 55
    }
}
