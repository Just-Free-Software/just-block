package fyi.justfreesoftware.justblock.core

/**
 * Configuration for the primary upstream DNS resolver, reached over DoQ
 * (port 853, fixed in the Rust proxy).
 *
 * @param host        IP address or hostname of the upstream server.
 * @param sniHostname TLS SNI hostname used for DoQ (e.g. "dns.adguard-dns.com").
 */
data class UpstreamConfig(
    val host        : String = "94.140.14.14",
    val sniHostname : String = "dns.adguard-dns.com",
) {
    companion object {
        private const val SEPARATOR = "\t"

        /**
         * Encodes this config to a single string for DataStore persistence.
         * Format: host and sniHostname, separated by a tab.
         * Example: "94.140.14.14\tdns.adguard-dns.com"
         */
        fun encode(config: UpstreamConfig): String = listOf(
            config.host,
            config.sniHostname,
        ).joinToString(SEPARATOR)

        /**
         * Decodes a string produced by [encode] back into an [UpstreamConfig].
         * Returns null if the string is malformed so callers can fall back to
         * the default.
         *
         * Also accepts the older four-field format
         * (host, doqPort, sniHostname, dohUrl); the port and URL were never
         * used and are ignored.
         */
        fun decode(encoded: String): UpstreamConfig? {
            val parts = encoded.split(SEPARATOR)
            val sniHostname = when (parts.size) {
                2 -> parts[1]
                4 -> parts[2]
                else -> return null
            }
            val host = parts[0].ifEmpty { return null }
            return UpstreamConfig(host = host, sniHostname = sniHostname)
        }
    }
}
