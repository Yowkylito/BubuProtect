package com.personal.bubuprotect.core.shield.intel

import android.content.pm.PackageManager
import android.content.pm.SigningInfo
import android.os.Build
import androidx.annotation.RequiresApi
import java.security.MessageDigest

/**
 * Reads the signing certificate of an installed app and reduces it to a comparable identity.
 *
 * ### Why the signer and not the package name
 *
 * A package name is a string an APK author chooses. Anyone can build an APK that declares
 * `com.personal.bubuprotect`, and Android will happily install it under a different signature as long
 * as this app is not already present. A signing certificate is the one property of an installed app
 * that cannot be claimed without holding the private key.
 *
 * That distinction is load-bearing twice over:
 *
 *  - **Self-exemption.** Bubu Protect excludes itself from its own suspicious-app list. Doing that by
 *    package name would publish a one-line bypass: any adware declaring this app's package name would
 *    be silently whitelisted by the very scanner meant to catch it. [isSelf] compares certificates.
 *    The debug build makes the point on its own - `applicationIdSuffix = ".debug"` means the package
 *    name is not even stable across this project's own variants, while the signer is.
 *  - **Family clustering.** Adware ships in families: dozens of APKs, different names and icons, one
 *    developer key - because rotating the key would cost them the ability to update installs they
 *    already have. One blocklisted certificate identifies the whole family, variants included.
 *
 * ### Fails soft, and says which kind of nothing it found
 *
 * Every read is wrapped. A package that vanished mid-scan, a manufacturer ROM that throws, or an API
 * level that cannot answer all produce `null` rather than taking the scan down. `null` means *could
 * not read*, which is a different claim from *no match* - a caller that folds them together would
 * report an unreadable app as clean.
 *
 * @param selfPackageName this app's own package name, injected rather than read from a `Context` so
 *   the class stays a plain object with no Android lifecycle attached to it. It is used only as the
 *   lookup key for reading *our own* certificate; the comparison itself is never on this string.
 */
class SignerFingerprinter(private val selfPackageName: String) {

    /**
     * Our own certificate digests, resolved once.
     *
     * Cached because [isSelf] is called once per installed app during a scan - a few hundred times on
     * a normal phone - and each miss would otherwise re-read and re-hash our own APK's certificate
     * chain. Empty when our own signature could not be read at all, in which case [isSelf] answers
     * `false` for everything: over-reporting ourselves as a suspicious app is a cosmetic bug, while
     * under-reporting anything else would be a hole.
     */
    private var selfDigests: Set<String>? = null

    /**
     * Every certificate this app is signed with, as uppercase hex SHA-256.
     *
     * Returns the full set rather than one value so that key rotation cannot be used to slip past a
     * blocklist. Android records a signing history when a developer rotates keys, and an adware family
     * that rotates would otherwise present a brand-new identity while still being the same author. A
     * blocklist check should hit if *any* certificate in the history is listed.
     *
     * @return an empty set when the platform declined to answer, which is distinct from an app with
     *   no signers - there is no such thing as an installed APK without one.
     */
    fun fingerprints(packages: PackageManager, packageName: String): Set<String> = try {
        certificatesOf(packages, packageName).mapTo(mutableSetOf(), ::digestOf)
    } catch (_: Exception) {
        // PackageManager.NameNotFoundException for a package uninstalled mid-scan, and
        // RuntimeException from OEM ROMs that throw on a malformed signing block. Neither is worth
        // failing a whole-device scan over.
        emptySet()
    }

    /**
     * The single certificate that identifies this app, for display and for sibling grouping.
     *
     * The *current* signer specifically, not an arbitrary member of [fingerprints]: two apps are
     * siblings when they are signed by the same author today, and pairing them on a shared retired
     * key would group apps whose ownership has since diverged.
     *
     * @return null when nothing could be read, never a placeholder string. A caller that renders
     *   "unknown" is making a decision about wording; that decision does not belong here.
     */
    fun fingerprint(packages: PackageManager, packageName: String): String? =
        try {
            certificatesOf(packages, packageName).firstOrNull()?.let(::digestOf)
        } catch (_: Exception) {
            null
        }

