package fyi.justfreesoftware.justblock.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import fyi.justfreesoftware.justblock.MainActivity
import fyi.justfreesoftware.justblock.R
import fyi.justfreesoftware.justblock.ServiceLocator
import fyi.justfreesoftware.justblock.data.BlocklistRepository
import fyi.justfreesoftware.justblock.data.BlocklistState
import fyi.justfreesoftware.justblock.data.UserPreferences
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import uniffi.just_block_rust.DnsProxy
import uniffi.just_block_rust.Doh3Backup

/**
 * Android VpnService that creates a local TUN interface and routes all DNS traffic
 * through the Rust-based [DnsProxy] (via UniFFI) for blocklist enforcement.
 *
 * Startup sequence:
 *   1. Build and establish the TUN interface.
 *   2. Pass the TUN file descriptor to [DnsProxy], which spawns an async Tokio
 *      loop that reads raw packets, parses IP/UDP/DNS headers, checks the
 *      blocklist trie, and forwards allowed queries upstream via DoQ.
 *   3. Instantly arm the blocklist from the local cache via [BlocklistRepository]
 *      so that blocking starts immediately.
 *   4. Download and compile the latest blocklist in the background. Once complete,
 *      [DnsFilter] is updated and the new blocklist is pushed to the Rust proxy.
 *
 */
class MyVpnService : VpnService() {

    companion object {
        private const val TAG = "MyVpnService"

        const val ACTION_START = "fyi.justfreesoftware.justblock.START"
        const val ACTION_STOP = "fyi.justfreesoftware.justblock.STOP"
        const val ACTION_SET_BLOCKING = "fyi.justfreesoftware.justblock.SET_BLOCKING"
        const val ACTION_FLUSH_DNS_CACHE = "fyi.justfreesoftware.justblock.FLUSH_DNS_CACHE"
        const val EXTRA_BLOCKING_ENABLED = "blocking_enabled"

        private const val NOTIFICATION_ID = 1
        private const val NOTIFICATION_CHANNEL = "justblock_vpn"

        /**
         * How long to wait for a stopped proxy's Rust task to exit and let
         * go of the TUN fd. It normally exits within milliseconds.
         */
        private const val PROXY_EXIT_TIMEOUT_MS = 2_000L

        /** Extra wait before restarting when the old proxy missed that timeout. */
        private const val PROXY_EXIT_GRACE_MS = 1_000L

        /** Coalesces bursts of rule changes into one TUN rebuild. */
        private const val REBUILD_DEBOUNCE_MS = 200L

        private const val BLOCKLIST_REFRESH_INTERVAL_MS = 24 * 60 * 60 * 1000L

        // The TUN carries only DNS: one host address and one host route per
        // family, pointing at the fake DNS servers.
        private const val TUN_ADDRESS_V4 = "10.0.0.2"
        private const val TUN_DNS_V4 = "10.0.0.1"
        private const val TUN_ADDRESS_V6 = "fd00:1::2"
        private const val TUN_DNS_V6 = "fd00:1::1"
        private const val HOST_PREFIX_V4 = 32
        private const val HOST_PREFIX_V6 = 128
        private const val TUN_MTU = 1500

        /**
         * Backup upstreams used when the primary (AdGuard DoQ) fails.
         * Cloudflare and Google don't offer DoQ, so these are DoH3
         * (DNS over HTTP/3, RFC 8484 over an HTTP/3 POST).
         */
        private val DOH3_BACKUPS = listOf(
            Doh3Backup("1.1.1.1", "2606:4700:4700::1111", "cloudflare-dns.com", "/dns-query"),
            Doh3Backup("8.8.8.8", "2001:4860:4860::8888", "dns.google", "/dns-query")
        )
    }

    private val userPreferences by lazy { UserPreferences(applicationContext) }

    private val dnsFilter get() = ServiceLocator.dnsFilter

    private var dnsProxy: DnsProxy? = null

