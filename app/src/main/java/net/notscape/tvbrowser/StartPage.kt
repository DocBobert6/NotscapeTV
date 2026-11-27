package net.notscape.tvbrowser

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * The start-page dashboard: News | Stocks | Weather, three columns on a
 * TV-landscape screen. Everything is a focusable Button with a large
 * target (min 64dp tall) so D-pad navigation works; the focused button
 * turns blue via btn_tv.xml.
 *
 * All network work runs on background threads; results are posted back
 * to the UI thread. A failed source shows an error line with a Retry
 * button instead of crashing or showing a blank page.
 */
class StartPageView(
    context: Context,
    private val prefs: Prefs,
    private val onOpenUrl: (String) -> Unit,
    private val onUseCurrentLocation: () -> Unit,
    private val onAddPlace: () -> Unit
) : ScrollView(context) {

    private val newsBox = LinearLayout(context)
    private val newsStatus = TextView(context)
    private val stocksBox = LinearLayout(context)
    private val stocksStatus = TextView(context)
    private val weatherBox = LinearLayout(context)
    private val weatherStatus = TextView(context)

    init {
        isFocusable = false
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(32), dp(24), dp(32), dp(32))
        }
        val title = TextView(context).apply {
            text = "Notscape TV"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f)
            setTypeface(null, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(context, android.R.color.white))
        }
        val subtitle = TextView(context).apply {
            text = "News  ·  Stocks  ·  Weather"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(ContextCompat.getColor(context, R.color.tv_muted))
            setPadding(0, dp(4), 0, dp(20))
        }
        root.addView(title)
        root.addView(subtitle)

        val columns = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        columns.addView(makeColumn("News", newsStatus, newsBox), columnParams(0, 2f))
        columns.addView(makeColumn("Stocks", stocksStatus, stocksBox), columnParams(0, 1f))
        columns.addView(makeColumn("Weather", weatherStatus, weatherBox), columnParams(0, 1f))
        root.addView(columns)
        addView(root)
        refreshAll()
    }

    private fun columnParams(width: Int, weight: Float): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.MATCH_PARENT, weight).apply {
            marginEnd = dp(24)
        }

    private fun makeColumn(title: String, status: TextView, box: LinearLayout): LinearLayout {
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val head = TextView(context).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            setTypeface(null, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(context, android.R.color.white))
            setPadding(0, 0, 0, dp(8))
        }
        status.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(ContextCompat.getColor(context, R.color.tv_muted))
            setPadding(0, 0, 0, dp(8))
        }
        box.orientation = LinearLayout.VERTICAL
        col.addView(head)
        col.addView(status)
        col.addView(box)
        return col
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun makeButton(label: String, onClick: () -> Unit): Button =
        Button(context).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(ContextCompat.getColor(context, android.R.color.white))
            background = ContextCompat.getDrawable(context, R.drawable.btn_tv)
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
            minHeight = dp(64)
            isFocusable = true
            isFocusableInTouchMode = true
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }

    private fun makeRetry(label: String, onClick: () -> Unit): Button = makeButton(label, onClick)

    fun refreshAll() {
        refreshNews()
        refreshStocks()
        refreshWeather()
    }

    // ---- News ----

    fun refreshNews() {
        newsStatus.text = "Loading…"
        newsBox.removeAllViews()
        Thread {
            val items = NewsRepository.fetch()
            post {
                newsBox.removeAllViews()
                if (items.isEmpty()) {
                    newsStatus.text = "News unavailable — check your connection."
                    newsBox.addView(makeRetry("Retry news") { refreshNews() })
                } else {
                    newsStatus.text = "Top stories"
                    for (item in items) {
                        newsBox.addView(makeButton("${item.title}\n${item.source}") {
                            onOpenUrl(item.link)
                        })
                    }
                }
            }
        }.start()
    }

    // ---- Stocks ----

    fun refreshStocks() {
        stocksStatus.text = "Loading…"
        stocksBox.removeAllViews()
        Thread {
            val quotes = StocksRepository.fetch()
            post {
                stocksBox.removeAllViews()
                if (quotes.isEmpty()) {
                    stocksStatus.text = "Quotes unavailable — check your connection."
                    stocksBox.addView(makeRetry("Retry stocks") { refreshStocks() })
                } else {
                    stocksStatus.text = "Delayed quotes · tap for details"
                    for (q in quotes) {
                        val sign = if (q.changePct >= 0) "+" else ""
                        val label = "${q.name}\n${"%,.2f".format(q.price)}  $sign${"%.2f".format(q.changePct)}%"
                        val b = makeButton(label) {
                            val url = StocksRepository.indices.firstOrNull { it.symbol == q.symbol }?.quoteUrl
                            if (url != null) onOpenUrl(url)
                        }
                        b.setTextColor(
                            ContextCompat.getColor(
                                context,
                                if (q.changePct >= 0) R.color.tv_green else R.color.tv_red
                            )
                        )
                        stocksBox.addView(b)
                    }
                    stocksBox.addView(makeButton("Refresh quotes") { refreshStocks() })
                }
            }
        }.start()
    }

    // ---- Weather ----

    fun refreshWeather() {
        weatherBox.removeAllViews()
        if (!prefs.hasWeatherPlace()) {
            weatherStatus.text = "No place set — works anywhere."
            weatherBox.addView(makeButton("Use current location") { onUseCurrentLocation() })
            weatherBox.addView(makeButton("Add a place…") { onAddPlace() })
            return
        }
        weatherStatus.text = "Loading…"
        val name = prefs.weatherPlaceName ?: ""
        val lat = prefs.weatherLat.toDouble()
        val lon = prefs.weatherLon.toDouble()
        Thread {
            val now = runCatching { WeatherRepository.fetch(name, lat, lon) }.getOrNull()
            post {
                weatherBox.removeAllViews()
                if (now == null) {
                    weatherStatus.text = "Weather unavailable — check your connection."
                    weatherBox.addView(makeRetry("Retry weather") { refreshWeather() })
                    weatherBox.addView(makeButton("Change place…") { onAddPlace() })
                    return@post
                }
                weatherStatus.text = now.placeName
                val current = TextView(context).apply {
                    text = "${now.tempF.round1()}°F  ·  ${now.condition}\nWind ${now.windMph.round1()} mph"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(ContextCompat.getColor(context, android.R.color.white))
                    setPadding(0, 0, 0, dp(12))
                }
                weatherBox.addView(current)
                for (day in now.daily) {
                    val row = TextView(context).apply {
                        text = "${WeatherRepository.dayLabel(day.date)}  ${day.highF.round1()}° / ${day.lowF.round1()}°  ${day.condition}"
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                        setTextColor(ContextCompat.getColor(context, android.R.color.white))
                        setPadding(dp(8), dp(8), dp(8), dp(8))
                    }
                    weatherBox.addView(row)
                }
                weatherBox.addView(makeButton("Change place…") { onAddPlace() })
                weatherBox.addView(makeButton("Use current location") { onUseCurrentLocation() })
            }
        }.start()
    }

    /** Called by MainActivity after the user picks a place. */
    fun setPlace(name: String, lat: Double, lon: Double) {
        prefs.weatherPlaceName = name
        prefs.weatherLat = lat.toFloat()
        prefs.weatherLon = lon.toFloat()
        refreshWeather()
    }

    /** First focusable child, so the remote lands somewhere useful on launch. */
    fun firstFocusable(): View? {
        for (i in 0 until newsBox.childCount) {
            val v = newsBox.getChildAt(i)
            if (v.isFocusable) return v
        }
        return null
    }
}
