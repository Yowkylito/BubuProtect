package com.personal.bubuprotect.core.shield.network

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import java.net.InetSocketAddress

/**
 * Turns a captured datagram into the name of the app that sent it.
 *
 * ### The API that makes network evidence possible
 *
 * `ConnectivityManager.getConnectionOwnerUid` takes a socket's five-tuple and returns the UID that owns
 * it. That is the difference between "something on this phone is talking to an ad network" - useless -
 * and "this app made 1,847 requests to 12 ad networks in the last hour", which is evidence the user can
 * act on. Nothing else on Android attributes traffic to an app without root.
 *
 * ### API 29 and above only
 *
 * The call does not exist below Android 10, and there is no workaround: the older `/proc/net` route was
 * closed off precisely because it let apps see each other's connections. On 26-28 the tunnel still runs
 * and still blocks, it just cannot say who asked - so the shield reports network evidence as
 * unavailable rather than absent, and convicts on overlays instead.
 *
 * ### One UID can be several packages
 *
 * Apps sharing a signature can share a UID. When that happens there is no way to narrow it further, so
 * the first package is used and the ambiguity is real but bounded - a shared UID means a shared signing
 * key, which means [com.personal.bubuprotect.domain.model.RiskSignal.SIGNER_SIBLING] would have grouped
 * them anyway.
 */
class UidAttributor(context: Context) {

    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val packages = context.packageManager

    val isSupported: Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && connectivity != null

    /**
     * @return the package that sent [datagram], or null when it cannot be determined - an unsupported
     *   API level, a socket already closed by the time we looked, or a UID with no package (the kernel
     *   and system UIDs have none).
     *
     *   Null is never treated as an accusation. An unattributed ad-network hit is dropped rather than
     *   recorded, because a hit with no owner cannot appear on any app's evidence card and a guess would
     *   be the one thing this design refuses to do.
     */
    fun packageFor(datagram: Ipv4UdpPacket.Datagram): String? {
        // Checked inline rather than through isSupported. Same condition, but a boolean computed in the
        // constructor is not something lint can trace back to a version guard, and suppressing the
        // warning would mean losing the check that the guard is actually there.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null

        return try {
            val uid = connectivity?.getConnectionOwnerUid(
                Ipv4UdpPacket.PROTOCOL_UDP,
                InetSocketAddress(datagram.sourceAddress, datagram.sourcePort),
                InetSocketAddress(datagram.destinationAddress, datagram.destinationPort)
            ) ?: return null

            if (uid < 0) return null

            packages.getPackagesForUid(uid)?.firstOrNull()
        } catch (_: Exception) {
            // SecurityException on builds that gate the call more tightly, and IllegalArgumentException
            // for an address pair the platform rejects. Both mean "no attribution", not "no traffic".
            null
        }
    }
}
