(function () {
    'use strict';

    const STORAGE_KEY = 'oswl-landing-locale';
    const SUPPORTED = ['en', 'ko', 'ja'];

    function readStoredLocale() {
        try { return localStorage.getItem(STORAGE_KEY); } catch (_) { return null; }
    }

    function detectLocale() {
        const param = new URLSearchParams(location.search).get('lang');
        if (SUPPORTED.includes(param)) return param;
        const stored = readStoredLocale();
        if (SUPPORTED.includes(stored)) return stored;
        const browser = (navigator.language || '').toLowerCase();
        if (browser.startsWith('ko')) return 'ko';
        if (browser.startsWith('ja')) return 'ja';
        return 'en';
    }

    function get(obj, path) {
        return path.split('.').reduce((o, k) => (o && o[k] != null ? o[k] : null), obj);
    }

    let currentLocale = 'en';
    let requestId = 0;
    let activeRequest;

    function updateLanguageButtons() {
        document.querySelectorAll('[data-set-lang]').forEach(btn => {
            const selected = btn.getAttribute('data-set-lang') === currentLocale;
            btn.classList.toggle('active', selected);
            btn.setAttribute('aria-current', selected ? 'true' : 'false');
        });
    }

    function applyMessages(messages) {
        document.querySelectorAll('[data-i18n]').forEach(el => {
            const val = get(messages, el.getAttribute('data-i18n'));
            if (val != null) el.textContent = val;
        });

        document.querySelectorAll('[data-i18n-html]').forEach(el => {
            const val = get(messages, el.getAttribute('data-i18n-html'));
            if (val != null) el.innerHTML = val;
        });

        document.querySelectorAll('[data-i18n-attr]').forEach(el => {
            el.getAttribute('data-i18n-attr').split(';').forEach(pair => {
                const [attr, key] = pair.trim().split(':').map(s => s.trim());
                const val = get(messages, key);
                if (val != null && attr) el.setAttribute(attr, val);
            });
        });

        const title = get(messages, 'meta.title');
        if (title) document.title = title;

        updateLanguageButtons();
    }

    async function loadAndApply(locale) {
        const id = ++requestId;
        if (activeRequest) activeRequest.abort();
        const controller = new AbortController();
        activeRequest = controller;
        const timeout = setTimeout(() => controller.abort(), 8000);
        try {
            const res = await fetch(`i18n/${locale}.json`, { signal: controller.signal });
            if (!res.ok) throw new Error(`HTTP ${res.status}`);
            const messages = await res.json();
            if (id !== requestId) return;
            currentLocale = locale;
            document.documentElement.lang = locale;
            applyMessages(messages);
            try { localStorage.setItem(STORAGE_KEY, locale); } catch (_) { /* Storage is optional. */ }
            try {
                const url = new URL(location.href);
                url.searchParams.delete('lang');
                history.replaceState(null, '', url.pathname + url.search + url.hash);
            } catch (_) { /* The selected language still works without history access. */ }
        } catch (e) {
            if (id === requestId) {
                // Keep the last complete language, or the readable English HTML on first load.
                console.warn('[landing-i18n] Failed to load locale:', locale, e);
            }
        } finally {
            clearTimeout(timeout);
            if (id === requestId) activeRequest = null;
        }
    }

    function setLocale(locale) {
        if (!SUPPORTED.includes(locale)) return;
        loadAndApply(locale);
    }

    function init() {
        updateLanguageButtons();
        document.querySelectorAll('[data-set-lang]').forEach(btn => {
            btn.addEventListener('click', () => setLocale(btn.getAttribute('data-set-lang')));
        });
        loadAndApply(detectLocale());
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
