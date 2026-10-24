package fyi.justfreesoftware.justblock.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WhitelistUrlParserTest {

    @Test
    fun fullHttpsUrlWithPath() {
        assertEquals("example.com", WhitelistUrlParser.extractDomain("https://example.com/article/123"))
    }

    @Test
    fun bareDomain() {
        assertEquals("example.com", WhitelistUrlParser.extractDomain("example.com"))
    }

    @Test
    fun bareDomainWithPath() {
        assertEquals("example.com", WhitelistUrlParser.extractDomain("example.com/some/page"))
    }

    @Test
    fun wwwPrefixStrippedFromUrl() {
        assertEquals("example.com", WhitelistUrlParser.extractDomain("https://www.example.com/article"))
    }

    @Test
    fun wwwPrefixStrippedFromBareDomain() {
        assertEquals("example.com", WhitelistUrlParser.extractDomain("www.example.com"))
    }

    @Test
    fun mixedCaseHostLowercased() {
        assertEquals("example.com", WhitelistUrlParser.extractDomain("https://WWW.Example.COM/"))
    }

    @Test
    fun surroundingWhitespaceTrimmed() {
        assertEquals("example.com", WhitelistUrlParser.extractDomain("  https://example.com  "))
    }

    @Test
    fun httpWithPortAndPath() {
        assertEquals("example.com", WhitelistUrlParser.extractDomain("http://www.example.com:8080/x"))
    }

    @Test
    fun blankInputReturnsNull() {
        assertNull(WhitelistUrlParser.extractDomain("   "))
    }

    @Test
    fun emptyInputReturnsNull() {
        assertNull(WhitelistUrlParser.extractDomain(""))
    }

    @Test
    fun unparseableFreeTextReturnsNull() {
        assertNull(WhitelistUrlParser.extractDomain("not a url"))
    }

    @Test
    fun schemeOnlyReturnsNull() {
        assertNull(WhitelistUrlParser.extractDomain("http://"))
    }

    @Test
    fun literalWwwComKeptIntact() {
        assertEquals("www.com", WhitelistUrlParser.extractDomain("www.com"))
    }

    @Test
    fun subdomainPreserved() {
        assertEquals("docs.example.com", WhitelistUrlParser.extractDomain("https://docs.example.com/guide"))
    }

    @Test
    fun queryAndFragmentIgnored() {
        assertEquals("example.com", WhitelistUrlParser.extractDomain("https://example.com/?q=1#top"))
    }

    @Test
    fun userInfoIgnored() {
        assertEquals("example.com", WhitelistUrlParser.extractDomain("https://user:pass@example.com/"))
    }

    @Test
    fun internationalizedDomainConvertedToPunycode() {
        assertEquals("xn--bcher-kva.de", WhitelistUrlParser.extractDomain("https://bücher.de/"))
    }

    @Test
    fun nonHttpSchemeReturnsNull() {
        assertNull(WhitelistUrlParser.extractDomain("ftp://example.com/file"))
    }
}
