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
}
