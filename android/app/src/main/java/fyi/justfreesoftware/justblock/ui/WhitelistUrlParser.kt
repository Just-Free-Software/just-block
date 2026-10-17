package fyi.justfreesoftware.justblock.ui

import java.net.URI
import java.net.URISyntaxException

/**
 * Extracts the domain to whitelist from raw user input, which may be a full
 * URL (`https://example.com/article`) or a bare domain (`example.com`).
 *
 * A leading `www.` is stripped so the entry matches blocklist entries keyed
 * by the bare domain. Returns null when no usable host can be parsed.
 */
object WhitelistUrlParser {

    fun extractDomain(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return null
        val withScheme = if (trimmed.contains("://")) trimmed else "https://$trimmed"
        val host = try {
            URI(withScheme).host
        } catch (_: URISyntaxException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } ?: return null
        val lowered = host.lowercase()
        // Only strip "www." when what remains is still a multi-label domain
        // (keeps a literal "www.com" intact).
        val stripped = lowered.removePrefix("www.")
        val cleaned = if (lowered.startsWith("www.") && stripped.contains(".")) stripped else lowered
        return cleaned.ifBlank { null }
    }
}
