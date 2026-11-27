package net.notscape.tvbrowser

import android.content.Context
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.atomic.AtomicLong

/**
 * Request blocking with real filter lists (EasyList + EasyPrivacy +
 * AdGuard + uBlock), ported from Notscape Mariner's FilterEngine
 * (WebTools.kt). Trimmed for v1:
 *
 * - No Compose state, no per-tab counts, no per-day history.
 * - No user rules (no UserBlockRules in v1).
 * - No binary compiled.bin cache: lists are cached as text on disk
 *   (listN.txt + .bak rollback) and parsed in full on cold start.
 *   The parse is background-only, so this costs startup CPU, not
 *   navigation latency.
 * - The engine itself is pure JVM (java.net.URI, no android.net.Uri)
 *   so unit tests can pin the matching rules without Robolectric.
 *
 * Decision order (Mariner's): $important beats exceptions, exceptions
 * beat blocks, main-frame loads are never blocked, player/CDN hosts
 * are never host-blocked.
 */
object FilterEngine {

    const val CAT_ADS = 0
    const val CAT_TRACKER = 1
    const val CAT_OTHER = 2

    private val builtinHosts = setOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com",
        "google-analytics.com", "googletagmanager.com", "googletagservices.com",
        "analytics.twitter.com", "ads-twitter.com", "connect.facebook.net",
        "facebook.net", "adservice.google.com", "pagead2.googlesyndication.com",
        "tpc.googlesyndication.com", "partner.googleadservices.com",
        "amazon-adsystem.com", "aax.amazon-adsystem.com", "ads.yahoo.com",
        "analytics.yahoo.com", "advertising.com", "adnxs.com", "adsrvr.org",
        "rubiconproject.com", "pubmatic.com", "openx.net", "criteo.com",
        "criteo.net", "taboola.com", "outbrain.com", "scorecardresearch.com",
        "quantserve.com", "moatads.com", "zedo.com", "smartadserver.com",
        "lijit.com", "sovrn.com", "sharethrough.com", "triplelift.com",
        "indexww.com", "casalemedia.com", "contextweb.com", "spotxchange.com",
        "mathtag.com", "bluekai.com", "demdex.net", "krxd.net", "exelator.com",
        "turn.com", "adform.net", "bidswitch.net", "w55c.net", "rlcdn.com",
        "pippio.com", "eyeota.net", "tapad.com", "drawbridge.com",
        "crossdevice.com", "adsymptotic.com", "nr-data.net", "newrelic.com",
        "hotjar.com", "fullstory.com", "mouseflow.com", "crazyegg.com",
        "mixpanel.com", "segment.com", "segment.io", "amplitude.com",
        "branch.io", "adjust.com", "appsflyer.com", "kochava.com",
        "leanplum.com", "clevertap.com", "onesignal.com", "pushwoosh.com",
        "bat.bing.com", "clarity.ms", "mc.yandex.ru",
        "adskeeper.com", "mgid.com", "revcontent.com", "contentad.net",
        "zergnet.com", "newsmaxfeednetwork.com", "disqusads.com",
        "propellerads.com", "propellerclick.com", "onclicks.net",
        "popads.net", "popcash.net", "popmyads.com", "clickadu.com",
        "trafficjunky.net", "exoclick.com", "juicyads.com",
        "applovin.com", "unityads.unity3d.com", "adroll.com",
        "advertising.yahoo.com", "ysm.yahoo.com", "adtech.yahooinc.com",
        "bingads.microsoft.com", "ads.microsoft.com",
        "ads.linkedin.com", "px.ads.linkedin.com", "snap.licdn.com",
        "fls-na.amazon.com", "fingerprintjs.com", "fpjs.io",
        "siftscience.com", "permutive.com", "onetag-sys.com",
        "id5-sync.com", "doubleverify.com",
        "coinimp.com", "webminepool.com", "minero.cc", "mineralt.io",
        "monerominer.rocks", "ads.vk.com", "ad.mail.ru",
        "pixel.quora.com", "qevents.quora.com", "px.arvcs.tumblr.com",
        "widgets.pinterest.com", "advertising.apple.com", "xp.apple.com",
        "metrics.mzstatic.com"
    )

    private val pathKeywords = listOf(
        "/ads/", "/adservice", "/pagead/", "/gtag/js", "google-analytics",
        "/analytics.js", "/beacon", "/pixel.gif", "prebid", "adsbygoogle"
    )

    val listUrls = listOf(
        "https://easylist.to/easylist/easylist.txt",
        "https://easylist.to/easylist/easyprivacy.txt",
        "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts",
        "https://filters.adtidy.org/extension/ublock/filters/3.txt",
        "https://filters.adtidy.org/extension/ublock/filters/11.txt",
        "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/filters.txt",
        "https://filters.adtidy.org/extension/ublock/filters/2.txt"
    )

    /** Player/CDN hosts that must never be host-blocked (Mariner). */
    private val neverBlockHosts = setOf(
        "googlevideo.com", "googleapis.com", "youtube.com", "ytimg.com",
        "ggpht.com", "jwplatform.com", "jwpcdn.com", "jwplayer.com",
        "vimeo.com", "vimeocdn.com", "akamaihd.net", "dailymotion.com",
        "twitch.tv"
    )

    private val TYPE_OPTIONS = setOf(
        "script", "image", "stylesheet", "css", "font", "media",
        "object", "object-subrequest", "xmlhttprequest", "xhr",
        "subdocument", "frame", "document", "popup", "websocket",
        "webrtc", "other", "ping", "beacon", "manifest", "empty", "mp4"
    )

    class ScopedRule(
        val pathPrefix: String?,
        val domains: Set<String>?,
        val notDomains: Set<String>?,
        val exception: Boolean,
        val thirdParty: Boolean? = null,
        val important: Boolean = false,
        val category: Int = CAT_ADS,
        val document: Boolean = false
    )

    internal class FilterData {
        val hosts = HashSet<String>()
        val exceptions = HashSet<String>()
        val scoped = HashMap<String, MutableList<ScopedRule>>()
        val generic = LinkedHashSet<String>()
        val perDomain = HashMap<String, MutableList<String>>()
        val docHosts = HashSet<String>()
        val trackerHosts = HashSet<String>()
        val excGeneric = HashSet<String>()
        val excDomain = HashMap<String, MutableList<String>>()
        val hideOff = HashSet<String>()

        fun isEmpty() = hosts.isEmpty() && scoped.isEmpty() && generic.isEmpty()
        fun total() = hosts.size + scoped.values.sumOf { it.size } + generic.size

        fun merge(other: FilterData) {
            hosts.addAll(other.hosts)
            exceptions.addAll(other.exceptions)
            other.scoped.forEach { (k, v) -> scoped.getOrPut(k) { mutableListOf() }.addAll(v) }
            generic.addAll(other.generic)
            other.perDomain.forEach { (k, v) -> perDomain.getOrPut(k) { mutableListOf() }.addAll(v) }
            docHosts.addAll(other.docHosts)
            trackerHosts.addAll(other.trackerHosts)
            excGeneric.addAll(other.excGeneric)
            other.excDomain.forEach { (k, v) -> excDomain.getOrPut(k) { mutableListOf() }.addAll(v) }
            hideOff.addAll(other.hideOff)
        }
    }

    @Volatile private var listHosts: Set<String> = emptySet()
    @Volatile private var trackerHosts: Set<String> = emptySet()
    @Volatile private var exceptionHosts: Set<String> = emptySet()
    @Volatile private var scopedRules: Map<String, List<ScopedRule>> = emptyMap()
    @Volatile private var genericSelectors: List<String> = emptyList()
    @Volatile private var domainSelectors: Map<String, List<String>> = emptyMap()
    @Volatile private var docHosts: Set<String> = emptySet()
    @Volatile private var cosmeticExcGeneric: Set<String> = emptySet()
    @Volatile private var cosmeticExcDomain: Map<String, List<String>> = emptyMap()
    @Volatile private var genericHideOffHosts: Set<String> = emptySet()

    @Volatile var documentStartScript: String = ""
        private set
    @Volatile private var cssCache: Pair<String, String>? = null

    @Volatile var ruleCount: Int = 0
        private set
    @Volatile var updatedAt: Long = 0L
        private set
    @Volatile var updating: Boolean = false
        private set
    @Volatile var lastError: String? = null
        private set

    private val blockedCountAtomic = AtomicLong(0)
    val blockedCount: Long get() = blockedCountAtomic.get()
    fun noteBlocked() { blockedCountAtomic.incrementAndGet() }

    private fun dir(context: Context) = File(context.filesDir, "filters").apply { mkdirs() }

    /** Load cached lists; refresh in the background if stale (>24 hours). */
    fun initAsync(context: Context) {
        val appCtx = context.applicationContext
        Thread {
            loadFromDisk(appCtx)
            val missing = listUrls.indices.any { !File(dir(appCtx), "list$it.txt").exists() }
            val stale = updatedAt == 0L || System.currentTimeMillis() - updatedAt > 24L * 3600 * 1000
            if (stale || missing) refresh(appCtx)
        }.start()
    }

    fun refreshAsync(context: Context) {
        val appCtx = context.applicationContext
        Thread { refresh(appCtx) }.start()
    }

    internal fun categoryForList(i: Int): Int = when (i) {
        1, 3 -> CAT_TRACKER
        else -> CAT_ADS
    }

    internal fun parseInto(i: Int, text: String, data: FilterData, category: Int = categoryForList(i)) {
        if (i == 2) parseHosts(text, data.hosts)
        else parse(text, data, category)
    }

    internal fun applyData(data: FilterData, newest: Long) {
        listHosts = data.hosts
        trackerHosts = data.trackerHosts
        exceptionHosts = data.exceptions
        scopedRules = data.scoped
        genericSelectors = data.generic.toList()
        domainSelectors = data.perDomain
        docHosts = data.docHosts
        cosmeticExcGeneric = data.excGeneric
        cosmeticExcDomain = data.excDomain
        genericHideOffHosts = data.hideOff
        cssCache = null
        documentStartScript = buildDocumentStartScript(
            chunkCosmeticSelectors(genericSelectors.filter { it !in cosmeticExcGeneric }, budget = 150_000),
            genericHideOffHosts
        )
        ruleCount = data.hosts.size + data.scoped.values.sumOf { it.size }
        updatedAt = newest
    }

    private fun loadFromDisk(context: Context) {
        val d = dir(context)
        val total = FilterData()
        var newest = 0L
        listUrls.forEachIndexed { i, _ ->
            val f = File(d, "list$i.txt")
            val bak = File(d, "list$i.txt.bak")
            val srcFile = when {
                f.exists() -> f
                bak.exists() -> bak
                else -> null
            } ?: return@forEachIndexed
            newest = maxOf(newest, srcFile.lastModified())
            val one = FilterData()
            runCatching { parseInto(i, srcFile.readText(), one) }
                .onFailure { lastError = it.javaClass.simpleName }
            if (one.isEmpty() && srcFile == f && bak.exists()) {
                val fromBak = FilterData()
                runCatching { parseInto(i, bak.readText(), fromBak) }
                    .onFailure { lastError = it.javaClass.simpleName }
                if (!fromBak.isEmpty()) {
                    total.merge(fromBak)
                    return@forEachIndexed
                }
            }
            total.merge(one)
        }
        if (total.hosts.isNotEmpty() && listHosts.isNotEmpty() &&
            total.hosts.size < listHosts.size / 4
        ) return
        if (total.hosts.isNotEmpty()) applyData(total, newest)
    }

    private fun refresh(context: Context) {
        if (updating) return
        updating = true
        try {
            val d = dir(context)
            var ok = false
            listUrls.forEachIndexed { i, url ->
                runCatching {
                    val conn = URL(url).openConnection() as HttpURLConnection
                    conn.connectTimeout = 20_000
                    conn.readTimeout = 30_000
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) NotscapeTV")
                    val text = conn.inputStream.bufferedReader().readText()
                    conn.disconnect()
                    val sane = text.length > 50_000 && when (i) {
                        2 -> text.contains("0.0.0.0")
                        else -> text.contains("||") && !text.trimStart().startsWith("<")
                    }
                    if (!sane) return@runCatching
                    val candidate = FilterData()
                    parseInto(i, text, candidate)
                    if (candidate.isEmpty()) return@runCatching
                    val target = File(d, "list$i.txt")
                    if (target.exists()) {
                        val current = FilterData()
                        runCatching { parseInto(i, target.readText(), current) }
                            .onFailure { lastError = it.javaClass.simpleName }
                        if (!current.isEmpty() && candidate.total() < current.total() / 2) {
                            return@runCatching
                        }
                        runCatching { target.copyTo(File(d, "list$i.txt.bak"), overwrite = true) }
                    }
                    val tmp = File(d, "list$i.txt.tmp")
                    tmp.writeText(text)
                    if (!tmp.renameTo(target)) {
                        tmp.copyTo(target, overwrite = true)
                        tmp.delete()
                    }
                    ok = true
                }.onFailure { lastError = it.javaClass.simpleName }
            }
            if (ok) loadFromDisk(context)
        } finally {
            updating = false
        }
    }

    // ---- Parsing (ABP / AdGuard syntax, Mariner's rules) ----

    internal fun parse(text: String, data: FilterData, category: Int) {
        // Pass 1: collect $badfilter signatures so the matching rules
        // are removed instead of applied.
        val badSignatures = HashSet<String>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.contains("\$badfilter")) {
                ruleSignature(line)?.let { badSignatures.add(it) }
            }
        }
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("!") || line.startsWith("[")) continue
            // Cosmetic rules: domain##selector, ##selector (generic),
            // domain#@#selector (exception).
            if (line.contains("#@#")) {
                val idx = line.indexOf("#@#")
                val domainsPart = line.substring(0, idx)
                val selector = line.substring(idx + 3).trim()
                if (selector.isEmpty() || !isCssSelector(selector)) continue
                if (domainsPart.isEmpty()) {
                    data.excGeneric.add(selector)
                } else {
                    for (dd in domainsPart.split(",")) {
                        val d = dd.trim().lowercase()
                        if (d.isNotEmpty() && !d.startsWith("~") && d.contains('.')) {
                            data.excDomain.getOrPut(d) { mutableListOf() }.add(selector)
                        }
                    }
                }
                continue
            }
            if (line.contains("##")) {
                val idx = line.indexOf("##")
                val domainsPart = line.substring(0, idx)
                val selector = line.substring(idx + 2).trim()
                if (selector.isEmpty() || !isCssSelector(selector)) continue
                if (domainsPart.isEmpty()) {
                    data.generic.add(selector)
                } else {
                    val positives = mutableListOf<String>()
                    val negatives = mutableListOf<String>()
                    for (dd in domainsPart.split(",")) {
                        val d = dd.trim().lowercase()
                        if (d.startsWith("~")) negatives.add(d.substring(1))
                        else if (d.isNotEmpty() && d.contains('.')) positives.add(d)
                    }
                    when {
                        positives.isNotEmpty() -> {
                            for (d in positives) {
                                val list = data.perDomain.getOrPut(d) { mutableListOf() }
                                if (selector !in list) list.add(selector)
                            }
                            for (d in negatives) {
                                data.excDomain.getOrPut(d) { mutableListOf() }.add(selector)
                            }
                        }
                        negatives.isNotEmpty() -> {
                            data.generic.add(selector)
                            for (d in negatives) {
                                data.excDomain.getOrPut(d) { mutableListOf() }.add(selector)
                            }
                        }
                        else -> data.generic.add(selector)
                    }
                }
                continue
            }
            // Other #-anchored procedural rules are not plain CSS: dropped.
            if (line.contains("#\$#") || line.contains("#?#")) continue
            var work = line
            if (work.startsWith("@@") && !work.startsWith("@@||")) {
                if (work.contains("generichide") || work.contains("ghide")) {
                    val dm = work.indexOf("domain=")
                    if (dm >= 0) {
                        val rest = work.substring(dm + 7)
                        val end = rest.indexOf('$')
                        val domPart = if (end >= 0) rest.substring(0, end) else rest
                        for (dd in domPart.split('|')) {
                            val d = dd.trim().lowercase()
                            if (d.isNotEmpty() && !d.startsWith("~") && d.contains('.')) {
                                data.hideOff.add(d)
                            }
                        }
                    }
                    continue
                }
                work = bareExceptionToDouble(work) ?: continue
            }
            val exception = work.startsWith("@@")
            val body0 = when {
                work.startsWith("@@||") -> work.substring(4)
                work.startsWith("||") -> work.substring(2)
                work.startsWith("|") -> singlePipeBody(work.substring(1)) ?: continue
                else -> continue
            }
            val dollar = body0.indexOf('$')
            val bodyRaw = if (dollar >= 0) body0.substring(0, dollar) else body0
            val body = if (bodyRaw.startsWith("*.")) bodyRaw.substring(2) else bodyRaw
            val options = if (dollar >= 0) body0.substring(dollar + 1) else ""
            if (options.split(',').any { it.trim().lowercase() == "badfilter" }) continue
            if (badSignatures.isNotEmpty() && ruleSignature(work) in badSignatures) continue
            var domains: Set<String>? = null
            var notDomains: Set<String>? = null
            var thirdParty: Boolean? = null
            var important = false
            var hideModifier = false
            var docType = false
            var otherType = false
            var optionsOk = true
            if (options.isNotEmpty()) {
                for (opt in options.split(',')) {
                    val o = opt.trim().lowercase()
                    when {
                        o == "third-party" || o == "3p" || o == "~1p" -> thirdParty = true
                        o == "~third-party" || o == "~3p" || o == "1p" -> thirdParty = false
                        o == "important" -> important = true
                        o in TYPE_OPTIONS || (o.startsWith("~") && o.substring(1) in TYPE_OPTIONS) -> {
                            val base = o.removePrefix("~")
                            if (base == "document" || base == "popup") docType = true
                            else otherType = true
                        }
                        o == "generichide" || o == "ghide" || o == "elemhide" -> hideModifier = true
                        o == "match-case" || o == "all" -> Unit
                        o.startsWith("domain=") -> {
                            val pos = HashSet<String>()
                            val neg = HashSet<String>()
                            for (dd in o.substring(7).split('|')) {
                                val d = dd.trim()
                                if (d.startsWith("~")) neg.add(d.substring(1))
                                else if (d.isNotEmpty()) pos.add(d)
                            }
                            domains = pos.ifEmpty { null }
                            notDomains = neg.ifEmpty { null }
                        }
                        else -> { optionsOk = false; break }
                    }
                }
            }
            if (!optionsOk) continue
            if (exception && hideModifier && !otherType && thirdParty == null && !important) {
                val targets = domains
                if (targets != null) {
                    data.hideOff.addAll(targets)
                } else {
                    var he = body.length
                    for (i in body.indices) {
                        val c = body[i]
                        if (c == '/' || c == '^' || c == '|' || c == '*') { he = i; break }
                    }
                    val h = body.substring(0, he).trim().lowercase()
                    if (h.contains('.')) data.hideOff.add(h)
                }
                continue
            }
            var hostEnd = body.length
            for (i in body.indices) {
                val c = body[i]
                if (c == '/' || c == '^' || c == '|' || c == '*') { hostEnd = i; break }
            }
            val host = body.substring(0, hostEnd).trim().lowercase()
            if (!host.contains('.') ||
                !host.all { it.isLetterOrDigit() || it == '.' || it == '-' }
            ) continue
            val rest = body.substring(hostEnd)
            val isScoped = domains != null || notDomains != null || thirdParty != null || important
            if (rest.isEmpty() || rest == "^") {
                if (isScoped) {
                    data.scoped.getOrPut(host) { mutableListOf() }
                        .add(ScopedRule(null, domains, notDomains, exception, thirdParty, important, category = category, document = docType))
                } else {
                    (if (exception) data.exceptions else data.hosts).add(host)
                    if (!exception && category == CAT_TRACKER) data.trackerHosts.add(host)
                    if (!exception && docType) data.docHosts.add(host)
                }
                continue
            }
            if (rest.startsWith("/") || rest.startsWith("^/")) {
                var path = if (rest.startsWith("^")) rest.substring(1) else rest
                val star = path.indexOf('*')
                if (star >= 0) path = path.substring(0, star)
                val caret = path.indexOf('^')
                if (caret >= 0) path = path.substring(0, caret)
                val pipe = path.indexOf('|')
                if (pipe >= 0) path = path.substring(0, pipe)
                if (path.length < 2) continue
                data.scoped.getOrPut(host) { mutableListOf() }
                    .add(ScopedRule(path.lowercase(), domains, notDomains, exception, thirdParty, important, category = category, document = docType))
                continue
            }
        }
    }

    private fun singlePipeBody(rest: String): String? {
        if (rest.isEmpty() || rest.startsWith("*")) return null
        val scheme = rest.indexOf("://")
        val body = if (scheme >= 0) rest.substring(scheme + 3) else rest
        if (body.isEmpty() || body.startsWith("*")) return null
        return body
    }

    private fun bareToDoublePipe(line: String): String? {
        val dollar = line.indexOf('$')
        val body = if (dollar >= 0) line.substring(0, dollar) else line
        val host = body.trim().lowercase()
        if (!host.contains('.') ||
            !host.all { it.isLetterOrDigit() || it == '.' || it == '-' }
        ) return null
        return if (dollar >= 0) "||$host\$${line.substring(dollar + 1)}" else "||$host"
    }

    private fun bareExceptionToDouble(line: String): String? {
        val rest = line.substring(2)
        if (rest.startsWith("|")) {
            val body = singlePipeBody(rest.substring(1)) ?: return null
            return "@@||$body"
        }
        val dollar = rest.indexOf('$')
        val body = if (dollar >= 0) rest.substring(0, dollar) else rest
        val host = body.trim().lowercase()
        if (!host.contains('.') ||
            !host.all { it.isLetterOrDigit() || it == '.' || it == '-' }
        ) return null
        return if (dollar >= 0) "@@||$host\$${rest.substring(dollar + 1)}" else "@@||$host"
    }

    private fun ruleSignature(line: String): String? {
        val exception = line.startsWith("@@")
        val body0 = when {
            line.startsWith("@@||") -> line.substring(4)
            line.startsWith("||") -> line.substring(2)
            else -> return null
        }
        val dollar = body0.indexOf('$')
        val body = if (dollar >= 0) body0.substring(0, dollar) else body0
        val options = if (dollar >= 0) body0.substring(dollar + 1) else ""
        val opts = options.split(',').map { it.trim().lowercase() }
            .filter { it.isNotEmpty() && it != "badfilter" }.sorted()
        return (if (exception) "@@" else "") + body + "$" + opts.joinToString(",")
    }

    private fun isCssSelector(sel: String): Boolean {
        if (sel.isEmpty() || sel.length > 200) return false
        val banned = listOf(
            ":contains", ":has-text", ":xpath", ":matches-css",
            ":matches-attr", ":style(", ":remove(", ":upward",
            ":-abp-", ":if(", ":min-text-length", ":watch-attr", "::"
        )
        if (banned.any { sel.contains(it) }) return false
        return sel.all { it.isLetterOrDigit() || it in "#.[]=\\\"'^$*|_- >+~,()%!:" }
    }

    internal fun parseHosts(text: String, hosts: MutableSet<String>) {
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val parts = line.split(Regex("\\s+"))
            if (parts.size < 2) continue
            if (parts[0] != "0.0.0.0" && parts[0] != "127.0.0.1") continue
            val host = parts[1].lowercase()
            if (host.contains('.') && host.any { it.isLetter() } &&
                host.all { it.isLetterOrDigit() || it == '.' || it == '-' }
            ) {
                hosts.add(host)
            }
        }
    }

    // ---- Matching ----

    private fun isThirdParty(reqHost: String, pageHost: String): Boolean {
        if (pageHost.isEmpty() || reqHost == pageHost) return false
        return !Hosts.sameSite(reqHost, pageHost)
    }

    private fun scopedRuleMatch(
        reqHost: String,
        path: String,
        pageHost: String,
        wantException: Boolean,
        onlyImportant: Boolean = false,
        onlyDocument: Boolean = false
    ): ScopedRule? {
        for (h in Hosts.hostChain(reqHost)) {
            val rules = scopedRules[h] ?: continue
            for (r in rules) {
                if (r.exception != wantException) continue
                if (onlyImportant && !r.important) continue
                if (onlyDocument && !r.document) continue
                if (r.domains != null &&
                    (pageHost.isEmpty() || !Hosts.matches(r.domains, pageHost))
                ) continue
                if (r.notDomains != null && pageHost.isNotEmpty() &&
                    Hosts.matches(r.notDomains, pageHost)
                ) continue
                if (r.thirdParty != null &&
                    r.thirdParty != isThirdParty(reqHost, pageHost)
                ) continue
                if (r.pathPrefix != null && !path.startsWith(r.pathPrefix)) continue
                return r
            }
        }
        return null
    }

    fun isBlocked(
        url: String,
        isForMainFrame: Boolean,
        blockAds: Boolean,
        blockTrackers: Boolean,
        pageHost: String = ""
    ): Boolean = blockCategory(url, isForMainFrame, blockAds, blockTrackers, pageHost) != null

    fun blockCategory(
        url: String,
        isForMainFrame: Boolean,
        blockAds: Boolean,
        blockTrackers: Boolean,
        pageHost: String = ""
    ): Int? {
        if (isForMainFrame) return null
        if (!blockAds && !blockTrackers) return null
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val host = (uri.host ?: "").lowercase()
        if (host.isEmpty()) return null
        if (Hosts.matches(neverBlockHosts, host)) return null
        val path = (uri.path ?: "").lowercase()
        val page = pageHost.lowercase()
        scopedRuleMatch(host, path, page, wantException = false, onlyImportant = true)
            ?.let { return it.category }
        if (Hosts.matches(exceptionHosts, host)) return null
        if (scopedRuleMatch(host, path, page, wantException = true) != null) return null
        if (blockTrackers && Hosts.matches(builtinHosts, host)) return CAT_TRACKER
        if ((blockAds || blockTrackers) && Hosts.matches(listHosts, host)) {
            return if (Hosts.matches(trackerHosts, host)) CAT_TRACKER else CAT_ADS
        }
        scopedRuleMatch(host, path, page, wantException = false)
            ?.let { return it.category }
        if (blockAds) {
            val s = url.lowercase()
            if (pathKeywords.any { s.contains(it) }) return CAT_ADS
        }
        return null
    }

    fun isDocumentHost(host: String): Boolean {
        val h = host.lowercase()
        if (h.isEmpty()) return false
        if (Hosts.matches(neverBlockHosts, h)) return false
        if (Hosts.matches(exceptionHosts, h)) return false
        return Hosts.matches(docHosts, h)
    }

    fun isDocumentBlocked(url: String, pageHost: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val host = (uri.host ?: "").lowercase()
        if (host.isEmpty()) return false
        if (Hosts.matches(neverBlockHosts, host)) return false
        val path = (uri.path ?: "").lowercase()
        val page = pageHost.lowercase()
        scopedRuleMatch(host, path, page, wantException = false, onlyImportant = true, onlyDocument = true)
            ?.let { return true }
        if (Hosts.matches(exceptionHosts, host)) return false
        if (scopedRuleMatch(host, path, page, wantException = true, onlyDocument = true) != null) {
            return false
        }
        if (isDocumentHost(host)) return true
        return scopedRuleMatch(host, path, page, wantException = false, onlyDocument = true) != null
    }

    // ---- Cosmetic hiding ----

    internal fun chunkCosmeticSelectors(sels: Collection<String>, budget: Int): String {
        if (sels.isEmpty()) return ""
        val suffix = "{display:none!important}"
        val sb = StringBuilder()
        var chunk = StringBuilder()
        var inChunk = 0
        fun flushChunk() {
            if (chunk.isNotEmpty()) {
                sb.append(chunk).append(suffix)
                chunk = StringBuilder()
                inChunk = 0
            }
        }
        for (sel in sels) {
            val extra = if (inChunk == 0) sel.length else sel.length + 1
            if (sb.length + chunk.length + extra + suffix.length > budget) break
            if (inChunk > 0) chunk.append(',')
            chunk.append(sel)
            inChunk++
            if (inChunk >= 25) flushChunk()
        }
        flushChunk()
        return sb.toString()
    }

    internal fun buildDocumentStartScript(css: String, offHosts: Set<String>): String {
        if (css.isEmpty()) return ""
        val hosts = offHosts.joinToString(",") { h ->
            "\"" + h.filter { it != '"' && it != '\\' } + "\""
        }
        val esc = css
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("</", "<\\/")
        return "(function(){try{var OFF=[HOSTS];var h=location.hostname;var off=false;while(h){if(OFF.indexOf(h)>=0){off=true;break;}var d=h.indexOf('.');if(d<0)break;h=h.substring(d+1);}if(off)return;var s=document.createElement('style');s.textContent='CSS';(document.documentElement||document).appendChild(s);}catch(e){}})();"
            .replace("HOSTS", hosts)
            .replace("CSS", esc)
    }

    internal fun orderedSelectors(
        host: String,
        generic: List<String>,
        domain: Map<String, List<String>>,
        excGeneric: Set<String>,
        excDomain: Map<String, List<String>>,
        hideOff: Set<String>
    ): LinkedHashSet<String> {
        val sels = LinkedHashSet<String>()
        for (h in Hosts.hostChain(host)) domain[h]?.let { sels.addAll(it) }
        if (!Hosts.matches(hideOff, host)) sels.addAll(generic)
        sels.removeAll(excGeneric)
        for (h in Hosts.hostChain(host)) excDomain[h]?.let { sels.removeAll(it) }
        return sels
    }

    /** CSS that hides ad containers on [pageHost]; "" when nothing applies. */
    fun cosmeticCss(pageHost: String): String {
        val host = pageHost.lowercase()
        if (host.isEmpty()) return ""
        cssCache?.let { if (it.first == host) return it.second }
        val sels = orderedSelectors(
            host, genericSelectors, domainSelectors,
            cosmeticExcGeneric, cosmeticExcDomain, genericHideOffHosts
        )
        if (sels.isEmpty()) return ""
        val suffix = "{display:none!important}"
        val budget = 200_000
        val sb = StringBuilder()
        var chunk = StringBuilder()
        var inChunk = 0
        fun flushChunk() {
            if (chunk.isNotEmpty()) {
                sb.append(chunk).append(suffix)
                chunk = StringBuilder()
                inChunk = 0
            }
        }
        for (sel in sels) {
            val extra = if (inChunk == 0) sel.length else sel.length + 1
            if (sb.length + chunk.length + extra + suffix.length > budget) break
            if (inChunk > 0) chunk.append(',')
            chunk.append(sel)
            inChunk++
            if (inChunk >= 25) flushChunk()
        }
        flushChunk()
        if (sb.isEmpty()) return ""
        val css = sb.toString()
        cssCache = host to css
        return css
    }

    fun emptyResponse(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
}
