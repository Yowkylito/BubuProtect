package com.personal.bubuprotect.core.shield.network

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import com.personal.bubuprotect.R
import com.personal.bubuprotect.core.shield.recorder.FlightRecorder
import com.personal.bubuprotect.domain.model.ShieldEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * A DNS-only tunnel that attributes ad-network lookups to the app that made them.
 *
 * ### The design decision the whole class rests on
 *
 * A `VpnService` normally captures every packet on the device, which means implementing a userspace
 * TCP/IP stack to forward the 99% of traffic you did not want to inspect. That is why ad-blocking VPNs
 * are enormous, slow, and hard on the battery.
 *
 * This one routes a single `/32`. The tunnel advertises [FAKE_DNS] as the DNS server and adds a route
 * for exactly that one address, so DNS is the only traffic the kernel sends into the tunnel and
 * everything else - all TCP, all media, all of it - goes out the normal path untouched. The result needs
 * no TCP handling, no connection tracking, and no reassembly, and it cannot slow down or break traffic
 * it never sees.
 *
 * It also means this app is *incapable* of carrying the user's traffic anywhere, which is a stronger
 * statement than promising not to. There is no remote endpoint in this file.
 *
 * ### What it does per query
 *
 *  1. Reads a packet, parses IPv4/UDP, pulls the DNS question name out.
 *  2. Attributes it to an app via [UidAttributor].
 *  3. If the name is on the ad list, records it against that app. If that app is one the user chose to
 *     filter, answers NXDOMAIN and never forwards.
 *  4. Otherwise forwards to the upstream resolver the phone was already using, and relays the answer.
 *
 * ### Upstream, and why it refuses to start without one
 *
 * Queries are forwarded to whichever DNS server the underlying network already supplies - never a
 * hardcoded public resolver, because silently moving a user's DNS to a third party is a privacy change
 * they did not ask for. That address has to be captured *before* the tunnel is established, because
 * afterwards the active network is the tunnel and its DNS server is the fake one.
 *
 * If no upstream can be determined, the service stops instead of establishing. Breaking every name
 * lookup on the phone is far worse than not running an ad filter.
 */
class ShieldVpnService : VpnService(), KoinComponent {

    private val recorder: FlightRecorder by inject()

