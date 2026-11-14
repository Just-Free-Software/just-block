package fyi.justfreesoftware.justblock.data

import android.util.Log
import fyi.justfreesoftware.justblock.core.DnsFilter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import uniffi.just_block_rust.AddFileOutcome
import uniffi.just_block_rust.BlocklistBuilder

private const val TAG = "BlocklistRepository"

/** Longest valid DNS name, in characters. */
private const val MAX_DOMAIN_LENGTH = 253
private const val HTTP_NOT_FOUND = 404
private const val HTTP_GONE = 410
private const val READ_CHUNK_BYTES = 8192
private const val NEWLINE = '\n'.code.toByte()

/** Domains handed to the Rust blocklist builder per call. */
private const val BATCH_DOMAINS = 10_000

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
    OVER_TOTAL_LIMIT
}

sealed interface FetchResult {
    /** The source parsed; [count] domains were handed to the sink. */
    data class Loaded(val count: Int) : FetchResult
    data class Failed(val problem: SourceProblem) : FetchResult
}

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
            line.startsWith("||") -> line.substring(2)
            else -> line
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
        if (domain.isEmpty() || domain.length > MAX_DOMAIN_LENGTH) return false
        if (domain == "localhost" || domain == "127.0.0.1" || domain == "0.0.0.0") return false
        return HOSTNAME_REGEX.matches(domain)
    }

    /** Parses a whole blocklist file, dropping invalid lines and duplicates. */
    fun parseAll(lines: Sequence<String>): Set<String> = lines.mapNotNullTo(HashSet()) { parse(it) }
}

/**
 * Downloads and parses blocklists, with hard size limits so a malicious or
 * broken source can't run the app out of memory: a huge file, a single
 * endless line, or millions of junk domains are all rejected.
 *
 * Nothing is collected in memory: each domain is handed to a sink as it is
 * parsed. A source can therefore fail after its sink has already received
 * domains, so the caller must discard what it got unless the result is
 * [FetchResult.Loaded]. A domain a source repeats is handed over, and
 * counted, each time.
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
        const val MAX_DOMAINS = 3_000_000
    }

    private val parser: FilterParser = FilterParser()
    private val client = okhttp3.OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    suspend fun fetch(url: String, sink: (String) -> Unit): FetchResult =
        withContext(Dispatchers.IO) {
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
                    readResponse(response, sink)
                }
            } catch (e: java.net.UnknownHostException) {
                Log.w("BlocklistFetcher", "Host not found for $url", e)
                FetchResult.Failed(SourceProblem.SITE_NOT_FOUND)
            } catch (e: java.io.IOException) {
                Log.w("BlocklistFetcher", "Download failed for $url", e)
                FetchResult.Failed(SourceProblem.DOWNLOAD_FAILED)
            }
        }

    internal fun readResponse(response: okhttp3.Response, sink: (String) -> Unit): FetchResult {
        if (!response.isSuccessful) {
            return FetchResult.Failed(
                if (response.code == HTTP_NOT_FOUND || response.code == HTTP_GONE) {
                    SourceProblem.PAGE_NOT_FOUND
                } else {
                    SourceProblem.SERVER_ERROR
                }
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
        val count = response.body.byteStream().use { parseCapped(it, sink) }
        return when (count) {
            null -> FetchResult.Failed(SourceProblem.TOO_LARGE)
            0 -> FetchResult.Failed(SourceProblem.NOT_A_BLOCKLIST)
            else -> FetchResult.Loaded(count)
        }
    }

    /**
     * Parses [input] line by line without ever buffering more than
     * [maxLineLength] bytes of a line, handing each domain to [sink], and
     * returns how many there were. Overlong lines are skipped; going over
     * [maxBytes] or [maxDomains] rejects the whole source and returns null.
     */
    internal fun parseCapped(input: java.io.InputStream, sink: (String) -> Unit): Int? {
        var count = 0
        val lines = CappedLines(maxLineLength) { text ->
            parser.parse(text)?.let {
                // Stop handing over once the source is already over the limit.
                if (count++ < maxDomains) sink(it)
            }
        }
        val chunk = ByteArray(READ_CHUNK_BYTES)
        var totalBytes = 0L
        while (count <= maxDomains) {
            val read = input.read(chunk)
            if (read == -1) break
            totalBytes += read
            if (totalBytes > maxBytes) return null
            lines.feed(chunk, read)
        }
        lines.end()
        return count.takeIf { it <= maxDomains }
    }
}

/**
 * Splits a byte stream into lines without ever buffering more than
 * [maxLength] bytes of one. Longer lines are skipped whole.
 */
private class CappedLines(private val maxLength: Int, private val onLine: (String) -> Unit) {
    private val line = ByteArray(maxLength)
    private var length = 0
    private var overlong = false

