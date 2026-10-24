package fyi.justfreesoftware.justblock.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import fyi.justfreesoftware.justblock.ServiceLocator
import fyi.justfreesoftware.justblock.core.UpstreamConfig
import fyi.justfreesoftware.justblock.core.MyVpnService
import fyi.justfreesoftware.justblock.data.BlocklistRepository
import fyi.justfreesoftware.justblock.data.BlocklistState
import fyi.justfreesoftware.justblock.data.SourceIssue
import fyi.justfreesoftware.justblock.data.UserPreferences
import fyi.justfreesoftware.justblock.ui.theme.ThemeMode
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Bridges the UI layer to [UserPreferences], [BlocklistRepository], and [MyVpnService].
 *
 * All state the UI needs is exposed as [StateFlow]s so Compose can collect them
 * efficiently. All user actions are plain functions the UI calls directly — no
 * events/channels needed at this scale.
 *
 * Uses [AndroidViewModel] rather than [ViewModel] because starting/stopping
 * [MyVpnService] requires an [Application] context.
 */
class VpnViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs      = UserPreferences(application)
    private val repository get() = ServiceLocator.blocklistRepository

    /** Active auto-resume coroutines, keyed by domain. */
    private val pauseTimers = mutableMapOf<String, Job>()

    init {
        // Restore persisted pauses into the live DnsFilter and schedule
        // auto-resume timers for any non-indefinite pauses that haven't
        // expired yet.
        viewModelScope.launch {
            val persisted = prefs.getPausedDomains()
            val now = System.currentTimeMillis()
            var hadExpired = false
            for ((domain, expiresAt) in persisted) {
                if (expiresAt != Long.MAX_VALUE && now >= expiresAt) {
                    // Already expired — resume inline (live filter + persisted
                    // state, awaited) so the UI state read below cannot race a
                    // pending removal. The Rust proxy has been blocking it
                    // since expiry; this converges all state.
                    ServiceLocator.dnsFilter.resumeDomain(domain)
                    prefs.removePausedDomain(domain)
                    hadExpired = true
                    continue
                }
                ServiceLocator.dnsFilter.pauseDomain(domain, expiresAt)
                if (expiresAt != Long.MAX_VALUE) {
                    schedulePauseTimer(domain, expiresAt - now)
                }
            }
            // Emit initial state
            _pausedDomains.value = prefs.getPausedDomains()
            if (hadExpired) {
                flushDnsCache()
            }
        }
    }

    // -------------------------------------------------------------------------
    // VPN on/off state
    // -------------------------------------------------------------------------

    /**
     * Whether the VPN is currently active. Persisted in DataStore so the toggle
     * reflects the correct state after process death and recreation.
     */
    val isVpnEnabled: StateFlow<Boolean> = prefs.isVpnEnabledFlow
        .stateIn(
            scope         = viewModelScope,
            started       = SharingStarted.WhileSubscribed(5_000),
            initialValue  = false,
        )

    // ── Blocking enabled toggle ───────────────────────────────────────────────────

    /**
     * Toggles the VPN on or off.
     *
     * Persists the new state to DataStore first, then starts or stops the service.
     * The service reads its own upstream config independently on start, so no
     * extra coordination is needed here.
     *
     * If [isVpnEnabled] is already in the requested state (e.g. double-tap) this
     * is a no-op at the service level — [MyVpnService.startVpn] guards against
     * duplicate starts internally.
     */
    fun setVpnEnabled(enabled: Boolean) {
        viewModelScope.launch {
            prefs.setVpnEnabled(enabled)
            val action = if (enabled) MyVpnService.ACTION_START else MyVpnService.ACTION_STOP
            val serviceIntent = Intent(getApplication(), MyVpnService::class.java).apply {
                this.action = action
            }
            if (enabled && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                getApplication<Application>().startForegroundService(serviceIntent)
            } else {
                getApplication<Application>().startService(serviceIntent)
            }
        }
    }

    /**
     * Whether DNS filtering is currently active. When false, the VPN tunnel is
     * still up (traffic is protected / encrypted upstream) but no domains are
     * blocked. Persisted so the setting survives process death.
     */
    val isBlockingEnabled: StateFlow<Boolean> = prefs.isBlockingEnabledFlow
        .stateIn(
            scope        = viewModelScope,
            started      = SharingStarted.WhileSubscribed(5_000),
            initialValue = true,
        )

    fun setBlockingEnabled(enabled: Boolean) {
        viewModelScope.launch {
            prefs.setBlockingEnabled(enabled)
            // Push the change to the running service, which rebuilds the TUN
            // to apply it. When the VPN is off, don't wake the service: it
            // reads the setting from prefs on its next start.
            if (!prefs.isVpnEnabledFlow.first()) return@launch
            val serviceIntent = Intent(getApplication(), MyVpnService::class.java).apply {
                action = MyVpnService.ACTION_SET_BLOCKING
                putExtra(MyVpnService.EXTRA_BLOCKING_ENABLED, enabled)
            }
            getApplication<Application>().startForegroundService(serviceIntent)
        }
    }

    // ── Start on boot ─────────────────────────────────────────────────────────────

    val isStartOnBoot: StateFlow<Boolean> = prefs.isStartOnBootFlow
        .stateIn(
            scope        = viewModelScope,
            started      = SharingStarted.WhileSubscribed(5_000),
            initialValue = false,
        )

    fun setStartOnBoot(enabled: Boolean) {
        viewModelScope.launch { prefs.setStartOnBoot(enabled) }
    }

    val themeMode: StateFlow<ThemeMode> = prefs.themeModeFlow
        .map { 
            try {
                ThemeMode.valueOf(it)
            } catch (e: IllegalArgumentException) {
                ThemeMode.Dark
            }
        }
        .stateIn(
            scope        = viewModelScope,
            started      = SharingStarted.WhileSubscribed(5_000),
            initialValue = ThemeMode.Dark,
        )

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { prefs.setThemeMode(mode.name) }
    }

    val hasSeenWelcome: StateFlow<Boolean?> = prefs.hasSeenWelcomeFlow
        .stateIn(
            scope        = viewModelScope,
            started      = SharingStarted.WhileSubscribed(5_000),
            initialValue = null,
        )

    fun setHasSeenWelcome(seen: Boolean) {
        viewModelScope.launch { prefs.setHasSeenWelcome(seen) }
    }

    // -------------------------------------------------------------------------
    // Upstream DNS config
    // -------------------------------------------------------------------------

    /**
     * The currently saved upstream resolver.
     * The UI uses this to show which resolver is active and pre-populate the
     * settings screen. [MyVpnService] reads this independently via DataStore
     * on start — the ViewModel doesn't push it to the service directly.
     */
    val upstreamConfig: StateFlow<UpstreamConfig> = prefs.upstreamConfigFlow
        .stateIn(
            scope        = viewModelScope,
            started      = SharingStarted.WhileSubscribed(5_000),
            initialValue = UserPreferences.DEFAULT_UPSTREAM,
        )

    /**
     * Saves a new upstream resolver.
     *
     * If the VPN is currently running, a restart is required for the change to
     * take effect. The UI should surface this via [pendingRestartReason].
     */
    fun setUpstreamConfig(config: UpstreamConfig) {
        viewModelScope.launch {
            prefs.setUpstreamConfig(config)
            if (isVpnEnabled.value) {
                _pendingRestartReason.update { PendingRestartReason.UPSTREAM_CHANGED }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Pending restart banner
    // -------------------------------------------------------------------------

    /**
     * Set when a settings change requires a VPN restart to take effect.
     * The UI should show a non-intrusive banner ("Restart VPN to apply changes")
     * while this is non-null, and clear it once the user restarts or dismisses.
     */
    enum class PendingRestartReason { UPSTREAM_CHANGED }

    private val _pendingRestartReason = MutableStateFlow<PendingRestartReason?>(null)
    val pendingRestartReason: StateFlow<PendingRestartReason?> = _pendingRestartReason.asStateFlow()

    fun dismissRestartBanner() {
        _pendingRestartReason.update { null }
    }

    /**
     * Restarts the VPN to apply pending settings changes, then clears the banner.
     */
    fun restartVpn() {
        viewModelScope.launch {
            setVpnEnabled(false)
            delay(500) // Give the service time to tear down the TUN interface
            setVpnEnabled(true)
            _pendingRestartReason.update { null }
        }
    }

    // -------------------------------------------------------------------------
    // Manual blocked domains
    // -------------------------------------------------------------------------

    val manualBlockedDomains: StateFlow<Set<String>> = prefs.manualBlockedDomainsFlow
        .stateIn(
            scope        = viewModelScope,
            started      = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptySet(),
        )

    /**
     * Blocks the host of [domain], which may be a bare domain or a full URL.
     *
     * Input with no valid host is still saved, like an invalid blocklist
     * link, so the list can show why it isn't blocking (see
     * [isValidManualBlock]); it never reaches the filter.
     */
    fun addManualBlockedDomain(domain: String) {
        val trimmed = domain.trim()
        // "|" would corrupt the persisted "domain|expiry" pause encoding.
        if (trimmed.isBlank() || trimmed.contains("|")) return
        // A pasted URL like "https://www.example.com/page" is reduced to
        // "example.com": stored verbatim it would never match a DNS query,
        // and keeping "www." would leave the rest of the site reachable.
        val host = DomainInputParser.extractDomain(trimmed)
        viewModelScope.launch { prefs.addManualBlockedDomain(host ?: trimmed) }
        if (host == null) return
        // Push to the live DNS filter immediately so blocking takes effect
        // without waiting for a full blocklist refresh.
        ServiceLocator.dnsFilter.addDomain(host)
        flushDnsCache()
    }

    /** False for a saved manual block that isn't a host, so it can't block anything. */
    fun isValidManualBlock(domain: String): Boolean =
        DomainInputParser.extractDomain(domain) == domain

    fun removeManualBlockedDomain(domain: String) {
        val cleaned = domain.trim().lowercase()
        viewModelScope.launch {
            prefs.removeManualBlockedDomain(cleaned)
            // Also clear any active pause for this domain
            prefs.removePausedDomain(cleaned)
        }
        // Remove from the live filter. If the domain also appears in a
        // remote blocklist it will reappear on the next full refresh,
        // which is the expected behaviour (user should whitelist instead).
        ServiceLocator.dnsFilter.removeDomain(cleaned)
        cancelPauseTimer(cleaned)
        _pausedDomains.update { it - cleaned }
        flushDnsCache()
    }

    // -------------------------------------------------------------------------
    // Per-domain pause
    // -------------------------------------------------------------------------

    private val _pausedDomains = MutableStateFlow<Map<String, Long>>(emptyMap())

    /**
     * Currently paused domains and their epoch-millis expiry times.
     * [Long.MAX_VALUE] represents an indefinite pause.
     */
    val pausedDomains: StateFlow<Map<String, Long>> = _pausedDomains.asStateFlow()

    /**
     * Pauses blocking for [domain] for the given [durationMs].
     * Pass `null` for an indefinite pause.
     */
    fun pauseBlockedDomain(domain: String, durationMs: Long?) {
        val cleaned = domain.trim().lowercase()
        if (cleaned.contains("|")) return
        val expiresAt = if (durationMs != null)
            System.currentTimeMillis() + durationMs
        else
            Long.MAX_VALUE

        // Update live filter
        ServiceLocator.dnsFilter.pauseDomain(cleaned, expiresAt)

        // Persist
        viewModelScope.launch { prefs.addPausedDomain(cleaned, expiresAt) }

        // Update UI state
        _pausedDomains.update { it + (cleaned to expiresAt) }

        // Schedule auto-resume timer (cancel any existing one first)
        cancelPauseTimer(cleaned)
        if (durationMs != null) {
            schedulePauseTimer(cleaned, durationMs)
        }
        flushDnsCache()
    }

    /**
     * Immediately resumes blocking for a previously paused [domain].
     */
    fun resumeBlockedDomain(domain: String) {
        val cleaned = domain.trim().lowercase()

        // Update live filter
        ServiceLocator.dnsFilter.resumeDomain(cleaned)

        // Persist
        viewModelScope.launch { prefs.removePausedDomain(cleaned) }

        // Update UI state
        _pausedDomains.update { it - cleaned }

        cancelPauseTimer(cleaned)
        flushDnsCache()
    }

    private fun schedulePauseTimer(domain: String, delayMs: Long) {
        pauseTimers[domain] = viewModelScope.launch {
            delay(delayMs)
            // Auto-resume when the timer fires
            resumeBlockedDomain(domain)
        }
    }

    private fun cancelPauseTimer(domain: String) {
        pauseTimers.remove(domain)?.cancel()
    }

    // -------------------------------------------------------------------------
    // Whitelisted domains
    // -------------------------------------------------------------------------

    val whitelistedDomains: StateFlow<Set<String>> = prefs.whitelistedDomainsFlow
        .stateIn(
            scope        = viewModelScope,
            started      = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptySet(),
        )

    fun addWhitelistedDomain(domain: String) {
        val cleaned = domain.trim().lowercase()
        if (cleaned.isBlank()) return
        viewModelScope.launch { prefs.addWhitelistedDomain(cleaned) }
        // Allowlist takes precedence over the blocklist in the proxy, so
        // this unblocks the domain and its subdomains immediately.
        ServiceLocator.dnsFilter.allowDomain(cleaned)
        flushDnsCache()
    }

    fun removeWhitelistedDomain(domain: String) {
        val cleaned = domain.trim().lowercase()
        viewModelScope.launch { prefs.removeWhitelistedDomain(cleaned) }
        // Blocklist entries were never removed for the whitelist, so
        // dropping the allowlist entry restores whatever blocking applies.
        ServiceLocator.dnsFilter.disallowDomain(cleaned)
        flushDnsCache()
    }

    /** Outcome of adding a URL/domain via the Whitelist screen's URLs tab. */
    enum class AddUrlResult { Added, AlreadyWhitelisted, InvalidUrl }

    /**
     * Whitelists the domain of [raw], which may be a full URL or a bare
     * domain. Returns the outcome so the UI can show an appropriate
     * snackbar.
     */
    fun addWhitelistedUrl(raw: String): AddUrlResult {
        val domain = DomainInputParser.extractDomain(raw)
            ?: return AddUrlResult.InvalidUrl
        if (domain in whitelistedDomains.value) return AddUrlResult.AlreadyWhitelisted
        addWhitelistedDomain(domain)
        return AddUrlResult.Added
    }

    // -------------------------------------------------------------------------
    // Bypassed apps (exceptions)
    // -------------------------------------------------------------------------

    /**
     * Package names of apps whose traffic bypasses the VPN entirely.
     * Backed by [VpnService.Builder.addDisallowedApplication] at tunnel
     * creation time.
     */
    val bypassedApps: StateFlow<Set<String>> = prefs.bypassedAppsFlow
        .stateIn(
            scope        = viewModelScope,
            started      = SharingStarted.WhileSubscribed(5_000),
            initialValue = UserPreferences.DEFAULT_BYPASSED_APPS,
        )

    /**
     * Toggles whether [packageName] bypasses the VPN.
     * Persists the change to DataStore and flushes DNS cache so connections rebuild.
     */
    fun setAppBypassed(packageName: String, bypassed: Boolean) {
        viewModelScope.launch {
            if (bypassed) {
                prefs.addBypassedApp(packageName)
            } else {
                prefs.removeBypassedApp(packageName)
            }
            flushDnsCache()
        }
    }

    /**
     * Restores the bypass list to [UserPreferences.DEFAULT_BYPASSED_APPS],
     * clearing any user-added bypasses and re-enabling the system defaults.
     */
    fun resetBypassedAppsToDefaults() {
        viewModelScope.launch {
            prefs.setBypassedApps(UserPreferences.DEFAULT_BYPASSED_APPS)
            flushDnsCache()
        }
    }

    private fun flushDnsCache() {
        viewModelScope.launch {
            // Read the persisted setting rather than the isVpnEnabled StateFlow
            // snapshot — during early init (before UI subscribers attach) the
            // flow may not have emitted yet and would report a stale false.
            if (prefs.isVpnEnabledFlow.first()) {
                val serviceIntent = Intent(getApplication(), MyVpnService::class.java).apply {
                    action = MyVpnService.ACTION_FLUSH_DNS_CACHE
                }
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    getApplication<Application>().startForegroundService(serviceIntent)
                } else {
                    getApplication<Application>().startService(serviceIntent)
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Custom blocklist source URLs
    // -------------------------------------------------------------------------

    val customSourceUrls: StateFlow<Set<String>> = prefs.customSourceUrlsFlow
        .stateIn(
            scope        = viewModelScope,
            started      = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptySet(),
        )

    fun addCustomSourceUrl(url: String) {
        val cleaned = url.trim()
        if (cleaned.isBlank()) return
        viewModelScope.launch {
            prefs.addCustomSourceUrl(cleaned)
            reloadBlocklistsIfVpnOn()
        }
    }

    fun removeCustomSourceUrl(url: String) {
        viewModelScope.launch {
            prefs.removeCustomSourceUrl(url)
            reloadBlocklistsIfVpnOn()
        }
    }

    /**
     * Applies a changed source list to the running VPN. The repository
     * serializes loads, so a change made mid-refresh is picked up by a
     * follow-up load instead of being skipped. When the VPN is off, the
     * service loads the current sources on its next start anyway.
     */
    private suspend fun reloadBlocklistsIfVpnOn() {
        if (prefs.isVpnEnabledFlow.first()) {
            repository.loadAndCompileBlocklists()
        }
    }

    /** Sources that failed in the last refresh, keyed by URL. */
    val sourceIssues: StateFlow<Map<String, SourceIssue>> = repository.sourceIssues
        .stateIn(
            scope        = viewModelScope,
            started      = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyMap(),
        )



    // -------------------------------------------------------------------------
    // Blocklist refresh
    // -------------------------------------------------------------------------

    /**
     * Live state of the blocklist download pipeline, sourced directly from
     * [BlocklistRepository.state]. The UI observes this to show a loading
     * indicator, a domain count on success, or an error message.
     *
     * Possible values:
     *   [BlocklistState.Idle]    — nothing running, initial state
     *   [BlocklistState.Loading] — download + parse in progress
     *   [BlocklistState.Success] — done, carries total domain count
     *   [BlocklistState.Error]   — failed, carries error message string
     */
    val blocklistState: StateFlow<BlocklistState> = repository.state
        .stateIn(
            scope        = viewModelScope,
            started      = SharingStarted.WhileSubscribed(5_000),
            initialValue = BlocklistState.Idle,
        )

    /**
     * Triggers a fresh download and parse of all enabled blocklists via
     * [BlocklistRepository.loadAndCompileBlocklists]. Pulls the latest custom
     * source URLs from [UserPreferences] and merges them with the built-in lists.
     * Ignores the call if a refresh is already in progress.
     */
    fun refreshBlocklists() {
        if (blocklistState.value is BlocklistState.Loading) return
        viewModelScope.launch {
            repository.loadAndCompileBlocklists()
        }
    }
}