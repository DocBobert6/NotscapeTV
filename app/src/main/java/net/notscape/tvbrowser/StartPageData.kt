package net.notscape.tvbrowser

import android.util.Xml
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.roundToInt

// ---- Models ----

data class NewsItem(val title: String, val link: String, val source: String, val time: String)
data class Quote(val symbol: String, val name: String, val price: Double, val changePct: Double, val source: String)
data class WeatherNow(
    val placeName: String,
    val tempF: Double,
    val condition: String,
    val windMph: Double,
    val daily: List<DayForecast>
)
data class DayForecast(val date: String, val highF: Double, val lowF: Double, val condition: String)
data class PlaceResult(val name: String, val lat: Double, val lon: Double)

// ---- Small HTTP helper (background threads only, never the UI thread) ----

private fun httpGet(url: String, timeoutMs: Int = 15_000): String {
    val conn = URL(url).openConnection() as HttpURLConnection
    conn.connectTimeout = timeoutMs
    conn.readTimeout = timeoutMs
    conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) NotscapeTV")
    conn.setRequestProperty("Accept", "*/*")
    try {
        val code = conn.responseCode
        if (code !in 200..299) throw java.io.IOException("HTTP $code for $url")
        val reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
        return reader.readText()
    } finally {
        conn.disconnect()
    }
}

// ---- News: free RSS feeds, no key ----

object NewsRepository {
    /**
     * Fallback chain, first feed that parses to items wins per feed;
     * results are merged and deduped by link.
     *
     * Grounded 2026-11-27: AP's own apnews.com RSS now blocks readers
     * (and hub/ap-top-news.rss 404s since Feb 2026), so AP comes via
     * its Flipboard account; BBC News RSS is the stable primary.
     */
    val feeds = listOf(
        "BBC News" to "https://feeds.bbci.co.uk/news/rss.xml",
        "AP Top Stories" to "https://flipboard.com/@associatedpress/top-stories-u7govdf1z.rss",
        "Google News" to "https://news.google.com/rss?hl=en-US&gl=US&ceid=US:en"
    )

    fun fetch(maxItems: Int = 12): List<NewsItem> {
        val out = ArrayList<NewsItem>()
        val seenLinks = HashSet<String>()
        for ((source, url) in feeds) {
            if (out.size >= maxItems) break
            runCatching {
                val items = parseRss(httpGet(url), source)
                for (item in items) {
                    if (seenLinks.add(item.link)) out.add(item)
                    if (out.size >= maxItems) break
                }
            }
        }
        return out
    }

    fun parseRss(xml: String, source: String): List<NewsItem> {
        val items = ArrayList<NewsItem>()
        try {
            val parser = Xml.newPullParser()
            parser.setFeature(Xml.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(xml.byteInputStream(Charsets.UTF_8), "UTF-8")
            var event = parser.eventType
            var title: String? = null
            var link: String? = null
            var pubDate: String? = null
            var inItem = false
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name.lowercase(Locale.US)) {
                            "item", "entry" -> { inItem = true; title = null; link = null; pubDate = null }
                            "title" -> if (inItem) title = parser.nextText()
                            "link" -> if (inItem) {
                                val href = parser.getAttributeValue(null, "href")
                                link = href ?: parser.nextText()
                            }
                            "pubdate", "published", "updated" -> if (inItem) pubDate = parser.nextText()
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if ((parser.name.equals("item", ignoreCase = true) ||
                                parser.name.equals("entry", ignoreCase = true)) && inItem
                        ) {
                            inItem = false
                            if (!title.isNullOrBlank() && !link.isNullOrBlank()) {
                                items.add(NewsItem(title.trim(), link.trim(), source, (pubDate ?: "").trim()))
                            }
                        }
                    }
                }
                event = parser.next()
            }
        } catch (_: Exception) {
            // A broken feed returns what parsed so far (possibly nothing);
            // the caller tries the next feed.
        }
        return items
    }
}

// ---- Stocks: defaults only (S&P 500, Dow, Nasdaq), no key ----

object StocksRepository {
    data class IndexDef(val symbol: String, val name: String, val quoteUrl: String)

    val indices = listOf(
        IndexDef("^GSPC", "S&P 500", "https://finance.yahoo.com/quote/%5EGSPC/"),
        IndexDef("^DJI", "Dow Jones", "https://finance.yahoo.com/quote/%5EDJI/"),
        IndexDef("^IXIC", "Nasdaq", "https://finance.yahoo.com/quote/%5EIXIC/")
    )

    fun fetch(): List<Quote> {
        val out = ArrayList<Quote>()
        for (def in indices) {
            fetchOne(def)?.let { out.add(it) }
        }
        return out
    }

