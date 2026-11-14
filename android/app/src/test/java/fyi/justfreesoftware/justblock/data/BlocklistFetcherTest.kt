package fyi.justfreesoftware.justblock.data

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BlocklistFetcherTest {

    /** The domains handed to the sink, or null if the source was rejected. */
    private fun parse(text: String, fetcher: BlocklistFetcher = BlocklistFetcher()): List<String>? {
        val domains = ArrayList<String>()
        val count = fetcher.parseCapped(text.byteInputStream()) { domains.add(it) } ?: return null
        assertEquals(count, domains.size)
        return domains
    }

    @Test
    fun parsesNormalList() {
        assertEquals(
            listOf("a.com", "b.com", "c.com"),
            parse("a.com\r\n0.0.0.0 b.com\n||c.com^")
        )
    }

    @Test
    fun skipsOverlongLineButKeepsTheRest() {
        val fetcher = BlocklistFetcher(maxLineLength = 16)
        val text = "a.com\n" + "x".repeat(10_000) + ".com\nb.com\n"
        assertEquals(listOf("a.com", "b.com"), parse(text, fetcher))
    }

    @Test
    fun lineAtExactLimitIsKept() {
        val fetcher = BlocklistFetcher(maxLineLength = 5)
        assertEquals(listOf("a.com"), parse("a.com", fetcher))
    }

    @Test
    fun endlessLineWithoutNewlineDoesNotBuffer() {
        val fetcher = BlocklistFetcher(maxLineLength = 16)
        assertEquals(emptyList<String>(), parse("x".repeat(100_000), fetcher))
    }

    @Test
    fun abortsWhenOverByteLimit() {
        val fetcher = BlocklistFetcher(maxBytes = 100)
        assertNull(parse("a.com\n".repeat(50), fetcher))
    }

    @Test
    fun abortsWhenOverDomainLimit() {
        val fetcher = BlocklistFetcher(maxDomains = 3)
        assertNull(parse((1..4).joinToString("\n") { "d$it.com" }, fetcher))
    }

    @Test
    fun listAtExactDomainLimitIsKept() {
        val fetcher = BlocklistFetcher(maxDomains = 3)
        assertEquals(listOf("d1.com", "d2.com", "d3.com"), parse("d1.com\nd2.com\nd3.com", fetcher))
    }

    @Test
    fun repeatedDomainsAreHandedOverAndCountedEachTime() {
        assertEquals(listOf("a.com", "a.com", "a.com"), parse("a.com\n".repeat(3)))
        assertNull(parse("a.com\n".repeat(10), BlocklistFetcher(maxDomains = 3)))
    }

    @Test
    fun sinkGetsNoMoreThanTheDomainLimit() {
        val handed = ArrayList<String>()
        val fetcher = BlocklistFetcher(maxDomains = 3)
        assertNull(fetcher.parseCapped("a.com\n".repeat(10).byteInputStream()) { handed.add(it) })
        assertEquals(3, handed.size)
    }
}

class BlocklistFetcherResponseTest {

    private val fetcher = BlocklistFetcher()

    private fun response(code: Int, body: String, contentType: String = "text/plain") =
        okhttp3.Response.Builder()
            .request(okhttp3.Request.Builder().url("https://example.org/list.txt").build())
            .protocol(okhttp3.Protocol.HTTP_1_1)
            .code(code)
            .message("")
            .body(body.toResponseBody(contentType.toMediaType()))
            .build()

    private fun problemOf(result: FetchResult) = (result as FetchResult.Failed).problem

    @Test
    fun loadsPlainTextList() {
        val domains = ArrayList<String>()
        assertEquals(
            FetchResult.Loaded(2),
            fetcher.readResponse(response(200, "a.com\nb.com\n")) { domains.add(it) }
        )
        assertEquals(listOf("a.com", "b.com"), domains)
    }

    @Test
    fun missingPageIsPageNotFound() {
        assertEquals(
            SourceProblem.PAGE_NOT_FOUND,
            problemOf(fetcher.readResponse(response(404, "")) {})
        )
        assertEquals(
            SourceProblem.PAGE_NOT_FOUND,
            problemOf(fetcher.readResponse(response(410, "")) {})
        )
    }

    @Test
    fun otherHttpErrorIsServerError() {
        assertEquals(
            SourceProblem.SERVER_ERROR,
            problemOf(
                fetcher.readResponse(response(503, "")) {
                }
            )
        )
    }

    @Test
    fun htmlPageIsNotABlocklist() {
        val page = "<html>\na.com\n</html>"
        assertEquals(
            SourceProblem.NOT_A_BLOCKLIST,
            problemOf(fetcher.readResponse(response(200, page, "text/html; charset=utf-8")) {})
        )
    }

    @Test
    fun textWithNoDomainsIsNotABlocklist() {
        assertEquals(
            SourceProblem.NOT_A_BLOCKLIST,
            problemOf(fetcher.readResponse(response(200, "# just a comment\nhello world\n")) {})
        )
    }

    @Test
    fun oversizedListIsTooLarge() {
        val small = BlocklistFetcher(maxBytes = 10)
        assertEquals(
            SourceProblem.TOO_LARGE,
            problemOf(small.readResponse(response(200, "a.com\n".repeat(10))) {})
        )
    }

    @Test
    fun malformedLinkIsInvalid() = kotlinx.coroutines.runBlocking {
        assertEquals(SourceProblem.INVALID_LINK, problemOf(fetcher.fetch("https://") {}))
    }
}
