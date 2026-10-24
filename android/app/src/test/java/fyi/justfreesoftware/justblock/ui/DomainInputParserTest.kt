package fyi.justfreesoftware.justblock.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DomainInputParserTest {

    @Test
    fun fullHttpsUrlWithPath() {
        assertEquals("example.com", DomainInputParser.extractDomain("https://example.com/article/123"))
    }

    @Test
    fun bareDomain() {
        assertEquals("example.com", DomainInputParser.extractDomain("example.com"))
    }

    @Test
    fun bareDomainWithPath() {
        assertEquals("example.com", DomainInputParser.extractDomain("example.com/some/page"))
    }

    @Test
    fun wwwPrefixStrippedFromUrl() {
        assertEquals("example.com", DomainInputParser.extractDomain("https://www.example.com/article"))
    }

    @Test
    fun wwwPrefixStrippedFromBareDomain() {
        assertEquals("example.com", DomainInputParser.extractDomain("www.example.com"))
    }

    @Test
    fun mixedCaseHostLowercased() {
        assertEquals("example.com", DomainInputParser.extractDomain("https://WWW.Example.COM/"))
    }

    @Test
    fun surroundingWhitespaceTrimmed() {
        assertEquals("example.com", DomainInputParser.extractDomain("  https://example.com  "))
    }

    @Test
    fun httpWithPortAndPath() {
        assertEquals("example.com", DomainInputParser.extractDomain("http://www.example.com:8080/x"))
    }

    @Test
    fun blankInputReturnsNull() {
        assertNull(DomainInputParser.extractDomain("   "))
    }

    @Test
    fun emptyInputReturnsNull() {
        assertNull(DomainInputParser.extractDomain(""))
    }

    @Test
    fun unparseableFreeTextReturnsNull() {
        assertNull(DomainInputParser.extractDomain("not a url"))
    }

    @Test
    fun schemeOnlyReturnsNull() {
        assertNull(DomainInputParser.extractDomain("http://"))
    }

    @Test
    fun literalWwwComKeptIntact() {
        assertEquals("www.com", DomainInputParser.extractDomain("www.com"))
    }

    @Test
    fun subdomainPreserved() {
        assertEquals("docs.example.com", DomainInputParser.extractDomain("https://docs.example.com/guide"))
    }

    @Test
    fun queryAndFragmentIgnored() {
        assertEquals("example.com", DomainInputParser.extractDomain("https://example.com/?q=1#top"))
    }

    @Test
    fun userInfoIgnored() {
        assertEquals("example.com", DomainInputParser.extractDomain("https://user:pass@example.com/"))
    }

    @Test
    fun internationalizedDomainConvertedToPunycode() {
        assertEquals("xn--bcher-kva.de", DomainInputParser.extractDomain("https://bücher.de/"))
    }

    @Test
    fun nonHttpSchemeReturnsNull() {
        assertNull(DomainInputParser.extractDomain("ftp://example.com/file"))
    }
}