    private fun fetchOne(def: IndexDef): Quote? {
        // Primary: Yahoo Finance chart API (no key). Fallback: Stooq
        // daily CSV (no key) — only for S&P 500 and Dow; Stooq has no
        // Nasdaq Composite symbol with the same meaning, so Nasdaq
        // stays Yahoo-only and reports unavailable rather than a
        // wrong number.
        runCatching {
            val enc = URLEncoder.encode(def.symbol, "UTF-8")
            val json = JSONObject(httpGet("https://query1.finance.yahoo.com/v8/finance/chart/$enc?range=5d&interval=1d"))
            val meta = json.getJSONObject("chart").getJSONArray("result").getJSONObject(0).getJSONObject("meta")
            val price = meta.getDouble("regularMarketPrice")
            val prev = meta.optDouble("chartPreviousClose", Double.NaN)
            val changePct = if (!prev.isNaN() && prev != 0.0) (price - prev) / prev * 100.0 else 0.0
            return Quote(def.symbol, def.name, price, changePct, "Yahoo Finance")
        }
        val stooqSymbol = when (def.symbol) {
            "^GSPC" -> "^spx"
            "^DJI" -> "^dji"
            else -> return null
        }
        return runCatching {
            val csv = httpGet("https://stooq.com/q/d/l/?s=$stooqSymbol&i=d")
            val rows = csv.lineSequence().drop(1).mapNotNull { line ->
                val parts = line.split(",")
                if (parts.size >= 5) parts[4].toDoubleOrNull() else null
            }.toList()
            if (rows.size < 2) return null
            val price = rows.last()
            val prev = rows[rows.size - 2]
            Quote(def.symbol, def.name, price, (price - prev) / prev * 100.0, "Stooq")
        }.getOrNull()
    }
}

// ---- Weather: Open-Meteo, free, no key ----

object WeatherRepository {
    fun geocode(query: String): List<PlaceResult> {
        val enc = URLEncoder.encode(query, "UTF-8")
        val json = JSONObject(httpGet("https://geocoding-api.open-meteo.com/v1/search?name=$enc&count=5&language=en&format=json"))
        val out = ArrayList<PlaceResult>()
        val results = json.optJSONArray("results") ?: return out
        for (i in 0 until results.length()) {
            val o = results.getJSONObject(i)
            val name = listOfNotNull(
                o.optString("name", "").ifBlank { null },
                o.optString("admin1", "").ifBlank { null },
                o.optString("country", "").ifBlank { null }
            ).joinToString(", ")
            out.add(PlaceResult(name, o.getDouble("latitude"), o.getDouble("longitude")))
        }
        return out
    }

    fun fetch(placeName: String, lat: Double, lon: Double): WeatherNow? {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,weather_code,wind_speed_10m" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min" +
            "&temperature_unit=fahrenheit&wind_speed_unit=mph&timezone=auto&forecast_days=7"
        val json = JSONObject(httpGet(url))
        val current = json.getJSONObject("current")
        val daily = json.getJSONObject("daily")
        val times = daily.getJSONArray("time")
        val codes = daily.getJSONArray("weather_code")
        val highs = daily.getJSONArray("temperature_2m_max")
        val lows = daily.getJSONArray("temperature_2m_min")
        val days = ArrayList<DayForecast>()
        for (i in 0 until times.length()) {
            days.add(
                DayForecast(
                    date = times.getString(i),
                    highF = highs.getDouble(i),
                    lowF = lows.getDouble(i),
                    condition = wmoText(codes.getInt(i))
                )
            )
        }
        return WeatherNow(
            placeName = placeName,
            tempF = current.getDouble("temperature_2m"),
            condition = wmoText(current.getInt("weather_code")),
            windMph = current.getDouble("wind_speed_10m"),
            daily = days
        )
    }

    fun wmoText(code: Int): String = when (code) {
        0 -> "Clear"
        1 -> "Mainly clear"
        2 -> "Partly cloudy"
        3 -> "Overcast"
        45, 48 -> "Fog"
        51, 53, 55 -> "Drizzle"
        56, 57 -> "Freezing drizzle"
        61, 63, 65 -> "Rain"
        66, 67 -> "Freezing rain"
        71, 73, 75 -> "Snow"
        77 -> "Snow grains"
        80, 81, 82 -> "Rain showers"
        85, 86 -> "Snow showers"
        95 -> "Thunderstorm"
        96, 99 -> "Thunderstorm, hail"
        else -> "Unknown"
    }

    fun dayLabel(isoDate: String): String {
        return runCatching {
            val inFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val date = inFmt.parse(isoDate) ?: return isoDate
            SimpleDateFormat("EEE MMM d", Locale.US).format(date)
        }.getOrElse { isoDate }
    }
}

fun Double.round1(): Double = (this * 10).roundToInt() / 10.0
