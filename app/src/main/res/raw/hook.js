/*
 * AnySave document-start hook.
 *
 * Runs before the page's own scripts. It wraps fetch and XMLHttpRequest and
 * records responses whose URL contains one of window.__anysavePatterns.
 * It never builds or signs a request: the page itself does that, we only
 * observe what the page already fetched.
 */
(function () {
    try {
        window.__anysaveHits = window.__anysaveHits || [];
        var patterns = window.__anysavePatterns || [];

        function isTarget(url) {
            if (typeof url !== 'string' || !url) return false;
            for (var i = 0; i < patterns.length; i++) {
                if (url.indexOf(patterns[i]) >= 0) return true;
            }
            return false;
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
                                window.__anysaveHits.push({
                                    via: 'fetch',
                                    url: String(url).slice(0, 300),
                                    status: response.status,
                                    len: text.length
                                });
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
                    window.__anysaveHits.push({
                        via: 'xhr',
                        url: String(xhr.__anysaveUrl).slice(0, 300),
                        status: xhr.status,
                        len: text.length
                    });
                });
            }
            return originalSend.apply(this, arguments);
        };
    } catch (e) {
        window.__anysaveHookError = String(e);
    }
})();
