package com.personal.bubuprotect.ui

import kotlinx.serialization.Serializable

/**
 * The unlocked graph.
 *
 * There is deliberately no `Unlock` destination. Locked and unlocked are not two places in one back
 * stack - if they were, the system back gesture could walk from the vault onto the lock screen and
 * then forward again, and a saved back stack would try to restore a vault screen with no keys behind
 * it. Instead the whole graph below only exists while [com.personal.bubuprotect.session.VaultSession]
 * is open, and locking tears it down.
 *
 * Only [Detail] and [Editor] carry an id, and it is the entry's UUID - never a secret. Navigation
 * arguments end up in a `Bundle` that the system may save to disk on process death.
 */
@Serializable
sealed interface Routes {

    @Serializable
    data object Vault : Routes

    @Serializable
    data object SecurityGuide : Routes

    /**
     * The breach report.
     *
     * Carries no arguments on purpose. The screen reads the whole vault list from the shared
     * [com.personal.bubuprotect.ui.vm.VaultViewModel], so a list of breached entry ids never ends up
     * in a navigation `Bundle` that the system may write to disk on process death - which is the same
     * reason [Detail] carries only a UUID.
     */
    @Serializable
    data object BreachReport : Routes

    /**
     * The device check.
     *
     * Argument-free for the same reason as [BreachReport], and one more: its findings name other apps
     * on the phone, and a list of those in a navigation `Bundle` is a list the system may write to
     * disk on process death. The report is re-derived on arrival instead - it costs a few
     * milliseconds, and a device finding is only meaningful as of *now* anyway.
     */
    @Serializable
    data object DeviceCheck : Routes

    @Serializable
    data class Detail(val entryId: String) : Routes

    /** A null [entryId] opens the editor in create mode. */
    @Serializable
    data class Editor(val entryId: String? = null) : Routes
}
