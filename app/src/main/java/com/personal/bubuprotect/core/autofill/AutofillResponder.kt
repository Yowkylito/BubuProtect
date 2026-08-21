package com.personal.bubuprotect.core.autofill

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.autofill.Dataset
import android.service.autofill.FillResponse
import android.service.autofill.SaveInfo
import com.personal.bubuprotect.core.autofill.DatasetCompat.putAuthentication
import com.personal.bubuprotect.core.autofill.DatasetCompat.putValue
import android.view.autofill.AutofillId
import android.view.autofill.AutofillValue
import com.personal.bubuprotect.R
import com.personal.bubuprotect.core.otp.OtpAuthUri
import com.personal.bubuprotect.core.otp.TotpGenerator
import com.personal.bubuprotect.domain.model.ItemKind
import com.personal.bubuprotect.domain.model.VaultDraft
import com.personal.bubuprotect.domain.model.VaultEntry
import com.personal.bubuprotect.domain.model.VaultItem
import com.personal.bubuprotect.domain.model.totpSource
import com.personal.bubuprotect.domain.repository.VaultRepository
import com.personal.bubuprotect.session.VaultSession
import kotlinx.coroutines.flow.first
import java.util.concurrent.atomic.AtomicInteger

/**
 * Everything autofill does that touches the vault.
 *
 * Extracted from [BubuAutofillService] because the service is not the only thing that has to build
 * these objects: after an unlock, [com.personal.bubuprotect.ui.autofill.AutofillAuthActivity] has to
 * produce the *same* response the service would have produced had the vault been open. Two
 * implementations of that would drift, and the way that drift shows up is a dataset whose
 * presentation says one entry and whose values come from another.
 */
