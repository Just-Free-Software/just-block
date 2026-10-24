package fyi.justfreesoftware.justblock.data

import android.util.Log
import fyi.justfreesoftware.justblock.core.DnsFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Locale

sealed interface BlocklistState {
    object Idle : BlocklistState
    object Loading : BlocklistState
    data class Success(val totalDomains: Int) : BlocklistState
    data class Error(val message: String) : BlocklistState
}

/** Why a blocklist source failed in the last refresh. */
enum class SourceProblem {
    /** Not a well-formed https:// URL. */
    INVALID_LINK,
    /** The host name doesn't resolve: no such site, or the device is offline. */
    SITE_NOT_FOUND,
    /** The server answered 404 or 410. */
    PAGE_NOT_FOUND,
    /** The server answered with any other non-2xx status. */
    SERVER_ERROR,
    /** The download failed for another reason, such as a timeout. */
    DOWNLOAD_FAILED,
    /** The link is a web page, or nothing in it parsed as a domain. */
    NOT_A_BLOCKLIST,
    /** The source itself is over one of [BlocklistFetcher]'s size limits. */
    TOO_LARGE,
    /** Adding the source would push all sources past [BlocklistFetcher.MAX_DOMAINS]. */
    OVER_TOTAL_LIMIT,
}

sealed interface FetchResult {
    data class Loaded(val domains: Set<String>) : FetchResult
    data class Failed(val problem: SourceProblem) : FetchResult
}

data class FilterSource(
    val url: String,
    val enabled: Boolean = true
)



/**
 * Turns blocklist lines into bare domains.
 *
 * NOTE: every entry is treated as a wildcard. The Rust proxy matches
 * entries against a query and all of its parent domains, so any entry
 * `example.com` blocks `example.com` *and* `*.example.com`, whatever
 * format it came from:
 *  - plain domain lists (`example.com`)
 *  - hosts files (`0.0.0.0 example.com`). In a real hosts file this would
 *    block only the exact name; here it covers subdomains too.
 *  - adblock network rules (`||example.com^`). These already mean
 *    "domain and subdomains", so nothing changes.
 *  - `*.example.com`. The `*.` is stripped, so the apex `example.com` is
 *    blocked as well.
 * Adblock rules that can't be expressed as a domain block (cosmetic `##`
 * rules, `@@` exceptions, rules with paths or `$` options) are skipped.
 */
class FilterParser {
    companion object {
        private val WHITESPACE_REGEX = Regex("\\s+")
        private val HOSTNAME_REGEX = Regex("^[a-z0-9_-]+(\\.[a-z0-9_-]+)+$")
        // Adblock element-hiding / scriptlet markers. These rules hide parts
        // of a page and must not become a block of the whole domain.
        private val COSMETIC_MARKERS = listOf("##", "#@#", "#?#", "#$#", "#%#")
    }

    /**
     * Normalizes blocking rules: Adblock syntax (`||badsite.com^`) etc.
     * Returns null for comment lines, empty lines, and invalid entries.
     */
    fun parse(rawLine: String): String? {
        var line = rawLine.trim()

        // Adblock headers, comments, exceptions and cosmetic rules
        if (line.startsWith("!") || line.startsWith("[") || line.startsWith("@@")) return null
        if (COSMETIC_MARKERS.any { line.contains(it) }) return null

        // Strip inline comments
        if (line.contains("#")) line = line.substringBefore("#").trim()
        if (line.contains(";")) line = line.substringBefore(";").trim()

        if (line.isEmpty()) return null

        // Support popular AdBlock/uBlock style simple wildcard domains: ||example.com^
        line = when {
            line.startsWith("||") && line.endsWith("^") -> line.substring(2, line.length - 1)
            line.startsWith("||")                              -> line.substring(2)
            else                                                      -> line
        }

        // Clean up leading wildcard formatting indicators
        if (line.startsWith("*.")) line = line.substring(2)

        // Split tokens to break up standard hosts records (IP <space> Host)
        val segments = line.split(WHITESPACE_REGEX)
        val candidate = if (segments.size >= 2) segments[1] else segments[0]

        val cleaned = candidate.lowercase(Locale.ROOT).trim()
        return if (isValid(cleaned)) cleaned else null
    }

