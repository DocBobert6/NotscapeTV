package net.notscape.tvbrowser

/**
 * Host comparisons on registrable-domain (eTLD+1) boundaries, ported from
 * Notscape Mariner's Hosts.kt. Guava is deliberately not a dependency
 * here, so the multi-label suffix list below plus a last-two-labels rule
 * is the whole implementation (Mariner's fallback path).
 */
object Hosts {

    private val MULTI_LABEL_SUFFIXES = setOf(
        "ac.uk", "co.uk", "gov.uk", "me.uk", "net.uk", "nhs.uk", "org.uk", "police.uk", "sch.uk",
        "asn.au", "com.au", "edu.au", "gov.au", "id.au", "net.au", "org.au",
        "ac.nz", "co.nz", "geek.nz", "govt.nz", "net.nz", "org.nz", "school.nz",
        "ac.jp", "co.jp", "go.jp", "ne.jp", "or.jp",
        "ac.kr", "co.kr", "go.kr", "ne.kr", "or.kr",
        "ac.in", "co.in", "firm.in", "gen.in", "gov.in", "ind.in", "net.in", "org.in",
        "com.br", "gov.br", "net.br", "org.br",
        "com.ar", "com.co", "com.mx", "gob.mx", "com.pe", "com.ve",
        "com.cn", "gov.cn", "net.cn", "org.cn",
        "com.tw", "gov.tw", "org.tw",
        "com.hk", "gov.hk", "org.hk",
        "com.sg", "gov.sg",
        "ac.za", "co.za", "gov.za", "org.za",
        "ac.il", "co.il", "gov.il", "org.il",
        "com.tr", "gov.tr", "org.tr",
        "com.pl", "com.ua", "com.vn", "com.ph", "com.my", "com.pk", "com.ng", "com.eg",
        "com.sa", "com.ae", "com.qa", "com.bd", "com.np", "com.lk", "com.kh", "com.mm"
    )

    private val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")

    fun registrableDomain(host: String): String {
        val h = host.lowercase().trimEnd('.')
        if (IPV4.matches(h)) return h
        val labels = h.split('.').filter { it.isNotEmpty() }
        if (labels.size <= 2) return labels.joinToString(".")
        val lastTwo = labels.takeLast(2).joinToString(".")
        return if (lastTwo in MULTI_LABEL_SUFFIXES) labels.takeLast(3).joinToString(".") else lastTwo
    }

    fun sameSite(a: String, b: String): Boolean {
        val ha = a.lowercase().trimEnd('.')
        val hb = b.lowercase().trimEnd('.')
        if (ha == hb) return true
        if (IPV4.matches(ha) || IPV4.matches(hb)) return false
        return registrableDomain(ha) == registrableDomain(hb)
    }

    /** True if [host] or any parent domain is in [set]. */
    fun matches(set: Set<String>, host: String): Boolean {
        var h = host.lowercase()
        while (true) {
            if (set.contains(h)) return true
            val dot = h.indexOf('.')
            if (dot < 0) return false
            h = h.substring(dot + 1)
            if (!h.contains('.')) return set.contains(h)
        }
    }

    /** [host] and each parent domain, longest first. */
    fun hostChain(host: String): List<String> {
        val out = ArrayList<String>()
        var h = host.lowercase()
        while (true) {
            out.add(h)
            val dot = h.indexOf('.')
            if (dot < 0) break
            h = h.substring(dot + 1)
            if (!h.contains('.')) { out.add(h); break }
        }
        return out
    }
}
