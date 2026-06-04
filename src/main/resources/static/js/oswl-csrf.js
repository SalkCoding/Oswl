/**
 * CSRF helpers — token is read from <meta name="csrf-token"> (HttpOnly cookie is not exposed to JS).
 */
(function (global) {
    'use strict';

    function token() {
        var el = document.querySelector('meta[name="csrf-token"]');
        return el ? el.getAttribute('content') || '' : '';
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
