package fyi.justfreesoftware.justblock.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FilterParserTest {

    private val parser = FilterParser()

    // A blocklist mixing every format users commonly link to.
    private val sampleList = """
        [Adblock Plus 2.0]
        ! Title: Sample list
        ! Homepage: https://example.org/list
        # hosts-style comment
        127.0.0.1 localhost
        ::1 localhost
        0.0.0.0 0.0.0.0

        0.0.0.0 hosts-zero.com
        127.0.0.1 hosts-loopback.com # trailing comment
        plain-domain.com
        UPPER-Case.COM
        ||adblock-network.com^
        ||adblock-no-caret.com
        *.wildcard-prefix.com
        plain-domain.com
        sub.plain-domain.com

        example-cosmetic.com##.ad-banner
        example-cosmetic-exception.com#@#.ad-banner
        @@||exception.com^
        ||with-options.com^${'$'}third-party
        ||with-path.com/ads^
    """.trimIndent()

    @Test
    fun parsesMixedFormatBlocklist() {
        assertEquals(
            setOf(
                "hosts-zero.com",
                "hosts-loopback.com",
                "plain-domain.com",
                "sub.plain-domain.com",
                "upper-case.com",
                "adblock-network.com",
                "adblock-no-caret.com",
                "wildcard-prefix.com",
            ),
            parser.parseAll(sampleList.lineSequence()),
        )
    }

    @Test
    fun wildcardPrefixIsStrippedToBareDomain() {
        // Blocking is wildcard-style (entries cover subdomains), so "*." adds
        // nothing and the apex domain ends up blocked too.
        assertEquals("example.com", parser.parse("*.example.com"))
    }

    @Test
    fun hostsEntryUsesHostColumn() {
        assertEquals("example.com", parser.parse("0.0.0.0 example.com"))
    }

    @Test
    fun hostsEntryOnlyTakesFirstHost() {
        // Known limitation: extra hostnames on the same line are ignored.
        assertEquals("a.com", parser.parse("0.0.0.0 a.com b.com"))
    }

    @Test
    fun cosmeticRuleDoesNotBlockWholeDomain() {
        assertNull(parser.parse("youtube.com##.ytd-ad-slot-renderer"))
        assertNull(parser.parse("youtube.com#@#.ad"))
        assertNull(parser.parse("youtube.com#?#.ad:has(span)"))
    }

    @Test
    fun exceptionRuleIsSkipped() {
        assertNull(parser.parse("@@||example.com^"))
    }

    @Test
    fun rulesWithOptionsOrPathsAreSkipped() {
        assertNull(parser.parse("||example.com^\$third-party"))
        assertNull(parser.parse("||example.com/ads^"))
    }

    @Test
    fun singleLabelNamesAreRejected() {
        assertNull(parser.parse("localhost"))
        assertNull(parser.parse("intranet"))
    }
}
