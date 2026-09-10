/**
 * Shared helpers for settings tabs with their own sub-tab navigation (admin, ai), so a
 * documentation link like /settings?tab=ai&section=prompts opens directly on that sub-tab
 * instead of always landing on the default one.
 */
function initialSettingsSection(defaultKey, allowedKeys) {
    const params = new URLSearchParams(window.location.search);
    const requested = params.get('section');
    return requested && (!allowedKeys || allowedKeys.includes(requested)) ? requested : defaultKey;
}

function syncSettingsSection(key) {
    const url = new URL(window.location);
    url.searchParams.set('section', key);
    if (window.location.href !== url.href) {
        history.pushState({ section: key }, '', url);
    }
    window.dispatchEvent(new CustomEvent('oswl:settings-section', { detail: key }));
}

function onSettingsSectionPopstate(callback) {
    const handler = () => callback();
    window.addEventListener('popstate', handler);
    return () => window.removeEventListener('popstate', handler);
}
