package com.personal.bubuprotect.di

import com.personal.bubuprotect.core.autofill.AutofillResponder
import com.personal.bubuprotect.core.backup.VaultBackupService
import com.personal.bubuprotect.core.importer.CredentialImportService
import com.personal.bubuprotect.core.crypto.KeystoreKek
import com.personal.bubuprotect.core.crypto.VaultKeyManager
import com.personal.bubuprotect.core.nfc.EmvCardReader
import com.personal.bubuprotect.core.nfc.NfcCardScanner
import com.personal.bubuprotect.core.shield.AppRiskScanner
import com.personal.bubuprotect.core.shield.ShieldCapabilities
import com.personal.bubuprotect.core.shield.enforce.RemediationLadder
import com.personal.bubuprotect.core.shield.enforce.ShizukuGateway
import com.personal.bubuprotect.core.shield.intel.SignerFingerprinter
import com.personal.bubuprotect.core.shield.intel.StaticApkAnalyzer
import com.personal.bubuprotect.core.shield.recorder.FlightRecorder
import com.personal.bubuprotect.core.shield.recorder.UsageTimelineProbe
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
import com.personal.bubuprotect.ui.vm.ImportViewModel
import com.personal.bubuprotect.ui.vm.RecoveryKitViewModel
import com.personal.bubuprotect.ui.vm.RecoveryUnlockViewModel
import com.personal.bubuprotect.ui.vm.ShieldViewModel
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
    // Two Keystore keys with identical properties and independent lifetimes: one wraps the root
    // key for biometric unlock, the other guards the recovery screen.
    single { VaultKeyManager(get(), KeystoreKek(), KeystoreKek(KeystoreKek.ALIAS_RECOVERY_GUARD)) }
    single { BiometricAuthenticator(androidContext()) }
    single { IntegrityChecker() }
    single { LockoutTracker(get()) }
    single { SecureClipboard(androidContext(), get(ApplicationScopeQualifier)) }
    single<PwnedPasswordsClient> { HibpPwnedPasswordsClient() }
    single { PwnedPasswordChecker(get()) }
    single { VaultBreachScanner(get(), get()) }
    single { DeviceThreatScanner(get()) }

    // BubuShield.
    //
    // The FlightRecorder is a singleton and has to be: the accessibility service, the notification
    // listener and the VPN's packet loop are all constructed by the *system*, not by Koin, and they
    // reach in via KoinComponent to find this exact instance. A factory here would give each sensor its
    // own private buffer and the shield would observe nothing.
    single { FlightRecorder() }
    single { SignerFingerprinter(androidContext().packageName) }
    single { StaticApkAnalyzer() }
    single { UsageTimelineProbe() }
    single { ShizukuGateway() }
    single { RemediationLadder(get()) }
    single { ShieldCapabilities(get(), get()) }
    single { AppRiskScanner(get(), get(), get()) }
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
    single { CredentialImportService(androidContext()) }

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

    viewModel { UnlockViewModel(get(), get(), get(), get(), get(), get(), get(), androidContext()) }

    // RecoveryUnlockViewModel lives on the locked side and holds the recovered root key
    // between its two steps, wiping it in onCleared - see its class doc for why that is
    // unavoidable. RecoveryKitViewModel is the opposite: it needs an already-open session,
    // because minting a kit means wrapping the live key.
    viewModel { RecoveryUnlockViewModel(get(), get(), get(), get()) }
    viewModel { RecoveryKitViewModel(get(), get(), get(), androidContext()) }
    viewModel { ImportViewModel(get(), get()) }
    viewModel { VaultViewModel(get(), get(), get(), get(), get(), get(), get(), get()) }

    // Application context, not the Activity's: this one is scoped to the unlocked shell and outlives
    // configuration changes, so an Activity reference here would be a leak per rotation.
    viewModel { DeviceCheckViewModel(get(), get(), androidContext()) }

    // Same ownership as DeviceCheckViewModel: scoped to the unlocked shell, application context rather
    // than the Activity's, and cleared on lock - which is also what drops the recorder's buffer.
    viewModel {
        ShieldViewModel(get(), get(), get(), get(), get(), get(), get(), androidContext())
    }

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
