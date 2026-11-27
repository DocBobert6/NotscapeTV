package net.notscape.tvbrowser

/**
 * Strict popup blocker, ported from Notscape Mariner's BrowserScreen.kt
 * (onCreateWindow + popunder guard in shouldOverrideUrlLoading).
 *
 * TV adaptation, reported honestly: Mariner gives an allowed popup a
 * REAL window (a new tab) because some pages depend on the window
 * object. This app has no tabs, so an allowed popup loads in the main
 * view instead (spec). Pages that depend on window.opener
 * communication (some logins) will not work through a popup here.
 *
 * - Strict by default (popupStrict = true): a popup opens only if the
 *   site is in the per-site allow set.
 * - Blocked popups are captured: their target URL is held in a queue
 *   (max 5, deduped) and a notice is shown.
 * - Popunder guard: a script-driven main-frame hop with no user
 *   gesture, aimed at a known $document/$popup ad host, is captured
 *   like a blocked popup instead of loading.
 * - window.close() on a page opened as an allowed popup returns to
 *   the page that was showing before it.
 * - v1 cut: no "Always deny" silencing. A site the user keeps
 *   blocking will show the notice again for each new popup.
 */
class PopupBlocker(private val prefs: Prefs) {

    /** Pending popup target URLs, oldest first. Max 5, deduped. */
    val pendingUrls = ArrayDeque<String>()

    /** Host of the page that produced the pending popups. */
    var pendingPageHost: String = ""
        private set

    /** URL showing before an allowed popup loaded in the main view. */
    var popupPreviousUrl: String? = null
        private set

    fun isAllowed(pageHost: String): Boolean =
        !prefs.popupStrict || prefs.siteAllowsPopups(pageHost)

    fun isSilenced(pageHost: String): Boolean = prefs.siteDeniesPopups(pageHost)

    /** Record a captured popup target. Returns true if it was newly queued. */
    fun capture(url: String, pageHost: String): Boolean {
        if (url.isBlank() || url == "about:blank") return false
        pendingPageHost = pageHost
        if (url !in pendingUrls && pendingUrls.size < 5) {
            pendingUrls.addLast(url)
            return true
        }
        return false
    }

    fun clearPending() {
        pendingUrls.clear()
        pendingPageHost = ""
    }

    fun takeFirstPending(): String? {
        val url = pendingUrls.removeFirstOrNull()
        if (pendingUrls.isEmpty()) pendingPageHost = ""
        return url
    }

    /** An allowed popup is about to load in the main view; remember where to return. */
    fun noteAllowedPopup(currentUrl: String) {
        if (popupPreviousUrl == null) popupPreviousUrl = currentUrl
    }

    /** window.close() on an allowed-popup page: return the previous URL, if any. */
    fun takePreviousUrl(): String? {
        val url = popupPreviousUrl
        popupPreviousUrl = null
        return url
    }
}
