/**
 * Minimal focus trap for modal/slide-over overlays — not a full ARIA APG implementation,
 * just Tab/Shift+Tab wrapping plus restoring focus to whatever triggered the overlay on close.
 * Call oswlTrapFocus(el) right after the overlay becomes visible and oswlReleaseFocus() right
 * before/as it closes.
 */
(function (global) {
    let trapEl = null;
    let previouslyFocused = null;

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

    global.oswlTrapFocus = function (el) {
        if (!el) return;
        previouslyFocused = document.activeElement;
        trapEl = el;
        document.addEventListener('keydown', handleKeydown, true);
        // A tick lets HTMX-injected content and enter transitions finish rendering first.
        setTimeout(() => {
            const focusables = focusableElements(el);
            (focusables[0] || el).focus();
        }, 50);
    };

    global.oswlReleaseFocus = function () {
        document.removeEventListener('keydown', handleKeydown, true);
        trapEl = null;
        if (previouslyFocused && typeof previouslyFocused.focus === 'function') {
            previouslyFocused.focus();
        }
        previouslyFocused = null;
    };
})(window);
