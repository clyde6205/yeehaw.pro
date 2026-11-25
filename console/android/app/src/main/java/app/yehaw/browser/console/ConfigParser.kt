package app.yehaw.browser.console

import org.json.JSONArray
import org.json.JSONObject

data class SpeedDialEntry(val title: String, val url: String, val sponsored: Boolean)

data class RemoteConfig(
    val version: Int,
    val defaultSearchUrl: String,
    val affiliateTag: String,
    val speedDial: List<SpeedDialEntry>,
    val blacklistedDomains: Set<String>,
    val admobEnabled: Boolean,
    val ttlSeconds: Int,
) {
    companion object {
        /** Used until the first verified config arrives. Ads stay OFF by default. */
        val DEFAULT = RemoteConfig(0, "https://duckduckgo.com/?q={query}", "", emptyList(), emptySet(), false, 3600)
    }
}

/** Parses and validates a config body. Returns null if anything is off, so a bad config can never half-apply. */
object ConfigParser {
    fun parse(text: String): RemoteConfig? {
        return try {
            val o = JSONObject(text)
            val template = o.getString("default_search_url")
            if (!ConfigRules.isValidSearchTemplate(template)) return null
            val tag = o.optString("affiliate_tag", "")
            if (tag.length > 128) return null
            val dial = ArrayList<SpeedDialEntry>()
            val da = o.optJSONArray("speed_dial") ?: JSONArray()
            if (da.length() > 12) return null
            for (i in 0 until da.length()) {
                val e = da.getJSONObject(i)
                val title = e.getString("title").trim()
                val url = e.getString("url")
                if (title.isEmpty() || title.length > 24 || !ConfigRules.isValidHttpsUrl(url)) return null
                dial.add(SpeedDialEntry(title, url, e.optBoolean("sponsored", false)))
            }
            val blocked = HashSet<String>()
            val ba = o.optJSONArray("blacklisted_domains") ?: JSONArray()
            if (ba.length() > 5000) return null
            for (i in 0 until ba.length()) blocked.add(ConfigRules.normalizeDomain(ba.getString(i)) ?: return null)
            RemoteConfig(
                version = o.getInt("version"), defaultSearchUrl = template, affiliateTag = tag, speedDial = dial,
                blacklistedDomains = blocked, admobEnabled = o.getBoolean("admob_enabled"),
                ttlSeconds = o.optInt("ttl_seconds", 3600).coerceIn(300, 86_400),
            )
        } catch (_: Throwable) {
            null
        }
    }
}
