package com.personal.bubuprotect.ui.autofill

import android.content.Intent
import android.os.Bundle
import android.os.Parcelable
import android.view.autofill.AutofillManager
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import coil.ImageLoader
import com.personal.bubuprotect.R
import com.personal.bubuprotect.core.autofill.AutofillIntents
import com.personal.bubuprotect.core.autofill.AutofillResponder
import com.personal.bubuprotect.core.autofill.CapturedCredential
import com.personal.bubuprotect.core.autofill.FillSpec
import com.personal.bubuprotect.core.autofill.PendingCapture
import com.personal.bubuprotect.core.crypto.VaultKeyManager
import com.personal.bubuprotect.core.security.SecureWindow
import com.personal.bubuprotect.domain.model.VaultItem
import com.personal.bubuprotect.session.VaultSession
import com.personal.bubuprotect.ui.components.LocalBubuImageLoader
import com.personal.bubuprotect.ui.theme.BubuProtectTheme
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import timber.log.Timber

/**
 * Everything autofill needs a screen for: unlocking, choosing an entry, and saving a new one.
 *
 * ### Why one activity and not three
 *
 * All four modes share the same preamble - the vault has to be open before any of them can do
 * anything - and the vault being locked is the *normal* case here, not the exception. Splitting
 * them would mean four copies of "wait for an unlock, then continue", and four chances for one of
 * them to continue without waiting.
 *
 * ### Most of its life is invisible
 *
 * [AutofillIntents.MODE_RESOLVE] is the common path: the user taps a suggestion, this activity
 * starts with the vault already open, decrypts one entry, returns a dataset and finishes without
 * ever drawing. That is why the theme is translucent and why [Stage.Working] renders nothing at all
 * - a spinner that appears for two frames is worse than no spinner.
 *
 * ### What it hands back
 *
 * `EXTRA_AUTHENTICATION_RESULT`, carrying a `FillResponse` for a response-level authentication and a
 * `Dataset` for a dataset-level one. A cancelled screen returns nothing, which the framework reads
 * as "the user declined" and leaves the form alone - the correct outcome for a back press.
 */
class AutofillAuthActivity : AppCompatActivity() {

    private val responder: AutofillResponder by inject()
    private val session: VaultSession by inject()
    private val keyManager: VaultKeyManager by inject()
    private val imageLoader: ImageLoader by inject()

    private var stage by mutableStateOf<Stage>(Stage.Working)

    private val mode: Int get() = intent.getIntExtra(AutofillIntents.EXTRA_MODE, 0)
    private var spec: FillSpec? = null
    private var capture: CapturedCredential? = null

    /** Guards [advance] against the session flow re-announcing an unlock that is already handled. */
    private var advanced = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Before any content exists. This window shows the master passphrase field and, in the
        // picker, the labels of every login in the vault - on top of another app.
        SecureWindow.harden(this)

        if (!prepare()) return

        setContent {
            BubuProtectTheme {
                CompositionLocalProvider(LocalBubuImageLoader provides imageLoader) {
                    AutofillAuthHost(
                        stage = stage,
                        onPick = ::pick,
                        onSave = ::save,
                        onCancel = ::cancel
                    )
                }
            }
        }

        lifecycleScope.launch {
            session.isUnlocked.collect { unlocked ->
                if (unlocked) {
                    advance()
                } else {
                    // Covers the vault locking underneath an open picker as well as the first pass.
                    advanced = false
                    stage = Stage.Unlocking
                }
            }
        }
    }

    /**
     * Reads the request, and refuses the ones there is nothing to be done about.
     *
     * @return false when the activity has already finished.
     */
    private fun prepare(): Boolean {
        // No vault at all. Autofill is not the place to run first-time setup: the user is mid-login
        // in another app, and a "choose a master passphrase" screen on top of it is an ambush.
        if (!keyManager.isEnrolled) {
            cancel()
            return false
        }

        if (mode == AutofillIntents.MODE_SAVE) {
            capture = PendingCapture.claim(
                intent.getStringExtra(AutofillIntents.EXTRA_CAPTURE_TOKEN)
            )
            // Single-use tokens expire. A stale one means this intent was replayed or sat around too
            // long, and there is no credential left to save.
            if (capture == null) {
                cancel()
                return false
            }
            return true
        }

        spec = AutofillIntents.readSpec(intent)
        if (spec == null) {
            cancel()
            return false
        }
        return true
    }

    /** Runs once the vault is open, and does whatever this mode was launched for. */
    private fun advance() {
        if (advanced) return
        advanced = true

        lifecycleScope.launch {
            try {
                when (mode) {
                    AutofillIntents.MODE_UNLOCK -> {
                        val current = spec ?: return@launch cancel()
                        finishWith(responder.buildResponse(current, current.kind))
                    }

                    AutofillIntents.MODE_RESOLVE -> {
                        val current = spec ?: return@launch cancel()
                        val entryId = intent.getStringExtra(AutofillIntents.EXTRA_ENTRY_ID)
                        val dataset = entryId?.let { responder.datasetFor(it, current) }
                        if (dataset != null) finishWith(dataset) else cancel()
                    }

                    AutofillIntents.MODE_PICK -> {
                        val current = spec ?: return@launch cancel()
                        stage = Stage.Picking(
                            targetName = current.target.displayName,
                            items = responder.pickerItems(current, current.kind)
                        )
                    }

                    AutofillIntents.MODE_SAVE -> {
                        val current = capture ?: return@launch cancel()
                        stage = Stage.Saving(current)
                    }

                    else -> cancel()
                }
            } catch (failure: Throwable) {
                Timber.tag(TAG).w(failure, "Autofill authentication could not complete")
                cancel()
            }
        }
    }

    private fun pick(item: VaultItem) {
        val current = spec ?: return cancel()
        stage = Stage.Working
        lifecycleScope.launch {
            try {
                // Written before the dataset is built, so the choice survives even if the fill
                // itself fails. Next time this app asks, the entry is already the top suggestion.
                responder.rememberLink(current, item.id)
                val dataset = responder.datasetFor(item.id, current)
                if (dataset != null) finishWith(dataset) else cancel()
            } catch (failure: Throwable) {
                Timber.tag(TAG).w(failure, "Could not fill the chosen entry")
                cancel()
            }
        }
    }

    private fun save(label: String) {
        val current = capture ?: return cancel()
        stage = Stage.Working
        lifecycleScope.launch {
            try {
                responder.saveCapture(current, label)
                Toast.makeText(
                    this@AutofillAuthActivity,
                    R.string.autofill_save_done,
                    Toast.LENGTH_SHORT
                ).show()
            } catch (failure: Throwable) {
                Timber.tag(TAG).w(failure, "Could not save the captured credential")
            }
            // Nothing to hand back either way: a save request has no dataset to return, and the
            // framework is not waiting on a result.
            finish()
        }
    }

    private fun finishWith(result: Parcelable) {
        setResult(
            RESULT_OK,
            Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, result)
        )
        finish()
    }

    private fun cancel() {
        setResult(RESULT_CANCELED)
        finish()
    }

    private companion object {
        const val TAG = "Autofill"
    }
}

/** What the activity is showing. [Working] draws nothing, on purpose. */
internal sealed interface Stage {
    data object Working : Stage
    data object Unlocking : Stage
    data class Picking(val targetName: String, val items: List<VaultItem>) : Stage
    data class Saving(val capture: CapturedCredential) : Stage
}
