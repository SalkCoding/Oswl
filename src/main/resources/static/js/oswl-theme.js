/**
 * OsWL theme engine — handles light / dark / system preference.
 *
 * The chosen mode is persisted server-side for signed-in users and cached in
 * localStorage so the theme can be applied before the first paint.
 */
(function (global) {
    'use strict';

    var STORAGE_KEY = 'oswl-theme';
    var SYSTEM_QUERY = '(prefers-color-scheme: dark)';
    var MODES = ['LIGHT', 'DARK', 'SYSTEM'];
    var serverSyncVersion = 0;

    function readStorage() {
        try {
            return localStorage.getItem(STORAGE_KEY);
        } catch (e) {
            return null;
        }
    }

    function writeStorage(value) {
        try {
            localStorage.setItem(STORAGE_KEY, value);
        } catch (e) {
            // ignore
        }
    }

    function systemPrefersDark() {
        return global.matchMedia && global.matchMedia(SYSTEM_QUERY).matches;
    }

    function isDark(mode) {
        if (mode === 'DARK') return true;
        if (mode === 'LIGHT') return false;
        return systemPrefersDark();
    }

    function apply(mode) {
        var dark = isDark(mode);
        var root = document.documentElement;
        root.classList.toggle('dark', dark);
        root.setAttribute('data-theme', dark ? 'dark' : 'light');
    }

    function normalize(mode) {
        var m = String(mode || 'LIGHT').toUpperCase();
        return MODES.indexOf(m) >= 0 ? m : 'LIGHT';
    }

    var currentMode = normalize(readStorage());

    // Apply the cached theme as early as possible to avoid a flash.
    apply(currentMode);

    // Re-apply when the OS preference changes while in SYSTEM mode.
    if (global.matchMedia) {
        global.matchMedia(SYSTEM_QUERY).addEventListener('change', function () {
            if (currentMode === 'SYSTEM') {
                apply(currentMode);
                notify();
            }
        });
    }

    var listeners = [];

    function notify() {
        listeners.forEach(function (fn) {
            try { fn(currentMode); } catch (e) { /* ignore */ }
        });
    }

    function setMode(mode, options) {
        options = options || {};
        var normalized = normalize(mode);
        if (normalized === currentMode && !options.force) return;
        currentMode = normalized;
        writeStorage(normalized);
        apply(normalized);
        notify();

        // Sync to server for signed-in users.
        if (options.sync !== false && typeof fetch !== 'undefined') {
            var requestVersion = ++serverSyncVersion;
            fetch('/api/my/theme', {
                method: 'POST',
                headers: window.oswlJsonHeaders ? window.oswlJsonHeaders() : { 'Content-Type': 'application/json' },
                body: JSON.stringify({ theme: normalized })
            }).then(function (response) {
                if (!response.ok && requestVersion === serverSyncVersion) throw new Error('theme save failed');
            }).catch(function () {
                if (requestVersion !== serverSyncVersion) return;
                var message = (document.querySelector('meta[name="theme-save-error"]') || {}).content
                    || 'Theme preference could not be saved. This device will keep using your selection.';
                var event = new CustomEvent('oswl:theme-error', { detail: { message: message } });
                document.dispatchEvent(event);
                var status = document.getElementById('oswl-theme-status');
                if (!status) {
                    status = document.createElement('div');
                    status.id = 'oswl-theme-status';
                    status.setAttribute('role', 'status');
                    status.className = 'oswl-theme-status';
                    document.body.appendChild(status);
                }
                status.textContent = message;
                status.hidden = false;
                global.setTimeout(function () { status.hidden = true; }, 6000);
            });
        }
    }

    function getMode() {
        return currentMode;
    }

    function getEffectiveMode() {
        return isDark(currentMode) ? 'DARK' : 'LIGHT';
    }

    function subscribe(fn) {
        listeners.push(fn);
        return function unsubscribe() {
            var idx = listeners.indexOf(fn);
            if (idx >= 0) listeners.splice(idx, 1);
        };
    }

    function cssVar(name, fallback) {
        var value = getComputedStyle(document.documentElement).getPropertyValue(name).trim();
        return value || fallback;
    }

    // Sync with the server preference on every page load.
    if (typeof fetch !== 'undefined') {
        document.addEventListener('DOMContentLoaded', function () {
            var loadVersion = serverSyncVersion;
            fetch('/api/my/theme', { method: 'GET', cache: 'no-store' })
                .then(function (r) {
                    if (!r.ok) throw new Error('theme load failed');
                    return r.json();
                })
                .then(function (data) {
                    if (loadVersion === serverSyncVersion && data && data.theme) {
                        setMode(data.theme, { sync: false, force: false });
                    }
                })
                .catch(function () {
                    // Unauthenticated or offline — keep cached choice.
                });
        });
    }

    global.OswlTheme = {
        LIGHT: 'LIGHT',
        DARK: 'DARK',
        SYSTEM: 'SYSTEM',
        getMode: getMode,
        getEffectiveMode: getEffectiveMode,
        isDark: function () { return isDark(currentMode); },
        setMode: setMode,
        subscribe: subscribe,
        cssVar: cssVar
    };
})(window);
