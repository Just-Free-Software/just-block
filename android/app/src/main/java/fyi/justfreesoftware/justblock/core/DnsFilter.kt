package fyi.justfreesoftware.justblock.core

import android.util.Log
import uniffi.just_block_rust.BlocklistBuilder
import uniffi.just_block_rust.FilterRules

private const val TAG = "DnsFilter"

/**
 * The app's handle on the filtering rules: blocklist, allowlist, pauses and
 * the blocking switch.
 *
 * The rules themselves live in Rust, in [rules], which every DnsProxy
 * shares. That is the only copy of the blocklist: it is streamed across in
 * batches through a [BlocklistBuilder] and never held on this side. Only
 * the allowlist and the pauses, a handful of user entries each, are also
 * kept here, because they are edited one entry at a time and pushed whole.
 *
 * Changes take effect on the proxy's next query.
 */
class DnsFilter {
    /**
     * The Rust-side rules. One instance for the whole process, so a new
     * proxy (TUN rebuild, upstream reconnect) picks up the current rules
     * without anything being pushed again. Created on first use, which is
     * also what loads the native library.
     */
    val rules: FilterRules by lazy { FilterRules() }

    /**
     * User-whitelisted domains. An allowlisted domain (and every subdomain
     * of it) overrides the blocklist, so it doesn't matter which exact form
     * a blocklist uses.
     */
    private var allowlist: Set<String> = emptySet()

    /**
     * Domains whose blocking is temporarily paused.
     * Key = lowercased domain, Value = epoch-millis expiry time
     * ([Long.MAX_VALUE] for indefinite pauses).
     *
     * A paused domain stays in the blocklist; the proxy ignores its entry
     * while it is in this map and blocks it again once the pause expires.
     * Expiry is enforced in Rust at query time, so pauses keep working even
     * when the app process (and its countdown timers) is gone.
     */
    private val pausedDomains = HashMap<String, Long>()

    /**
     * Replaces the blocklist with what [builder] has collected, leaving the
     * builder empty.
     */
    @Synchronized
    fun updateBlocklist(builder: BlocklistBuilder) {
        Log.d(TAG, "Blocklist replaced with ${builder.count()} rules.")
        rules.setBlocklist(builder)
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
            rules.addBlocked(cleaned)
            Log.d(TAG, "Added domain to live filter: $cleaned")
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
            rules.removeBlocked(cleaned)
            if (pausedDomains.remove(cleaned) != null) pushPauses()
            Log.d(TAG, "Removed domain from live filter: $cleaned")
        }
    }

    /**
     * With blocking disabled the proxy forwards every query; the lists are
     * kept, so enabling it again needs no reload.
     */
    fun setBlockingEnabled(enabled: Boolean) {
        rules.setBlockingEnabled(enabled)
    }

    // -------------------------------------------------------------------------
    // Allowlist
    // -------------------------------------------------------------------------

    @Synchronized
    fun updateAllowlist(newAllowlist: Set<String>) {
        allowlist = newAllowlist.map { it.lowercase().trim() }.filter { it.isNotEmpty() }.toSet()
        pushAllowlist()
    }

    @Synchronized
    fun allowDomain(domain: String) {
        val cleaned = domain.lowercase().trim()
        if (cleaned.isNotEmpty()) {
            allowlist = allowlist + cleaned
            Log.d(TAG, "Allowlisted domain: $cleaned")
            pushAllowlist()
        }
    }

    @Synchronized
    fun disallowDomain(domain: String) {
        val cleaned = domain.lowercase().trim()
        if (cleaned.isNotEmpty()) {
            allowlist = allowlist - cleaned
            Log.d(TAG, "Removed domain from allowlist: $cleaned")
            pushAllowlist()
        }
    }

    private fun pushAllowlist() = rules.setAllowlist(allowlist.toList())

    // -------------------------------------------------------------------------
    // Per-domain pause
    // -------------------------------------------------------------------------

    /**
     * Pauses blocking for [domain] until [expiresAt] epoch millis.
     * Use [Long.MAX_VALUE] for an indefinite pause.
     *
     * Only the entry for [domain] itself is lifted: it stays blocked if a
     * parent domain is on the blocklist.
     */
    @Synchronized
    fun pauseDomain(domain: String, expiresAt: Long) {
        val cleaned = domain.lowercase().trim()
        if (cleaned.isNotEmpty()) {
            pausedDomains[cleaned] = expiresAt
            Log.d(
                TAG,
                "Paused domain: $cleaned until ${if (expiresAt == Long.MAX_VALUE) "indefinitely" else expiresAt}"
            )
            pushPauses()
        }
    }

    /** Resumes blocking for a previously paused [domain]. */
    @Synchronized
    fun resumeDomain(domain: String) {
        val cleaned = domain.lowercase().trim()
        if (cleaned.isNotEmpty()) {
            pausedDomains.remove(cleaned)
            Log.d(TAG, "Resumed domain: $cleaned")
            pushPauses()
        }
    }

    private fun pushPauses() = rules.setPauses(HashMap(pausedDomains))

    /** Empties every list, here and in Rust, freeing the blocklist's memory. */
    @Synchronized
    fun clear() {
        allowlist = emptySet()
        pausedDomains.clear()
        rules.clear()
        Log.d(TAG, "DnsFilter memory cleared.")
    }
}
