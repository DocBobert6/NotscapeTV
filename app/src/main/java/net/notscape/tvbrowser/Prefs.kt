package net.notscape.tvbrowser

import android.content.Context
import android.content.SharedPreferences

/**
 * Tiny SharedPreferences helper. All v1 settings live in one file so the
 * settings menu stays small: adblock toggle, popup per-site sets, weather
 * place, and the blocked-this-session count (session-only in memory).
 */
class Prefs(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("notscape_tv", Context.MODE_PRIVATE)

    var blockAds: Boolean
        get() = prefs.getBoolean("block_ads", true)
        set(v) = prefs.edit().putBoolean("block_ads", v).apply()

    var popupStrict: Boolean
        get() = prefs.getBoolean("popup_strict", true)
        set(v) = prefs.edit().putBoolean("popup_strict", v).apply()

    private fun siteSet(key: String): MutableSet<String> =
        prefs.getStringSet(key, emptySet())?.toMutableSet() ?: mutableSetOf()

    fun siteAllowsPopups(host: String): Boolean = siteSet("popup_allow").contains(host.lowercase())
    fun siteDeniesPopups(host: String): Boolean = siteSet("popup_deny").contains(host.lowercase())

    fun setSitePopup(host: String, allow: Boolean) {
        val h = host.lowercase()
        val allowSet = siteSet("popup_allow")
        val denySet = siteSet("popup_deny")
        if (allow) { allowSet.add(h); denySet.remove(h) } else { denySet.add(h); allowSet.remove(h) }
        prefs.edit().putStringSet("popup_allow", allowSet).putStringSet("popup_deny", denySet).apply()
    }

    fun clearSitePopup(host: String) {
        val h = host.lowercase()
        val allowSet = siteSet("popup_allow"); allowSet.remove(h)
        val denySet = siteSet("popup_deny"); denySet.remove(h)
        prefs.edit().putStringSet("popup_allow", allowSet).putStringSet("popup_deny", denySet).apply()
    }

    var weatherPlaceName: String?
        get() = prefs.getString("weather_place_name", null)
        set(v) = prefs.edit().putString("weather_place_name", v).apply()
    var weatherLat: Float
        get() = prefs.getFloat("weather_lat", Float.NaN)
        set(v) = prefs.edit().putFloat("weather_lat", v).apply()
    var weatherLon: Float
        get() = prefs.getFloat("weather_lon", Float.NaN)
        set(v) = prefs.edit().putFloat("weather_lon", v).apply()

    fun hasWeatherPlace(): Boolean =
        weatherPlaceName != null && !weatherLat.isNaN() && !weatherLon.isNaN()
}
