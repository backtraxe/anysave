package io.github.backtraxe.anysave.session

import org.json.JSONObject

/**
 * Parses per-site login signals (M11).
 *
 * The checks run in a hidden WebView that shares the app's session cookies; the
 * page itself exposes whether the session is valid:
 * - Douyin: `www.douyin.com/user/self` shows the signed-in nickname in the title
 *   (`<nickname>的抖音 - 抖音`); logged out it stays `抖音-记录美好生活`.
 * - Bilibili: `api.bilibili.com/x/web-interface/nav` returns
 *   `{"data":{"isLogin":true,"uname":"..."}}`, which is the canonical session
 *   probe (no signed API replay, just the account state endpoint).
 * - Xiaohongshu: `www.xiaohongshu.com/explore` hydrates
 *   `__INITIAL_STATE__.user.loggedIn` (and `userInfo.nickname`).
 *
 * No cookie values are read or logged anywhere; only the boolean state and the
 * public nickname are used.
 */
object LoginSignals {

    data class Result(
        /** null = the probe could not determine the state. */
        val loggedIn: Boolean?,
        val account: String = "",
    )

    private const val DOUYIN_LOGGED_OUT_TITLE = "记录美好生活"
    private const val DOUYIN_TITLE_SUFFIX = "的抖音"

    fun douyin(title: String): Result {
        val clean = title.trim()
        if (clean.isEmpty()) return Result(null)
        val loggedIn = clean.contains("抖音") && !clean.contains(DOUYIN_LOGGED_OUT_TITLE)
        val account = if (loggedIn) {
            val index = clean.indexOf(DOUYIN_TITLE_SUFFIX)
            if (index > 0) clean.substring(0, index).trim() else ""
        } else {
            ""
        }
        return Result(loggedIn, account)
    }

    fun bilibili(navJson: String?): Result {
        if (navJson.isNullOrBlank()) return Result(null)
        return try {
            val root = JSONObject(navJson)
            val data = root.optJSONObject("data")
                ?: return Result(false)
            val loggedIn = data.optBoolean("isLogin", false)
            val account = if (loggedIn) data.optString("uname").trim() else ""
            Result(loggedIn, account)
        } catch (t: Throwable) {
            // A non-JSON body means the probe page did not load the endpoint.
            Result(null)
        }
    }

    fun xiaohongshu(stateJson: String?): Result {
        if (stateJson.isNullOrBlank()) return Result(null)
        return try {
            val root = JSONObject(stateJson)
            if (!root.has("logged")) return Result(null)
            Result(root.optBoolean("logged", false), root.optString("nickname").trim())
        } catch (t: Throwable) {
            Result(null)
        }
    }
}
