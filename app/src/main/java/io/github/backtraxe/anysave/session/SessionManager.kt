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
