package fyi.justfreesoftware.justblock.core

/**
 * Configuration for the upstream DNS resolver.
 * 
 * @param host        IP address of the upstream server.
 * @param doqPort     QUIC port for DoQ (default 853).
 * @param sniHostname TLS SNI hostname used for DoQ (e.g. "dns.adguard-dns.com").
 * @param dohUrl      Full HTTPS URL for DoH (e.g. "https://dns.adguard-dns.com/dns-query").
 */
data class UpstreamConfig(
    val host        : String = "94.140.14.14",
    val doqPort     : Int    = 853,
    val sniHostname : String = "dns.adguard-dns.com",
    val dohUrl      : String = "https://dns.adguard-dns.com/dns-query",
) {
    companion object {
        private const val SEPARATOR = "\t"

        /**
         * Encodes this config to a single string for DataStore persistence.
         * Format: "host|doqPort|sniHostname|dohUrl"
         * Example: "94.140.14.14|853|dns.adguard-dns.com|https://dns.adguard-dns.com/dns-query"
         */
        fun encode(config: UpstreamConfig): String = listOf(
            config.host,
            config.doqPort.toString(),
            config.sniHostname,
            config.dohUrl,
        ).joinToString(SEPARATOR)

        /**
         * Decodes a string produced by [encode] back into an [UpstreamConfig].
         * Returns null if the string is malformed so callers can fall back to
         * the default.
         */
        fun decode(encoded: String): UpstreamConfig? {
            val parts = encoded.split(SEPARATOR)
            if (parts.size != 4) return null
            val port = parts[1].toIntOrNull() ?: return null
            val host = parts[0].ifEmpty { return null }

            return UpstreamConfig(
                host        = host,
                doqPort     = port,
                sniHostname = parts[2],
                dohUrl      = parts[3],
            )
        }
    }
}
