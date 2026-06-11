(function () {
    'use strict';

    const STORAGE_KEY = 'oswl-landing-locale';
    const SUPPORTED = ['en', 'ko'];

    function detectLocale() {
        const param = new URLSearchParams(location.search).get('lang');
        if (SUPPORTED.includes(param)) return param;
        const stored = localStorage.getItem(STORAGE_KEY);
        if (SUPPORTED.includes(stored)) return stored;
        if ((navigator.language || '').toLowerCase().startsWith('ko')) return 'ko';
        return 'en';
    }

    function get(obj, path) {
        return path.split('.').reduce((o, k) => (o && o[k] != null ? o[k] : null), obj);
    }

    let currentLocale = 'en';

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

        document.querySelectorAll('[data-set-lang]').forEach(btn => {
            const lang = btn.getAttribute('data-set-lang');
            btn.classList.toggle('active', lang === currentLocale);
            btn.setAttribute('aria-current', lang === currentLocale ? 'true' : 'false');
        });
    }

    async function loadAndApply(locale) {
        currentLocale = locale;
        document.documentElement.lang = locale;

        try {
            const res = await fetch(`i18n/${locale}.json`);
            if (!res.ok) throw new Error(`HTTP ${res.status}`);
            applyMessages(await res.json());
        } catch (e) {
            console.warn('[landing-i18n] Failed to load locale:', locale, e);
        } finally {
            document.documentElement.classList.remove('i18n-pending');
        }
    }

    function setLocale(locale) {
        if (!SUPPORTED.includes(locale)) return;
        localStorage.setItem(STORAGE_KEY, locale);
        loadAndApply(locale);
    }

    async function init() {
        const param = new URLSearchParams(location.search).get('lang');
        if (SUPPORTED.includes(param)) {
            localStorage.setItem(STORAGE_KEY, param);
            const u = new URL(location.href);
            u.searchParams.delete('lang');
            history.replaceState(null, '', u.pathname + u.search + u.hash);
        }

        await loadAndApply(detectLocale());

        document.querySelectorAll('[data-set-lang]').forEach(btn => {
            btn.addEventListener('click', () => setLocale(btn.getAttribute('data-set-lang')));
        });
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
