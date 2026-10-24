package fyi.justfreesoftware.justblock.core

import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages the live state of the blocklist, including user-added rules and temporary pauses.
 * 
 * This class holds the active rules in memory and pushes any changes over to the Rust-based 
 * DnsProxy via the [rustProxyCallback], which handles the actual DNS packet filtering.
 */
class DnsFilter {
    private val TAG = "DnsFilter"

    @Volatile
    private var blocklist: Set<String> = emptySet()

    /**
     * User-whitelisted domains. Enforced by the Rust proxy, where an
     * allowlisted domain (and every subdomain of it) overrides the
     * blocklist, so it doesn't matter which exact form a blocklist uses.
     */
    @Volatile
    private var allowlist: Set<String> = emptySet()

    /**
     * Pushes the current blocklist and pause set to the Rust proxy whenever
     * either changes. The proxy enforces pause expiry lazily at query time,
     * so pauses keep working even when the app process (and its countdown
     * timers) is gone.
     */
    var rustProxyCallback: ((List<String>, Map<String, Long>) -> Unit)? = null

    /** Pushes the current allowlist to the Rust proxy whenever it changes. */
    var rustAllowlistCallback: ((List<String>) -> Unit)? = null

    /**
     * Domains whose blocking is temporarily paused.
     * Key = lowercased domain, Value = epoch-millis expiry time
     * ([Long.MAX_VALUE] for indefinite pauses).
     *
     * A paused domain stays in the persisted manual-blocked list but is
     * removed from the live [blocklist] so [shouldBlock] won't match it.
     * When the pause expires (or the user resumes), the domain is moved
     * back into the live blocklist.
     */
    private val pausedDomains = ConcurrentHashMap<String, Long>()

    @Synchronized
    fun updateBlocklist(newBlocklist: Set<String>) {
        this.blocklist = newBlocklist.filter { it.isNotBlank() }.toSet() - pausedDomains.keys
        Log.d(TAG, "DnsFilter initialized with ${this.blocklist.size} static rules.")
        rustProxyCallback?.invoke(blocklist.toList(), pausedDomains.toMap())
    }

    /**
     * Adds a single domain to the live blocklist immediately.
     * Used when the user adds a manual blocked domain so it takes effect
     * without waiting for a full blocklist refresh.
     */
    @Synchronized
    fun addDomain(domain: String) {
        val cleaned = domain.lowercase().trim()
        if (cleaned.isNotEmpty()) {
            blocklist = blocklist + cleaned
            Log.d(TAG, "Added domain to live filter: $cleaned (total: ${blocklist.size})")
            rustProxyCallback?.invoke(blocklist.toList(), pausedDomains.toMap())
        }
    }

    /**
     * Removes a single domain from the live blocklist immediately.
     * Used when the user deletes a manual blocked domain.
     *
     * Also drops any pause on it: the proxy blocks a domain whose pause has
     * expired, so a leftover pause would re-block the deleted domain.
     */
    @Synchronized
    fun removeDomain(domain: String) {
        val cleaned = domain.lowercase().trim()
        if (cleaned.isNotEmpty()) {
            blocklist = blocklist - cleaned
            pausedDomains.remove(cleaned)
            Log.d(TAG, "Removed domain from live filter: $cleaned (total: ${blocklist.size})")
            rustProxyCallback?.invoke(blocklist.toList(), pausedDomains.toMap())
        }
    }

    // -------------------------------------------------------------------------
    // Allowlist
    // -------------------------------------------------------------------------

    @Synchronized
    fun updateAllowlist(newAllowlist: Set<String>) {
        allowlist = newAllowlist.map { it.lowercase().trim() }.filter { it.isNotEmpty() }.toSet()
        rustAllowlistCallback?.invoke(allowlist.toList())
    }

    @Synchronized
    fun allowDomain(domain: String) {
        val cleaned = domain.lowercase().trim()
        if (cleaned.isNotEmpty()) {
            allowlist = allowlist + cleaned
            Log.d(TAG, "Allowlisted domain: $cleaned")
            rustAllowlistCallback?.invoke(allowlist.toList())
        }
    }

    @Synchronized
    fun disallowDomain(domain: String) {
        val cleaned = domain.lowercase().trim()
        if (cleaned.isNotEmpty()) {
            allowlist = allowlist - cleaned
            Log.d(TAG, "Removed domain from allowlist: $cleaned")
            rustAllowlistCallback?.invoke(allowlist.toList())
        }
    }

    fun getAllowlist(): List<String> = allowlist.toList()

    // -------------------------------------------------------------------------
    // Per-domain pause
    // -------------------------------------------------------------------------

    /**
     * Pauses blocking for [domain] until [expiresAt] epoch millis.
     * Use [Long.MAX_VALUE] for an indefinite pause.
     *
     * The domain is removed from the live blocklist so [shouldBlock] will
     * not match it.  It remains in the persisted manual-blocked list so
     * the user can resume later.
     */
    @Synchronized
    fun pauseDomain(domain: String, expiresAt: Long) {
        val cleaned = domain.lowercase().trim()
        if (cleaned.isNotEmpty()) {
            pausedDomains[cleaned] = expiresAt
            blocklist = blocklist - cleaned
            Log.d(TAG, "Paused domain: $cleaned until ${if (expiresAt == Long.MAX_VALUE) "indefinitely" else expiresAt}")
            rustProxyCallback?.invoke(blocklist.toList(), pausedDomains.toMap())
        }
    }

    /**
     * Resumes blocking for a previously paused [domain].
     * The domain is re-added to the live blocklist.
     */
    @Synchronized
    fun resumeDomain(domain: String) {
        val cleaned = domain.lowercase().trim()
        if (cleaned.isNotEmpty()) {
            pausedDomains.remove(cleaned)
            blocklist = blocklist + cleaned
            Log.d(TAG, "Resumed domain: $cleaned (total: ${blocklist.size})")
            rustProxyCallback?.invoke(blocklist.toList(), pausedDomains.toMap())
        }
    }

    @Synchronized
    fun clear() {
        this.blocklist = emptySet()
        this.allowlist = emptySet()
        pausedDomains.clear()
        Log.d(TAG, "DnsFilter memory cleared.")
        rustProxyCallback?.invoke(blocklist.toList(), pausedDomains.toMap())
        rustAllowlistCallback?.invoke(allowlist.toList())
    }

    fun getBlocklist(): List<String> = blocklist.toList()

    @Synchronized
    fun getPausedDomains(): Map<String, Long> = pausedDomains.toMap()
}