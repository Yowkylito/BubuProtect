package com.personal.bubuprotect.core.backup

import com.personal.bubuprotect.core.crypto.AesGcm
import com.personal.bubuprotect.core.crypto.Argon2Kdf
import com.personal.bubuprotect.core.crypto.PassphraseKdf
import com.personal.bubuprotect.core.crypto.SecureBytes
import com.personal.bubuprotect.core.crypto.wipe
import java.io.IOException
import javax.crypto.AEADBadTagException

/** The passphrase did not open this file. Deliberately carries no detail beyond that. */
class WrongBackupPassphraseException : IOException("That passphrase does not open this backup")

/** The bytes are not a Bubu backup, are truncated, or were modified after they were written. */
class CorruptBackupException(message: String) : IOException(message)

/**
 * Written by a newer build than this one. Refused rather than guessed at.
 *
 * A backup is a store of last resort; a partial read that produced *plausible* entries would be far
 * worse than a clear refusal, because the user would restore it, see something, and believe they
 * were whole.
 */
class UnsupportedBackupVersionException(val version: Int) :
    IOException("This backup was written by a newer version of Bubu Protect")

/**
 * The on-disk container for an exported vault.
 *
 * ### Layout
 *
 * ```
 * header (authenticated, not encrypted)
 *   magic         8   "BUBUBAK1"
 *   version       1   format version
 *   kdfId         1   1 = PBKDF2-HMAC-SHA512, 2 = Argon2id
 *   kdfParamA     4   PBKDF2: iterations   Argon2: memory in KiB
 *   kdfParamB     4   PBKDF2: unused       Argon2: iterations
 *   kdfParamC     4   PBKDF2: unused       Argon2: parallelism
 *   saltLength    1
 *   salt         32
 * body
 *   wrappedKey   2 + n   AES-GCM box over the content key
 *   payload      4 + n   AES-GCM box over the serialised vault
 * ```
 *
 * Three generic parameter slots rather than one, because a memory-hard KDF needs more than a single
 * cost number and a format that cannot express that is a format that has to be replaced the first
 * time the KDF is upgraded.
 *
 * ### Why the file has its own content key
 *
 * The obvious design is to export the vault's own passphrase-wrapped MEK. It would be less code and
 * it would work. It would also mean every backup the user has ever taken is a copy of the key that
 * opens their live vault - so an old export recovered from a shared drive years later is not merely
 * a stale snapshot, it is the current vault's key material.
 *
 * Instead each export mints a fresh random content key, seals the payload with it, and wraps *that*
 * under the passphrase. The vault's MEK never enters the file. A leaked backup costs the user the
 * data that was in it at that moment and nothing else, and restoring produces a vault with entirely
 * new keys.
 *
 * ### Why the header is AAD
 *
 * Both boxes authenticate the header, so the version and KDF cost cannot be edited without failing
 * the tag check. Without that, a file claiming `kdfCost = 1` would still be a well-formed container;
 * an attacker could not decrypt it, but a future format revision that made any parameter meaningful
 * to the *reader* would inherit a silent downgrade path. Binding it now costs nothing.
 *
 * ### Why the KDF here is not the vault's KDF
 *
 * [PassphraseKdf] protects key material in app-private storage: to attack it you need the device,
 * and once you have it the lockout throttles you. PBKDF2 at OWASP's cost is right for that.
 *
 * This file is *designed* to leave the device, so the passphrase is the only factor left and the
 * attacker gets unlimited offline attempts on hardware of their choosing. PBKDF2's lack of memory
 * cost is decisive there - it parallelises across a GPU almost for free. So backups are written with
 * [Argon2Kdf], whose memory requirement is what actually runs out on parallel hardware.
 *
 * The PBKDF2 reader path is kept rather than deleted. It is the mechanism that lets the KDF be
 * replaced again later without orphaning files: every backup states which algorithm and parameters
 * produced it, and readers dispatch on that rather than assuming today's choice.
 */
object VaultBackupEnvelope {

    /** Suggested file extension. Nothing depends on it; the magic bytes are the real check. */
    const val FILE_EXTENSION = "bubuvault"

    const val MIME_TYPE = "application/octet-stream"

    const val FORMAT_VERSION = 1

    /** Readable, never written by this build. Kept so the dispatch on [kdfId] stays exercised. */
    const val KDF_PBKDF2_SHA512 = 1

    /** What every backup this build writes uses. */
    const val KDF_ARGON2ID = 2

    private val MAGIC = "BUBUBAK1".toByteArray(Charsets.US_ASCII)
    private const val KEY_LENGTH = 32
    private const val SALT_LENGTH = PassphraseKdf.SALT_LENGTH
    private const val HEADER_LENGTH = 8 + 1 + 1 + 4 + 4 + 4 + 1 + SALT_LENGTH

