// Slide-over load feedback: HTMX swaps into #slideout-content; toggle the overlay on
    // request start/end and show the retry card on failure. Retry re-clicks the originating
    // row so the exact original request (incl. hx-select) is re-issued.
    (function () {
        var lastRow = null;
        function show(id, on) {
            var n = document.getElementById(id);
            if (n) n.style.display = on ? 'flex' : 'none';
        }
        function isDetailRequest(evt) {
            return evt.detail.target && evt.detail.target.id === 'slideout-content';
        }
        document.body.addEventListener('htmx:beforeRequest', function (evt) {
            if (!isDetailRequest(evt)) return;
            lastRow = evt.detail.elt;
            show('slideout-loading', true);
            show('slideout-error', false);
        });
        document.body.addEventListener('htmx:afterRequest', function (evt) {
            if (!isDetailRequest(evt)) return;
            show('slideout-loading', false);
        });
        function onError(evt) {
            if (!isDetailRequest(evt)) return;
            show('slideout-loading', false);
            show('slideout-error', true);
        }
        document.body.addEventListener('htmx:responseError', onError);
        document.body.addEventListener('htmx:sendError', onError);
        document.body.addEventListener('htmx:timeout', onError);
        document.getElementById('slideout-retry').addEventListener('click', function () {
            if (!lastRow) return;
            show('slideout-error', false);
            lastRow.click();
        });
    })();
