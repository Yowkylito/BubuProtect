package com.personal.bubuprotect.core.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test
import javax.crypto.AEADBadTagException

/**
 * The round trip the whole recovery feature rests on.
 *
 * If sealing and opening ever disagree, the failure is silent until the day someone reaches for their
 * printed kit after forgetting their passphrase - at which point the vault is gone and there is
 * nothing to fall back to. So this is tested directly rather than inferred from the primitives.
 */
class RecoveryWrapperTest {

    private val rootKey = ByteArray(32) { it.toByte() }

    @Test
    fun `a code opens what it sealed`() {
        RecoveryCode.generate().use { code ->
            val salt = RecoveryWrapper.newSalt()
            val box = RecoveryWrapper.seal(rootKey, code, salt)

            assertArrayEquals(rootKey, RecoveryWrapper.open(box, code, salt))
        }
    }

    /** The realistic path: sealed on one device, typed back off paper later. */
    @Test
    fun `a code retyped from its printed form still opens the box`() {
        val salt = RecoveryWrapper.newSalt()
        val (box, printed) = RecoveryCode.generate().use { code ->
            RecoveryWrapper.seal(rootKey, code, salt) to code.formatted()
        }

        val retyped = RecoveryCode.parse(printed.lowercase().replace("-", " "))
        assertNotNull(retyped)
        retyped!!.use { code ->
            assertArrayEquals(rootKey, RecoveryWrapper.open(box, code, salt))
        }
    }

    @Test
    fun `a different code does not open it`() {
        val salt = RecoveryWrapper.newSalt()
        val box = RecoveryCode.generate().use { RecoveryWrapper.seal(rootKey, it, salt) }

        RecoveryCode.generate().use { other ->
            assertThrows(AEADBadTagException::class.java) {
                RecoveryWrapper.open(box, other, salt)
            }
        }
    }

    /**
     * Replacing the salt is how a kit is revoked - see `VaultKeyStore.writeRecoveryWrapper`. If the
     * old code still worked against a new salt, "print a new kit" would not actually retire the old
     * page, and a user who thought they had revoked a lost kit would be wrong.
     */
    @Test
    fun `a new salt retires the old code`() {
        RecoveryCode.generate().use { code ->
            val firstSalt = RecoveryWrapper.newSalt()
            val box = RecoveryWrapper.seal(rootKey, code, firstSalt)

            val secondSalt = RecoveryWrapper.newSalt()
            assertFalse(firstSalt.contentEquals(secondSalt))
            assertThrows(AEADBadTagException::class.java) {
                RecoveryWrapper.open(box, code, secondSalt)
            }
        }
    }

    @Test
    fun `a tampered box is rejected rather than decrypted`() {
        RecoveryCode.generate().use { code ->
            val salt = RecoveryWrapper.newSalt()
            val box = RecoveryWrapper.seal(rootKey, code, salt)

            // Flip a bit in the ciphertext, past the IV.
            val tampered = box.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
            assertThrows(AEADBadTagException::class.java) {
                RecoveryWrapper.open(tampered, code, salt)
            }
        }
    }

    /** Two seals of the same key under the same code must differ - AES-GCM draws a fresh IV. */
    @Test
    fun `sealing twice produces different bytes`() {
        RecoveryCode.generate().use { code ->
            val salt = RecoveryWrapper.newSalt()
            val first = RecoveryWrapper.seal(rootKey, code, salt)
            val second = RecoveryWrapper.seal(rootKey, code, salt)

            assertFalse(first.contentEquals(second))
            assertArrayEquals(rootKey, RecoveryWrapper.open(first, code, salt))
            assertArrayEquals(rootKey, RecoveryWrapper.open(second, code, salt))
        }
    }

    @Test
    fun `the root key is left intact for the caller`() {
        val original = rootKey.copyOf()
        RecoveryCode.generate().use { code ->
            RecoveryWrapper.seal(rootKey, code, RecoveryWrapper.newSalt())
        }
        // seal() must not wipe it: at kit-creation time this is the live session key.
        assertArrayEquals(original, rootKey)
    }

    @Test
    fun `salts are the documented length and not reused`() {
        assertEquals(32, RecoveryWrapper.SALT_LENGTH)
        assertEquals(RecoveryWrapper.SALT_LENGTH, RecoveryWrapper.newSalt().size)
        assertFalse(RecoveryWrapper.newSalt().contentEquals(RecoveryWrapper.newSalt()))
    }
}
