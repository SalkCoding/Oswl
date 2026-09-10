(function (global) {
    'use strict';
    class HttpError extends Error {
        constructor(kind, status) { super(kind); this.kind = kind; this.status = status; }
    }
    async function request(url, options = {}) {
        const { timeoutMs = 30000, responseType, ...init } = options;
        const timeout = AbortSignal.timeout(timeoutMs);
        init.signal = init.signal ? AbortSignal.any([init.signal, timeout]) : timeout;
        try {
            const response = await fetch(url, init);
            if (response.status === 401 || (response.redirected && /\/(login|otp-verify)(?:[/?]|$)/.test(new URL(response.url).pathname))) {
                throw new HttpError('unauthenticated', 401);
            }
            if (!response.ok) throw new HttpError(response.status === 403 ? 'forbidden' : 'server', response.status);
            if (responseType === 'text') return await response.text();
            if (responseType === 'json') return await response.json();
            return response;
        } catch (error) {
            if (error instanceof HttpError || options.signal?.aborted) throw error;
            if (error instanceof SyntaxError) throw new HttpError('server', 0);
            throw new HttpError(timeout.aborted ? 'timeout' : 'network', 0);
        }
    }
    function fragment(container, html, append = false) {
        if (append) container.insertAdjacentHTML('beforeend', html);
        else container.innerHTML = html;
        if (global.htmx) global.htmx.process(container);
        // Alpine observes inserted nodes itself; manually initTree would register handlers twice.
    }
    global.OswlHttp = { request, fragment, HttpError,
        text: (url, options = {}) => request(url, { ...options, responseType: 'text' }),
        json: (url, options = {}) => request(url, { ...options, responseType: 'json' })
    };
})(window);
