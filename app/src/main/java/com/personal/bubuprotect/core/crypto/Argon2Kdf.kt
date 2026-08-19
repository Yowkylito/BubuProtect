package com.personal.bubuprotect.core.crypto

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets

/**
 * Argon2id, used for the exported-backup file and nothing else.
 *
 * ### Why this exists alongside [PassphraseKdf]
 *
 * They protect different things against different attackers, and the right answer differs.
 *
 * The vault's own wrapped key sits in app-private storage. To attack it you need the device, and
 * [com.personal.bubuprotect.core.security.LockoutTracker] throttles you once you have it. PBKDF2 at
 * OWASP's recommended cost is a sound fit for that.
 *
 * A backup file is *designed* to be portable - it is only useful if the user can put it in cloud
 * storage or on another phone - so it must be assumed to be somewhere an attacker can copy and
 * attack offline, unlimited attempts, no device required. There, the passphrase is the only factor
 * left, and PBKDF2's weakness becomes decisive: it needs almost no memory, so a GPU runs tens of
 * thousands of guesses in parallel.
 *
 * ### What memory-hardness buys
 *
 * Argon2id forces each guess through [MEMORY_KIB] of memory that must be written and re-read. A GPU
 * with 24 GB of VRAM can therefore hold roughly 375 concurrent guesses at 64 MiB each, against tens
 * of thousands for PBKDF2 - the parallelism that made offline attack cheap is what runs out first.
 * The cost to a legitimate user is one allocation, once, during an explicit export or restore.
 *
 * ### The parameters
 *
 * [MEMORY_KIB] = 64 MiB, [ITERATIONS] = 2, [PARALLELISM] = 1. Comfortably above OWASP's stated
 * Argon2id floor of 46 MiB / t=1 / p=1, and chosen with memory rather than time as the lever:
 * memory is what defeats parallel hardware, while raising `t` costs the defender and the attacker in
 * equal proportion. `p = 1` because Bouncy Castle's implementation is single-threaded regardless, so
 * a higher value would only mislead a future reader.
 *
 * Every value is written into the backup's header, so raising them here can never orphan a file
 * somebody already has.
 *
 * ### Encoding
 *
 * The passphrase is encoded as UTF-8 here rather than handed to Bouncy Castle's `CharArray` overload,
 * for the same reason [PassphraseKdf] hand-rolls PBKDF2: a KDF whose output depends on some library's
 * choice of character encoding is a vault that stops opening after a dependency bump.
 */
object Argon2Kdf {

    /** 64 MiB. Large enough to hurt parallel hardware, small enough for a phone to allocate once. */
    const val MEMORY_KIB = 64 * 1024

    const val ITERATIONS = 2

    const val PARALLELISM = 1

    /**
     * Blocking and memory-hungry by design - call it off the main thread.
     *
     * Does not wipe [passphrase]; the caller owns it.
     */
    fun deriveKey(
        passphrase: CharArray,
        salt: ByteArray,
        memoryKib: Int = MEMORY_KIB,
        iterations: Int = ITERATIONS,
        parallelism: Int = PARALLELISM,
        length: Int = 32
    ): SecureBytes {
        require(memoryKib in MIN_MEMORY_KIB..MAX_MEMORY_KIB) { "Argon2 memory cost is out of range" }
        require(iterations in 1..MAX_ITERATIONS) { "Argon2 iteration count is out of range" }
        require(parallelism in 1..MAX_PARALLELISM) { "Argon2 parallelism is out of range" }
        require(salt.isNotEmpty()) { "Argon2 needs a salt" }

        val parameters = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withMemoryAsKB(memoryKib)
            .withIterations(iterations)
            .withParallelism(parallelism)
            .withSalt(salt)
            .build()

        val generator = Argon2BytesGenerator().apply { init(parameters) }
        val passwordBytes = toUtf8(passphrase)
        val output = ByteArray(length)
        return try {
            generator.generateBytes(passwordBytes, output)
            // adopt() copies and zeroes its source, so the derived key exists in exactly one place
            // once this returns. The wipe below covers the paths where generateBytes threw.
            SecureBytes.adopt(output)
        } finally {
            passwordBytes.wipe()
            output.wipe()
        }
    }

    /** Encodes without ever materialising the passphrase as an immutable, unwipeable String. */
    private fun toUtf8(chars: CharArray): ByteArray {
        val encoded = StandardCharsets.UTF_8.encode(CharBuffer.wrap(chars))
        val bytes = ByteArray(encoded.remaining())
        encoded.get(bytes)
        if (encoded.hasArray()) encoded.array().wipe()
        return bytes
    }

    /*
     * Floors, not preferences. These bound what this build will honour from a *file header* - a
     * backup that declares 8 KiB of memory is either damaged or crafted, and deriving from it would
     * hand an attacker a cheap key. The upper bounds stop a hostile header from demanding an
     * allocation that kills the process.
     */
    private const val MIN_MEMORY_KIB = 16 * 1024
    private const val MAX_ITERATIONS = 32
    private const val MAX_PARALLELISM = 16

    /** The largest memory cost this build will attempt for a file that asks for one. */
    const val MAX_MEMORY_KIB = 512 * 1024
}
