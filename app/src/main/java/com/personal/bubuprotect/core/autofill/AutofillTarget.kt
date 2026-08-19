package com.personal.bubuprotect.core.autofill

/**
 * Who is asking to be filled.
 *
 * The single most important thing this feature gets right is *not* filling the wrong app. Copying a
 * password to the clipboard asks the user to be the matcher, and a person cannot reliably tell
 * `paypal.com` from `paypa1.com` at a glance on a phone. This type is the machine-checkable answer:
 * a credential is only ever offered to the identity it was stored or learned for.
 *
 * ### Why the web domain outranks the package
 *
 * A browser is one package hosting the entire internet. Keying on `com.android.chrome` would mean
 * every site the user visits shares one identity, so a phishing page would be offered the real
 * bank's credentials because it happens to be rendered by the same browser. Whenever the structure
 * reports a domain, that domain *is* the identity and the package is only a label.
 *
 * ### Why the signature is carried
 *
 * [signature] is the SHA-256 of the requesting package's signing certificate. It exists so a learned
 * association can be pinned to the app that earned it. Package names are not a security boundary
 * outside Play - a sideloaded APK can declare any name it likes, including one the user has already
 * taught this vault to trust. Recording the signer means a substituted app is a *different* app and
 * has to earn the link again, rather than inheriting one.
 */
data class AutofillTarget(
    val packageName: String,
    val webDomain: String? = null,
    val signature: String? = null
) {

    val isWeb: Boolean get() = webDomain != null

    /**
     * The identity credentials are keyed to, and the primary key of a learned link.
     *
     * Prefixed so a package and a domain can never collide in the same column, and built from the
     * *registrable* domain so `login.reddit.com` and `www.reddit.com` are one identity rather than
     * two that each have to be taught separately.
     */
    val key: String
        get() = webDomain
            ?.let { "web:" + WebDomains.registrable(it) }
            ?: "app:$packageName"

    /** What the picker calls this target. Not used for matching. */
    val displayName: String get() = webDomain ?: packageName

    companion object {
        /**
         * Builds a target, preferring the web domain when the structure reported one.
         *
         * @param webDomain raw, as reported - normalised here so callers cannot skip it.
         * @param signature the requesting package's signer hash. **Dropped for a web target**, and
         *   that is not a tidy-up. On a website the identity is the domain; the package is just
         *   whichever browser happens to be open. Keeping the signer would pin every learned link to
         *   one browser, so opening the same site in a second one would silently stop offering the
         *   credential - and the user would have no way to tell that from the matcher being broken.
         */
        fun of(packageName: String, webDomain: String?, signature: String? = null): AutofillTarget {
            val host = WebDomains.normalize(webDomain)
            return AutofillTarget(
                packageName = packageName,
                webDomain = host,
                signature = signature.takeIf { host == null }
            )
        }
    }
}
