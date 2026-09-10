function cliTab() {
    return {
        keys: [],
        projects: [],
        loading: true,
        slideout: false,
        creating: false,
        apiError: null,
        newTokenBanner: null,
        form: { projectId: '', label: '' },
        csrf() { return window.OswlCsrf ? window.OswlCsrf.token() : ''; },
        headers() { return oswlJsonHeaders(); },
        formatDate(s) { return s ? s.replace('T', ' ').slice(0, 16) : '—'; },

        async init() {
            await Promise.all([this.loadKeys(), this.loadProjects()]);
        },
        async loadKeys() {
            this.loading = true;
            try {
                const r = await fetch('/api/admin/cli-keys');
                if (r.status === 401) { location.href = '/login'; return; }
                if (!r.ok) { this.apiError = _fetchErr(r, _cliI18n.loadFailed); return; }
                this.keys = await r.json();
            } catch(e) {
                this.apiError = _cliI18n.loadFailed;
            } finally {
                this.loading = false;
            }
        },
        async loadProjects() {
            try {
                const r = await fetch('/projects/list');
                if (r.ok) this.projects = await r.json();
            } catch(e) { /* non-critical */ }
        },
        openCreate() {
            this.form = { projectId: '', label: '' };
            this.newTokenBanner = null;
            this.slideout = true;
            this.$nextTick(() => oswlTrapFocus(this.$refs.createPanel));
        },
        closeCreate() {
            this.slideout = false;
            oswlReleaseFocus();
        },
        async createKey() {
            if (!this.form.projectId) return;
            this.creating = true;
            try {
                const body = {
                    projectId: this.form.projectId,
                    label: this.form.label.trim() || 'CLI Key'
                };
                const r = await fetch('/api/admin/cli-keys', { method: 'POST', headers: this.headers(), body: JSON.stringify(body) });
                if (r.status === 401) { location.href = '/login'; return; }
                if (!r.ok) { this.apiError = _fetchErr(r, _cliI18n.createFailed); return; }
                const data = await r.json();
                this.newTokenBanner = data.token;
                this.closeCreate();
                await this.loadKeys();
            } finally {
                this.creating = false;
            }
        },
        async toggleKey(k) {
            try {
                const r = await fetch(`/api/admin/cli-keys/${k.id}/toggle`, { method: 'PATCH', headers: this.headers() });
                if (r.status === 401) { location.href = '/login'; return; }
                if (!r.ok) { this.apiError = _fetchErr(r, _cliI18n.updateFailed); return; }
                await this.loadKeys();
            } catch(e) { this.apiError = _cliI18n.updateFailed; }
        },
        async deleteKey(k) {
            if (!window.confirm(_cliI18n.deleteConfirm.replace('{0}', k.label || k.projectName || ''))) return;
            try {
                const r = await fetch('/api/admin/cli-keys/' + k.id, {method: 'DELETE', headers: this.headers()});
                if (!r.ok) { this.apiError = _fetchErr(r, _cliI18n.deleteFailed); return; }
                await this.loadKeys();
            } catch (_) { this.apiError = _cliI18n.deleteFailed; }
        },
        copyToken() {
            if (this.newTokenBanner) navigator.clipboard.writeText(this.newTokenBanner);
        }
    };
}
