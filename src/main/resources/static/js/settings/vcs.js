function vcsTab() {
    return {
        subTab: initialSettingsSection('github'),
        connections: [],
        loading: true,
        slideout: false,
        providerDropdownOpen: false,
        creating: false,
        confirmingDisconnect: null,
        apiError: null,
        toastVisible: false,
        toastMsg: '',
        githubMode: 'cloud',
        githubServerUrl: '',
        githubAccessToken: '',
        gitlabMode: 'cloud',
        gitlabServerUrl: '',
        gitlabAccessToken: '',
        bitbucketMode: 'cloud',
        bitbucketServerUrl: '',
        bitbucketUsername: '',
        bitbucketEmail: '',
        bitbucketAccessToken: '',
        form: { provider: '', serverUrl: '', vcsUsername: '', accessToken: '' },
        headers() {
            return Object.assign(
                { 'Accept': 'application/json' },
                oswlJsonHeaders()
            );
        },
        formatDate(s) { return s ? s.replace('T', ' ').slice(0, 16) : '—'; },
        providerLabel(p) {
            return p === 'GITHUB' ? _vcsI18n.providerGithub
                : p === 'GITLAB' ? _vcsI18n.providerGitlab
                : p === 'BITBUCKET' ? _vcsI18n.providerAtlassian
                : p;
        },
        showToast(msg) {
            this.toastMsg = msg;
            this.toastVisible = true;
            setTimeout(() => { this.toastVisible = false; }, 3000);
        },
        selectSubTab(key) {
            this.subTab = key;
            syncSettingsSection(key);
        },

        async init() {
            await this.loadConnections();
        },
        async loadConnections() {
            this.loading = true;
            try {
                const r = await fetch('/api/settings/vcs');
                if (r.status === 401) { location.href = '/login'; return; }
                if (!r.ok) { this.apiError = _fetchErr(r, _vcsFmt(_vcsI18n.loadConnectionsStatus, r.status)); return; }
                this.connections = await r.json();
            } catch (e) {
                this.apiError = _vcsI18n.loadFailed;
            } finally {
                this.loading = false;
            }
        },
        openCreate() {
            this.form = { provider: '', serverUrl: '', vcsUsername: '', accessToken: '' };
            this.providerDropdownOpen = false;
            this.slideout = true;
            this.$nextTick(() => oswlTrapFocus(this.$refs.createPanel));
        },
        closeCreate() {
            this.providerDropdownOpen = false;
            this.slideout = false;
            oswlReleaseFocus();
        },
        async addConnection() {
            if (!this.form.provider || !this.form.accessToken.trim()) return;
            this.creating = true;
            this.apiError = null;
            const providerName = this.providerLabel(this.form.provider);
            try {
                const body = {
                    provider: this.form.provider,
                    serverUrl: this.form.serverUrl.trim() || null,
                    vcsUsername: this.form.vcsUsername.trim() || null,
                    accessToken: this.form.accessToken
                };
                const r = await fetch('/api/settings/vcs', { method: 'POST', headers: this.headers(), body: JSON.stringify(body) });
                if (r.status === 401) { location.href = '/login'; return; }
                if (!r.ok) {
                    const errBody = await r.json().catch(() => ({}));
                    this.apiError = errBody.error || (_vcsI18n.connectFailed + ' (' + r.status + ')');
                    return;
                }
                this.closeCreate();
                await this.loadConnections();
                this.showToast(providerName + ' ' + _vcsI18n.connectedSuffix);
            } catch (e) {
                this.apiError = _vcsI18n.networkError;
            } finally {
                this.creating = false;
            }
        },
        disconnect(c) {
            this.confirmingDisconnect = c;
        },
        async doDisconnect() {
            const c = this.confirmingDisconnect;
            this.confirmingDisconnect = null;
            this.apiError = null;
            try {
                const r = await fetch('/api/settings/vcs/' + c.id, { method: 'DELETE', headers: this.headers() });
                if (r.status === 401) { location.href = '/login'; return; }
                if (!r.ok) { this.apiError = _vcsI18n.disconnectFailed; return; }
                await this.loadConnections();
                this.showToast(this.providerLabel(c.provider) + ' ' + _vcsI18n.disconnectedSuffix);
            } catch (e) {
                this.apiError = _vcsI18n.networkError;
            }
        },
        async connectGitlab() {
            if (!this.gitlabAccessToken.trim()) return;
            if (this.gitlabMode === 'selfhosted' && !this.gitlabServerUrl.trim()) return;
            const dirtyRevision = window.OswlDirty?.revision('vcs-gitlab');
            this.creating = true;
            this.apiError = null;
            try {
                const body = {
                    provider: 'GITLAB',
                    serverUrl: this.gitlabMode === 'selfhosted' ? this.gitlabServerUrl.trim() : null,
                    vcsUsername: null,
                    accessToken: this.gitlabAccessToken
                };
                const r = await fetch('/api/settings/vcs', { method: 'POST', headers: this.headers(), body: JSON.stringify(body) });
                if (r.status === 401) { location.href = '/login'; return; }
                if (!r.ok) {
                    const errBody = await r.json().catch(() => ({}));
                    this.apiError = errBody.error || (_vcsI18n.connectFailed + ' (' + r.status + ')');
                    return;
                }
                const savedCurrentRevision = !window.OswlDirty
                    || window.OswlDirty.clear('vcs-gitlab', dirtyRevision);
                if (savedCurrentRevision) {
                    this.gitlabMode = 'cloud';
                    this.gitlabServerUrl = '';
                    this.gitlabAccessToken = '';
                }
                await this.loadConnections();
                this.showToast(_vcsI18n.providerGitlab + ' ' + _vcsI18n.connectedSuffix);
            } catch (e) {
                this.apiError = _vcsI18n.networkError;
            } finally {
                this.creating = false;
            }
        },
        async connectGithub() {
            if (!this.githubAccessToken.trim()) return;
            if (this.githubMode === 'enterprise' && !this.githubServerUrl.trim()) return;
            const dirtyRevision = window.OswlDirty?.revision('vcs-github');
            this.creating = true;
            this.apiError = null;
            try {
                const body = {
                    provider: 'GITHUB',
                    serverUrl: this.githubMode === 'enterprise' ? this.githubServerUrl.trim() : null,
                    vcsUsername: null,
                    accessToken: this.githubAccessToken
                };
                const r = await fetch('/api/settings/vcs', { method: 'POST', headers: this.headers(), body: JSON.stringify(body) });
                if (r.status === 401) { location.href = '/login'; return; }
                if (!r.ok) {
                    const errBody = await r.json().catch(() => ({}));
                    this.apiError = errBody.error || (_vcsI18n.connectFailed + ' (' + r.status + ')');
                    return;
                }
                const savedCurrentRevision = !window.OswlDirty
                    || window.OswlDirty.clear('vcs-github', dirtyRevision);
                if (savedCurrentRevision) {
                    this.githubMode = 'cloud';
                    this.githubServerUrl = '';
                    this.githubAccessToken = '';
                }
                await this.loadConnections();
                this.showToast(_vcsI18n.providerGithub + ' ' + _vcsI18n.connectedSuffix);
            } catch (e) {
                this.apiError = _vcsI18n.networkError;
            } finally {
                this.creating = false;
            }
        },
        async connectBitbucket() {
            if (!this.bitbucketAccessToken.trim()) return;
            if (this.bitbucketMode === 'server' && !this.bitbucketServerUrl.trim()) return;
            if (this.bitbucketMode === 'cloud' && !this.bitbucketUsername.trim()) return;
            const dirtyRevision = window.OswlDirty?.revision('vcs-bitbucket');
            this.creating = true;
            this.apiError = null;
            try {
                const isServer = this.bitbucketMode === 'server';
                const email = this.bitbucketEmail.trim();
                const slug = this.bitbucketUsername.trim();
                const vcsUsername = isServer
                        ? null
                        : (email ? email + '|' + slug : slug);
                const body = {
                    provider: 'BITBUCKET',
                    serverUrl: isServer ? this.bitbucketServerUrl.trim() : null,
                    vcsUsername,
                    accessToken: this.bitbucketAccessToken
                };
                const r = await fetch('/api/settings/vcs', { method: 'POST', headers: this.headers(), body: JSON.stringify(body) });
                if (r.status === 401) { location.href = '/login'; return; }
                if (!r.ok) {
                    const errBody = await r.json().catch(() => ({}));
                    this.apiError = errBody.error || (_vcsI18n.connectFailed + ' (' + r.status + ')');
                    return;
                }
                const savedCurrentRevision = !window.OswlDirty
                    || window.OswlDirty.clear('vcs-bitbucket', dirtyRevision);
                if (savedCurrentRevision) {
                    this.bitbucketMode = 'cloud';
                    this.bitbucketServerUrl = '';
                    this.bitbucketUsername = '';
                    this.bitbucketEmail = '';
                    this.bitbucketAccessToken = '';
                }
                await this.loadConnections();
                this.showToast(_vcsI18n.providerBitbucket + ' ' + _vcsI18n.connectedSuffix);
            } catch (e) {
                this.apiError = _vcsI18n.networkError;
            } finally {
                this.creating = false;
            }
        }
    };
}
