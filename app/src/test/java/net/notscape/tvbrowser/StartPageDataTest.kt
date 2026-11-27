package net.notscape.tvbrowser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StartPageDataTest {

    @Test
    fun rssParsesItems() {
        val xml = """<?xml version="1.0"?><rss><channel>
            <item><title>First story</title><link>https://example.org/a</link><pubDate>Fri, 27 Nov 2026</pubDate></item>
            <item><title>Second story</title><link>https://example.org/b</link></item>
            </channel></rss>"""
        val items = NewsRepository.parseRss(xml, "Test")
        assertEquals(2, items.size)
        assertEquals("First story", items[0].title)
        assertEquals("https://example.org/a", items[0].link)
    }

    @Test
    fun wmoCodesMap() {
        assertEquals("Clear", WeatherRepository.wmoText(0))
        assertEquals("Rain", WeatherRepository.wmoText(63))
        assertEquals("Snow", WeatherRepository.wmoText(73))
        assertEquals("Thunderstorm", WeatherRepository.wmoText(95))
    }

    @Test
    fun sameSiteUsesRegistrableDomain() {
        assertTrue(Hosts.sameSite("www.example.co.uk", "login.example.co.uk"))
        assertTrue(Hosts.sameSite("a.example.com", "b.example.com"))
        assertEquals(false, Hosts.sameSite("example.com", "other.com"))
    }
}