    private val blocklistRepository get() = ServiceLocator.blocklistRepository

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private var vpnInterface: ParcelFileDescriptor? = null
    private var dailyRefreshJob: Job? = null

    /**
     * True from the moment a start is accepted until teardown. Set
     * synchronously, unlike [vpnInterface], which is only assigned once the
     * async startup has established the TUN.
     */
    @Volatile
    private var isRunning = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            // A null intent is a START_STICKY restart after process death;
            // SERVICE_INTERFACE is how the system starts an always-on VPN.
            ACTION_START, SERVICE_INTERFACE, null -> startVpn()
            ACTION_STOP -> {
                serviceScope.launch { userPreferences.setVpnEnabled(false) }
                stopVpn()
            }
            ACTION_SET_BLOCKING -> {
                val enabled = intent.getBooleanExtra(EXTRA_BLOCKING_ENABLED, true)
                dnsFilter.setBlockingEnabled(enabled)
                Log.i(TAG, "Blocking ${if (enabled) "enabled" else "disabled"}")
                if (isRunning) debounceRebuild() else stopIdleService()
            }
            ACTION_FLUSH_DNS_CACHE -> {
                if (isRunning) debounceRebuild() else stopIdleService()
            }
        }
        return START_STICKY
    }

    /**
     * Called when the VPN is taken away from us: another VPN app started, or
     * the user disconnected it in system settings. The default implementation
     * only calls stopSelf(), which would leave the proxy running and the
     * persisted toggle stuck on.
     */
    override fun onRevoke() {
        Log.w(TAG, "VPN permission revoked by the system")
        markVpnDisabled()
        stopVpn()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isRunning) teardown()
        unregisterNetworkCallback()
        serviceScope.cancel()
        Log.d(TAG, "MyVpnService destroyed")
    }

    /**
     * Handles a command that needs a running VPN when there is none. The
     * caller may have used startForegroundService(), so the service must
     * enter the foreground before stopping, or the system kills the app with
     * ForegroundServiceDidNotStartInTimeException.
     */
    private fun stopIdleService() {
        Log.w(TAG, "Command received while VPN is not running — stopping")
        startForeground(
            NOTIFICATION_ID,
            buildNotification(),
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Persists the VPN as off when it stops without the user asking (revoked,
     * failed to start), so the UI toggle doesn't claim it is still on.
     * NonCancellable because the service scope dies right after stopSelf().
     */
    private fun markVpnDisabled() {
        serviceScope.launch(NonCancellable) { userPreferences.setVpnEnabled(false) }
    }

    private fun startVpn() {
        if (isRunning) {
            Log.w(TAG, "VPN already running — ignoring start command")
            return
        }
        isRunning = true

        Log.i(TAG, "Starting VPN engine…")

        val notification = buildNotification()

        startForeground(
            NOTIFICATION_ID,
            notification,
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )

        serviceScope.launch {
            // Always-on VPN and sticky restarts start us without the UI, so
            // make the persisted toggle match.
            userPreferences.setVpnEnabled(true)
            try {
                connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as? ConnectivityManager
                activePhysicalNetwork = connectivityManager?.activeNetwork

                // Set before the proxy starts, so it never blocks while the
                // user has blocking switched off.
                val blockingEnabled = userPreferences.isBlockingEnabledFlow.first()
                dnsFilter.setBlockingEnabled(blockingEnabled)
                Log.i(TAG, "Blocking enabled: $blockingEnabled")

                // Hold the rebuild lock for the whole initial setup so a
                // flush or network change arriving now can't interleave.
                rebuildMutex.withLock {
                    val tun = buildTunInterface() ?: run {
                        Log.e(TAG, "Failed to establish TUN interface")
                        markVpnDisabled()
                        stopVpn()
                        return@launch
                    }
                    synchronized(dnsFilter) {
                        if (!isRunning) {
                            tun.close()
                            return@launch
                        }
                        vpnInterface = tun
                    }

                    // Restore persisted pauses into the live filter before the
                    // proxy starts. Without this, a service start without the UI
                    // process (boot, START_STICKY restart) would forget active
                    // pauses and block mid-pause domains.
                    val persistedPauses = userPreferences.getPausedDomains()
                    val now = System.currentTimeMillis()
                    for ((domain, expiresAt) in persistedPauses) {
                        if (expiresAt != Long.MAX_VALUE && now >= expiresAt) {
                            // Already expired — drop it; the Rust proxy lazily
                            // enforces expiry at query time anyway.
                            userPreferences.removePausedDomain(domain)
                        } else {
                            dnsFilter.pauseDomain(domain, expiresAt)
                        }
                    }

                    startTunLoop(tun)
                }
                registerNetworkCallback()

                val savedUpstream = userPreferences.getUpstreamConfig()
                Log.i(TAG, "Upstream initialised: $savedUpstream")

                Log.i(TAG, "Fetching blocklist in background…")
                blocklistRepository.loadAndCompileBlocklists()

                when (val state = blocklistRepository.state.value) {
                    is BlocklistState.Success ->
                        Log.i(TAG, "Blocklist ready — ${state.totalDomains} domains loaded")
                    is BlocklistState.Error ->
                        Log.e(TAG, "Blocklist failed to load: ${state.message}")
                    else -> Unit
                }

                startDailyRefresh()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error starting VPN — cleaning up", e)
                markVpnDisabled()
                stopVpn()
            }
        }
    }

    /**
     * Re-downloads and re-compiles all blocklists once a day while the VPN is
     * running, so lists stay current without a manual refresh button. The
     * loop dies with [serviceScope] when the service is destroyed; a fresh
     * service start always re-downloads immediately anyway.
     */
    private fun startDailyRefresh() {
        dailyRefreshJob?.cancel()
        dailyRefreshJob = serviceScope.launch {
            while (true) {
                kotlinx.coroutines.delay(BLOCKLIST_REFRESH_INTERVAL_MS)
                if (vpnInterface == null) break
                Log.i(TAG, "Daily blocklist refresh…")
                blocklistRepository.loadAndCompileBlocklists()
            }
        }
    }

    private fun stopVpn() {
        teardown()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Releases the tunnel, the proxy and the filter state. */
    private fun teardown() {
        Log.i(TAG, "Stopping VPN engine…")
        isRunning = false

        dailyRefreshJob?.cancel()
        dailyRefreshJob = null
        unregisterNetworkCallback()
        releaseProxy()

        synchronized(dnsFilter) {
            try {
                vpnInterface?.close()
            } catch (e: IOException) {
                Log.e(TAG, "Failed to close TUN interface cleanly", e)
            } finally {
                vpnInterface = null
            }

            // Frees the blocklist; the next start reloads it from the cache.
            dnsFilter.clear()
        }
    }

    /**
     * Stops and frees the current proxy. Closing releases its QUIC sockets
     * now rather than whenever the Kotlin handle is garbage collected; the
     * Rust task still holds what it needs until it exits.
     *
     * With [awaitExit], blocks (up to [PROXY_EXIT_TIMEOUT_MS]) until the
     * proxy's Rust task has exited and let go of the TUN fd, and returns
     * false if it hasn't by then. Callers about to reuse or close the TUN
     * fd use this so the old task can't interfere with the next proxy. It
     * must not be used on the main thread.
     *
     * The proxy is published and unpublished under the [dnsFilter] monitor,
     * then stopped and closed outside the lock, so waiting for it to exit
     * doesn't stall rule updates. The rules live in [DnsFilter.rules], not
     * in the proxy, so nothing is lost with it.
     */
    private fun releaseProxy(awaitExit: Boolean = false): Boolean {
        val proxy = synchronized(dnsFilter) {
            dnsProxy.also { dnsProxy = null }
        } ?: return true

        val exited = if (awaitExit) {
            proxy.stopAndWait(PROXY_EXIT_TIMEOUT_MS.toULong())
        } else {
            proxy.stop()
            true
        }
        proxy.close()
        return exited
    }

    /**
     * Creates a [DnsProxy] on [tun], sharing the filter's rules, and starts
     * it. Suspends until the proxy is running (or discarded), so
     * callers holding [rebuildMutex] cover the whole startup.
     *
     * If the VPN was stopped or the TUN replaced while this was resolving the
     * upstream, the new proxy is discarded instead of being started on a
     * closed (and possibly reused) fd.
     */
    private suspend fun startTunLoop(tun: ParcelFileDescriptor) {
        val fd = tun.fd
        val upstreamConfig = userPreferences.getUpstreamConfig()

        var upstreamV4: String? = null
        var upstreamV6: String? = null

        try {
            val activeNetwork = connectivityManager?.activeNetwork
            val addresses = if (activeNetwork != null) {
                activeNetwork.getAllByName(upstreamConfig.host)
            } else {
                java.net.InetAddress.getAllByName(upstreamConfig.host)
            }

            upstreamV4 = addresses.firstOrNull { it is java.net.Inet4Address }?.hostAddress
            upstreamV6 = addresses.firstOrNull { it is java.net.Inet6Address }?.hostAddress
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resolve upstream IP", e)
        }

        // Fallbacks in case it's already an IP string
        if (upstreamV4 == null && upstreamV6 == null) {
            if (upstreamConfig.host.contains(":")) {
                upstreamV6 = upstreamConfig.host
            } else {
                upstreamV4 = upstreamConfig.host
            }
        }

        Log.i(TAG, "Resolved upstream ${upstreamConfig.host} to v4: $upstreamV4, v6: $upstreamV6")

        // Pass resolved IPs, but keep SNI hostname for the QUIC handshake.
        // Backups (Cloudflare, Google) are literal IPs — no resolution needed.
        val proxy = try {
            DnsProxy(
                fd,
                upstreamV4,
                upstreamV6,
                upstreamConfig.sniHostname,
                DOH3_BACKUPS,
                dnsFilter.rules
            )
        } catch (e: Exception) {
            // Rust panics (e.g. a socket that can't be bound) surface here.
            Log.e(TAG, "Failed to create DNS proxy", e)
            return
        }

        for (quicFd in proxy.getQuicFds()) {
            if (protect(quicFd)) {
                Log.i(TAG, "Protected QUIC socket (fd: $quicFd) from VPN tunnel")
            } else {
                Log.e(TAG, "Failed to protect QUIC socket (fd: $quicFd) from VPN tunnel")
            }
        }

        synchronized(dnsFilter) {
            if (!isRunning || vpnInterface !== tun) {
                Log.w(TAG, "TUN changed while the proxy was starting — discarding it")
                proxy.close()
                return
            }
            dnsProxy = proxy
            proxy.start()
        }
    }

    private val rebuildMutex = Mutex()
    private var rebuildJob: Job? = null
    private var networkReconnectJob: Job? = null

    private fun debounceRebuild() {
        rebuildJob?.cancel()
        rebuildJob = serviceScope.launch {
            kotlinx.coroutines.delay(REBUILD_DEBOUNCE_MS)
            rebuildTunInterface()
        }
    }

    /**
     * Tears down and re-establishes the TUN interface.
     *
     * Closing the old file descriptor causes the Rust [DnsProxy] read loop to
     * exit (the TUN fd returns EOF / error).  A new TUN is established and a
     * fresh [DnsProxy] is started, which also forces Android to flush its DNS
     * resolver cache so all apps immediately re-query through our filter
     * instead of using stale cached results.
     *
     * Used for blocking toggle and DNS cache flush — NOT for network changes,
     * which use [reconnectUpstream] instead to avoid tearing down the VPN.
     */
    private suspend fun rebuildTunInterface() {
        rebuildMutex.withLock {
            if (!isRunning) return
            Log.i(TAG, "Rebuilding TUN to flush DNS cache…")

            // Wait for the old proxy to let go of the TUN before closing it:
            // the new TUN can be handed the same fd number. If it doesn't
            // exit in time, carry on; the new proxy retries its registration.
            if (!releaseProxy(awaitExit = true)) {
                Log.w(TAG, "Old proxy still exiting — rebuilding anyway")
            }

            try {
                vpnInterface?.close()
            } catch (e: IOException) {
                Log.w(TAG, "Error closing old TUN fd", e)
            }

            val tun = buildTunInterface() ?: run {
                Log.e(TAG, "Failed to re-establish TUN after blocking re-enabled")
                return
            }
            synchronized(dnsFilter) {
                if (!isRunning) {
                    // Stopped while establishing: don't resurrect the tunnel.
                    tun.close()
                    return
                }
                vpnInterface = tun
            }

            startTunLoop(tun)
            Log.i(TAG, "TUN rebuilt — DNS cache flushed")
        }
    }

    /**
     * Recycles the upstream [DnsProxy] without tearing down the TUN interface.
     *
     * When the physical network changes (WiFi ↔ Mobile), the old QUIC sockets
     * are bound to the previous network's routing and can no longer reach the
     * upstream DNS server.  This method:
     *   1. Stops the old [DnsProxy] (kills stale QUIC connections).
     *   2. Updates the VPN's underlying network so Android reports correct
     *      connectivity status for bypassed apps (GMS, etc.).
     *   3. Starts a fresh [DnsProxy] with new QUIC sockets on the same TUN fd.
     *
     * Crucially, the TUN interface stays alive throughout, so Android never
     * deregisters the VPN as the default network.  This prevents the
     * connectivity disruption that caused bypassed apps like Google Messages
     * to lose their RCS / Play Services connections.
     */
    private suspend fun reconnectUpstream() {
        rebuildMutex.withLock {
            val tun = vpnInterface ?: return

            Log.i(TAG, "Reconnecting upstream on new network…")

            // 1. Stop the old proxy (kills stale QUIC connections bound to
            //    the old network) and wait for its Tokio task to release the
            //    TUN fd, so the new proxy can register it straight away.
            if (!releaseProxy(awaitExit = true)) {
                // 2. It didn't exit in time. Give it a little longer; the new
                //    proxy also retries its registration for a few seconds.
                Log.w(TAG, "Old proxy still exiting — delaying the restart")
                kotlinx.coroutines.delay(PROXY_EXIT_GRACE_MS)
            }

            // 3. Update underlying network so the VPN inherits the new network's
            //    connectivity status — bypassed apps see uninterrupted internet.
            activePhysicalNetwork?.let { setUnderlyingNetworks(arrayOf(it)) }

            // 4. Start a fresh proxy on the same TUN fd with new QUIC sockets
            //    (discarded by startTunLoop if the VPN stopped meanwhile)
            startTunLoop(tun)
            Log.i(TAG, "Upstream reconnected — new QUIC sockets on current network")
        }
    }

    /**
     * Builds and establishes the TUN interface to redirect DNS queries to the
     * Rust [DnsProxy]. Reads whitelisted apps from [UserPreferences] and
     * excludes them from the VPN via [Builder.addDisallowedApplication].
     */
    private suspend fun buildTunInterface(): ParcelFileDescriptor? {
        if (connectivityManager == null) {
            connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as? ConnectivityManager
        }

        val builder = Builder()
            .setSession("JustBlockVPN")
            // IPv4 Setup
            .addAddress(TUN_ADDRESS_V4, HOST_PREFIX_V4)
            .addDnsServer(TUN_DNS_V4)
            // Route DNS traffic through TUN to our packet router
            .addRoute(TUN_DNS_V4, HOST_PREFIX_V4)
            // IPv6 Setup
            .addAddress(TUN_ADDRESS_V6, HOST_PREFIX_V6)
            .addDnsServer(TUN_DNS_V6)
            .addRoute(TUN_DNS_V6, HOST_PREFIX_V6)
            .setMtu(TUN_MTU)

        // Set underlying network explicitly to inherit NET_CAPABILITY_INTERNET immediately
        if (activePhysicalNetwork != null) {
            builder.setUnderlyingNetworks(arrayOf(activePhysicalNetwork!!))
        } else {
            val activeNetwork = connectivityManager?.activeNetwork
            if (activeNetwork != null) {
                builder.setUnderlyingNetworks(arrayOf(activeNetwork))
            }
        }

        // Allow apps to explicitly bypass the VPN to fix Google SMS / RCS
        builder.allowBypass()

        // Exclude bypassed apps from the VPN tunnel
        val bypassedApps = userPreferences.getBypassedApps().toMutableSet()

        // Critical system packages that must bypass the VPN to maintain connectivity signalling
        val systemBypasses = listOf(
            packageName // Just Block itself
        )
        bypassedApps.addAll(systemBypasses)

        for (pkg in bypassedApps) {
            try {
                builder.addDisallowedApplication(pkg)
                Log.d(TAG, "Excluded app from VPN: $pkg")
            } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
                Log.w(TAG, "Skipping unknown package: $pkg", e)
            }
        }

        return builder.establish()
    }

    private var activePhysicalNetwork: Network? = null

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            Log.d(TAG, "Physical network available: $network (waiting for validation)")
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            // NOT_VPN is already filtered by the NetworkRequest, and only the
            // best matching network is reported, so a different network here
            // means the system really switched.
            // Only act once the network passes Android's connectivity validation.
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return
            if (network == activePhysicalNetwork) return

            Log.i(TAG, "Validated physical network: $network — reconnecting upstream")
            activePhysicalNetwork = network

            // Recycle the DnsProxy without tearing down the TUN.
            // This keeps the VPN interface alive so Android never reports a
            // connectivity gap to bypassed apps (GMS, Google Messages, etc.).
            networkReconnectJob?.cancel()
            networkReconnectJob = serviceScope.launch { reconnectUpstream() }
        }

        override fun onLost(network: Network) {
            Log.w(TAG, "Physical network lost: $network")
            if (network == activePhysicalNetwork) {
                activePhysicalNetwork = null
                // Try to reconnect on any other available network.
                // The ConnectivityManager will deliver onCapabilitiesChanged
                // for any remaining validated network, which will trigger reconnect.
            }
        }
    }

    private var connectivityManager: ConnectivityManager? = null

    /**
     * Registers a callback that tracks the single best physical (non-VPN)
     * network.
     *
     * [ConnectivityManager.registerDefaultNetworkCallback] can't be used: it
     * reports the VPN itself as the default network while the VPN is active.
     * [ConnectivityManager.registerNetworkCallback] can't either: it reports
     * every matching network, so with Wi-Fi and mobile data both up, routine
     * capability updates (e.g. signal strength) alternate between the two and
     * each one would recycle the proxy. The best-matching callback only ever
     * reports the network the system would pick for this request.
     */
    private fun registerNetworkCallback() {
        connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as? ConnectivityManager
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        connectivityManager?.registerBestMatchingNetworkCallback(
            request,
            networkCallback,
            android.os.Handler(android.os.Looper.getMainLooper())
        )
        Log.d(TAG, "Network callback registered (tracking best physical network)")
    }

    private fun unregisterNetworkCallback() {
        runCatching { connectivityManager?.unregisterNetworkCallback(networkCallback) }
        connectivityManager = null
    }

    private fun buildNotification(): Notification {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }

        getSystemService(NotificationManager::class.java)
            ?.createNotificationChannel(channel)

        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java).apply {
                action = MainActivity.ACTION_STOP_AND_CLOSE
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .setNumber(0)
            .addAction(
                android.R.drawable.ic_delete,
                getString(R.string.notification_stop),
                stopIntent
            )
            .build()
    }
}
