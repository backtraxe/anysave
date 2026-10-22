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
