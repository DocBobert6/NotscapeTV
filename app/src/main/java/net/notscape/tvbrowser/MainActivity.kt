package net.notscape.tvbrowser

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONTokener
import java.net.URLEncoder

/**
 * Notscape TV — single-tab WebView browser for Android TV.
 *
 * Back behaviour (exact, as shipped):
 * 1. Popup notice visible → Back dismisses the notice.
 * 2. Cursor visible → Back hides the cursor.
 * 3. Top bar visible → Back hides the top bar.
 * 4. Fullscreen web video → Back exits fullscreen.
 * 5. WebView can go back → Back goes back in history.
 * 6. Otherwise → Back shows the start page; Back again on the
 *    start page (with nothing to return to) exits the app.
 *
 * D-pad behaviour: when the cursor is hidden and the WebView has
 * focus, the first D-pad press shows the cursor at screen centre
 * (it does not scroll). While the cursor is visible, D-pad moves
 * it, OK clicks, long-OK long-presses. MENU toggles the top bar;
 * while the top bar is visible, D-pad moves focus between its
 * controls, and DPAD_DOWN from the top bar returns focus to the
 * WebView. A mouse moves the cursor natively; the WebView handles
 * mouse clicks itself.
 *
 * Video path (the one reliable path, grounded in Mariner):
 * direct media links (.mp4/.webm/.mkv/.mov/.m3u8/.mpd) are
 * intercepted in shouldOverrideUrlLoading, and an on-page JS scan
 * finds <video> sources and direct media links on the page. Both
 * funnel into the same PlayerActivity; embedded HTML player pages
 * (YouTube watch/embed, Vimeo, Dailymotion) are NOT sent to the
 * player — they play in the WebView.
 *
 * Cosmetic hiding adaptation: WebView has no document-start script
 * API, so the FilterEngine cosmetic CSS is injected on page
 * finished instead of before first paint. Ads may flash briefly
 * before the sheet lands.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var popupBlocker: PopupBlocker
    private lateinit var webView: WebView
    private lateinit var startPage: StartPageView
    private lateinit var topBar: LinearLayout
    private lateinit var addressField: EditText
    private lateinit var videoButton: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var cursorView: CursorView
    private lateinit var popupNotice: LinearLayout
    private lateinit var popupNoticeText: TextView
    private lateinit var root: FrameLayout

    private var showingStartPage = true
    private var topBarVisible = true
    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    private var currentPageHost: String = ""
    private val detectedMedia = ArrayList<Pair<String, String>>()
    private val handler = Handler(Looper.getMainLooper())
    private var popupHideRunnable: Runnable? = null

    private val LOCATION_REQUEST = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        popupBlocker = PopupBlocker(prefs)
        FilterEngine.initAsync(this)

        root = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor("#101826"))
        }

        webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        setupWebView()
        root.addView(webView)

        startPage = StartPageView(
            this, prefs,
            onOpenUrl = { url -> openUrl(url) },
            onUseCurrentLocation = { useCurrentLocation() },
            onAddPlace = { showAddPlaceDialog() }
        ).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(startPage)

        topBar = buildTopBar()
        root.addView(topBar)

        cursorView = CursorView(this, webView).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            visibility = View.GONE
        }
        root.addView(cursorView)

        popupNotice = buildPopupNotice()
        root.addView(popupNotice)

        setContentView(root)
        showStartPage()
        loadHomepageIfCustom()
    }

    // ---- Top bar ----

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun barButton(label: String): Button = Button(this).apply {
        text = label
        textSize = 18f
        setTextColor(Color.WHITE)
        background = ContextCompat.getDrawable(context, R.drawable.btn_tv)
        setPadding(dp(14), dp(8), dp(14), dp(8))
        minHeight = dp(56)
        isFocusable = true
        isFocusableInTouchMode = true
    }

    private fun buildTopBar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#CC101826"))
            setPadding(dp(12), dp(8), dp(12), dp(8))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP
            )
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val home = barButton("Home").apply { setOnClickListener { showStartPage() } }
        val back = barButton("‹").apply { setOnClickListener { if (webView.canGoBack()) webView.goBack() } }
        val fwd = barButton("›").apply {
            setOnClickListener { if (webView.canGoForward()) webView.goForward() }
        }
        val reload = barButton("Reload").apply { setOnClickListener { webView.reload() } }
        addressField = EditText(this).apply {
            hint = "Address or search"
            textSize = 18f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#A0BEDC"))
            setBackgroundColor(Color.parseColor("#1E2A3A"))
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            setSingleLine(true)
            minHeight = dp(56)
            isFocusable = true
            isFocusableInTouchMode = true
            setPadding(dp(12), dp(8), dp(12), dp(8))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_GO) {
                    openUrlOrSearch(text.toString())
                    true
                } else false
            }
        }
        videoButton = barButton("Video").apply {
            visibility = View.GONE
            setOnClickListener { openDetectedVideo() }
        }
        val menu = barButton("Menu").apply { setOnClickListener { showMenu() } }
        row.addView(home)
        row.addView(back)
        row.addView(fwd)
        row.addView(reload)
        row.addView(addressField)
        row.addView(videoButton)
        row.addView(menu)
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(4)
            )
            max = 100
            visibility = View.GONE
        }
        bar.addView(row)
        bar.addView(progressBar)
        return bar
    }

    private fun setTopBarVisible(v: Boolean) {
        topBarVisible = v
        topBar.visibility = if (v) View.VISIBLE else View.GONE
        if (!v) webView.requestFocus()
    }

    // ---- Navigation ----

    fun openUrl(url: String) {
        showingStartPage = false
        startPage.visibility = View.GONE
        setTopBarVisible(false)
        webView.visibility = View.VISIBLE
        webView.loadUrl(url)
        webView.requestFocus()
    }

    private fun openUrlOrSearch(input: String) {
        val text = input.trim()
        if (text.isEmpty()) return
        val url = when {
            text.startsWith("http://") || text.startsWith("https://") -> text
            text.contains(".") && !text.contains(" ") -> "https://$text"
            else -> "https://html.duckduckgo.com/html/?q=" + URLEncoder.encode(text, "UTF-8")
        }
        openUrl(url)
    }

    private fun showStartPage() {
        showingStartPage = true
        startPage.visibility = View.VISIBLE
        setTopBarVisible(true)
        startPage.refreshAll()
        startPage.firstFocusable()?.requestFocus()
    }

    private fun loadHomepageIfCustom() {
        val home = getSharedPreferences("notscape_tv", MODE_PRIVATE)
            .getString("homepage_url", null)
        if (!home.isNullOrBlank()) openUrl(home)
    }

    // ---- WebView ----

    private fun setupWebView() {
        val settings: WebSettings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.setSupportMultipleWindows(true)
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        webView.setDownloadListener { _, _, _, _, _ ->
            Toast.makeText(this, "Downloads are not included in v1", Toast.LENGTH_SHORT).show()
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView, request: WebResourceRequest
            ): WebResourceResponse? {
                val url = request.url.toString()
                inspectForMedia(url)
                if (prefs.blockAds &&
                    FilterEngine.isBlocked(url, request.isForMainFrame, true, true, currentPageHost)
                ) {
                    FilterEngine.noteBlocked()
                    return FilterEngine.emptyResponse()
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                val scheme = request.url.scheme ?: ""
                if (isMediaUrl(url)) {
                    openPlayer(url, url)
                    return true
                }
                // Popunder guard (Mariner 0.0.962): a script-driven
                // main-frame hop with no user gesture, aimed at a known
                // $document/$popup ad host, is the swap trick. Capture
                // it like a blocked popup instead of loading it. Typed
                // addresses never reach here (loadUrl skips this
                // callback) and tapped links carry a gesture.
                if (request.isForMainFrame && !request.hasGesture() &&
                    (scheme == "http" || scheme == "https")
                ) {
                    val targetHost = request.url.host ?: ""
                    if (targetHost.isNotEmpty() && targetHost != currentPageHost &&
                        FilterEngine.isDocumentBlocked(url, currentPageHost)
                    ) {
                        popupBlocker.capture(url, currentPageHost)
                        showPopupNotice()
                        return true
                    }
                }
                return when (scheme) {
                    "http", "https", "about" -> false
                    else -> true
                }
            }

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                currentPageHost = Uri.parse(url).host ?: ""
                detectedMedia.clear()
                runOnUiThread { videoButton.visibility = View.GONE }
                super.onPageStarted(view, url, favicon)
            }

            override fun onPageFinished(view: WebView, url: String) {
                addressField.setText(url)
                // Cosmetic hiding (see class comment: injected here,
                // WebView has no document-start API).
                if (prefs.blockAds) {
                    val css = FilterEngine.cosmeticCss(currentPageHost)
                    if (css.isNotEmpty()) {
                        val esc = css.replace("\\", "\\\\").replace("'", "\\'").replace("</", "<\\/")
                        view.evaluateJavascript(
                            "(function(){try{var s=document.createElement('style');s.textContent='$esc';(document.documentElement||document).appendChild(s);}catch(e){}})();",
                            null
                        )
                    }
                    val script = FilterEngine.documentStartScript
                    if (script.isNotEmpty()) view.evaluateJavascript(script, null)
                }
                view.evaluateJavascript(MEDIA_SCAN_JS) { raw ->
                    val found = parseMediaScan(raw)
                    if (found.isNotEmpty()) {
                        detectedMedia.clear()
                        detectedMedia.addAll(found)
                        videoButton.visibility = View.VISIBLE
                    }
                }
                super.onPageFinished(view, url)
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progressBar.progress = newProgress
                progressBar.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String, callback: GeolocationPermissions.Callback
            ) {
                callback.invoke(origin, false, false)
            }

            override fun onCreateWindow(
                view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?
            ): Boolean {
                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                val pageHost = currentPageHost
                // Capture the popup's target with a JS-disabled WebView
                // (Mariner's blocked path). A captured popup never runs JS.
                val capture = WebView(view.context)
                capture.settings.javaScriptEnabled = false
                capture.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                        val u = request.url.toString()
                        runCatching { capture.destroy() }
                        handlePopupTarget(u, pageHost)
                        return true
                    }
                }
                transport.webView = capture
                if (!popupBlocker.isAllowed(pageHost) && !popupBlocker.isSilenced(pageHost)) {
                    showPopupNotice()
                }
                resultMsg.sendToTarget()
                return true
            }

            override fun onCloseWindow(window: WebView) {
                val prev = popupBlocker.takePreviousUrl()
                if (prev != null) webView.loadUrl(prev)
                else if (webView.canGoBack()) webView.goBack()
            }

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                fullscreenView = view
                fullscreenCallback = callback
                root.addView(view, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                ))
                webView.visibility = View.GONE
                startPage.visibility = View.GONE
                topBar.visibility = View.GONE
            }

            override fun onHideCustomView() {
                fullscreenView?.let { root.removeView(it) }
                fullscreenView = null
                fullscreenCallback?.onCustomViewHidden()
                fullscreenCallback = null
                webView.visibility = View.VISIBLE
                if (showingStartPage) startPage.visibility = View.VISIBLE
                if (topBarVisible) topBar.visibility = View.VISIBLE
            }
        }
    }

    private fun handlePopupTarget(url: String, pageHost: String) {
        if (url.isBlank() || url == "about:blank") return
        if (popupBlocker.isAllowed(pageHost)) {
            popupBlocker.noteAllowedPopup(webView.url ?: "")
            openUrl(url)
        } else {
            popupBlocker.capture(url, pageHost)
            if (!popupBlocker.isSilenced(pageHost)) showPopupNotice()
        }
    }

    // ---- Video ----

    private fun isMediaUrl(url: String): Boolean {
        val lower = url.lowercase().substringBefore('?').substringBefore('#')
        return lower.endsWith(".mp4") || lower.endsWith(".webm") || lower.endsWith(".mkv") ||
            lower.endsWith(".mov") || lower.endsWith(".m3u8") || lower.endsWith(".mpd")
    }

    private fun isEmbedPageUrl(url: String): Boolean {
        val u = url.lowercase()
        return u.contains("youtube.com/embed/") || u.contains("youtube-nocookie.com/embed/") ||
            u.contains("youtube.com/watch") || u.contains("youtu.be/") ||
            u.contains("player.vimeo.com/video/") || u.contains("dailymotion.com/embed/")
    }

    private fun inspectForMedia(url: String) {
        if (isMediaUrl(url) && !isEmbedPageUrl(url)) {
            synchronized(detectedMedia) {
                if (detectedMedia.none { it.first == url }) {
                    detectedMedia.add(url to "stream")
                    runOnUiThread { videoButton.visibility = View.VISIBLE }
                }
            }
        }
    }

    private fun parseMediaScan(raw: String?): List<Pair<String, String>> {
        if (raw.isNullOrBlank() || raw == "null") return emptyList()
        return runCatching {
            val inner = JSONTokener(raw).nextValue() as? String ?: return emptyList()
            val arr = JSONArray(inner)
            val list = ArrayList<Pair<String, String>>()
            for (i in 0 until arr.length()) {
                val u = arr.getString(i)
                if (isMediaUrl(u) && !isEmbedPageUrl(u)) list.add(u to "video on page")
            }
            list
        }.getOrElse { emptyList() }
    }

    private fun openDetectedVideo() {
        when {
            detectedMedia.isEmpty() -> return
            detectedMedia.size == 1 -> openPlayer(detectedMedia[0].first, detectedMedia[0].second)
            else -> {
                val labels = detectedMedia.map { it.second + " — " + it.first }.toTypedArray()
                AlertDialog.Builder(this)
                    .setTitle("Video on this page")
                    .setItems(labels) { _, which ->
                        openPlayer(detectedMedia[which].first, detectedMedia[which].second)
                    }
                    .show()
            }
        }
    }

    private fun openPlayer(url: String, title: String) {
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_URL, url)
            putExtra(PlayerActivity.EXTRA_TITLE, title)
        }
        startActivity(intent)
    }

    // ---- Popup notice ----

    private fun buildPopupNotice(): LinearLayout {
        val notice = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#FF1E2A3A"))
            setPadding(dp(20), dp(16), dp(20), dp(16))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM
            )
            visibility = View.GONE
        }
        popupNoticeText = TextView(this).apply {
            textSize = 18f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, dp(12))
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val openOnce = barButton("Open once").apply {
            setOnClickListener {
                popupBlocker.takeFirstPending()?.let { url ->
                    popupBlocker.noteAllowedPopup(webView.url ?: "")
                    openUrl(url)
                }
                hidePopupNotice()
            }
        }
        val allowSite = barButton("Always allow").apply {
            setOnClickListener {
                val host = popupBlocker.pendingPageHost
                if (host.isNotEmpty()) prefs.setSitePopup(host, true)
                popupBlocker.takeFirstPending()?.let { url ->
                    popupBlocker.noteAllowedPopup(webView.url ?: "")
                    openUrl(url)
                }
                hidePopupNotice()
            }
        }
        val block = barButton("Block").apply {
            setOnClickListener {
                popupBlocker.clearPending()
                hidePopupNotice()
            }
        }
        row.addView(openOnce)
        row.addView(allowSite)
        row.addView(block)
        notice.addView(popupNoticeText)
        notice.addView(row)
        return notice
    }

    private fun showPopupNotice() {
        val host = popupBlocker.pendingPageHost.ifBlank { currentPageHost }
        popupNoticeText.text = "Popup blocked from $host"
        popupNotice.visibility = View.VISIBLE
        popupHideRunnable?.let { handler.removeCallbacks(it) }
        val r = Runnable { hidePopupNotice() }
        popupHideRunnable = r
        handler.postDelayed(r, 12_000)
        (popupNotice.getChildAt(1) as? ViewGroup)?.getChildAt(0)?.requestFocus()
    }

    private fun hidePopupNotice() {
        popupHideRunnable?.let { handler.removeCallbacks(it) }
        popupNotice.visibility = View.GONE
    }

    // ---- Menu ----

    private fun showMenu() {
        val blocked = FilterEngine.blockedCount
        val rules = FilterEngine.ruleCount
        val items = arrayOf(
            if (prefs.blockAds) "Block ads & trackers: On" else "Block ads & trackers: Off",
            "Update filter lists now",
            "Homepage…",
            "Clear browsing data"
        )
        AlertDialog.Builder(this)
            .setTitle("Menu")
            .setMessage("Blocked this session: $blocked\nFilter rules loaded: $rules")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> {
                        prefs.blockAds = !prefs.blockAds
                        Toast.makeText(
                            this,
                            if (prefs.blockAds) "Ad blocking on" else "Ad blocking off",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    1 -> {
                        FilterEngine.refreshAsync(this)
                        Toast.makeText(this, "Updating filter lists…", Toast.LENGTH_SHORT).show()
                    }
                    2 -> showHomepageDialog()
                    3 -> clearBrowsingData()
                }
            }
            .show()
    }

    private fun showHomepageDialog() {
        val prefsFile = getSharedPreferences("notscape_tv", MODE_PRIVATE)
        val current = prefsFile.getString("homepage_url", null)
        val input = EditText(this).apply {
            hint = "Custom homepage URL (blank = dashboard)"
            setText(current ?: "")
            inputType = InputType.TYPE_TEXT_VARIATION_URI
        }
        AlertDialog.Builder(this)
            .setTitle("Homepage")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val v = input.text.toString().trim()
                prefsFile.edit().putString("homepage_url", v.ifBlank { null }).apply()
                Toast.makeText(this, "Homepage saved", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun clearBrowsingData() {
        webView.clearCache(true)
        webView.clearHistory()
        WebStorage.getInstance().deleteAllData()
        CookieManager.getInstance().removeAllCookies(null)
        Toast.makeText(this, "Browsing data cleared", Toast.LENGTH_SHORT).show()
    }

    // ---- Weather place: location + add-a-place ----

    private fun useCurrentLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), LOCATION_REQUEST
            )
            return
        }
        fetchLastLocation()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_REQUEST &&
            grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            fetchLastLocation()
        } else if (requestCode == LOCATION_REQUEST) {
            Toast.makeText(this, "Location denied — add a place instead", Toast.LENGTH_SHORT).show()
        }
    }

    private fun fetchLastLocation() {
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        val loc = try {
            lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        } catch (_: SecurityException) {
            null
        }
        if (loc == null) {
            Toast.makeText(this, "No location yet — add a place instead", Toast.LENGTH_SHORT).show()
            return
        }
        startPage.setPlace("Current location", loc.latitude, loc.longitude)
    }

    private fun showAddPlaceDialog() {
        val input = EditText(this).apply {
            hint = "City or place name"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        AlertDialog.Builder(this)
            .setTitle("Add a place")
            .setView(input)
            .setPositiveButton("Search") { _, _ ->
                val query = input.text.toString().trim()
                if (query.isEmpty()) return@setPositiveButton
                Thread {
                    val results = runCatching { WeatherRepository.geocode(query) }.getOrElse { emptyList() }
                    runOnUiThread {
                        if (results.isEmpty()) {
                            Toast.makeText(this, "No place found for “$query”", Toast.LENGTH_SHORT).show()
                        } else if (results.size == 1) {
                            startPage.setPlace(results[0].name, results[0].lat, results[0].lon)
                        } else {
                            AlertDialog.Builder(this)
                                .setTitle("Choose a place")
                                .setItems(results.map { it.name }.toTypedArray()) { _, which ->
                                    val r = results[which]
                                    startPage.setPlace(r.name, r.lat, r.lon)
                                }
                                .show()
                        }
                    }
                }.start()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- Keys: cursor, top bar, back ----

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_MENU) {
            setTopBarVisible(!topBarVisible)
            if (topBarVisible) addressField.requestFocus()
            return true
        }
        if (popupNotice.visibility == View.VISIBLE) {
            // Let focus navigation work inside the notice; Back is handled below.
            return super.dispatchKeyEvent(event)
        }
        if (cursorView.visible) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> if (event.action == KeyEvent.ACTION_DOWN) return cursorView.onDpad(-1, 0)
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (event.action == KeyEvent.ACTION_DOWN) return cursorView.onDpad(1, 0)
                KeyEvent.KEYCODE_DPAD_UP -> if (event.action == KeyEvent.ACTION_DOWN) return cursorView.onDpad(0, -1)
                KeyEvent.KEYCODE_DPAD_DOWN -> if (event.action == KeyEvent.ACTION_DOWN) return cursorView.onDpad(0, 1)
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (event.action == KeyEvent.ACTION_DOWN) return cursorView.onCenterDown()
                    if (event.action == KeyEvent.ACTION_UP) return cursorView.onCenterUp()
                }
            }
            return super.dispatchKeyEvent(event)
        }
        if (!showingStartPage && !topBarVisible) {
            // First D-pad press in the WebView shows the cursor.
            if (event.action == KeyEvent.ACTION_DOWN &&
                event.keyCode in listOf(
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN
                )
            ) {
                cursorView.showAtCenter()
                return true
            }
        }
        if (topBarVisible && event.action == KeyEvent.ACTION_DOWN &&
            event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN &&
            currentFocus != null && isDescendantOf(currentFocus, topBar)
        ) {
            setTopBarVisible(false)
            webView.requestFocus()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun isDescendantOf(view: View?, parent: ViewGroup): Boolean {
        var v = view?.parent
        while (v != null) {
            if (v === parent) return true
            v = v.parent
        }
        return false
    }

    override fun onGenericMotionEvent(event: android.view.MotionEvent): Boolean {
        if (event.source and android.view.InputDevice.SOURCE_MOUSE != 0 &&
            event.action == android.view.MotionEvent.ACTION_HOVER_MOVE
        ) {
            if (!showingStartPage) cursorView.onMouseMove(event.x, event.y)
        }
        return super.onGenericMotionEvent(event)
    }

    override fun onBackPressed() {
        when {
            popupNotice.visibility == View.VISIBLE -> hidePopupNotice()
            cursorView.visible -> cursorView.hide()
            topBarVisible -> setTopBarVisible(false)
            fullscreenView != null -> webView.webChromeClient?.onHideCustomView()
            showingStartPage -> {
                if (webView.url != null) {
                    showingStartPage = false
                    startPage.visibility = View.GONE
                    webView.visibility = View.VISIBLE
                    webView.requestFocus()
                } else super.onBackPressed()
            }
            webView.canGoBack() -> webView.goBack()
            else -> showStartPage()
        }
    }

    override fun onDestroy() {
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        /** On-page media scan (Mariner's MediaDetect.JS, trimmed to video). */
        const val MEDIA_SCAN_JS = """(function(){var out=[],seen={};function add(u){if(!u||seen[u])return;try{u=new URL(u,document.baseURI).href}catch(e){return}if(u.indexOf('blob:')===0)return;seen[u]=1;out.push(u)}document.querySelectorAll('video').forEach(function(v){add(v.currentSrc||v.src);v.querySelectorAll('source').forEach(function(s){add(s.src)})});document.querySelectorAll('a[href]').forEach(function(a){var h=a.href||'';if(/\.(mp4|webm|mkv|mov|m3u8|mpd)(\?|#|$)/i.test(h))add(h)});return JSON.stringify(out)})()"""
    }
}
