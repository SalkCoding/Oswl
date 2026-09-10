function inlineTeamInvite() {
    return {
        roles: [], selected: '', email: '', name: '', password: '', loaded: false, loading: false, saving: false, error: '', success: false,
        async init() { await this.load(); },
        async load() {
            if (this.loading) return;
            this.loading = true; this.loaded = false; this.error = '';
            try {
                const response = await fetch('/api/admin/role-templates');
                if (!response.ok) throw new Error();
                const roles = await response.json();
                if (!Array.isArray(roles)) throw new Error();
                if (!roles.length) { this.error = _onbI18n.noRoles; return; }
                this.roles = roles; this.loaded = true;
            } catch (_) { this.error = _onbI18n.connectFailed; }
            finally { this.loading = false; }
        },
        async invite() {
            if (!this.loaded || this.saving || !this.selected) return;
            this.saving = true; this.error = ''; this.success = false;
            try {
                const response = await fetch('/api/admin/users', {
                    method: 'POST', headers: oswlJsonHeaders(),
                    body: JSON.stringify({displayName: this.name.trim(), email: this.email.trim(), temporaryPassword: this.password, templateIds: [Number(this.selected)]})
                });
                if (!response.ok) {
                    const body = await response.json().catch(() => ({}));
                    this.error = body.message || body.error || _onbI18n.connectFailed;
                    return;
                }
                this.password = ''; this.email = ''; this.name = ''; this.success = true;
                this.done.teamReady = true;
            } catch (_) { this.error = _onbI18n.connectFailed; }
            finally { this.saving = false; }
        }
    };
}