    /**
     * Whether this package is Bubu Protect itself.
     *
     * Certificate comparison, for the reason given in the class docs. The package name is used only
     * as a cheap pre-filter to skip the hashing work for the hundreds of packages that obviously are
     * not us - a package name match is *necessary* but never *sufficient*, and an attacker who
     * satisfies the pre-filter still has to satisfy the certificate check underneath it.
     */
    fun isSelf(packages: PackageManager, packageName: String): Boolean {
        if (packageName != selfPackageName) return false

        val ours = selfDigests ?: fingerprints(packages, selfPackageName).also { selfDigests = it }
        if (ours.isEmpty()) return false

        return fingerprints(packages, packageName).any(ours::contains)
    }

    /**
     * Raw certificate bytes for a package, current signers first.
     *
     * `apkContentsSigners` is what the APK is signed with right now; `signingCertificateHistory`
     * carries keys it was previously signed with. Ordering matters to [fingerprint], which takes the
     * head.
     *
     * On a multiply-signed APK `apkContentsSigners` returns every signer, and all of them are kept -
     * dropping any would let a family evade a blocklist by adding a second, unlisted key.
     */
    private fun certificatesOf(packages: PackageManager, packageName: String): List<ByteArray> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            @Suppress("DEPRECATION")
            val info = packages.getPackageInfo(
                packageName,
                PackageManager.GET_SIGNING_CERTIFICATES
            )
            info.signingInfo?.let { signing -> certificatesOf(signing) }.orEmpty()
        } else {
            // API 26-27 predates SigningInfo. GET_SIGNATURES is the only option here and is
            // deprecated purely because of the v2-signature spoofing issue fixed in P - which does
            // not apply, because this is used to identify an app rather than to authenticate it.
            @Suppress("DEPRECATION")
            packages.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
                .signatures
                ?.map { it.toByteArray() }
                .orEmpty()
        }

    /**
     * @receiver only ever reached from the `SDK_INT >= P` branch of the overload above. Annotated
     *   because `SigningInfo` itself does not exist before 28, and the version check being one frame up
     *   is not something lint can follow.
     */
    @RequiresApi(Build.VERSION_CODES.P)
    private fun certificatesOf(info: SigningInfo): List<ByteArray> =
        buildList {
            info.apkContentsSigners?.forEach { add(it.toByteArray()) }
            if (!info.hasMultipleSigners()) {
                info.signingCertificateHistory?.forEach { add(it.toByteArray()) }
            }
        }

    companion object {

        /**
         * SHA-256 of a DER-encoded certificate, as uppercase hex with no separators.
         *
         * Pure and public so the scoring and blocklist code can be tested without a `PackageManager`,
         * and so a blocklist shipped as text can be compared against this output with a plain string
         * equality check rather than a parser.
         *
         * SHA-256 because it is what every public APK-signature database publishes, so a blocklist
         * can be assembled from existing sources instead of recomputed from collected samples.
         */
        fun digestOf(certificate: ByteArray): String =
            MessageDigest.getInstance("SHA-256")
                .digest(certificate)
                .joinToString("") { "%02X".format(it) }

        /**
         * Accepts the shapes a signature hash is published in and returns the form [digestOf] emits.
         *
         * Public sources print certificate hashes as colon-separated lowercase, space-separated
         * uppercase, or bare hex. Normalising on the way in means the shipped blocklist can be pasted
         * from whichever source it came from, and a formatting mismatch cannot silently turn into a
         * blocklist that never matches anything.
         *
         * @return null for input that is not a 64-character hex string, so a malformed blocklist line
         *   is dropped loudly at load time rather than sitting there as an entry that can never hit.
         */
        fun normalize(hash: String): String? = hash
            .filterNot { it == ':' || it == ' ' || it == '-' }
            .uppercase()
            .takeIf { it.length == 64 && it.all(::isHex) }

        private fun isHex(c: Char): Boolean = c in '0'..'9' || c in 'A'..'F'
    }
}
