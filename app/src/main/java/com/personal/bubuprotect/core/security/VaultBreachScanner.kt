package com.personal.bubuprotect.core.security

import com.personal.bubuprotect.domain.model.BreachStatus
import com.personal.bubuprotect.domain.model.VaultItem
import com.personal.bubuprotect.domain.repository.VaultLockedException
import com.personal.bubuprotect.domain.repository.VaultRepository
import com.personal.bubuprotect.domain.repository.VaultTamperedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import timber.log.Timber

/** Why a whole scan gave up, as opposed to one entry failing. */
enum class BreachScanFailure {
    /** The vault locked underneath the scan. Expected, not an error worth apologising for. */
    VAULT_LOCKED,

    /** Repeated network failures. Almost always the device being offline. */
    UNREACHABLE
}

sealed interface BreachScanState {
    data object Idle : BreachScanState

    data class Running(val completed: Int, val total: Int) : BreachScanState {
        val fraction: Float get() = if (total == 0) 0f else completed.toFloat() / total
    }

    /**
     * @param skipped entries whose lookup failed individually - a malformed response, or one request
     *   timing out on an otherwise working connection. Reported rather than swallowed, because
     *   "8 of 10 checked" and "10 of 10 checked" are different answers to "is my vault clean".
     */
    data class Finished(
        val checked: Int,
        val breached: Int,
        val skipped: Int
    ) : BreachScanState

    data class Failed(val reason: BreachScanFailure, val completed: Int) : BreachScanState
}

/**
 * Walks the vault and asks Have I Been Pwned about every password-shaped secret in it.
 *
 * ### One entry in memory at a time
 *
 * The obvious implementation - decrypt everything, then check everything - would put every password
 * in the vault in the heap simultaneously for the length of a network-bound loop. That is precisely
 * the invariant [com.personal.bubuprotect.ui.vm.EntryDetailViewModel] documents holding onto, so
 * this decrypts one entry, checks it, records the verdict, and lets it go before touching the next.
 * The cost is that identical passwords are looked up twice; the benefit is that a heap dump taken
 * during a scan of a fifty-entry vault contains one password rather than fifty.
 *
 * ### Sequential, and slowly
 *
 * Requests are serialised with a pause between them. Parallelism would finish a personal vault a few
 * seconds sooner and, in exchange, would emit a recognisable burst of same-size requests to one host
 * - which is the traffic-analysis signature the `Add-Padding` header exists to blur. The pause also
 * keeps the app from looking like abuse to a free public API it is a guest of.
 *
 * ### Failures are per entry until they are not
 *
 * One bad lookup skips one entry. [CONSECUTIVE_FAILURE_LIMIT] in a row means the network is gone
 * rather than the corpus being odd, and the scan stops instead of grinding through forty guaranteed
 * timeouts.
 */
