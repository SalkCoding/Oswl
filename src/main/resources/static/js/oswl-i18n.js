/* ============================================================
   Shared i18n accessors for server-injected message bundles.
   Templates bridge messages.properties into a JS object via
   <script th:inline="javascript"> (e.g. _qiI18n in quick-import);
   these helpers read such bundles. A missing key surfaces as the
   key name itself, so gaps show up on screen instead of silently
   falling back to hardcoded text.
   ============================================================ */

/** Returns bundle[key], or the key name itself when the bundle or key is missing. */
function oswlI18n(bundle, key) {
    return (bundle && bundle[key]) ?? key;
}

/** Fills {0}, {1}, … placeholders in template with the given args. */
function oswlI18nFmt(template, ...args) {
    let s = String(template);
    for (let i = 0; i < args.length; i++) {
        s = s.split('{' + i + '}').join(args[i]);
    }
    return s;
}