    private var tunnel: ParcelFileDescriptor? = null
    private var scope: CoroutineScope? = null
    private var loop: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            teardown()
            stopSelf()
            return START_NOT_STICKY
        }

        if (tunnel != null) return START_STICKY

        val upstream = upstreamResolver()
        if (upstream == null) {
            // No usable resolver. Establishing anyway would black-hole DNS for the whole device.
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundNotice()

        val established = try {
            Builder()
                .setSession(getString(R.string.shield_vpn_channel))
                .addAddress(TUN_ADDRESS, 32)
                .addDnsServer(FAKE_DNS)
                // The single route that makes this a DNS filter rather than a full VPN.
                .addRoute(FAKE_DNS, 32)
                // Our own lookups must not re-enter the tunnel: the upstream socket is protected
                // below, and excluding the package as well makes the loop impossible by construction.
                .addDisallowedApplication(packageName)
                .setBlocking(true)
                .establish()
        } catch (_: Exception) {
            null
        }

        if (established == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        tunnel = established
        running = true

        val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = serviceScope
        loop = serviceScope.launch { pump(established, upstream) }

        return START_STICKY
    }

    /**
     * The packet loop.
     *
     * Blocking reads on the tunnel descriptor, on an IO dispatcher, cancelled by closing the descriptor
     * in [teardown] - which is what unblocks a thread parked in `read`. Cancelling the coroutine alone
     * would not, because a blocking file read is not a suspension point.
     */
    private suspend fun pump(descriptor: ParcelFileDescriptor, upstream: InetAddress) {
        val attributor = UidAttributor(applicationContext)
        val blocklist = DomainBlocklist.load(applicationContext)

        val input = FileInputStream(descriptor.fileDescriptor)
        val output = FileOutputStream(descriptor.fileDescriptor)
        val buffer = ByteArray(MAX_PACKET_BYTES)

        val socket = DatagramSocket().apply {
            soTimeout = UPSTREAM_TIMEOUT_MILLIS
            // Keeps our own upstream queries out of the tunnel we are servicing.
            protect(this)
        }

        try {
            while (scope?.isActive == true) {
                val read = try {
                    input.read(buffer)
                } catch (_: Exception) {
                    break
                }
                if (read <= 0) continue

                val datagram = Ipv4UdpPacket.parse(buffer, read) ?: continue
                if (datagram.destinationPort != DNS_PORT) continue

                val hostname = DnsMessage.questionName(datagram.payload, datagram.payloadLength)
                val owner = attributor.packageFor(datagram)
                val onAdList = hostname != null && blocklist.matches(hostname)

                if (onAdList && owner != null) {
                    val filtered = owner in filteredPackages
                    recorder.record(
                        ShieldEvent.AdHostResolved(
                            packageName = owner,
                            at = System.currentTimeMillis(),
                            host = hostname,
                            blocked = filtered
                        )
                    )

                    if (filtered) {
                        DnsMessage.nameErrorResponse(datagram.payload, datagram.payloadLength)
                            ?.let { output.write(Ipv4UdpPacket.buildReply(datagram, it)) }
                        continue
                    }
                }

                forward(socket, upstream, datagram, output)
            }
        } finally {
            runCatching { socket.close() }
            runCatching { input.close() }
            runCatching { output.close() }
        }
    }

    /**
     * Relays one query upstream and writes the answer back into the tunnel.
     *
     * A timeout is not retried and not answered. The client's own resolver already retries - that is
     * what its timeout is for - and manufacturing a reply for a query we failed to resolve would turn a
     * slow network into a wrong answer.
     */
    private fun forward(
        socket: DatagramSocket,
        upstream: InetAddress,
        request: Ipv4UdpPacket.Datagram,
        output: FileOutputStream
    ) {
        try {
            socket.send(
                DatagramPacket(
                    request.payload,
                    request.payloadLength,
                    InetSocketAddress(upstream, DNS_PORT)
                )
            )

            val answer = ByteArray(MAX_PACKET_BYTES)
            val received = DatagramPacket(answer, answer.size)
            socket.receive(received)

            output.write(
                Ipv4UdpPacket.buildReply(request, answer.copyOf(received.length))
            )
        } catch (_: Exception) {
            // Timeout, unreachable resolver, or a closed tunnel mid-write. The client retries.
        }
    }

    /**
     * The resolver the phone is already using, read before the tunnel exists.
     *
     * IPv4 only, because the tunnel only carries IPv4 - an IPv6 resolver address could not be reached
     * through the socket this service creates, and picking one would mean every forward silently timing
     * out.
     */
    private fun upstreamResolver(): InetAddress? = try {
        val connectivity = getSystemService(ConnectivityManager::class.java)
        val active = connectivity?.activeNetwork
        connectivity?.getLinkProperties(active)
            ?.dnsServers
            ?.firstOrNull { it is Inet4Address }
    } catch (_: Exception) {
        null
    }

    /**
     * The foreground notice.
     *
     * Required, and worth being blunt in: a persistent notification saying an app is handling your DNS
     * is exactly what a user should see, and the text says what does and does not go through the tunnel
     * rather than a vague "protection active".
     */
    private fun startForegroundNotice() {
        val manager = getSystemService(NotificationManager::class.java)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager?.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.shield_vpn_channel),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }

        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.shield_vpn_running))
            .setContentText(getString(R.string.shield_vpn_running_detail))
            .setSmallIcon(R.drawable.loading3)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun teardown() {
        running = false
        loop?.cancel()
        scope?.cancel()
        scope = null
        loop = null
        // Closing the descriptor is what unblocks a thread parked in a blocking read.
        runCatching { tunnel?.close() }
        tunnel = null
    }

    override fun onRevoke() {
        // The user turned the tunnel off from Settings, or another VPN took over. Only one VPN can hold
        // the interface, so this is a normal outcome rather than an error.
        teardown()
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    companion object {

        private const val ACTION_STOP = "com.personal.bubuprotect.shield.STOP_VPN"

        /**
         * The tunnel's own addresses.
         *
         * Both from 198.18.0.0/15, which RFC 2544 reserves for benchmarking and which is therefore not
         * routable on any real network. A private-range address such as 10.0.0.1 would collide with
         * whatever the user's home or corporate network actually uses.
         */
        private const val TUN_ADDRESS = "198.18.0.2"
        private const val FAKE_DNS = "198.18.0.1"

        private const val DNS_PORT = 53

        /** Comfortably above the 512-byte classic DNS limit and any EDNS0 size a client will advertise. */
        private const val MAX_PACKET_BYTES = 4096

        private const val UPSTREAM_TIMEOUT_MILLIS = 5_000

        private const val CHANNEL_ID = "bubu_shield_filter"
        private const val NOTIFICATION_ID = 4711

        @Volatile
        var running: Boolean = false
            private set

        /**
         * Apps whose ad-network lookups are answered NXDOMAIN rather than merely counted.
         *
         * Blocking is opt-in per app, and this is the whole opt-in. Everything else is only attributed,
         * because a device-wide ad block would break free apps whose ads are how they are paid for -
         * a call this app has no standing to make for the user.
         *
         * A plain volatile set read by the packet loop and replaced wholesale by the orchestrator.
         * Replaced rather than mutated so the loop can never observe a half-updated collection.
         */
        @Volatile
        var filteredPackages: Set<String> = emptySet()

        /**
         * Whether Android will let the tunnel start without asking the user first.
         *
         * @return null when consent is already granted, otherwise the Intent that has to be launched
         *   *from an Activity* - `VpnService.prepare` requires it, which is why this returns the Intent
         *   for the UI to launch rather than launching it here.
         */
        fun consentIntent(context: Context): Intent? = prepare(context)

        fun start(context: Context) {
            context.startService(Intent(context, ShieldVpnService::class.java))
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, ShieldVpnService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
