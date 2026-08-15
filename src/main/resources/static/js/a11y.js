/**
 * Minimal focus trap for modal/slide-over overlays — not a full ARIA APG implementation,
 * just Tab/Shift+Tab wrapping plus restoring focus to whatever triggered the overlay on close.
 * Call oswlTrapFocus(el) right after the overlay becomes visible and oswlReleaseFocus() right
 * before/as it closes.
 *
 * While an overlay is trapped, everything outside it is marked `inert` so screen readers
 * stop announcing the background page and browsers skip it in sequential focus navigation.
 *
 * Also installs the ARIA APG menu keyboard pattern site-wide: ArrowUp/ArrowDown/Home/End
 * move focus between the items of whichever [role="menu"] currently has focus. Menus in
 * this project mark their items as plain links/buttons (no menuitem roles), so the handler
 * walks those.
 */
(function (global) {
    let trapEl = null;
    let previouslyFocused = null;
    let inerted = [];

    function focusableElements(container) {
        return Array.from(container.querySelectorAll(
            'a[href], button:not([disabled]), textarea:not([disabled]), input:not([disabled]), select:not([disabled]), [tabindex]:not([tabindex="-1"])'
        )).filter(el => el.offsetParent !== null);
    }

    function handleKeydown(e) {
        if (e.key !== 'Tab' || !trapEl) return;
        const focusables = focusableElements(trapEl);
        if (!focusables.length) return;
        const first = focusables[0];
        const last = focusables[focusables.length - 1];
        if (e.shiftKey && document.activeElement === first) {
            e.preventDefault();
            last.focus();
        } else if (!e.shiftKey && document.activeElement === last) {
            e.preventDefault();
            first.focus();
        }
    }

    // Walk from the overlay up to <body>, inert-ing siblings at each level so the
    // overlay's own ancestors stay reachable. Elements already inert are left alone
    // (and left inert on release — we only undo what we set).
    function inertBackground(el) {
        releaseBackground();
        let node = el;
        while (node && node !== document.body) {
            const parent = node.parentElement;
            if (!parent) break;
            for (const sibling of parent.children) {
                if (sibling !== node && !sibling.hasAttribute('inert')) {
                    sibling.setAttribute('inert', '');
                    inerted.push(sibling);
                }
            }
            node = parent;
        }
    }

    function releaseBackground() {
        for (const el of inerted) el.removeAttribute('inert');
        inerted = [];
    }

    global.oswlTrapFocus = function (el) {
        if (!el) return;
        previouslyFocused = document.activeElement;
        trapEl = el;
        document.addEventListener('keydown', handleKeydown, true);
        inertBackground(el);
        // A tick lets HTMX-injected content and enter transitions finish rendering first.
        setTimeout(() => {
            const focusables = focusableElements(el);
            (focusables[0] || el).focus();
        }, 50);
    };

    global.oswlReleaseFocus = function () {
        document.removeEventListener('keydown', handleKeydown, true);
        trapEl = null;
        releaseBackground();
        if (previouslyFocused && typeof previouslyFocused.focus === 'function') {
            previouslyFocused.focus();
        }
        previouslyFocused = null;
    };

    // Arrow-key navigation for [role="menu"] containers (ARIA APG menu pattern).
    // Tab is deliberately untouched — per APG it moves focus out of the menu.
    document.addEventListener('keydown', (e) => {
        if (e.key !== 'ArrowDown' && e.key !== 'ArrowUp' && e.key !== 'Home' && e.key !== 'End') return;
        if (!(e.target instanceof Element)) return;
        const menu = e.target.closest('[role="menu"]');
        if (!menu) return;
        const items = focusableElements(menu);
        if (!items.length) return;
        const idx = items.indexOf(document.activeElement);
        e.preventDefault();
        if (e.key === 'Home') {
            items[0].focus();
        } else if (e.key === 'End') {
            items[items.length - 1].focus();
        } else if (e.key === 'ArrowDown') {
            items[idx < 0 ? 0 : (idx + 1) % items.length].focus();
        } else {
            items[idx < 0 ? items.length - 1 : (idx - 1 + items.length) % items.length].focus();
        }
    });
})(window);
