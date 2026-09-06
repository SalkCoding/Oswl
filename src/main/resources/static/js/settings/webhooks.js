function webhooksTab() {
return {
    form: {
        provider: 'SLACK',
        url: '',
        enabled: false,
        notifyNewCve: true,
        notifyGateFailure: true,
        notifyScanFailure: true,
        notifyWaiverExpiry: true
    },
    saving: false,
    testing: false,
    testStatus: null,
    testMessage: '',
    apiError: null,
    toastVisible: false,
    toastMsg: '',
    history: [],
    historyLoading: false,
    historyError: false,
    historyFilter: { status: '', eventType: '' },

    init() {
        this.load();
        this.loadHistory();
    },

    showToast(msg) {
        this.toastMsg = msg;
        this.toastVisible = true;
        setTimeout(() => { this.toastVisible = false; }, 3000);
    },

    async load() {
        try {
            const r = await fetch('/api/settings/webhooks');
            if (!r.ok) { this.apiError = _fetchErr(r, _webhooksI18n.backendError); return; }
            const d = await r.json();
            if (d.provider)            this.form.provider           = d.provider;
            if (d.enabled != null)     this.form.enabled            = d.enabled;
            if (d.notifyNewCve != null)        this.form.notifyNewCve        = d.notifyNewCve;
            if (d.notifyGateFailure != null)   this.form.notifyGateFailure   = d.notifyGateFailure;
            if (d.notifyScanFailure != null)   this.form.notifyScanFailure   = d.notifyScanFailure;
            if (d.notifyWaiverExpiry != null)  this.form.notifyWaiverExpiry  = d.notifyWaiverExpiry;
            // URL is never returned; keep the input empty so the user can change it.
        } catch (e) { this.apiError = _webhooksI18n.backendError; }
    },

    async save() {
        const dirtyRevision = window.OswlDirty?.revision('webhooks');
        this.saving = true;
        this.apiError = null;
        this.testStatus = null;
        try {
            const r = await fetch('/api/settings/webhooks', {
                method: 'PUT',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(this.form)
            });
            if (r.ok) {
                const savedCurrentRevision = !window.OswlDirty
                    || window.OswlDirty.clear('webhooks', dirtyRevision);
                this.showToast(_webhooksI18n.saved);
                if (savedCurrentRevision) this.form.url = '';
                this.loadHistory();
            } else {
                const body = await r.json().catch(() => ({}));
                this.apiError = body.message || _webhooksI18n.backendError;
            }
        } catch (e) {
            this.apiError = _webhooksI18n.backendError;
        } finally {
            this.saving = false;
        }
    },

    async testWebhook() {
        if (!this.form.url || !this.form.url.trim()) {
            this.apiError = _webhooksI18n.urlRequired;
            return;
        }
        this.testing = true;
        this.testStatus = null;
        this.apiError = null;
        try {
            const r = await fetch('/api/settings/webhooks/test', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ url: this.form.url.trim() })
            });
            const body = await r.json().catch(() => ({}));
            if (r.ok && body.success) {
                this.testStatus = 'ok';
                this.testMessage = _webhooksI18n.testOk;
            } else {
                this.testStatus = 'error';
                this.testMessage = body.error || _webhooksI18n.testFailed;
            }
        } catch (e) {
            this.testStatus = 'error';
            this.testMessage = _webhooksI18n.backendError;
        } finally {
            this.testing = false;
        }
    },

    async loadHistory() {
        this.historyLoading = true;
        this.historyError = false;
        try {
            const params = new URLSearchParams();
            params.set('limit', '50');
            if (this.historyFilter.status)    params.set('status',    this.historyFilter.status);
            if (this.historyFilter.eventType) params.set('eventType', this.historyFilter.eventType);
            const r = await fetch('/api/settings/webhooks/deliveries?' + params.toString());
            if (r.ok) {
                this.history = await r.json();
            } else {
                this.historyError = true;
                this.history = [];
            }
        } catch (e) {
            this.historyError = true;
            this.history = [];
        }
        this.historyLoading = false;
    },

    formatTime(iso) {
        if (!iso) return '-';
        const d = new Date(iso);
        return d.toLocaleString(undefined, {
            year: 'numeric', month: 'short', day: 'numeric',
            hour: '2-digit', minute: '2-digit'
        });
    }
};
}
