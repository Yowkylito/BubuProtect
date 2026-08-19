package com.personal.bubuprotect.core.autofill

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.CancellationSignal
import android.os.SystemClock
import android.service.autofill.AutofillService
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.SaveCallback
import android.service.autofill.SaveRequest
import com.personal.bubuprotect.domain.model.ItemKind
import com.personal.bubuprotect.ui.autofill.AutofillAuthActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import timber.log.Timber
import java.util.concurrent.atomic.AtomicInteger

/**
 * Fills credentials into other apps, so that *using* a password stops meaning copying one.
 *
 * ### The problem this exists to remove
 *
 * Without it, the only route from the vault to a login form is the clipboard, and
 * [com.personal.bubuprotect.core.util.SecureClipboard] is honest about what that costs: the value
 * leaves this app's sandbox into a buffer the focused app and the current IME can read. Every
 * password the user actually uses takes that trip.
 *
 * It also leaves the user as the matcher. Nothing about copy-and-paste checks that the app in front
 * of them is the app the credential belongs to, and nobody reliably tells `paypal.com` from
 * `paypa1.com` on a phone screen. Autofill moves that decision into [AutofillMatcher], where it is a
 * comparison against the package or domain the *system* reports - a check that cannot be talked out
 * of its answer.
 *
 * And it removes the reason people pick weak passwords. A generated twenty-four character secret is
 * free to use when it arrives with one tap and miserable when it has to be ferried by hand; the
 * generator this app already ships is only as good as its delivery.
 *
 * ### Why the vault is usually locked here
 *
 * The service shares a process, and therefore a [com.personal.bubuprotect.session.VaultSession],
 * with the app. By the time the user is in another app typing a login, the auto-lock has almost
 * always fired. That is working as intended - the locked path is the normal path, and it costs one
 * authentication.
 *
 * The building of responses lives in [AutofillResponder], because the activity that runs after that
 * authentication has to produce exactly what this class would have produced with the vault open.
 */
class BubuAutofillService : AutofillService() {

    private val responder: AutofillResponder by inject()

    private lateinit var scope: CoroutineScope
    private val requestCode = AtomicInteger()

    override fun onCreate() {
        super.onCreate()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onFillRequest(
        request: FillRequest,
        cancellationSignal: CancellationSignal,
        callback: FillCallback
    ) {
        val parsed = parse(request.fillContexts.lastOrNull()?.structure)
        if (parsed == null) {
            callback.onSuccess(null)
            return
        }

        val kind = requestedKind(parsed)
        val spec = FillSpec.from(parsed, kind)

        if (responder.isLocked) {
            // Nothing can be read, so the whole response is one authentication entry. What comes
            // back from it is the complete response, built with the vault open.
            callback.onSuccess(responder.lockedResponse(spec, kind))
            return
        }

        val job = scope.launch {
            val response: FillResponse? = try {
                responder.buildResponse(spec, kind)
            } catch (cancelled: CancellationException) {
                // Rethrown rather than swallowed by the catch below. A cancelled request must end
                // *without* answering: the screen it was about is gone, and calling back now would
                // attach a response to whatever replaced it.
                throw cancelled
            } catch (failure: Throwable) {
                // A fill request is speculative - the user has not asked for anything yet. Failing
                // it quietly costs one suggestion; letting it throw takes down the service and with
                // it every later request until the process restarts.
                Timber.tag(TAG).w(failure, "Could not build a fill response")
                null
            }
            callback.onSuccess(response)
        }

        // Honoured so a slow read cannot answer a question about a screen that has gone away.
        cancellationSignal.setOnCancelListener { job.cancel() }
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        val parsed = parse(request.fillContexts.lastOrNull()?.structure)
        val secret = parsed?.valueOf(FieldRole.PASSWORD)
        if (parsed == null || secret.isNullOrBlank()) {
            // Nothing worth keeping. Reported as success because the save did not *fail* - there was
            // no credential in the form, and an error here would be a lie the user has to dismiss.
            callback.onSuccess()
            return
        }

        val token = PendingCapture.offer(
            CapturedCredential(
                target = parsed.target,
                username = parsed.valueOf(FieldRole.USERNAME),
                secret = secret,
                capturedAt = SystemClock.elapsedRealtime()
            )
        )

        // Only the token travels. The credential itself stays on this heap - see [PendingCapture].
        val intent = Intent(this, AutofillAuthActivity::class.java)
            .putExtra(AutofillIntents.EXTRA_MODE, AutofillIntents.MODE_SAVE)
            .putExtra(AutofillIntents.EXTRA_CAPTURE_TOKEN, token)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // Let the system launch it: it knows when the save dialog has finished animating away,
            // and it is not subject to the background-activity-start rules that bind this service.
            callback.onSuccess(
                PendingIntent.getActivity(
                    this,
                    requestCode.incrementAndGet(),
                    intent,
                    PendingIntent.FLAG_CANCEL_CURRENT or
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            PendingIntent.FLAG_MUTABLE
                        } else {
                            0
                        }
                ).intentSender
            )
        } else {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            callback.onSuccess()
        }
    }

    /**
     * Parses a request, refusing the ones this service must not answer.
     *
     * Its own package is excluded first: the vault authenticates its own fields, and an autofill
     * dropdown offering to complete the master passphrase box would be a second, weaker way in.
     */
    private fun parse(structure: android.app.assist.AssistStructure?): ParsedStructure? {
        val requester = structure?.activityComponent?.packageName ?: return null
        if (requester == packageName) return null
        return StructureParser.parse(structure, PackageSignatures.of(this, requester))
    }

    /**
     * Which vault kind can answer this request.
     *
     * The focused field decides when there is one, because a checkout page often carries both a card
     * block and a "sign in to check out faster" block - and someone standing in the CVV box wants
     * cards, not logins. With nothing focused the request is page-level and logins win, since a form
     * with both is usually asking for that first.
     */
    private fun requestedKind(parsed: ParsedStructure): ItemKind {
        parsed.fields.firstOrNull(DetectedField::isFocused)?.let { return it.role.servedBy }
        return if (parsed.hasLoginFields) ItemKind.LOGIN else ItemKind.CARD
    }

    private companion object {
        const val TAG = "Autofill"
    }
}
