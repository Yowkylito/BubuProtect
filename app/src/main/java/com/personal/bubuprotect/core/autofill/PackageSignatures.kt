package com.personal.bubuprotect.core.autofill

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import timber.log.Timber
import java.security.MessageDigest

/**
 * The signing identity of the app asking to be filled.
 *
 * A package name is a *label*, not an identity. Play enforces uniqueness inside Play, but nothing
 * stops a sideloaded APK declaring `com.mybank.android`, and an autofill service that keys trust on
 * the string alone will hand the bank credential to whatever holds that name today.
 *
 * The signing certificate is the identity Android actually enforces: it is what gates updates, what
 * gates `sharedUserId`, and what cannot be claimed by an app that does not hold the private key.
 * Hashing it gives a stable, short value to store next to a learned link.
 *
 * ### On multiple signers
 *
 * A package can be signed by several certificates. All of them are hashed, sorted and joined, so the
 * result is order-independent and changes if the signer set changes at all. Sorting matters because
 * `PackageManager` makes no promise about the order it returns them in, and an unstable value would
 * silently invalidate every link on some future read.
 *
 * ### On key rotation
 *
 * API 28 introduced signing key rotation, so a legitimate update can change the current signer.
 * [signingCertificateHistory] is read where available so a rotated app still matches the link it
 * earned. An app that rotates on a platform too old to report the history loses its link and the
 * user is asked once more - the failure mode is a question, not a wrong fill.
 */
internal object PackageSignatures {

    /** @return the signer hash, or null when the package cannot be inspected. */
    fun of(context: Context, packageName: String?): String? {
        if (packageName.isNullOrEmpty()) return null
        return try {
            val signatures = signaturesOf(context.packageManager, packageName)
            if (signatures.isEmpty()) return null
            signatures
                .map { sha256(it.toByteArray()) }
                .sorted()
                .joinToString(",")
        } catch (missing: PackageManager.NameNotFoundException) {
            Timber.tag(TAG).w("No such package while checking signature: %s", packageName)
            null
        } catch (failure: Exception) {
            // Never fatal. A signature we could not read means "unknown signer", which downgrades to
            // asking the user - the safe direction. Crashing here would take out the fill request.
            Timber.tag(TAG).w(failure, "Could not read the signature of %s", packageName)
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun signaturesOf(manager: PackageManager, packageName: String): List<Signature> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = manager.getPackageInfo(
                packageName,
                PackageManager.GET_SIGNING_CERTIFICATES
            )
            val signing = info.signingInfo
            when {
                signing == null -> emptyList()
                signing.hasMultipleSigners() -> signing.apkContentsSigners.orEmpty().toList()
                // The history includes the current signer, so a rotated app keeps matching a link it
                // earned under its previous key.
                else -> signing.signingCertificateHistory.orEmpty().toList()
            }
        } else {
            manager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
                .signatures.orEmpty().toList()
        }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte) }

    private const val TAG = "Autofill"
}