    /**
     * Refuses to allocate more than this from a file we have not authenticated yet.
     *
     * The length prefixes are read before the tag is checked - they have to be, they say how much to
     * read - so they are attacker-controlled until proven otherwise. Without a ceiling, a six-byte
     * file claiming a 4 GiB payload is an OOM kill.
     */
    private const val MAX_PAYLOAD_BYTES = 64 * 1024 * 1024

    /**
     * @param plaintext the serialised vault. **Wiped by this function**, whether it succeeds or not -
     *   it is every secret the user owns in the clear and must not outlive the call.
     */
    fun seal(plaintext: ByteArray, passphrase: CharArray): ByteArray {
        val salt = PassphraseKdf.newSalt()
        val header = buildHeader(
            version = FORMAT_VERSION,
            kdfId = KDF_ARGON2ID,
            paramA = Argon2Kdf.MEMORY_KIB,
            paramB = Argon2Kdf.ITERATIONS,
            paramC = Argon2Kdf.PARALLELISM,
            salt = salt
        )

        return try {
            SecureBytes.random(KEY_LENGTH).use { contentKey ->
                val payload = AesGcm.seal(contentKey.toSecretKey(), plaintext, aad = header)
                val wrappedKey = deriveWrappingKey(
                    kdfId = KDF_ARGON2ID,
                    paramA = Argon2Kdf.MEMORY_KIB,
                    paramB = Argon2Kdf.ITERATIONS,
                    paramC = Argon2Kdf.PARALLELISM,
                    salt = salt,
                    passphrase = passphrase
                ).use { wrappingKey ->
                    AesGcm.seal(wrappingKey.toSecretKey(), contentKey.use(), aad = header)
                }

                header +
                    wrappedKey.withShortLengthPrefix() +
                    payload.withIntLengthPrefix()
            }
        } finally {
            plaintext.wipe()
        }
    }

    /**
     * @return the serialised vault. The caller owns it and must wipe it once parsed.
     * @throws WrongBackupPassphraseException when the passphrase is wrong *or* the file was altered.
     *   The two are not distinguished: GCM cannot tell them apart, and inventing a distinction would
     *   mean adding a separate verifier, which is a second oracle for guessing the passphrase.
     */
    fun open(bytes: ByteArray, passphrase: CharArray): ByteArray {
        if (bytes.size < HEADER_LENGTH) throw CorruptBackupException("Backup file is truncated")
        if (!bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            throw CorruptBackupException("This is not a Bubu Protect backup")
        }

        val header = bytes.copyOfRange(0, HEADER_LENGTH)
        var offset = MAGIC.size

        val version = bytes[offset++].toInt() and 0xff
        if (version != FORMAT_VERSION) throw UnsupportedBackupVersionException(version)

        val kdfId = bytes[offset++].toInt() and 0xff
        if (kdfId != KDF_ARGON2ID && kdfId != KDF_PBKDF2_SHA512) {
            throw UnsupportedBackupVersionException(version)
        }

        val paramA = bytes.readIntAt(offset).also { offset += 4 }
        val paramB = bytes.readIntAt(offset).also { offset += 4 }
        val paramC = bytes.readIntAt(offset).also { offset += 4 }

        val saltLength = bytes[offset++].toInt() and 0xff
        if (saltLength != SALT_LENGTH) throw CorruptBackupException("Backup header is malformed")
        val salt = bytes.copyOfRange(offset, offset + SALT_LENGTH).also { offset += SALT_LENGTH }

        val wrappedKeyLength = bytes.readShortAt(offset).also { offset += 2 }
        val wrappedKey = bytes.sliceOrThrow(offset, wrappedKeyLength).also { offset += wrappedKeyLength }

        val payloadLength = bytes.readIntAt(offset).also { offset += 4 }
        if (payloadLength !in 1..MAX_PAYLOAD_BYTES) {
            throw CorruptBackupException("Backup file is malformed")
        }
        val payload = bytes.sliceOrThrow(offset, payloadLength)

        // Parameters come from the file, never from this build's constants: a backup written before
        // an upgrade has to keep opening with the cost it was actually written with.
        val contentKey = deriveWrappingKey(kdfId, paramA, paramB, paramC, salt, passphrase)
            .use { wrappingKey ->
                try {
                    SecureBytes.adopt(
                        AesGcm.open(wrappingKey.toSecretKey(), wrappedKey, aad = header)
                    )
                } catch (badTag: AEADBadTagException) {
                    throw WrongBackupPassphraseException()
                }
            }

        return contentKey.use { key ->
            try {
                AesGcm.open(key.toSecretKey(), payload, aad = header)
            } catch (badTag: AEADBadTagException) {
                // The key unwrapped, so the passphrase was right and the payload is the damaged part.
                throw CorruptBackupException("This backup is damaged and cannot be restored")
            }
        }
    }

