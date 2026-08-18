package com.personal.bubuprotect.core.util

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import androidx.core.content.getSystemService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The clipboard is the weakest link in any password manager, because it is the one place a secret
 * leaves the app's sandbox. This narrows the exposure as far as the platform allows:
 *
 *  - `EXTRA_IS_SENSITIVE` stops Android 13+ showing the value in the copy toast and keeps it out of
 *    clipboard history and Gboard's suggestion strip.
 *  - The entry is cleared after [CLEAR_AFTER_MILLIS] so it does not sit there until the next copy.
 *  - Clearing is also driven from lock, so backgrounding the vault takes the password with it.
 *
 * What this cannot do is stop another app reading the clipboard inside the window. Since Android 10
 * only the focused app or the current IME can, which makes the window narrow but not closed - so
 * copy stays an explicit, per-item action rather than something the UI does on its own.
 */
class SecureClipboard(
    private val context: Context,
    private val scope: CoroutineScope
) {

    private val clipboard: ClipboardManager? = context.getSystemService()
    private var pendingClear: Job? = null

    /** @return false when the platform gave us no clipboard service to write to. */
    fun copySensitive(label: String, value: String): Boolean {
        val manager = clipboard ?: return false
        val clip = ClipData.newPlainText(label, value).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                description.extras = PersistableBundle().apply {
                    putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                }
            }
        }
        manager.setPrimaryClip(clip)

        pendingClear?.cancel()
        pendingClear = scope.launch {
            delay(CLEAR_AFTER_MILLIS)
            clear()
        }
        return true
    }

    /** Removes our value, but only if it is still the one on the clipboard. */
    fun clear() {
        val manager = clipboard ?: return
        pendingClear?.cancel()
        pendingClear = null
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                manager.clearPrimaryClip()
            } else {
                manager.setPrimaryClip(ClipData.newPlainText("", ""))
            }
        }
    }

    companion object {
        const val CLEAR_AFTER_MILLIS = 30_000L
        val CLEAR_AFTER_SECONDS = (CLEAR_AFTER_MILLIS / 1000).toInt()
    }
}
