/**
 * Unsaved-changes guard for the settings page.
 *
 * Tab navigation on /settings is a full page load, so a single beforeunload handler covers
 * both tab switches and closing the browser — no custom confirm modal is needed.
 *
 * Markup contract:
 *   data-dirty-scope="name"  on a container — edits inside it mark that scope dirty.
 *   data-dirty-mark          on a button — clicks mark the enclosing scope dirty, for the
 *                            custom radios/dropdowns/pills that fire no input/change event.
 *   data-dirty-ignore        on an element — excluded (search/filter boxes that save nothing).
 *
 * Before sending a save, tabs capture window.OswlDirty.revision(scope), then pass that
 * revision to clear(scope, revision) after success. Edits made while the request is in
 * flight advance the revision and therefore remain dirty. A failed save never clears.
 *
 * This guard is event/revision based rather than value based: changing a value and then
 * restoring it still counts as unsaved activity until a save succeeds or the owning UI
 * explicitly discards the edit with clear(scope).
 */
(function () {
    const dirtyScopes = new Set();
    const revisions = new Map();

    function scopeOf(el) {
        const container = el.closest('[data-dirty-scope]');
        return container ? container.getAttribute('data-dirty-scope') : null;
    }

    window.OswlDirty = {
        mark(scope) {
            if (!scope) return;
            revisions.set(scope, (revisions.get(scope) || 0) + 1);
            dirtyScopes.add(scope);
        },
        revision(scope) { return revisions.get(scope) || 0; },
        clear(scope, savedRevision) {
            if (savedRevision !== undefined && savedRevision !== (revisions.get(scope) || 0)) {
                return false;
            }
            dirtyScopes.delete(scope);
            return true;
        },
        clearAll() { dirtyScopes.clear(); },
        isDirty() { return dirtyScopes.size > 0; }
    };

    // Native inputs/textareas/selects (including sr-only ones behind custom styling).
    ['input', 'change'].forEach((type) => {
        document.addEventListener(type, (e) => {
            const t = e.target;
            if (!(t instanceof Element)) return;
            if (t.closest('[data-dirty-ignore]')) return;
            window.OswlDirty.mark(scopeOf(t));
        });
    });

    // Custom click-based controls (radio images, listbox options, toggle pills).
    document.addEventListener('click', (e) => {
        const t = e.target;
        if (!(t instanceof Element)) return;
        const trigger = t.closest('[data-dirty-mark]');
        if (!trigger || trigger.closest('[data-dirty-ignore]')) return;
        window.OswlDirty.mark(scopeOf(trigger));
    });

    window.addEventListener('beforeunload', (e) => {
        if (dirtyScopes.size === 0) return;
        e.preventDefault();
        e.returnValue = ''; // legacy browsers
    });
})();
