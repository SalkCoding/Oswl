/**
 * Shared helpers for settings tabs with their own sub-tab navigation (admin, ai), so a
 * documentation link like /settings?tab=ai&section=prompts opens directly on that sub-tab
 * instead of always landing on the default one.
 */
function initialSettingsSection(defaultKey) {
    const params = new URLSearchParams(window.location.search);
    return params.get('section') || defaultKey;
}

function syncSettingsSection(key) {
    const url = new URL(window.location);
    url.searchParams.set('section', key);
    history.replaceState({}, '', url);
}
