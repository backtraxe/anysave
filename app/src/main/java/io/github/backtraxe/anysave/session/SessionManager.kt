package io.github.backtraxe.anysave.session

import android.webkit.CookieManager
import android.webkit.WebStorage

/**
 * Login/session management (M9).
 *
 * AnySave never exports cookies. Login happens in the app's own WebView (the
 * site's desktop login page, scanned with a phone); the session lives in the
 * system WebView cookie store under the app's private data. This helper only
 * powers the "open login page" shortcuts and a full log-out.
 */
object SessionManager {

    data class LoginSite(
        val id: String,
        val label: String,
        val url: String,
    )

    /** Domains each importable site may receive cookies for. */
    private data class CookieSite(
        val domains: List<String>,
        val defaultDomain: String,
    )

    private val COOKIE_SITES = mapOf(
        "douyin" to CookieSite(listOf("douyin.com"), ".douyin.com"),
        "bilibili" to CookieSite(listOf("bilibili.com"), ".bilibili.com"),
        "xiaohongshu" to CookieSite(listOf("xiaohongshu.com"), ".xiaohongshu.com"),
    )

    private data class ClearSite(
        val domains: List<String>,
        val urls: List<String>,
        val origins: List<String>,
        val criticalCookies: List<String>,
    )

    private val CLEAR_SITES = mapOf(
        "douyin" to ClearSite(
            domains = listOf(".douyin.com"),
            urls = listOf("https://www.douyin.com", "https://m.douyin.com"),
            origins = listOf("https://www.douyin.com", "https://m.douyin.com"),
            criticalCookies = listOf(
                "sessionid", "sessionid_ss", "sid_tt", "sid_guard", "sid_ucp_v1",
                "uid_tt", "uid_tt_ss", "ttwid", "UIFID", "UIFID_TEMP", "n_mh",
                "passport_csrf_token", "passport_csrf_token_default", "odin_tt",
            ),
        ),
        "bilibili" to ClearSite(
            domains = listOf(".bilibili.com"),
            urls = listOf("https://www.bilibili.com", "https://api.bilibili.com"),
            origins = listOf("https://www.bilibili.com", "https://m.bilibili.com"),
            criticalCookies = listOf(
                "SESSDATA", "bili_jct", "DedeUserID", "DedeUserID__ckMd5",
                "bili_ticket", "bili_ticket_expires", "buvid3", "buvid4", "sid", "b_nut",
            ),
        ),
        "xiaohongshu" to ClearSite(
            domains = listOf(".xiaohongshu.com"),
            urls = listOf("https://www.xiaohongshu.com"),
            origins = listOf("https://www.xiaohongshu.com"),
            criticalCookies = listOf(
                "web_session", "id_token", "last_web_session", "a1", "webId",
                "xsecappid", "websectiga", "sec_poison_id",
            ),
        ),
    )

    /**
     * Logs one site out: expires its cookies (session-critical names plus every
     * visible cookie) and clears that origin's web storage (M13). Other sites
     * are untouched.
     *
     * @return number of cookie names expired (best effort).
     */
    fun clearSite(siteId: String): Int {
        val site = CLEAR_SITES[siteId]
            ?: throw IllegalArgumentException("unknown cookie site: $siteId")
        val cookieManager = CookieManager.getInstance()
        val names = LinkedHashSet<String>(site.criticalCookies)
        for (url in site.urls) {
            cookieManager.getCookie(url)
                ?.split(';')
                ?.forEach { pair ->
                    val name = pair.substringBefore('=').trim()
                    if (name.isNotEmpty()) names += name
                }
        }
        for (name in names) {
            for (domain in site.domains) {
                val host = domain.removePrefix(".")
                val expired = "$name=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; " +
                    "Domain=$domain; Path=/"
                cookieManager.setCookie("https://$host/", expired)
            }
        }
        cookieManager.flush()
        for (origin in site.origins) {
            runCatching { WebStorage.getInstance().deleteOrigin(origin) }
        }
        return names.size
    }

    /**
     * Writes browser-exported cookies into the app's WebView session (M12).
     *
     * @return number of cookies written (0 when nothing matched the site).
     * @throws IllegalArgumentException when [text] is empty/unparseable.
     */
    fun importCookies(siteId: String, text: String): Int {
        val site = COOKIE_SITES[siteId]
            ?: throw IllegalArgumentException("unknown cookie site: $siteId")
        val cookies = CookieImport.parse(text)
        val cookieManager = CookieManager.getInstance()
        var imported = 0
        for (cookie in cookies) {
            val rawDomain = cookie.domain?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
                ?: site.defaultDomain
            val host = rawDomain.removePrefix(".")
            if (site.domains.none { host == it || host.endsWith(".$it") }) continue
            val domainAttribute = if (rawDomain.startsWith(".")) rawDomain else ".$host"
            val value = buildString {
                append(cookie.name).append('=').append(cookie.value)
                append("; Domain=").append(domainAttribute)
                append("; Path=").append(cookie.path.ifBlank { "/" })
                if (cookie.secure) append("; Secure")
                if (cookie.httpOnly) append("; HttpOnly")
            }
            cookieManager.setCookie("https://$host/", value)
            imported++
        }
        cookieManager.flush()
        return imported
    }

    /** Sites that need (or optionally benefit from) a logged-in session. */
    val SITES: List<LoginSite> = listOf(
        LoginSite("douyin", "抖音", "https://www.douyin.com/"),
        LoginSite("bilibili", "B站", "https://www.bilibili.com/"),
        LoginSite("xiaohongshu", "小红书", "https://www.xiaohongshu.com/"),
    )

    /**
     * Removes every site session: WebView cookies plus web storage (some sites
     * keep login tokens in localStorage). [onDone] runs on the caller's thread.
     */
    fun clearAll(onDone: (Int) -> Unit) {
        val cookieManager = CookieManager.getInstance()
        try {
            WebStorage.getInstance().deleteAllData()
        } catch (_: Throwable) {
            // Web storage is best-effort; cookies are the primary session.
        }
        cookieManager.removeAllCookies {
            cookieManager.flush()
            onDone(SITES.size)
        }
    }
}