internal class AutofillResponder(
    private val context: Context,
    private val session: VaultSession,
    private val repository: VaultRepository
) {

    private val requestCode = AtomicInteger()

    val isLocked: Boolean get() = session.handle.value == null

    /**
     * The response for a request, assuming the vault is open.
     *
     * Datasets carry no values - see [BubuAutofillService] for why every one of them authenticates
     * before a secret is produced.
     */
    suspend fun buildResponse(spec: FillSpec, kind: ItemKind): FillResponse {
        val builder = FillResponse.Builder()
        saveInfoFor(spec)?.let(builder::setSaveInfo)

        val matches = matches(spec, kind).take(MAX_SUGGESTIONS)
        matches.forEach { match ->
            builder.addDataset(
                placeholderDataset(
                    spec = spec,
                    title = match.item.label,
                    subtitle = match.item.subtitle.ifBlank { spec.target.displayName },
                    kind = match.item.kind,
                    mode = AutofillIntents.MODE_RESOLVE,
                    entryId = match.item.id
                )
            )
        }

        /*
         * A separate row per matching entry that actually holds a seed.
         *
         * Conditional on the seed existing, which is what preserves the old protection: an entry with
         * no 2FA still offers nothing for a one-time-code box, so a password can never land there.
         */
        if (FieldRole.OTP in spec.roles) {
            matches.forEach { match ->
                if (!hasSeed(match.item.id)) return@forEach
                builder.addDataset(
                    placeholderDataset(
                        spec = spec,
                        title = match.item.label,
                        subtitle = context.getString(R.string.autofill_code_subtitle),
                        kind = match.item.kind,
                        mode = AutofillIntents.MODE_RESOLVE,
                        entryId = match.item.id,
                        codeOnly = true
                    )
                )
            }
        }

        // Always present, even when something matched: it is the second account on a site, and it is
        // the only path by which an unmatched native app can become a matched one, because picking
        // here is what writes the link.
        builder.addDataset(
            placeholderDataset(
                spec = spec,
                title = context.getString(R.string.autofill_browse_title),
                subtitle = if (matches.isNotEmpty()) {
                    context.getString(R.string.autofill_browse_subtitle_more)
                } else {
                    context.getString(
                        R.string.autofill_browse_subtitle_none,
                        spec.target.displayName
                    )
                },
                kind = kind,
                mode = AutofillIntents.MODE_PICK,
                entryId = null
            )
        )

        return builder.build()
    }

    /** The one-entry response used when the vault was locked and could not be read. */
    fun lockedResponse(spec: FillSpec, kind: ItemKind): FillResponse =
        FillResponse.Builder()
            .apply { saveInfoFor(spec)?.let(::setSaveInfo) }
            .putAuthentication(
                spec.autofillIds,
                authIntent(AutofillIntents.MODE_UNLOCK, spec).intentSender,
                AutofillPresentations.row(
                    context = context,
                    title = context.getString(R.string.autofill_unlock_title),
                    subtitle = context.getString(R.string.autofill_unlock_subtitle),
                    kind = kind
                )
            )
            .build()

    /**
     * Whether an entry carries a 2FA seed.
     *
     * Decrypts the entry to find out, which is the cost of the seed living inside the encrypted
     * extras blob rather than in a plain column. That is the right trade: a plain `has_totp` column
     * would let anyone who defeated SQLCipher enumerate which accounts the user has a second factor
     * on, which is a map of exactly where to concentrate an attack.
     */
    private suspend fun hasSeed(entryId: String): Boolean =
        runCatching { repository.getEntry(entryId)?.totpSource() != null }.getOrDefault(false)

    /** Entries worth offering, best first. */
    suspend fun matches(spec: FillSpec, kind: ItemKind): List<AutofillMatch> {
        val items = repository.observeItems().first()
        val linked = repository.linkedEntryIds(spec.target.key, spec.target.signature)
        return AutofillMatcher.match(items, spec.target, linked, kind)
    }

    /**
     * What the picker shows: the matches, then everything else of the right kind.
     *
     * The tail is there because the matcher being wrong has to be recoverable. An entry saved with
     * no website, for an app whose package name resembles nothing, would otherwise be unreachable
     * from the one screen whose entire job is to reach it.
     */
    suspend fun pickerItems(spec: FillSpec, kind: ItemKind): List<VaultItem> {
        val ranked = matches(spec, kind)
        val rankedIds = ranked.mapTo(mutableSetOf()) { it.item.id }
        val rest = repository.observeItems().first()
            .filter { it.kind == kind && it.id !in rankedIds }
            .sortedBy { it.label.lowercase() }
        return ranked.map(AutofillMatch::item) + rest
    }

    /**
     * Decrypts one entry and builds the dataset that actually fills the form.
     *
     * The only place in this feature where a secret exists in the clear, and it is reached only
     * after the user has tapped a specific entry.
     *
     * @return null when the entry is gone, or holds nothing for any field on this screen. Returning
     *   an empty dataset instead would have the framework report a fill that put nothing anywhere.
     */
    suspend fun datasetFor(entryId: String, spec: FillSpec): Dataset? {
        val entry = repository.getEntry(entryId) ?: return null

        val values = spec.fields.mapNotNull { field ->
            entry.valueFor(field.role)
                ?.takeIf(String::isNotEmpty)
                ?.let { field.autofillId to AutofillValue.forText(it) }
        }
        if (values.isEmpty()) return null

        val builder = DatasetCompat.datasetBuilder(
            AutofillPresentations.row(
                context = context,
                title = entry.label,
                subtitle = entry.identity.ifBlank { spec.target.displayName },
                kind = entry.kind
            )
        )
        values.forEach { (id, value) -> builder.putValue(id, value) }
        return builder.build()
    }

    /**
     * The dataset that fills a one-time code, and nothing else.
     *
     * @return null when the entry has no seed, or its seed no longer parses. Both mean there is no
     *   code to offer, and an empty dataset would report a fill that put nothing anywhere.
     */
    suspend fun codeDatasetFor(entryId: String, spec: FillSpec, nowMillis: Long): Dataset? {
        val otpId = spec.idOf(FieldRole.OTP) ?: return null
        val entry = repository.getEntry(entryId) ?: return null
        val secret = entry.totpSource()?.let(OtpAuthUri::parse) ?: return null
        val seed = secret.secretBytes() ?: return null

        val code = runCatching {
            TotpGenerator.code(
                secret = seed,
                timeMillis = nowMillis,
                period = secret.periodSeconds,
                digits = secret.digits,
                algorithm = secret.algorithm
            )
        }.getOrNull() ?: return null

        val builder = DatasetCompat.datasetBuilder(
            AutofillPresentations.row(
                context = context,
                title = entry.label,
                // The code itself is never in the presentation. That row is drawn on top of another
                // app's window, and a one-time code sitting there is readable by anyone looking at
                // the screen - and by the app underneath if it can capture it.
                subtitle = context.getString(R.string.autofill_code_subtitle),
                kind = entry.kind
            )
        )
        builder.putValue(otpId, AutofillValue.forText(code))
        return builder.build()
    }

    /** Called when the user picks from the picker - never inferred. */
    suspend fun rememberLink(spec: FillSpec, entryId: String) {
        repository.rememberAutofillLink(spec.target.key, entryId, spec.target.signature)
    }

    /**
     * Stores a credential captured from another app.
     *
     * The website is recorded only for a web target. Writing a package name into the `website`
     * column would put something that is not a URL where every other part of the app expects one -
     * the detail screen offers to open it, the matcher parses it as a host. The link table is the
     * right home for "this belongs to that app", and [rememberLink] puts it there.
     */
    suspend fun saveCapture(capture: CapturedCredential, label: String): String {
        val id = repository.save(
            VaultDraft(
                kind = ItemKind.LOGIN,
                label = label.trim().ifEmpty { capture.target.displayName },
                identity = capture.username.orEmpty(),
                secret = capture.secret,
                website = capture.target.webDomain
            )
        )
        repository.rememberAutofillLink(capture.target.key, id, capture.target.signature)
        return id
    }

    /**
     * Reads whichever field of the entry answers [role].
     *
     * The kind check comes first and is not a formality. Every kind stores its primary secret in the
     * same slot, so without it a Wi-Fi entry reached through the picker would hand its network key
     * to a field classified as a password and nothing in the types would object.
     */
    private fun VaultEntry.valueFor(role: FieldRole): String? {
        if (kind != role.servedBy) return null
        return when (role) {
            FieldRole.USERNAME -> identity
            FieldRole.PASSWORD -> secret
            FieldRole.CARD_NUMBER -> secret
            FieldRole.CARD_HOLDER -> identity
            // These keys are the ones ItemKind.CARD declares in its field list. They are persisted
            // strings, so they are matched here rather than renamed.
            FieldRole.CARD_EXPIRY -> extras["expiry"]
            FieldRole.CARD_SECURITY_CODE -> extras["cvv"]

            /*
             * Never filled here, and returning null is how that rule is enforced rather than
             * remembered.
             *
             * A login form and its one-time-code box can appear in the same structure. Filling both
             * from one tap would mean a single successful phishing page captures a complete, usable
             * session - password and second factor together - which is exactly the outcome 2FA exists
             * to prevent. Codes come from [codeDatasetFor], which the user reaches as a separate,
             * separately authenticated action.
             */
            FieldRole.OTP -> null
        }
    }

    private fun placeholderDataset(
        spec: FillSpec,
        title: String,
        subtitle: String,
        kind: ItemKind,
        mode: Int,
        entryId: String?,
        codeOnly: Boolean = false
    ): Dataset {
        val builder = DatasetCompat.datasetBuilder(
            AutofillPresentations.row(context, title, subtitle, kind)
        )
        // Null values throughout: the dataset authenticates, so the framework wants the ids it will
        // fill and nothing more until that authentication returns.
        /*
         * Every field is declared, including the code box on a credential row.
         *
         * The separation between a password and a code is enforced on the *values* - `valueFor` returns
         * null for OTP, and `codeDatasetFor` fills nothing else - not on which ids a placeholder
         * mentions. Filtering here as well would look tidier and would break: on a screen whose only
         * fillable field is the code box, the credential placeholder would declare nothing at all, and
         * `Dataset.Builder.build()` rejects a dataset with no fields.
         */
        spec.fields.forEach { builder.putValue(it.autofillId, null) }
        builder.setAuthentication(authIntent(mode, spec, entryId, codeOnly).intentSender)
        return builder.build()
    }

    /**
     * Offers to store a credential the user typed elsewhere.
     *
     * Logins only, deliberately. A checkout form yields a card number and an expiry, but the entry
     * the user actually wants also has a security code, an issuer and a name - and a card that looks
     * saved while missing the field needed to use it is worse than no card at all. Cards come from
     * the NFC scan path, which reads the chip and gets those fields right.
     */
    private fun saveInfoFor(spec: FillSpec): SaveInfo? {
        val passwordId = spec.idOf(FieldRole.PASSWORD) ?: return null
        val usernameId = spec.idOf(FieldRole.USERNAME)

        val type = if (usernameId != null) {
            SaveInfo.SAVE_DATA_TYPE_USERNAME or SaveInfo.SAVE_DATA_TYPE_PASSWORD
        } else {
            SaveInfo.SAVE_DATA_TYPE_PASSWORD
        }

        return SaveInfo.Builder(type, arrayOf(passwordId))
            .apply { usernameId?.let { setOptionalIds(arrayOf<AutofillId>(it)) } }
            // Most login screens swap the form out rather than navigate, so "every field went away"
            // is the submit signal. Without this the save prompt often never fires at all.
            .setFlags(SaveInfo.FLAG_SAVE_ON_ALL_VIEWS_INVISIBLE)
            .build()
    }

    private fun authIntent(
        mode: Int,
        spec: FillSpec,
        entryId: String? = null,
        codeOnly: Boolean = false
    ): PendingIntent {
        val intent = Intent(context, AUTH_ACTIVITY)
            .putExtra(AutofillIntents.EXTRA_MODE, mode)
            .putExtra(AutofillIntents.EXTRA_CODE_ONLY, codeOnly)
            .also { AutofillIntents.putSpec(it, spec) }
        entryId?.let { intent.putExtra(AutofillIntents.EXTRA_ENTRY_ID, it) }

        return PendingIntent.getActivity(
            context,
            // A fresh request code per intent. `PendingIntent` equality ignores extras, so reusing
            // one would let a new request be answered with a previous screen's field ids - which is
            // the classic way an autofill service types a password into the wrong box.
            requestCode.incrementAndGet(),
            intent,
            PendingIntent.FLAG_CANCEL_CURRENT or mutabilityFlag()
        )
    }

    /**
     * Autofill authentication intents have to be mutable: the framework adds its own extras before
     * launching them, and an immutable one would arrive stripped. Everything this app relies on is
     * put there by [AutofillIntents], not by the system.
     */
    private fun mutabilityFlag(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0

    private companion object {
        val AUTH_ACTIVITY = com.personal.bubuprotect.ui.autofill.AutofillAuthActivity::class.java

        /**
         * A dropdown is a glance, not a list. Past a handful the user is scrolling a picker inside a
         * suggestion bar, which is worse than the one this app draws itself.
         */
        const val MAX_SUGGESTIONS = 6
    }
}
