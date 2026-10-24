package fyi.justfreesoftware.justblock.ui

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Extracts the domain to whitelist from raw user input, which may be a full
 * URL (`https://example.com/article`) or a bare domain (`example.com`).
 *
 * Parsing is delegated to OkHttp's [okhttp3.HttpUrl], which validates the
 * host, lowercases it and converts internationalized names to the punycode
 * form that actually appears in DNS queries.
 *
 * A leading `www.` is stripped so the entry covers the whole site (the
 * proxy's allowlist matches subdomains). Returns null when no usable host
 * can be parsed.
 */
object WhitelistUrlParser {

    fun extractDomain(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return null
        val withScheme = if (trimmed.contains("://")) trimmed else "https://$trimmed"
        val host = withScheme.toHttpUrlOrNull()?.host ?: return null
        // Only strip "www." when what remains is still a multi-label domain
        // (keeps a literal "www.com" intact).
        val stripped = host.removePrefix("www.")
        return if (host.startsWith("www.") && stripped.contains(".")) stripped else host
    }
}
