/** Shared appearance helpers. OsWL supports the light design only. */
(function (global) {
    'use strict';

    function applyLight() {
        document.documentElement.classList.remove('dark');
        document.documentElement.setAttribute('data-theme', 'light');
        try { localStorage.setItem('oswl-theme', 'LIGHT'); } catch (e) { /* Storage is optional. */ }
    }

    applyLight();
    // Reapply when a page is restored from the browser's back/forward cache.
    global.addEventListener('pageshow', applyLight);

    global.OswlTheme = {
        LIGHT: 'LIGHT',
        getMode: function () { return 'LIGHT'; },
        getEffectiveMode: function () { return 'LIGHT'; },
        isDark: function () { return false; },
        setMode: applyLight,
        subscribe: function () { return function () {}; },
        cssVar: function (name, fallback) {
            return getComputedStyle(document.documentElement).getPropertyValue(name).trim() || fallback;
        }
    };
})(window);
