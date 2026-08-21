package com.personal.bubuprotect.domain.model

/**
 * How sure Bubu is about one installed app.
 *
 * ### The honest scope, stated first
 *
 * [DeviceProbe] deliberately refuses to name any app as malware, and that refusal is correct for
 * what it does: it reads capability grants, and a grant is not evidence of abuse. This file is the
 * other half of the question - **"which app is spamming ads at me right now, and can I prove it?"**
 *
 * The distinction matters because it is where every adware scanner on the market goes wrong. They
 * score an app's *permission list* and print the result as a threat, which is why they flag banking
 * apps, launchers and keyboards. Holding `SYSTEM_ALERT_WINDOW` is not the same as drawing an ad over
 * someone's screen; a permission list only says what an app *could* do.
 *
 * So nothing here convicts on capability alone. A set of capability [RiskSignal]s raises an app to
 * [SUSPECT] at most. Promotion to [CONVICTED] needs *observed behaviour* - an overlay this app
 * watched appear, or ad-network traffic attributed to that UID - or a signing certificate already on
 * the shipped blocklist. Capability narrows the search. Behaviour makes the accusation.
 *
 * ### Why it is worded like a courtroom
 *
 * Because the user has to act on it, and the action is destructive. "Uninstall this, trust me" is not
 * something a scanner has earned the right to say. "This app opened a full-screen window over Chrome
 * 23 times today while you were not using it" is a sentence the user can check against their own
 * memory of the last hour, and agree or disagree with. Every conviction carries its receipt.
 */
enum class Conviction {

    /** Asked, answered, nothing worth showing. */
    CLEAN,

    /**
     * Capability signals only.
     *
     * Shown in a collapsed "worth a look" section, never in an alarming colour, and never with a
     * one-tap uninstall beside it. The overwhelmingly common cause of a high capability score is a
     * legitimate app that genuinely needs the grants - a launcher, a screen reader, an MDM client.
     */
    SUSPECT,

    /**
     * Capability signals **plus** behaviour this app watched happen, or a blocklisted signer.
     *
     * The only state that offers remediation, because it is the only state backed by something that
     * can be shown to the user rather than asked of them.
     */
    CONVICTED
}

/**
 * One thing worth noticing about an installed app.
 *
 * @property weight contribution to the suspicion score. Deliberately small and comparable across the
 *   capability signals - no single grant should be able to convict, because every one of them has a
 *   legitimate owner. [KNOWN_BAD_SIGNER] and the behavioural signals are the exceptions, and they are
 *   exceptions because they are identities and observations rather than inferences.
 * @property behavioural true when the signal records something that *happened* rather than something
 *   that is *possible*. Only these can push an app to [Conviction.CONVICTED].
 */
enum class RiskSignal(val weight: Int, val behavioural: Boolean = false) {

    // ---- Identity: an identity match, not an inference, so it decides alone --------------------

    /**
     * The signing certificate is on the shipped blocklist.
     *
     * The strongest cheap signal in the design, and the reason this can beat a permission scorer with
     * no backend behind it. Adware families ship dozens of APKs - different names, different icons,
     * different package names - all signed with one developer key, because rotating the key means
     * losing the ability to update the installs they already have. Fingerprint the signer of one
     * confirmed-bad app and every sibling is identified, including variants that do not exist yet.
     */
    KNOWN_BAD_SIGNER(weight = 100),

    /**
     * Shares a signing certificate with an app already convicted on this device.
     *
     * Family resemblance derived locally, needing no blocklist entry. If one app has been caught
     * drawing overlays and a second carries the same key, the second is the same developer - and
     * adware is rarely a one-off.
     */
    SIGNER_SIBLING(weight = 60),

    // ---- Behaviour: observed, timestamped, replayable ------------------------------------------

    /** Watched opening a window over another app's. Attributed at the moment it happened. */
    DREW_OVERLAY(weight = 50, behavioural = true),

    /** Resumed itself into the foreground with no user action, per `UsageStatsManager`. */
    SELF_LAUNCHED(weight = 40, behavioural = true),

    /** Posting notifications far faster than its category could justify. */
    NOTIFICATION_FLOOD(weight = 35, behavioural = true),

    /** Traffic to known ad or tracker hosts, attributed to this app's UID by the local filter. */
    AD_NETWORK_TRAFFIC(weight = 45, behavioural = true),

    // ---- Capability: narrows the search, never convicts ----------------------------------------

    /** Installed from something other than a recognised store. */
    SIDELOADED(weight = 15),

    /** No installer could be attributed at all - adb, a file manager, or a dropper. */
    UNKNOWN_INSTALLER(weight = 20),

    /** Holds `SYSTEM_ALERT_WINDOW`, the permission required to draw over other apps. */
    CAN_DRAW_OVERLAYS(weight = 20),

    /** Declares an accessibility service, so it can read the semantics tree of every screen. */
    DECLARES_ACCESSIBILITY(weight = 25),

    /** Declares a notification listener, so it can read - and dismiss - the shade. */
    DECLARES_NOTIFICATION_LISTENER(weight = 20),

    /** An active device administrator, which cannot be uninstalled until it is deactivated. */
    ACTIVE_DEVICE_ADMIN(weight = 25),