    private fun isValid(domain: String): Boolean {
        if (domain.isEmpty() || domain.length > 253) return false
        if (domain == "localhost" || domain == "127.0.0.1" || domain == "0.0.0.0") return false
        return HOSTNAME_REGEX.matches(domain)
    }

    /** Parses a whole blocklist file, dropping invalid lines and duplicates. */
    fun parseAll(lines: Sequence<String>): Set<String> =
        lines.mapNotNullTo(HashSet()) { parse(it) }
}

/**
 * Downloads and parses blocklists, with hard size limits so a malicious or
 * broken source can't run the app out of memory: a huge file, a single
 * endless line, or millions of junk domains are all rejected.
 */
class BlocklistFetcher(
    private val maxBytes: Long = MAX_BYTES,
    private val maxLineLength: Int = MAX_LINE_LENGTH,
    private val maxDomains: Int = MAX_DOMAINS
) {
    companion object {
        const val MAX_BYTES = 64L * 1024 * 1024
        // No valid domain line comes close; longer lines are skipped.
        const val MAX_LINE_LENGTH = 1024
        // Also the cap on all sources combined, see BlocklistRepository.
        const val MAX_DOMAINS = 2_000_000
    }

    private val parser: FilterParser = FilterParser()
    private val client = okhttp3.OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    suspend fun fetch(url: String): FetchResult = withContext(Dispatchers.IO) {
        val lowerUrl = url.lowercase(Locale.ROOT)
        // HTTPS only: blocklists are downloaded in the clear with http://, which
        // leaks browsing-adjacent metadata and lets a MITM swap the blocklist.
        if (!lowerUrl.startsWith("https://")) {
            Log.e("BlocklistFetcher", "Invalid URL scheme (HTTPS required): $url")
            return@withContext FetchResult.Failed(SourceProblem.INVALID_LINK)
        }
        val httpUrl = url.toHttpUrlOrNull()
            ?: return@withContext FetchResult.Failed(SourceProblem.INVALID_LINK)

        val request = okhttp3.Request.Builder().url(httpUrl).build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e("BlocklistFetcher", "HTTP error ${response.code} for $url")
                }
                readResponse(response)
            }
        } catch (e: java.net.UnknownHostException) {
            FetchResult.Failed(SourceProblem.SITE_NOT_FOUND)
        } catch (e: java.io.IOException) {
            FetchResult.Failed(SourceProblem.DOWNLOAD_FAILED)
        }
    }

    internal fun readResponse(response: okhttp3.Response): FetchResult {
        if (!response.isSuccessful) {
            return FetchResult.Failed(
                if (response.code == 404 || response.code == 410) SourceProblem.PAGE_NOT_FOUND
                else SourceProblem.SERVER_ERROR
            )
        }
        // Blocklists are plain text. An HTML page is usually a link to a
        // site, or to a GitHub page instead of the raw file.
        if (response.body.contentType()?.subtype == "html") {
            return FetchResult.Failed(SourceProblem.NOT_A_BLOCKLIST)
        }
        // Fail fast on an honest Content-Length; parseCapped still counts
        // the actual (decompressed) bytes for servers that lie or omit it.
        if (response.body.contentLength() > maxBytes) {
            return FetchResult.Failed(SourceProblem.TOO_LARGE)
        }
        val domains = response.body.byteStream().use { parseCapped(it) }
        return when {
            domains == null -> FetchResult.Failed(SourceProblem.TOO_LARGE)
            domains.isEmpty() -> FetchResult.Failed(SourceProblem.NOT_A_BLOCKLIST)
            else -> FetchResult.Loaded(domains)
        }
    }

    /**
     * Parses [input] line by line without ever buffering more than
     * [maxLineLength] bytes of a line. Overlong lines are skipped; going over
     * [maxBytes] or [maxDomains] rejects the whole source and returns null.
     */
    internal fun parseCapped(input: java.io.InputStream): Set<String>? {
        val result = HashSet<String>()
        val chunk = ByteArray(8192)
        val line = ByteArray(maxLineLength)
        var lineLength = 0
        var overlong = false
        var totalBytes = 0L

        fun endLine() {
            if (!overlong && lineLength > 0) {
                parser.parse(String(line, 0, lineLength, Charsets.UTF_8))?.let { result.add(it) }
            }
            lineLength = 0
            overlong = false
        }

        while (true) {
            val read = input.read(chunk)
            if (read == -1) break
            totalBytes += read
            if (totalBytes > maxBytes) return null
            for (i in 0 until read) {
                val b = chunk[i]
                when {
                    b == '\n'.code.toByte() -> {
                        endLine()
                        if (result.size > maxDomains) return null
                    }
                    overlong -> {}
                    lineLength == maxLineLength -> overlong = true
                    else -> line[lineLength++] = b
                }
            }
        }
        endLine()
        return if (result.size > maxDomains) null else result
    }
}