class VaultBreachScanner(
    private val repository: VaultRepository,
    private val checker: PwnedPasswordChecker,
    private val clock: () -> Long = System::currentTimeMillis
) {

    /**
     * @param items the list as the vault sees it. Only [VaultItem.isBreachCheckable] rows are
     *   visited, and unless [force] is set, only those whose verdict is missing or stale.
     */
    fun scan(items: List<VaultItem>, force: Boolean = false): Flow<BreachScanState> = flow {
        val now = clock()
        val targets = items.filter {
            it.isBreachCheckable && (force || it.breach.isDueForRecheck(now))
        }

        if (targets.isEmpty()) {
            emit(BreachScanState.Finished(checked = 0, breached = 0, skipped = 0))
            return@flow
        }

        var checked = 0
        var breached = 0
        var skipped = 0
        var consecutiveFailures = 0

        emit(BreachScanState.Running(completed = 0, total = targets.size))

        targets.forEachIndexed { index, item ->
            currentCoroutineContext().ensureActive()

            if (index > 0) delay(REQUEST_SPACING_MILLIS)

            val outcome = try {
                checkOne(item.id)
            } catch (locked: VaultLockedException) {
                emit(BreachScanState.Failed(BreachScanFailure.VAULT_LOCKED, checked))
                return@flow
            }

            when (outcome) {
                is Outcome.Checked -> {
                    consecutiveFailures = 0
                    checked++
                    if (outcome.exposureCount > 0L) breached++
                }

                Outcome.NotApplicable -> Unit

                // Already waited inside checkOne. Reported as skipped so the summary stays honest
                // about coverage, but the streak resets - the service is up, just busy.
                Outcome.Throttled -> {
                    skipped++
                    consecutiveFailures = 0
                }

                Outcome.Failed -> {
                    skipped++
                    consecutiveFailures++
                    if (consecutiveFailures >= CONSECUTIVE_FAILURE_LIMIT) {
                        emit(BreachScanState.Failed(BreachScanFailure.UNREACHABLE, checked))
                        return@flow
                    }
                }
            }

            emit(BreachScanState.Running(completed = index + 1, total = targets.size))
        }

        emit(BreachScanState.Finished(checked = checked, breached = breached, skipped = skipped))
    }.flowOn(Dispatchers.IO)

    private sealed interface Outcome {
        data class Checked(val exposureCount: Long) : Outcome

        /** Deleted mid-scan, empty, tampered, or a kind that has no business being looked up. */
        data object NotApplicable : Outcome

        /** The service asked for a pause and we took it. Nothing was learned; nothing went wrong. */
        data object Throttled : Outcome

        data object Failed : Outcome
    }

    private suspend fun checkOne(entryId: String): Outcome {
        val entry = try {
            repository.getEntry(entryId)
        } catch (tampered: VaultTamperedException) {
            // A row that fails its integrity check must not be silently marked safe, and its
            // plaintext is not trustworthy enough to send a hash prefix of. Leave it unchecked; the
            // detail screen is where the user is told about tampering.
            return Outcome.NotApplicable
        } ?: return Outcome.NotApplicable

        if (!entry.isBreachCheckable) return Outcome.NotApplicable

        val exposureCount = try {
            when (val result = checker.check(entry.secret)) {
                PwnedPasswordResult.NotFound -> 0L
                is PwnedPasswordResult.Found -> result.exposureCount
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (locked: VaultLockedException) {
            throw locked
        } catch (throttled: PwnedPasswordsThrottledException) {
            // Being asked to slow down is not a failure, and must not count toward the consecutive
            // failure limit - a vault big enough to trip the rate limit would otherwise abandon the
            // scan at exactly the point it started working.
            delay(
                throttled.retryAfterSeconds
                    ?.times(1_000L)
                    ?.coerceAtMost(MAX_BACKOFF_MILLIS)
                    ?: DEFAULT_BACKOFF_MILLIS
            )
            return Outcome.Throttled
        } catch (failure: Throwable) {
            // Never the throwable or its message: an HTTP stack's exception text can carry the
            // requested URL, and that URL contains the hash prefix.
            Timber.tag(TAG).w("Breach lookup failed (%s)", failure::class.java.simpleName)
            return Outcome.Failed
        }

        // Dropped if the user edited this password while the request was in flight - the verdict
        // would then describe a secret that is no longer in the row.
        repository.recordBreachCheck(
            id = entry.id,
            exposureCount = exposureCount,
            secretUpdatedAt = entry.secretUpdatedAt
        )
        return Outcome.Checked(exposureCount)
    }

    private companion object {
        const val TAG = "VaultBreachScanner"

        /**
         * Long enough that a vault scan does not read as a burst, short enough that fifty entries
         * finish inside a few seconds. Multiplied out: 50 entries is roughly 15s of spacing plus
         * whatever the requests themselves cost.
         */
        const val REQUEST_SPACING_MILLIS = 300L

        const val CONSECUTIVE_FAILURE_LIMIT = 3

        /** Used when the service throttles without saying for how long. */
        const val DEFAULT_BACKOFF_MILLIS = 2_000L

        /** Ceiling on an honoured `Retry-After`, so one header cannot stall a scan indefinitely. */
        const val MAX_BACKOFF_MILLIS = 30_000L
    }
}

/** True when nothing in this list has ever been checked. Drives the "run your first scan" copy. */
fun List<VaultItem>.hasNeverBeenScanned(): Boolean =
    filter { it.isBreachCheckable }
        .all { it.breach == BreachStatus.Unchecked }
