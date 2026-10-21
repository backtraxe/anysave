/*
 * AnySave document-start hook.
 *
 * Runs before the page's own scripts. It wraps fetch and XMLHttpRequest and
 * records responses whose URL contains one of window.__anysavePatterns,
 * including the response body so the app can parse it outside the page.
 * It never builds or signs a request: the page itself does that, we only
 * observe what the page already fetched.
 */
(function () {
    try {
        window.__anysaveHits = window.__anysaveHits || [];
        window.__anysaveSeen = window.__anysaveSeen || {};
        var patterns = window.__anysavePatterns || [];

        // Douyin detail responses are ~80 KB; keep a few, cap the rest so a
        // runaway page cannot grow the JS heap without bound.
        var MAX_BODY_CHARS = 4 * 1024 * 1024;
        var MAX_HITS = 8;
        var MAX_URL_CHARS = 300;

        function isTarget(url) {
            if (typeof url !== 'string' || !url) return false;
            for (var i = 0; i < patterns.length; i++) {
                if (url.indexOf(patterns[i]) >= 0) return true;
            }
            return false;
        }

        function record(url, via, status, body) {
            var key = String(url);
            if (window.__anysaveSeen[key]) return;
            if (window.__anysaveHits.length >= MAX_HITS) return;
            window.__anysaveSeen[key] = true;
            var text = body || '';
            if (text.length > MAX_BODY_CHARS) text = text.slice(0, MAX_BODY_CHARS);
            window.__anysaveHits.push({
                via: via,
                url: key.slice(0, MAX_URL_CHARS),
                status: status,
                len: text.length,
                body: text
            });
        }

        var originalFetch = window.fetch;
        if (originalFetch) {
            window.fetch = function () {
                var args = arguments;
                var url = (typeof args[0] === 'string')
                    ? args[0]
                    : ((args[0] && args[0].url) || '');
                var promise = originalFetch.apply(this, args);
                if (isTarget(url)) {
                    try {
                        promise.then(function (response) {
                            response.clone().text().then(function (text) {
                                record(url, 'fetch', response.status, text);
                            }).catch(function () {});
                        }).catch(function () {});
                    } catch (e) { /* ignore */ }
                }
                return promise;
            };
        }

        var originalOpen = XMLHttpRequest.prototype.open;
        var originalSend = XMLHttpRequest.prototype.send;

        XMLHttpRequest.prototype.open = function (method, url) {
            this.__anysaveUrl = url;
            return originalOpen.apply(this, arguments);
        };

        XMLHttpRequest.prototype.send = function () {
            var xhr = this;
            if (isTarget(xhr.__anysaveUrl)) {
                xhr.addEventListener('load', function () {
                    var text = '';
                    try { text = xhr.responseText || ''; } catch (e) { /* ignore */ }
                    record(xhr.__anysaveUrl, 'xhr', xhr.status, text);
                });
            }
            return originalSend.apply(this, arguments);
        };
    } catch (e) {
        window.__anysaveHookError = String(e);
    }
})();
