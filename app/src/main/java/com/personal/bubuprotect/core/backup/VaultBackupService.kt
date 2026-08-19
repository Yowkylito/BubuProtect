package com.personal.bubuprotect.core.backup

import android.content.Context
import android.net.Uri
import com.personal.bubuprotect.core.crypto.wipe
import com.personal.bubuprotect.domain.model.VaultEntry
import com.personal.bubuprotect.domain.repository.VaultRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Export and restore, over a caller-chosen document.
 *
 * ### Why the Storage Access Framework rather than a path
 *
 * Everything here goes through a [Uri] the user picked in the system file picker. The app never
 * names a directory, holds no storage permission, and cannot write a backup anywhere the user did
 * not explicitly point at. That matters more than usual for this feature: the file is the entire
 * vault, and an app that could quietly drop one in shared storage would have undone its own threat
 * model. It also means the destination - local, SD card, Drive, whatever the picker offers - is the
 * user's decision to make and ours to stay out of.
 *
 * ### Where the plaintext lives, and for how long
 *
 * An export is the one operation that necessarily holds every secret in the clear at once: an
 * archive of N entries cannot be built without N entries. The window is kept as narrow as the
 * platform allows - decrypt, serialise, seal, wipe - and [VaultBackupEnvelope.seal] wipes the
 * serialised buffer even when it throws.
 *
 * The `String`s inside the decrypted [VaultEntry] list cannot be wiped; Java strings are immutable
 * and the vault already holds secrets that way throughout. That is a known limit of the existing
 * design rather than one this file introduces, and it is bounded by the same thing that bounds the
 * rest: the process, which loses its keys on lock.
 */
class VaultBackupService(
    private val context: Context,
    private val repository: VaultRepository,
    private val clock: () -> Long = System::currentTimeMillis
) {

    /**
     * Seals the whole vault into [destination].
     *
     * @param passphrase the user's master passphrase. Not wiped - the caller owns it, and the UI
     *   usually needs it a moment longer for its own confirmation field.
     * @return how many entries were written.
     */
    suspend fun export(destination: Uri, passphrase: CharArray): Int =
        withContext(Dispatchers.IO) {
            val entries = repository.exportEntries()
            if (entries.isEmpty()) throw EmptyVaultException()

            val payload = VaultBackupPayload.encode(entries, exportedAt = clock())
            // seal() takes ownership of `payload` and wipes it, success or failure.
            val sealed = VaultBackupEnvelope.seal(payload, passphrase)

            try {
                // "wt" truncates. Without it, overwriting a larger existing backup leaves the tail of
                // the old file behind, producing bytes that pass the magic check and then fail the
                // GCM tag - which reads to the user as "my backup is corrupt" rather than "that
                // write went wrong".
                context.contentResolver.openOutputStream(destination, "wt")
                    ?.use { it.write(sealed) }
                    ?: throw IOException("Could not open the chosen file for writing")
            } finally {
                sealed.wipe()
            }
            entries.size
        }

    /**
     * Reads and decrypts a backup. Does **not** write anything to the vault - see [restoreInto].
     *
     * Split in two because the restore flow needs the entries before a vault exists to put them in:
     * the user picks a file and types the passphrase that protects it, and only once that succeeds
     * does the app enroll and create a database. Decrypting first also means a wrong passphrase
     * costs nothing but a failed tag check.
     */
    suspend fun read(source: Uri, passphrase: CharArray): VaultBackupPayload.Decoded =
        withContext(Dispatchers.IO) {
            val bytes = context.contentResolver.openInputStream(source)
                ?.use { stream ->
                    // Bounded read: the picker can hand back any document on the device, including
                    // something enormous that is not a backup at all.
                    val buffer = stream.readAtMost(MAX_FILE_BYTES)
                        ?: throw CorruptBackupException("That file is too large to be a backup")
                    buffer
                }
                ?: throw IOException("Could not open the chosen file")

            if (!VaultBackupEnvelope.looksLikeBackup(bytes)) {
                throw CorruptBackupException("That file is not a Bubu Protect backup")
            }

            val payload = VaultBackupEnvelope.open(bytes, passphrase)
            try {
                VaultBackupPayload.decode(payload)
            } finally {
                payload.wipe()
            }
        }

    /** Writes already-decrypted entries into the currently unlocked vault. */
    suspend fun restoreInto(entries: List<VaultEntry>): Int = repository.restoreEntries(entries)

    /**
     * A filename the picker pre-fills. Carries a date so successive exports do not silently
     * overwrite one another, and nothing else - not the entry count, not the device name. A backup
     * sitting in a shared folder should not advertise what it is a backup *of*.
     */
    fun suggestedFileName(dateStamp: String): String =
        "bubu-vault-$dateStamp.${VaultBackupEnvelope.FILE_EXTENSION}"

    private fun java.io.InputStream.readAtMost(limit: Int): ByteArray? {
        val buffer = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(DEFAULT_CHUNK)
        var total = 0
        while (true) {
            val read = read(chunk)
            if (read < 0) break
            total += read
            if (total > limit) return null
            buffer.write(chunk, 0, read)
        }
        return buffer.toByteArray()
    }

    private companion object {
        const val MAX_FILE_BYTES = 64 * 1024 * 1024
        const val DEFAULT_CHUNK = 16 * 1024
    }
}

/** Nothing to export. Surfaced rather than writing a valid backup of zero entries. */
class EmptyVaultException : IOException("There is nothing in the vault to back up")
