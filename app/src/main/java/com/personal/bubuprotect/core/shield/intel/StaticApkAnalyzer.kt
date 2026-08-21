package com.personal.bubuprotect.core.shield.intel

import android.content.pm.ApplicationInfo
import java.io.File
import java.util.zip.ZipFile

/**
 * Looks inside an APK for ad-mediation SDKs.
 *
 * ### Why the class-name path and not a DEX parser
 *
 * A real DEX parser would read the string table and give an exact answer. It would also be several
 * hundred lines of format handling, run into multidex, and have to cope with obfuscated builds - and
 * the payoff over reading entry names out of the zip is small, because ad SDKs are shipped as
 * libraries and their package paths survive R8: an app cannot rename `com.applovin` without breaking
 * the SDK's own reflection and manifest references.
 *
 * So this reads the APK's zip directory, which is cheap and needs no decompression, and looks for the
 * directory prefixes those SDKs occupy. On a modern build the classes are inside `classes.dex` rather
 * than as separate entries, so this also scans the entry *names* of native libraries and assets, where
 * ad SDKs reliably leave their own directories behind.
 *
 * ### It is a weak signal and weighted as one
 *
 * An ad SDK is not adware. Most free apps carry one, honestly, and they are the reason the app is
 * free. [com.personal.bubuprotect.domain.model.RiskSignal.AD_SDK_PRESENT] is worth 25 of a
 * 60-point threshold precisely so this can never convict anything on its own - it is here to break
 * ties between two apps that both drew an overlay, not to accuse anyone.
 *
 * ### Cost
 *
 * Opening a few hundred zip files is the single most expensive thing the shield does, so it runs last,
 * only for apps that already carry another signal, and never on the main thread. [analyze] takes the
 * caller's dispatcher on trust rather than switching, because the scanner that calls it is already on
 * IO and a nested `withContext` per package would be pure overhead.
 */
class StaticApkAnalyzer {

    /**
     * @return true when an ad-mediation SDK's footprint is present.
     *
     * False on any failure. An APK that cannot be opened - a split install, a compressed asset on a
     * filesystem we cannot read, a package uninstalled mid-scan - is not evidence of anything, and
     * treating "could not look" as "found it" would manufacture signals out of IO errors.
     */
    fun analyze(info: ApplicationInfo): Boolean {
        val apk = info.sourceDir?.let(::File)?.takeIf(File::exists) ?: return false

        return try {
            ZipFile(apk).use { zip ->
                zip.entries().asSequence().any { entry ->
                    AD_SDK_PATHS.any { marker -> entry.name.contains(marker, ignoreCase = true) }
                }
            }
        } catch (_: Exception) {
            false
        }
    }

    private companion object {

        /**
         * Directory fragments the major mobile ad-mediation SDKs leave in an APK.
         *
         * Chosen for the ones that ship native libraries or asset directories, because those survive
         * as named zip entries. A pure-Java SDK folded into `classes.dex` will be missed here, which
         * is the accepted limit of a zip-directory scan - and one more reason this is a tie-breaker
         * rather than a detector.
         */
        val AD_SDK_PATHS = listOf(
            "applovin",
            "unityads",
            "com/unity3d/ads",
            "adcolony",
            "vungle",
            "chartboost",
            "ironsource",
            "supersonic",
            "inmobi",
            "mopub",
            "tapjoy",
            "startapp",
            "mobfox",
            "appnext",
            "mintegral",
            "mbridge",
            "pangle",
            "bytedance/sdk",
            "fyber",
            "smaato"
        )
    }
}