    /**
     * `RECEIVE_BOOT_COMPLETED` and a foreground service together.
     *
     * The restart-forever pair. Also how alarms, messengers and sync clients legitimately work, so
     * it is weighted as one mild signal rather than treated as a tell.
     */
    BOOT_PERSISTENCE(weight = 15),

    /**
     * Installed, with no launcher entry, and not a system app.
     *
     * A classic adware trait - the icon is hidden right after install so the user cannot find the
     * thing to uninstall. Also true of keyboards, plugins and device-admin agents, which is why it
     * stays a signal rather than a verdict.
     */
    NO_LAUNCHER_ICON(weight = 30),

    /**
     * Built debuggable.
     *
     * Named for what is actually detectable. The obvious check would be the debug *signing key*, but
     * Android generates that per developer machine, so there is no fixed certificate to match against.
     * The manifest flag is a real fact about the APK and carries the same meaning: nothing published
     * in good faith ships with the debuggable flag set, because it lets any process on the device
     * attach to it.
     */
    DEBUGGABLE_BUILD(weight = 35),

    /** Aggressive ad-mediation SDK classes found in the APK. */
    AD_SDK_PRESENT(weight = 25),

    /** Installed within the last day, so it has had no time to earn a behavioural record yet. */
    ON_PROBATION(weight = 5);

    companion object {

        /**
         * Score at or above which an app is worth surfacing at all.
         *
         * Tuned so no two ordinary capability signals can reach it: a messenger holding overlay and
         * boot persistence scores 35 and stays silent. Clearing 60 on capability alone takes a
         * combination an honest app has little reason to hold.
         */
        const val SUSPECT_THRESHOLD = 60
    }
}

/**
 * What can be done about a convicted app, cheapest and most reversible first.
 *
 * ### Why a ladder instead of a button
 *
 * "Uninstall it" is the wrong first offer. Most people hit by ad-spam do not want to lose the app -
 * they installed it for a reason and it may still do that thing. They want the ads to stop. [NEUTER]
 * is the rung no other tool offers and the one most users actually want: revoke the capability, keep
 * the app.
 *
 * Every rung above [ADVISE] needs a shell-privileged helper the user starts themselves, and every
 * rung is reversible except [REMOVE].
 */
enum class RemediationTier {

    /**
     * Name the culprit, show the evidence, hand over an `ACTION_DELETE` intent and settings links.
     *
     * Always available and needs nothing. The system confirmation dialog stays in the loop, so this
     * app never removes anything behind the user's back.
     */
    ADVISE,

    /** Revoke the ad capability through `appops`, leaving the app installed and working. */
    NEUTER,

    /** `pm disable-user` - gone from the launcher, unable to run, restorable in one tap. */
    DISABLE,

    /** `pm uninstall --user 0` - actually removed, preinstalled apps included. Not reversible. */
    REMOVE
}

/**
 * The case against one installed app.
 *
 * @param packageName the identity remediation acts on. Never the primary label: "com.a.b.c is
 *   spamming you" is not actionable advice.
 * @param label resolved application label, or null when the APK's resources could not be read.
 * @param signerSha256 uppercase hex, no separators. Null when the platform declined to answer, which
 *   is a fact in itself - an app whose signer cannot be read cannot be cluster-matched.
 * @param signals capability and behaviour together, so the evidence card can order them by weight.
 * @param isSelf true for Bubu Protect itself, matched by signing certificate rather than by package
 *   name. See [com.personal.bubuprotect.core.shield.intel.SignerFingerprinter] for why that
 *   distinction is load-bearing.
 */
data class AppVerdict(
    val packageName: String,
    val label: String?,
    val signerSha256: String?,
    val signals: Set<RiskSignal>,
    val firstInstalledAt: Long,
    val isSelf: Boolean = false
) {

    /** Sum of signal weights. Uncapped on purpose - a runaway score is information, not a bug. */
    val score: Int = signals.sumOf { it.weight }

    val hasBehaviouralEvidence: Boolean = signals.any(RiskSignal::behavioural)

    /**
     * Capability narrows, behaviour convicts - with one exception.
     *
     * [RiskSignal.KNOWN_BAD_SIGNER] is an identity match against certificates belonging to known
     * adware families. It needs no local observation because the observation already happened, on
     * someone else's phone, before the list was built.
     */
    val conviction: Conviction = when {
        RiskSignal.KNOWN_BAD_SIGNER in signals -> Conviction.CONVICTED
        hasBehaviouralEvidence && score >= RiskSignal.SUSPECT_THRESHOLD -> Conviction.CONVICTED
        score >= RiskSignal.SUSPECT_THRESHOLD -> Conviction.SUSPECT
        else -> Conviction.CLEAN
    }

    /** What the user reads first. Falls back to the package name only when nothing else resolved. */
    val displayName: String get() = label?.takeIf(String::isNotBlank) ?: packageName

    /**
     * Signals worth printing on the evidence card, heaviest first.
     *
     * At equal weight, behavioural signals sort above capability ones: "it did this" reads as
     * evidence and "it could do this" reads as speculation, and the first line of the card should be
     * the one the user can check against their own experience of the last hour.
     */
    val evidence: List<RiskSignal>
        get() = signals.sortedWith(
            compareByDescending<RiskSignal> { it.weight }.thenByDescending { it.behavioural }
        )
}
