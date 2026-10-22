package io.github.backtraxe.anysave.session

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import io.github.backtraxe.anysave.model.DESKTOP_CHROME_UA
import org.json.JSONObject

/**
 * One-off login-state probe used when an extraction times out (M16).
 *
 * Creates a hidden 1x1 WebView that shares the app's cookie jar, loads a small
 * site-specific page and reads the same signals as the settings login card:
 * douyin user/self title, bilibili nav JSON, xiaohongshu loggedIn ref. The
 * WebView is destroyed as soon as the probe finishes (or times out).
 *
 * No cookie values are read or logged.
 */
class SiteLoginCheck(private val activity: Activity) {

    private val handler = Handler(Looper.getMainLooper())
    private var webView: WebView? = null
    private var generation = 0

    private data class Spec(
        val siteId: String,
        val url: String,
        val delayMs: Long,
        val script: String,
    )

    fun check(siteId: String, onResult: (LoginSignals.Result?) -> Unit) {
        cancel()
        val spec = SPECS[siteId]
        if (spec == null) {
            onResult(null)
            return
        }
        generation += 1
        val generationAtStart = generation
        val probe = WebView(activity).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = DESKTOP_CHROME_UA
            alpha = 0f
            importantForAccessibility = ViewGroup.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            layoutParams = ViewGroup.LayoutParams(1, 1)
        }
        webView = probe
        (activity.findViewById<ViewGroup>(android.R.id.content)).addView(probe)

        var finished = false
        fun finish(result: LoginSignals.Result?) {
            if (finished) return
            finished = true
            if (generationAtStart != generation) return
            webView = null
            probe.stopLoading()
            (probe.parent as? ViewGroup)?.removeView(probe)
            probe.destroy()
            handler.removeCallbacksAndMessages(null)
            onResult(result)
        }

        probe.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                if (url == null || hostOf(url) != hostOf(spec.url)) return
                handler.postDelayed({
                    if (generationAtStart != generation) {
                        finish(null)
                        return@postDelayed
                    }
                    view?.evaluateJavascript(spec.script) { raw ->
                        if (generationAtStart != generation) return@evaluateJavascript
                        val payload = jsString(raw)
                        val result = when (spec.siteId) {
                            "douyin" -> douyinResult(payload)
                            "bilibili" -> LoginSignals.bilibili(payload)
                            else -> LoginSignals.xiaohongshu(payload)
                        }
                        finish(result)
                    }
                }, spec.delayMs)
            }
        }
        probe.loadUrl(spec.url)
        handler.postDelayed({ finish(null) }, spec.delayMs + TOTAL_TIMEOUT_MS)
    }

    fun cancel() {
        generation += 1
        handler.removeCallbacksAndMessages(null)
        webView?.let { probe ->
            probe.stopLoading()
            (probe.parent as? ViewGroup)?.removeView(probe)
            probe.destroy()
        }
        webView = null
    }

    private fun douyinResult(payload: String): LoginSignals.Result = try {
        LoginSignals.douyin(JSONObject(payload).optString("title"))
    } catch (t: Throwable) {
        LoginSignals.Result(null)
    }

    private fun jsString(raw: String?): String {
        if (raw.isNullOrBlank() || raw == "null") return ""
        return try {
            val value = org.json.JSONTokener(raw).nextValue()
            value as? String ?: raw
        } catch (t: Throwable) {
            raw
        }
    }

    private fun hostOf(url: String): String = try {
        android.net.Uri.parse(url).host.orEmpty()
    } catch (t: Throwable) {
        ""
    }

    private companion object {
        private const val TOTAL_TIMEOUT_MS = 12_000L

        private val SPECS = mapOf(
            "douyin" to Spec(
                "douyin",
                "https://www.douyin.com/user/self",
                5_000L,
                "JSON.stringify({title: document.title})",
            ),
            "bilibili" to Spec(
                "bilibili",
                "https://api.bilibili.com/x/web-interface/nav",
                1_500L,
                "document.body.innerText",
            ),
            "xiaohongshu" to Spec(
                "xiaohongshu",
                "https://www.xiaohongshu.com/explore",
                5_000L,
                "(function(){try{var s=window.__INITIAL_STATE__;var u=s&&s.user;" +
                    "function val(x){return x&&typeof x==='object'&&'value' in x?x.value:x;}" +
                    "var li=val(u&&u.loggedIn);var ui=val(u&&u.userInfo);" +
                    "return JSON.stringify({logged:!!li,nickname:(ui&&ui.nickname)||''});" +
                    "}catch(e){return JSON.stringify({logged:null});}})()",
            ),
        )
    }
}