    /** Cheap enough to run on a picked file before asking the user for a passphrase. */
    fun looksLikeBackup(bytes: ByteArray): Boolean =
        bytes.size >= HEADER_LENGTH && bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)

    /**
     * Derives the key that wraps the content key.
     *
     * The parameter bounds live inside [Argon2Kdf] and [PassphraseKdf] rather than here, and they are
     * enforced against values taken from the file: a header claiming 8 KiB of memory or one PBKDF2
     * round is either damaged or crafted, and honouring it would derive a key an attacker could
     * reproduce cheaply. Refusing is the only safe response - a downgraded cost is not something to
     * be tolerant about.
     */
    private fun deriveWrappingKey(
        kdfId: Int,
        paramA: Int,
        paramB: Int,
        paramC: Int,
        salt: ByteArray,
        passphrase: CharArray
    ): SecureBytes = try {
        when (kdfId) {
            KDF_ARGON2ID -> Argon2Kdf.deriveKey(
                passphrase = passphrase,
                salt = salt,
                memoryKib = paramA,
                iterations = paramB,
                parallelism = paramC,
                length = KEY_LENGTH
            )

            KDF_PBKDF2_SHA512 -> {
                if (paramA < MIN_PBKDF2_ITERATIONS) {
                    throw CorruptBackupException("Backup header is malformed")
                }
                PassphraseKdf.deriveKey(passphrase, salt, paramA, KEY_LENGTH)
            }

            else -> throw CorruptBackupException("Backup header is malformed")
        }
    } catch (outOfRange: IllegalArgumentException) {
        throw CorruptBackupException("Backup header is malformed")
    }

    private fun buildHeader(
        version: Int,
        kdfId: Int,
        paramA: Int,
        paramB: Int,
        paramC: Int,
        salt: ByteArray
    ): ByteArray {
        require(salt.size == SALT_LENGTH) { "Unexpected salt length" }
        val header = ByteArray(HEADER_LENGTH)
        MAGIC.copyInto(header)
        var offset = MAGIC.size
        header[offset++] = version.toByte()
        header[offset++] = kdfId.toByte()
        offset = header.writeIntAt(offset, paramA)
        offset = header.writeIntAt(offset, paramB)
        offset = header.writeIntAt(offset, paramC)
        header[offset++] = salt.size.toByte()
        salt.copyInto(header, offset)
        return header
    }

    /** @return the offset just past the value written. */
    private fun ByteArray.writeIntAt(index: Int, value: Int): Int {
        this[index] = (value ushr 24).toByte()
        this[index + 1] = (value ushr 16).toByte()
        this[index + 2] = (value ushr 8).toByte()
        this[index + 3] = value.toByte()
        return index + 4
    }

    /** Below this a PBKDF2-sealed backup is refused rather than opened cheaply. */
    private const val MIN_PBKDF2_ITERATIONS = 100_000

    private fun ByteArray.withShortLengthPrefix(): ByteArray {
        require(size <= 0xffff) { "Wrapped key is too large" }
        return byteArrayOf((size ushr 8).toByte(), size.toByte()) + this
    }

    private fun ByteArray.withIntLengthPrefix(): ByteArray = byteArrayOf(
        (size ushr 24).toByte(),
        (size ushr 16).toByte(),
        (size ushr 8).toByte(),
        size.toByte()
    ) + this

    private fun ByteArray.readIntAt(index: Int): Int {
        if (index + 4 > size) throw CorruptBackupException("Backup file is truncated")
        return ((this[index].toInt() and 0xff) shl 24) or
            ((this[index + 1].toInt() and 0xff) shl 16) or
            ((this[index + 2].toInt() and 0xff) shl 8) or
            (this[index + 3].toInt() and 0xff)
    }

    private fun ByteArray.readShortAt(index: Int): Int {
        if (index + 2 > size) throw CorruptBackupException("Backup file is truncated")
        return ((this[index].toInt() and 0xff) shl 8) or (this[index + 1].toInt() and 0xff)
    }

    private fun ByteArray.sliceOrThrow(from: Int, length: Int): ByteArray {
        if (length < 0 || from + length > size) {
            throw CorruptBackupException("Backup file is truncated")
        }
        return copyOfRange(from, from + length)
    }
}
