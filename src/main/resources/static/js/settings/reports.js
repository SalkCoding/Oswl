function reportsTab() {
return {
    form: {
        companyName: '',
        logoDataUri: '',
        headerText: '',
        showCoverPage: false
    },
    loaded: false, loading: false,
    saving: false,
    apiError: null,
    loadError: false,
    toastVisible: false,
    toastMsg: '',

    init() {
        this.load();
    },

    showToast(msg) {
        this.toastMsg = msg;
        this.toastVisible = true;
        setTimeout(() => { this.toastVisible = false; }, 3000);
    },

    async load() {
        if (this.loading) return;
        this.loading = true; this.loaded = false; this.apiError = null;
        try {
            const r = await fetch('/api/settings/report-branding', { headers: oswlJsonHeaders() });
            if (!r.ok) { this.apiError = _fetchErr(r, _reportsI18n.backendError); this.loadError = true; return; }
            const d = await r.json();
            this.form.companyName = d.companyName || '';
            this.form.logoDataUri = d.logoDataUri || '';
            this.form.headerText = d.headerText || '';
            this.form.showCoverPage = !!d.showCoverPage;
            this.loadError = false; this.loaded = true;
        } catch (e) { this.apiError = _reportsI18n.backendError; this.loadError = true; }
        finally { this.loading = false; }
    },

    onLogoSelected(event) {
        const file = event.target.files && event.target.files[0];
        event.target.value = '';
        if (!file) return;
        if (!/^image\/(png|jpeg|svg\+xml)$/.test(file.type)) {
            this.apiError = _reportsI18n.logoInvalid;
            return;
        }
        if (file.size > 350 * 1024) {
            this.apiError = _reportsI18n.logoTooLarge;
            return;
        }
        const reader = new FileReader();
        reader.onload = () => { this.apiError = null; this.form.logoDataUri = reader.result; };
        reader.onerror = () => { this.apiError = _reportsI18n.backendError; };
        reader.readAsDataURL(file);
    },

    async save() {
        if (!this.loaded || this.loading || this.saving) return;
        const dirtyRevision = window.OswlDirty?.revision('reports');
        this.saving = true;
        this.apiError = null;
        try {
            const r = await fetch('/api/settings/report-branding', {
                method: 'PUT',
                headers: oswlJsonHeaders(),
                body: JSON.stringify(this.form)
            });
            if (r.ok) {
                if (window.OswlDirty) window.OswlDirty.clear('reports', dirtyRevision);
                this.showToast(_reportsI18n.saved);
            } else {
                const body = await r.json().catch(() => ({}));
                this.apiError = body.error || _fetchErr(r, _reportsI18n.backendError);
            }
        } catch (e) {
            this.apiError = _reportsI18n.backendError;
        } finally {
            this.saving = false;
        }
    }
};
}
