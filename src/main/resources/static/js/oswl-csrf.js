/**
 * CSRF helpers — token is read from <meta name="csrf-token"> (HttpOnly cookie is not exposed to JS).
 */
(function (global) {
    'use strict';

    function token() {
        var el = document.querySelector('meta[name="csrf-token"]');
        var fromMeta = el ? el.getAttribute('content') || '' : '';
        if (fromMeta) {
            return fromMeta;
        }
        // Fallback: Spring CookieCsrfTokenRepository cookie (non-HttpOnly by default).
        var m = document.cookie.match(/(?:^|; )XSRF-TOKEN=([^;]+)/);
        return m ? decodeURIComponent(m[1]) : '';
    }

    function jsonHeaders(extra) {
        var h = Object.assign({ 'Content-Type': 'application/json' }, extra || {});
        var t = token();
        if (t) {
            h['X-XSRF-TOKEN'] = t;
        }
        return h;
    }

    global.OswlCsrf = { token: token, jsonHeaders: jsonHeaders };
    global.oswlJsonHeaders = jsonHeaders;
})(window);
