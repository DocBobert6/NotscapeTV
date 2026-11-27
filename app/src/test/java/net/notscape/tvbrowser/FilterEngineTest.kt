package net.notscape.tvbrowser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * JVM unit tests for the FilterEngine matching rules. The engine is
 * pure JVM (java.net.URI), so these run without Robolectric.
 */
class FilterEngineTest {

    private fun load(vararg rules: String) {
        val data = FilterEngine.FilterData()
        FilterEngine.parseInto(0, rules.joinToString("\n"), data)
        FilterEngine.applyData(data, System.currentTimeMillis())
    }

    @Before
    fun setUp() {
        // Start each test from an empty rule set.
        val data = FilterEngine.FilterData()
        FilterEngine.applyData(data, 0L)
    }

    @Test
    fun hostRuleBlocksSubresourceNotMainFrame() {
        load("||ads.example.com^")
        assertTrue(FilterEngine.isBlocked("https://ads.example.com/banner.js", false, true, true, "news.example.org"))
        assertFalse(FilterEngine.isBlocked("https://ads.example.com/banner.js", true, true, true, "news.example.org"))
    }

    @Test
    fun exceptionUnblocks() {
        load("||ads.example.com^", "@@||ads.example.com^")
        assertFalse(FilterEngine.isBlocked("https://ads.example.com/banner.js", false, true, true, "news.example.org"))
    }

    @Test
    fun builtinTrackerHostBlocks() {
        val data = FilterEngine.FilterData()
        FilterEngine.applyData(data, System.currentTimeMillis())
        assertTrue(FilterEngine.isBlocked("https://doubleclick.net/ads", false, false, true, "example.org"))
    }

    @Test
    fun scopedDomainRuleOnlyAppliesOnNamedSite() {
        load("||cdn.example.com^\$domain=news.example.org")
        assertTrue(FilterEngine.isBlocked("https://cdn.example.com/x.js", false, true, true, "news.example.org"))
        assertFalse(FilterEngine.isBlocked("https://cdn.example.com/x.js", false, true, true, "other.example.org"))
    }

    @Test
    fun documentRuleFeedsPopunderGuard() {
        load("||popunder.example.com^\$document,popup")
        assertTrue(FilterEngine.isDocumentBlocked("https://popunder.example.com/landing", "news.example.org"))
        assertFalse(FilterEngine.isDocumentBlocked("https://innocent.example.com/landing", "news.example.org"))
    }

    @Test
    fun cosmeticSelectorsBuildCss() {
        val data = FilterEngine.FilterData()
        FilterEngine.parseInto(0, "##.ad-banner\nnews.example.org##.sponsored", data)
        FilterEngine.applyData(data, System.currentTimeMillis())
        val css = FilterEngine.cosmeticCss("news.example.org")
        assertTrue(css.contains(".ad-banner"))
        assertTrue(css.contains(".sponsored"))
        assertTrue(css.contains("display:none!important"))
    }

    @Test
    fun hostsFileParses() {
        val data = FilterEngine.FilterData()
        FilterEngine.parseHosts("0.0.0.0 evil.example.com\n127.0.0.1 tracker.example.net\n# comment\n", data.hosts)
        assertEquals(2, data.hosts.size)
        assertTrue(data.hosts.contains("evil.example.com"))
    }
}
