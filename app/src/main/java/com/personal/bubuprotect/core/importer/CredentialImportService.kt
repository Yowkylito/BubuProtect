package com.personal.bubuprotect.core.importer

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.IOException
import java.io.InputStream

/**
 * Reads and, if the user asks, destroys an export file.
 *
 * ### The file is the problem
 *
 * A password manager's CSV export is every credential the user owns, in the clear, sitting in
 * Downloads - which on most phones means it is also in Google Drive within the minute. It is the
 * single most dangerous object this app will ever touch, and it exists *because* of a feature this
 * app added.
 *
 * So the import flow ends by offering to delete it, and [delete] is not a nicety bolted on the end.
 * Leaving the file behind would mean the act of adopting a password manager measurably reduced the
 * user's security until they happened to remember to clean up.
 *
 * Everything goes through a user-picked [Uri]. The app names no directory, holds no storage
 * permission, and cannot read or delete anything the user did not point at.
 */
class CredentialImportService(private val context: Context) {

    /**
     * @throws UnreadableImportException if the document is too large or not text.
     *
     * Bounded, because the picker can return any document on the device. Read as UTF-8 with
     * replacement rather than strictly: a Windows-encoded export with one stray byte should import
     * with one odd character, not fail entirely.
     */
    suspend fun read(source: Uri): String = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(source)
            ?.use { stream -> stream.readAtMost(MAX_FILE_BYTES) }
            ?: throw IOException("Could not open that file")

        if (bytes == null) {
            throw UnreadableImportException(
                "That file is too large to be a password export. Bubu reads files up to 32 MB."
            )
        }
        String(bytes, Charsets.UTF_8)
    }

    /**
     * Deletes the export.
     *
     * @return false when the provider refused, which is common and not an error: a document opened
     *   read-only, or one on a provider with no delete support, simply cannot be removed by this app.
     *   The caller tells the user to do it themselves rather than pretending it worked.
     */
    suspend fun delete(source: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            DocumentsContract.deleteDocument(context.contentResolver, source)
        } catch (failure: Exception) {
            // Deliberately broad. Providers throw a variety of things here - SecurityException,
            // UnsupportedOperationException, IllegalArgumentException - and every one of them means
            // the same thing to the user, who still needs to be told the file is still there.
            Timber.tag(TAG).i(failure, "Could not delete the import source")
            false
        }
    }

    private fun InputStream.readAtMost(limit: Int): ByteArray? {
        val buffer = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(CHUNK_BYTES)
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
        const val TAG = "Import"

        /**
         * Generous but finite. A 32 MB CSV is roughly a quarter of a million credentials; anything
         * larger is not a password export and holding it in memory as a `String` would be an OOM
         * kill on the one screen that must not crash.
         */
        const val MAX_FILE_BYTES = 32 * 1024 * 1024
        const val CHUNK_BYTES = 16 * 1024
    }
}