    fun feed(bytes: ByteArray, count: Int) {
        for (i in 0 until count) {
            val b = bytes[i]
            when {
                b == NEWLINE -> end()
                overlong -> {}
                length == maxLength -> overlong = true
                else -> line[length++] = b
            }
        }
    }

    /** Ends the current line; also call once after the last byte. */
    fun end() {
        if (!overlong && length > 0) onLine(String(line, 0, length, Charsets.UTF_8))
        length = 0
        overlong = false
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

    /** The saved copy of [url], one domain per line, or null if there is none. */
    fun savedFile(url: String): java.io.File? = fileFor(url).takeIf { it.exists() }

    /**
     * Starts a new copy of [url]. It is written to a temp file and only
     * replaces the saved copy on [Staged.commit], so a failed download or a
     * crash mid-write can't leave a truncated copy behind.
     */
    fun stage(url: String): Staged {
        dir.mkdirs()
        val file = fileFor(url)
        return Staged(java.io.File(dir, "${file.name}.tmp"), file)
    }

    /** A copy of a source being written; see [stage]. */
    class Staged internal constructor(
        private val tmp: java.io.File,
        private val target: java.io.File
    ) {
        private var writer: java.io.BufferedWriter? = tmp.bufferedWriter()

        fun add(domain: String) {
            val out = checkNotNull(writer) { "Staged copy is already finished" }
            out.write(domain)
            out.newLine()
        }

        /** Makes this the saved copy of its source. */
        fun commit() {
            finish()
            if (!tmp.renameTo(target)) throw java.io.IOException("Couldn't replace $target")
        }

        /** Throws the copy away, keeping the saved one. Does nothing after [commit]. */
        fun discard() {
            try {
                finish()
            } catch (e: java.io.IOException) {
                Log.w(TAG, "Failed to close $tmp", e)
            }
            tmp.delete()
        }

        private fun finish() {
            writer?.also { writer = null }?.close()
        }
    }

    /** Deletes the saved copies of every source not in [urls], and stray temp files. */
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
    private val sourceCache = SourceCache(java.io.File(context.cacheDir, "blocklists"))

    // Single merged cache written by older versions. Only read to arm the
    // first start after updating, then deleted.
    private val legacyCacheFile = java.io.File(context.cacheDir, "blocklist_cache.txt")

    private val _state = MutableStateFlow<BlocklistState>(BlocklistState.Idle)
    val state: StateFlow<BlocklistState> = _state.asStateFlow()

    private val _sourceIssues = MutableStateFlow<Map<String, SourceIssue>>(emptyMap())

    /** Sources that failed in the last refresh, keyed by URL. */
    val sourceIssues: StateFlow<Map<String, SourceIssue>> = _sourceIssues.asStateFlow()

    /** Whether [count] more domains still fit under [BlocklistFetcher.MAX_DOMAINS]. */
    private fun BlocklistBuilder.hasRoomFor(count: Int) =
        size + count <= BlocklistFetcher.MAX_DOMAINS

    /**
     * Has Rust read [file] (one domain per line) straight into the builder,
     * unless that would take it past [BlocklistFetcher.MAX_DOMAINS]. The
     * domains never pass through Kotlin.
     */
    private fun BlocklistBuilder.addCacheFile(file: java.io.File): AddFileOutcome =
        addFile(file.path, BlocklistFetcher.MAX_DOMAINS.toULong())

    /** Loads the saved copy of [url] into [builder]; null if there is none. */
    private fun addSaved(builder: BlocklistBuilder, url: String): AddFileOutcome? {
        val outcome = sourceCache.savedFile(url)?.let { builder.addCacheFile(it) }
        if (outcome ==
            AddFileOutcome.UNREADABLE
        ) {
            Log.e(TAG, "Failed to read cached blocklist: $url")
        }
        return outcome
    }

    // Serializes loads so an older one (e.g. the service's startup load) can't
    // finish last and overwrite a newer one that saw the latest source list.
    private val loadMutex = Mutex()

    /**
     * Populates DnsFilter with blocklists, user rules, and whitelist rules
     */
    suspend fun loadAndCompileBlocklists() = loadMutex.withLock { loadAndCompile() }

    private suspend fun loadAndCompile() = withContext(Dispatchers.IO) {
        _state.value = BlocklistState.Loading

        try {
            val manualBlocks = userPreferences.getManualBlockedDomains().map { it.lowercase() }
            val whitelist = userPreferences.getWhitelistedDomains().map { it.lowercase() }.toSet()
            val customUrls = userPreferences.getCustomSourceUrls()

            dnsFilter.updateAllowlist(whitelist)
            armFromCache(customUrls, manualBlocks)

            // 2. Network Load: every source is downloaded straight to a temp
            // file next to its saved copy; nothing is held in memory.
            val downloads = download(customUrls)
            try {
                withContext(kotlinx.coroutines.NonCancellable) {
                    // 3. Final Merge: Apply the updated lists
                    BlocklistBuilder().use { fresh ->
                        _sourceIssues.value = merge(downloads, fresh)
                        try {
                            sourceCache.retainOnly(customUrls)
                            legacyCacheFile.delete()
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to clean up the blocklist cache", e)
                        }

                        val remoteDomains = fresh.size
                        fresh.addAll(manualBlocks)
                        dnsFilter.updateBlocklist(fresh)

                        _state.value = BlocklistState.Success(remoteDomains)
                    }
                }
            } finally {
                downloads.forEach { it.staged?.discard() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load blocklists", e)
            _state.value = BlocklistState.Error(e.message ?: "Unknown error")
        }
    }

    /** 1. Instant Arming: load cached remote lists and manual rules immediately. */
    private fun armFromCache(customUrls: Collection<String>, manualBlocks: List<String>) {
        BlocklistBuilder().use { armed ->
            val started = android.os.SystemClock.elapsedRealtime()
            customUrls.forEach { addSaved(armed, it) }
            if (legacyCacheFile.exists() &&
                armed.addCacheFile(legacyCacheFile) == AddFileOutcome.UNREADABLE
            ) {
                Log.e(TAG, "Failed to read blocklist cache")
            }
            armed.addAll(manualBlocks)
            val took = android.os.SystemClock.elapsedRealtime() - started
            Log.i(TAG, "Instantly armed ${armed.size} domains from cache/manual rules in $took ms")
            dnsFilter.updateBlocklist(armed)
        }
    }

    /** One source's download: what happened, and the new copy if it loaded. */
    private class Download(
        val url: String,
        val result: FetchResult,
        val staged: SourceCache.Staged?
    )

    /**
     * Fetches every source in parallel, each into a staged copy in the
     * cache. Only a source that loaded keeps its staged copy; the caller
     * commits or discards it.
     */
    private suspend fun download(customUrls: Collection<String>): List<Download> =
        kotlinx.coroutines.supervisorScope {
            customUrls.map { url -> async { downloadOne(url) } }.awaitAll()
        }

    private suspend fun downloadOne(url: String): Download {
        var staged: SourceCache.Staged? = null
        val result = try {
            staged = sourceCache.stage(url)
            fetcher.fetch(url, staged::add)
        } catch (e: kotlinx.coroutines.CancellationException) {
            staged?.discard()
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch source: $url", e)
            FetchResult.Failed(SourceProblem.DOWNLOAD_FAILED)
        }
        if (result !is FetchResult.Loaded) {
            staged?.discard()
            staged = null
        }
        return Download(url, result, staged)
    }

    /**
     * Saves the sources that loaded and streams every usable source into
     * [fresh] from its saved copy. A source that failed falls back to its
     * last good copy. Returns the sources that had a problem.
     */
    private fun merge(
        downloads: List<Download>,
        fresh: BlocklistBuilder
    ): Map<String, SourceIssue> {
        val issues = HashMap<String, SourceIssue>()
        for (download in downloads) {
            mergeOne(download, fresh)?.let { issues[download.url] = it }
        }
        return issues
    }

    /** Merges one source into [fresh]; returns its problem, if it had one. */
    private fun mergeOne(download: Download, fresh: BlocklistBuilder): SourceIssue? {
        val url = download.url
        val failure = (download.result as? FetchResult.Failed)?.problem
        val overLimit = SourceIssue(SourceProblem.OVER_TOTAL_LIMIT, usingSavedCopy = false)

        // Each source is capped, but many sources together could still
        // exhaust memory, so cap the combined set too. A fresh download is
        // checked before it replaces the saved copy.
        val loaded = download.result as? FetchResult.Loaded
        if (loaded != null) {
            if (!fresh.hasRoomFor(loaded.count)) return overLimit
            try {
                download.staged?.commit()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save blocklist: $url", e)
            }
        }
        // Fresh or not, the list is loaded from its saved copy. A source
        // that failed falls back to its last good one.
        return when (addSaved(fresh, url)) {
            AddFileOutcome.ADDED -> failure?.let { SourceIssue(it, usingSavedCopy = true) }
            AddFileOutcome.OVER_LIMIT -> overLimit
            AddFileOutcome.UNREADABLE, null ->
                SourceIssue(failure ?: SourceProblem.DOWNLOAD_FAILED, usingSavedCopy = false)
        }
    }
}

/** Distinct domains collected so far. */
private val BlocklistBuilder.size: Int get() = count().toInt()

/** Streams [domains] into the builder in batches. */
private fun BlocklistBuilder.addAll(domains: Collection<String>) {
    domains.asSequence().chunked(BATCH_DOMAINS).forEach(::add)
}
