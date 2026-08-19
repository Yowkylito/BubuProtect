package com.personal.bubuprotect.session

import android.os.SystemClock
import com.personal.bubuprotect.core.autofill.PendingCapture
import com.personal.bubuprotect.core.crypto.FieldCipher
import com.personal.bubuprotect.core.crypto.VaultKeys
import com.personal.bubuprotect.core.util.SecureClipboard
import com.personal.bubuprotect.data.local.AutofillLinkDao
import com.personal.bubuprotect.data.local.EncryptedDatabaseFactory
import com.personal.bubuprotect.data.local.PasswordDao
import com.personal.bubuprotect.data.local.VaultDatabase
import com.personal.bubuprotect.domain.repository.VaultLockedException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import kotlinx.coroutines.withContext

/**
 * Owns everything that exists only while the vault is open: the keys, the SQLCipher session, and the
 * field cipher.
 *
 * "Locked" here means the decryption capability is gone from the process, not that a screen is
 * covering the data. On [lock] the database is closed, the derived passphrase is zeroed, and the
 * root key is wiped. Re-entering costs a real authentication because there is nothing left in memory
 * to reconstruct the keys from.
 *
 * That distinction is the whole reason this class exists rather than a `@Single` Room instance plus a
 * boolean: a vault that keeps its keys live behind a locked-looking UI is one heap dump, one root
 * exploit, or one `run-as` away from giving up every password.
 */
class VaultSession(
    private val databaseFactory: EncryptedDatabaseFactory,
    private val clipboard: SecureClipboard,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {

    /** The live capability set. Non-null exactly while unlocked. */
    class Handle(
        internal val database: VaultDatabase,
        val dao: PasswordDao,
        val linkDao: AutofillLinkDao,
        val fieldCipher: FieldCipher,
        private val keys: VaultKeys
    ) {
        internal suspend fun <T> withKeys(block: suspend (VaultKeys) -> T): T = block(keys)

        internal suspend fun shutDown() = withContext(Dispatchers.IO) {
            runCatching { database.close() }
            keys.close()
        }
    }

    private val _handle = MutableStateFlow<Handle?>(null)
    val handle: StateFlow<Handle?> = _handle.asStateFlow()

    val isUnlocked: StateFlow<Boolean> = _handle
        .map { it != null }
        .stateIn(scope, SharingStarted.Eagerly, false)

    /** Serialises open/lock so a background lock cannot race an in-flight unlock. */
    private val transition = Mutex()

    private var backgroundedAtElapsed: Long? = null

    /**
     * Takes ownership of [keys] and opens the database. On failure the keys are wiped and the
     * exception is rethrown, so a half-open vault is never left behind.
     *
     * The database is opened eagerly rather than on first query: SQLCipher's key derivation runs
     * during the open, and doing it here puts that cost inside the unlock spinner and surfaces a
     * wrong-key failure as a failed unlock instead of as a crash on the first read.
     */
    suspend fun open(keys: VaultKeys) = transition.withLock {
        closeCurrent()
        try {
            withContext(Dispatchers.IO) {
                val database = databaseFactory.open(keys.databasePassphrase)
                database.openHelper.writableDatabase // force the keyed open now
                _handle.value = Handle(
                    database = database,
                    dao = database.passwordDao(),
                    linkDao = database.autofillLinkDao(),
                    fieldCipher = FieldCipher(keys.fieldKey),
                    keys = keys
                )
            }
        } catch (failure: Throwable) {
            keys.close()
            throw failure
        }
    }

    fun requireHandle(): Handle = _handle.value ?: throw VaultLockedException()

    /**
     * Lends the session's keys for one operation - adding a biometric wrapper, or re-wrapping under
     * a new passphrase. Lending rather than exposing keeps the session the sole owner, so nothing
     * else can outlive [lock] holding a reference to live key material.
     */
    suspend fun <T> withKeys(block: suspend (VaultKeys) -> T): T =
        requireHandle().withKeys(block)

    /** Wipes the session. Safe to call when already locked. */
    fun lock() {
        clipboard.clear()
        // A credential captured from another app but never confirmed is as sensitive as anything in
        // the vault, and it lives on the heap rather than in the database - so locking has to drop
        // it explicitly. Closing the database would otherwise leave it behind.
        PendingCapture.clear()
        backgroundedAtElapsed = null
        scope.launch { transition.withLock { closeCurrent() } }
    }

    private suspend fun closeCurrent() {
        val previous = _handle.value ?: return
        _handle.value = null
        previous.shutDown()
        Timber.tag(TAG).i("Vault locked; keys wiped and database closed")
    }

    // --- Auto-lock -----------------------------------------------------------------------------

    /**
     * Uses [SystemClock.elapsedRealtime] rather than the wall clock: it is monotonic and unaffected
     * by the user or an attacker changing the device time to dodge the timeout.
     */
    fun onMovedToBackground() {
        if (_handle.value != null) backgroundedAtElapsed = SystemClock.elapsedRealtime()
    }

    /**
     * @return true if the grace period expired and the vault was locked.
     *
     * The grace period exists because the normal way to use a password manager is to leave it,
     * paste somewhere, and come straight back. Locking on every `onStop` would make the app
     * unusable and push the user toward weaker habits, which is a net security loss.
     */
    fun onReturnedToForeground(): Boolean {
        val since = backgroundedAtElapsed ?: return false
        backgroundedAtElapsed = null
        val away = SystemClock.elapsedRealtime() - since
        return if (away >= BACKGROUND_GRACE_MILLIS) {
            lock()
            true
        } else {
            false
        }
    }

    /** Screen off is the device-theft signal; no grace period applies. */
    fun onScreenOff() = lock()

    companion object {
        private const val TAG = "VaultSession"
        const val BACKGROUND_GRACE_MILLIS = 60_000L
    }
}