/**
 * The last good copy of each blocklist source, one file per URL, so a source
 * that fails to refresh keeps blocking with what it had.
 */
class SourceCache(private val dir: java.io.File) {
    private fun fileFor(url: String): java.io.File {
        val hash = java.security.MessageDigest.getInstance("SHA-256")
            .digest(url.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return java.io.File(dir, "$hash.txt")
    }

    /** The saved copy of [url], or null if there is none. */
    fun read(url: String): Set<String>? {
        val file = fileFor(url)
        if (!file.exists()) return null
        return file.useLines { it.toHashSet() }
    }

    /**
     * Saves [domains] as the copy of [url]. Goes through a temp file so a
     * crash mid-write can't leave a truncated copy behind.
     */
    fun write(url: String, domains: Set<String>) {
        dir.mkdirs()
        val file = fileFor(url)
        val tmp = java.io.File(dir, "${file.name}.tmp")
        tmp.bufferedWriter().use { writer ->
            domains.forEach {
                writer.write(it)
                writer.newLine()
            }
        }
        if (!tmp.renameTo(file)) throw java.io.IOException("Couldn't replace $file")
    }

    /** Deletes the saved copies of every source not in [urls]. */
    fun retainOnly(urls: Collection<String>) {
        val keep = urls.mapTo(HashSet()) { fileFor(it).name }
        dir.listFiles()?.forEach { if (it.name !in keep) it.delete() }
    }
}

/**
 * A source that failed in the last refresh. [usingSavedCopy] is true when its
 * last good download is still being used for blocking.
 */
data class SourceIssue(val problem: SourceProblem, val usingSavedCopy: Boolean)

class BlocklistRepository(
    private val context: android.content.Context,
    private val dnsFilter: DnsFilter,
    private val userPreferences: UserPreferences,
    private val fetcher: BlocklistFetcher
) {
    private val TAG = "BlocklistRepository"

    private val sourceCache = SourceCache(java.io.File(context.cacheDir, "blocklists"))
    // Single merged cache written by older versions. Only read to arm the
    // first start after updating, then deleted.
    private val legacyCacheFile = java.io.File(context.cacheDir, "blocklist_cache.txt")

    private val _state = MutableStateFlow<BlocklistState>(BlocklistState.Idle)
    val state: StateFlow<BlocklistState> = _state.asStateFlow()

    private val _sourceIssues = MutableStateFlow<Map<String, SourceIssue>>(emptyMap())
    /** Sources that failed in the last refresh, keyed by URL. */
    val sourceIssues: StateFlow<Map<String, SourceIssue>> = _sourceIssues.asStateFlow()

    private fun readSaved(url: String): Set<String>? = try {
        sourceCache.read(url)
    } catch (e: Exception) {
        Log.e(TAG, "Failed to read cached blocklist: $url", e)
        null
    }

    /**
     * Populates DnsFilter with blocklists, user rules, and whitelist rules
     */
    suspend fun loadAndCompileBlocklists() = withContext(Dispatchers.IO) {

        _state.value = BlocklistState.Loading

        try {
            val manualBlocks = userPreferences.getManualBlockedDomains().map { it.lowercase() }
            val whitelist = userPreferences.getWhitelistedDomains().map { it.lowercase() }.toSet()
            val customUrls = userPreferences.getCustomSourceUrls()

            // 1. Instant Arming: Load cached remote lists and manual rules immediately
            val initialSet = HashSet<String>(250_000)
            for (url in customUrls) {
                val saved = readSaved(url) ?: continue
                if (initialSet.size + saved.size <= BlocklistFetcher.MAX_DOMAINS) initialSet.addAll(saved)
            }
            if (legacyCacheFile.exists()) {
                try {
                    legacyCacheFile.useLines { lines ->
                        initialSet.addAll(lines)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to read blocklist cache", e)
                }
            }
            initialSet.addAll(manualBlocks)
            dnsFilter.updateAllowlist(whitelist)
            dnsFilter.updateBlocklist(initialSet)
            Log.i(TAG, "Instantly armed ${initialSet.size} domains from cache/manual rules")

            // 2. Network Load: Fetch latest remote blocklists in the background
            val remoteCompiled = HashSet<String>(250_000)
            val allSources = customUrls.map { FilterSource(it) }
            val issues = HashMap<String, SourceIssue>()
            val toSave = ArrayList<Pair<String, Set<String>>>()

            kotlinx.coroutines.supervisorScope {
                allSources
                    .filter { it.enabled }
                    .map { source -> 
                        async { 
                            source.url to try {
                                fetcher.fetch(source.url)
                            } catch (e: Exception) {
                                if (e is kotlinx.coroutines.CancellationException) throw e
                                Log.e(TAG, "Failed to fetch source: ${source.url}", e)
                                FetchResult.Failed(SourceProblem.DOWNLOAD_FAILED)
                            }
                        } 
                    }
                    .awaitAll()
                    .forEach { (url, result) ->
                        // A failed source falls back to its last good copy.
                        val domains = when (result) {
                            is FetchResult.Loaded -> result.domains
                            is FetchResult.Failed -> readSaved(url).also {
                                issues[url] = SourceIssue(result.problem, usingSavedCopy = it != null)
                            }
                        } ?: return@forEach

                        // Each source is capped, but many sources together could
                        // still exhaust memory, so cap the combined set too.
                        if (remoteCompiled.size + domains.size > BlocklistFetcher.MAX_DOMAINS) {
                            issues[url] = SourceIssue(SourceProblem.OVER_TOTAL_LIMIT, usingSavedCopy = false)
                        } else {
                            remoteCompiled.addAll(domains)
                            if (result is FetchResult.Loaded) toSave.add(url to domains)
                        }
                    }
            }
            _sourceIssues.value = issues

            withContext(kotlinx.coroutines.NonCancellable) {
                // Save fresh downloads only, so a failed source keeps its old copy
                try {
                    toSave.forEach { (url, domains) -> sourceCache.write(url, domains) }
                    sourceCache.retainOnly(customUrls)
                    legacyCacheFile.delete()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to write blocklist cache", e)
                }

                // 3. Final Merge: Apply the updated lists
                val finalCompiled = HashSet<String>(remoteCompiled.size + manualBlocks.size)
                finalCompiled.addAll(remoteCompiled)
                finalCompiled.addAll(manualBlocks)

                dnsFilter.updateBlocklist(finalCompiled)

                _state.value = BlocklistState.Success(remoteCompiled.size)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load blocklists", e)
            _state.value = BlocklistState.Error(e.message ?: "Unknown error")
        }
    }
}
