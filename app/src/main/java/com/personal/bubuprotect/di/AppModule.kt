package com.personal.bubuprotect.di

import com.personal.bubuprotect.core.autofill.AutofillResponder
import com.personal.bubuprotect.core.backup.VaultBackupService
import com.personal.bubuprotect.core.crypto.KeystoreKek
import com.personal.bubuprotect.core.crypto.VaultKeyManager
import com.personal.bubuprotect.core.nfc.EmvCardReader
import com.personal.bubuprotect.core.nfc.NfcCardScanner
import com.personal.bubuprotect.core.security.BiometricAuthenticator
import com.personal.bubuprotect.core.security.DeviceThreatScanner
import com.personal.bubuprotect.core.security.HibpPwnedPasswordsClient
import com.personal.bubuprotect.core.security.IntegrityChecker
import com.personal.bubuprotect.core.security.LockoutTracker
import com.personal.bubuprotect.core.security.PwnedPasswordChecker
import com.personal.bubuprotect.core.security.PwnedPasswordsClient
import com.personal.bubuprotect.core.security.VaultBreachScanner
import com.personal.bubuprotect.core.util.SecureClipboard
import com.personal.bubuprotect.data.local.EncryptedDatabaseFactory
import com.personal.bubuprotect.data.local.UserPreferences
import com.personal.bubuprotect.data.local.VaultKeyStore
import com.personal.bubuprotect.data.repository.VaultRepositoryImpl
import com.personal.bubuprotect.domain.repository.VaultRepository
import com.personal.bubuprotect.session.VaultSession
import com.personal.bubuprotect.ui.components.createImageLoader
import com.personal.bubuprotect.ui.vm.DeviceCheckViewModel
import com.personal.bubuprotect.ui.vm.EntryDetailViewModel
import com.personal.bubuprotect.ui.vm.EntryEditorViewModel
import com.personal.bubuprotect.ui.vm.RevealAuthorizer
import com.personal.bubuprotect.ui.vm.UnlockViewModel
import com.personal.bubuprotect.ui.vm.VaultViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.core.qualifier.named
import org.koin.dsl.module

/** Scope for work that must outlive any screen - the clipboard's auto-clear, and vault teardown. */
private val ApplicationScopeQualifier = named("applicationScope")

val appModule = module {

    single(ApplicationScopeQualifier) { CoroutineScope(SupervisorJob() + Dispatchers.Default) }

    // Crypto and security primitives
    single { VaultKeyStore(androidContext()) }
    single { UserPreferences(androidContext()) }
    single { KeystoreKek() }
    single { VaultKeyManager(get(), get()) }
    single { BiometricAuthenticator(androidContext()) }
    single { IntegrityChecker() }
    single { LockoutTracker(get()) }
    single { SecureClipboard(androidContext(), get(ApplicationScopeQualifier)) }
    single<PwnedPasswordsClient> { HibpPwnedPasswordsClient() }
    single { PwnedPasswordChecker(get()) }
    single { VaultBreachScanner(get(), get()) }
    single { DeviceThreatScanner(get()) }
    single { RevealAuthorizer(get(), get()) }

    // Stateless, and holds no Context - the Activity is passed per scan so a sheet that leaves the
    // composition cannot leave reader mode enabled behind it.
    single { EmvCardReader() }
    single { NfcCardScanner(get()) }

    // Storage. The factory is a singleton; the database it produces deliberately is not.
    single { EncryptedDatabaseFactory(androidContext()) }
    single { VaultSession(get(), get()) }

    single<VaultRepository> { VaultRepositoryImpl(get()) }

    single { VaultBackupService(androidContext(), get()) }

    // Shared by the autofill service and the screen that runs after its authentication, so both
    // build datasets the same way. See AutofillResponder for why that has to be one implementation.
    single { AutofillResponder(androidContext(), get(), get()) }

    /**
     * One GIF-capable image loader for the process.
     *
     * Every Coil `ImageLoader` owns its own memory cache and bitmap pool, so building one per
     * composable - as the previous screens did - meant a fresh multi-megabyte cache per screen that
     * never shared a decoded frame. Provided through
     * [com.personal.bubuprotect.ui.components.LocalBubuImageLoader].
     */
    single { createImageLoader(androidContext()) }

    viewModel { UnlockViewModel(get(), get(), get(), get(), get(), get(), androidContext()) }
    viewModel { VaultViewModel(get(), get(), get(), get(), get(), get(), get(), get()) }

    // Application context, not the Activity's: this one is scoped to the unlocked shell and outlives
    // configuration changes, so an Activity reference here would be a leak per rotation.
    viewModel { DeviceCheckViewModel(get(), get(), androidContext()) }

    // Parameterised: the entry id comes from the navigation route, not from the graph.
    viewModel { parameters ->
        EntryEditorViewModel(get(), get(), parameters.getOrNull<String>())
    }
    viewModel { parameters ->
        EntryDetailViewModel(
            repository = get(),
            session = get(),
            authorizer = get(),
            clipboard = get(),
            pwnedPasswordChecker = get(),
            entryId = parameters.get()
        )
    }
}
