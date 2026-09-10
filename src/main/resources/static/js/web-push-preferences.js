function webPushPreferences() {
    return {
        supported: window.isSecureContext && 'serviceWorker' in navigator && 'PushManager' in window && 'Notification' in window,
        configured: false, loaded: false, busy: false, error: '', active: false, id: null, publicKey: '', newHighRisk: true, gateFailure: true,
        async init() { await this.load(); },
        async load() {
            if (this.busy) return;
            this.busy = true;
            this.loaded = false; this.error = ''; this.active = false; this.id = null;
            try {
                const response = await fetch('/api/my/web-push');
                if (!response.ok) throw new Error();
                const status = await response.json();
                this.configured = status.enabled; this.publicKey = status.publicKey;
                if (this.supported) {
                    const registration = await navigator.serviceWorker.getRegistration('/');
                    const subscription = registration && await registration.pushManager.getSubscription();
                    if (subscription) {
                        const bytes = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(subscription.endpoint));
                        const hash = Array.from(new Uint8Array(bytes), b => b.toString(16).padStart(2, '0')).join('');
                        const owned = status.subscriptions.find(s => s.endpointHash === hash);
                        if (owned) { this.id = owned.id; this.active = owned.active; this.newHighRisk = owned.newHighRisk; this.gateFailure = owned.gateFailure; }
                    }
                }
                this.loaded = true;
            } catch (_) { this.error = _pushI18n.failed; }
            finally { this.busy = false; }
        },
        async enable() {
            if (this.busy || !this.loaded || !this.configured || !this.supported) return;
            this.busy = true; this.error = '';
            try {
                if (await Notification.requestPermission() !== 'granted') { this.error = _pushI18n.denied; return; }
                const registration = await navigator.serviceWorker.register('/oswl-push-sw.js', {scope: '/'});
                await navigator.serviceWorker.ready;
                let subscription = await registration.pushManager.getSubscription();
                if (!subscription) {
                    const raw = atob(this.publicKey.replace(/-/g, '+').replace(/_/g, '/'));
                    subscription = await registration.pushManager.subscribe({userVisibleOnly: true, applicationServerKey: Uint8Array.from(raw, c => c.charCodeAt(0))});
                }
                const json = subscription.toJSON();
                const response = await fetch('/api/my/web-push', {method: 'POST', headers: oswlJsonHeaders(), body: JSON.stringify({
                    endpoint: json.endpoint, p256dh: json.keys.p256dh, auth: json.keys.auth,
                    newHighRisk: this.newHighRisk, gateFailure: this.gateFailure, locale: ['en','ko','ja'].includes(document.documentElement.lang) ? document.documentElement.lang : 'en'
                })});
                if (!response.ok) throw new Error();
                this.id = await response.json(); this.active = true;
            } catch (_) { this.error = _pushI18n.failed; }
            finally { this.busy = false; }
        },
        async disable() {
            if (this.busy || !this.loaded || !this.supported) return;
            this.busy = true; this.error = '';
            try {
                if (this.id !== null) {
                    const response = await fetch('/api/my/web-push/' + this.id, {method: 'DELETE', headers: oswlJsonHeaders()});
                    if (!response.ok) throw new Error();
                    this.id = null; this.active = false;
                }
                const registration = await navigator.serviceWorker.getRegistration('/');
                const subscription = registration && await registration.pushManager.getSubscription();
                if (subscription && !await subscription.unsubscribe()) throw new Error();
            } catch (_) { this.error = _pushI18n.failed; }
            finally { this.busy = false; }
        }
    };
}
