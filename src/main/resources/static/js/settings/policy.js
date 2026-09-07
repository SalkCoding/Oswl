function policyTab() {
    return {
        subTab: initialSettingsSection('policies', ['policies', 'waivers', 'sync']),
        apiError: null,
        retryTarget: null,
        toastVisible: false, toastMsg: '',

        scopeOptions: { organization: null, teams: [], projects: [] },
        scopeOptionsLoading: false,
        scopeOptionsLoaded: false,
        scopeOptionsFailed: false,
        policies: [],
        policiesLoading: false,
        formOpen: false,
        editingId: null,
        form: { scopeType: 'PROJECT', scopeId: '', name: '', description: '', locked: false, enabled: true,
                 failOnSeverity: '', failOnKev: '', failOnEpss: '', failOnLicenseViolation: '', onlyNew: '', onlyReachable: '', failOnSecrets: '' },

        waiverProjectId: '',
        exceptions: [],
        exceptionsLoading: false,
        waiverFormOpen: false,
        waiverForm: { reason: '', expiry: '', targetType: 'ALL', targetId: '', componentCoordinate: '' },

        importText: '',
        importing: false,
        importResult: null,
        exportingAll: false,
        gitops: { projectId: '', repositoryUrl: '', accessToken: '', branch: '' },
        gitopsSyncing: false,
        gitopsResult: null,
        gitopsOk: true,
        effectiveProjectId: '',
        effective: null,
        effectiveLoading: false,

        headers() { return oswlJsonHeaders(); },
        showToast(msg) { this.toastMsg = msg; this.toastVisible = true; setTimeout(() => { this.toastVisible = false; }, 2500); },
        formatTime(iso) {
            if (!iso) return '-';
            const d = new Date(iso);
            return d.toLocaleString(undefined, { year: 'numeric', month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' });
        },

        init() {
            onSettingsSectionPopstate(() => {
                const section = initialSettingsSection('policies', ['policies', 'waivers', 'sync']);
                if (this.subTab !== section) this.subTab = section;
            });
            this.loadScopeOptions();
            this.loadPolicies();
        },
        selectSubTab(key) {
            this.subTab = key;
            syncSettingsSection(key);
        },

        retry() {
            if (this.retryTarget === 'scope') return this.loadScopeOptions();
            if (this.retryTarget === 'exceptions') return this.loadExceptions(false);
            if (this.retryTarget === 'effective') return this.loadEffective();
            return this.loadPolicies();
        },

        async loadScopeOptions() {
            if (this.scopeOptionsLoading) return;
            this.scopeOptionsLoading = true;
            this.scopeOptionsFailed = false;
            try {
                const r = await fetch('/api/policies/scope-options');
                if (r.status === 401) { location.href = '/login'; return; }
                if (!r.ok) throw new Error('scope options failed');
                this.scopeOptions = await r.json();
                this.scopeOptionsLoaded = true;
                if (this.retryTarget === 'scope') {
                    this.apiError = null;
                    this.retryTarget = null;
                }
            } catch (e) {
                this.scopeOptionsFailed = true;
                this.apiError = _policyI18n.scopeLoadFailed;
                this.retryTarget = 'scope';
            } finally {
                this.scopeOptionsLoading = false;
            }
        },

        async loadPolicies() {
            this.policiesLoading = true;
            this.apiError = null;
            try {
                const r = await fetch('/api/policies');
                if (r.status === 401) { location.href = '/login'; return; }
                if (!r.ok) { this.apiError = _fetchErr(r, _policyI18n.loadFailed); this.retryTarget = 'policies'; return; }
                this.policies = await r.json();
                if (this.retryTarget === 'policies') this.retryTarget = null;
            } catch (e) {
                this.apiError = _policyI18n.loadFailed;
                this.retryTarget = 'policies';
            } finally {
                this.policiesLoading = false;
            }
        },

        scopeOptionsFor(scopeType) {
            if (scopeType === 'TEAM') return this.scopeOptions.teams || [];
            if (scopeType === 'PROJECT') return this.scopeOptions.projects || [];
            return [];
        },

        emptyForm() {
            return { scopeType: 'PROJECT', scopeId: '', name: '', description: '', locked: false, enabled: true,
                     failOnSeverity: '', failOnKev: '', failOnEpss: '', failOnLicenseViolation: '', onlyNew: '', onlyReachable: '', failOnSecrets: '' };
        },
        openNewPolicy() {
            this.editingId = null;
            this.form = this.emptyForm();
            this.formOpen = true;
        },
        openEditPolicy(p) {
            this.editingId = p.id;
            this.form = {
                scopeType: p.scope, scopeId: p.scopeId != null ? String(p.scopeId) : '',
                name: p.name, description: p.description || '',
                locked: p.locked, enabled: p.enabled,
                failOnSeverity: p.failOnSeverity || '',
                failOnKev: p.failOnKev === null || p.failOnKev === undefined ? '' : String(p.failOnKev),
                failOnEpss: p.failOnEpss === null || p.failOnEpss === undefined ? '' : String(p.failOnEpss),
                failOnLicenseViolation: p.failOnLicenseViolation === null || p.failOnLicenseViolation === undefined ? '' : String(p.failOnLicenseViolation),
                onlyNew: p.onlyNew === null || p.onlyNew === undefined ? '' : String(p.onlyNew),
                onlyReachable: p.onlyReachable === null || p.onlyReachable === undefined ? '' : String(p.onlyReachable),
                failOnSecrets: p.failOnSecrets === null || p.failOnSecrets === undefined ? '' : String(p.failOnSecrets)
            };
            this.formOpen = true;
        },
        closeForm() {
            this.formOpen = false;
            // Cancel discards the edits, so the leave-warning must go with them.
            if (window.OswlDirty) window.OswlDirty.clear('policy');
        },
        triBool(v) { return v === '' ? null : v === 'true'; },

        async savePolicy() {
            this.apiError = null;
            if (!this.form.name || !this.form.name.trim()) { this.apiError = _policyI18n.nameRequired; return; }
            const scopeId = this.form.scopeType === 'ORGANIZATION'
                ? (this.scopeOptions.organization ? this.scopeOptions.organization.id : null)
                : (this.form.scopeId ? Number(this.form.scopeId) : null);
            if (!scopeId) { this.apiError = _policyI18n.scopeRequired; return; }
            const body = {
                scopeType: this.form.scopeType,
                scopeId: scopeId,
                name: this.form.name,
                description: this.form.description,
                locked: this.form.locked,
                enabled: this.form.enabled,
                failOnSeverity: this.form.failOnSeverity || null,
                failOnKev: this.triBool(this.form.failOnKev),
                failOnEpss: this.form.failOnEpss === '' ? null : Number(this.form.failOnEpss),
                failOnLicenseViolation: this.triBool(this.form.failOnLicenseViolation),
                onlyNew: this.triBool(this.form.onlyNew),
                onlyReachable: this.triBool(this.form.onlyReachable),
                failOnSecrets: this.triBool(this.form.failOnSecrets)
            };
            const dirtyRevision = window.OswlDirty?.revision('policy');
            try {
                const url = this.editingId ? ('/api/policies/' + this.editingId) : '/api/policies';
                const method = this.editingId ? 'PUT' : 'POST';
                const r = await fetch(url, { method, headers: this.headers(), body: JSON.stringify(body) });
                if (r.status === 401) { location.href = '/login'; return; }
                if (!r.ok) { this.apiError = _fetchErr(r, _policyI18n.saveFailed); return; }
                const savedCurrentRevision = !window.OswlDirty
                    || window.OswlDirty.clear('policy', dirtyRevision);
                if (savedCurrentRevision) this.formOpen = false;
                await this.loadPolicies();
                this.showToast(_policyI18n.saved);
            } catch (e) {
                this.apiError = _policyI18n.saveFailed;
            }
        },

        async deletePolicy(p) {
            if (!confirm(_policyI18n.confirmDelete)) return;
            this.apiError = null;
            try {
                const r = await fetch('/api/policies/' + p.id, { method: 'DELETE', headers: this.headers() });
                if (r.status === 401) { location.href = '/login'; return; }
                if (!r.ok) { this.apiError = _fetchErr(r, _policyI18n.deleteFailed); return; }
                await this.loadPolicies();
                this.showToast(_policyI18n.deleted);
            } catch (e) {
                this.apiError = _policyI18n.deleteFailed;
            }
        },

        gateSummary(p) {
            const parts = [];
            if (p.failOnSeverity) parts.push(p.failOnSeverity);
            if (p.failOnKev === true) parts.push('KEV');
            if (p.failOnEpss !== null && p.failOnEpss !== undefined) parts.push('EPSS≥' + p.failOnEpss);
            if (p.failOnLicenseViolation === true) parts.push('License');
            if (p.onlyNew === true) parts.push('New-only');
            if (p.onlyReachable === true) parts.push('Reachable-only');
            if (p.failOnSecrets === true) parts.push('Secrets');
            return parts.length ? parts.join(' · ') : _policyI18n.noOverrides;
        },

        openNewWaiver() {
            this.waiverForm = { reason: '', expiry: '', targetType: 'ALL', targetId: '', componentCoordinate: '' };
            this.waiverFormOpen = true;
        },
        async loadExceptions(discardDraft = true) {
            // Switching projects discards the half-filled waiver form.
            if (discardDraft) {
                this.waiverFormOpen = false;
                if (window.OswlDirty) window.OswlDirty.clear('policy-waiver');
            }
            if (!this.waiverProjectId) { this.exceptions = []; return; }
            this.exceptionsLoading = true;
            this.apiError = null;
            try {
                const r = await fetch('/api/policies/exceptions/' + this.waiverProjectId);
                if (r.status === 401) { location.href = '/login'; return; }
                if (!r.ok) { this.apiError = _fetchErr(r, _policyI18n.waiverLoadFailed); this.retryTarget = 'exceptions'; return; }
                this.exceptions = await r.json();
                if (this.retryTarget === 'exceptions') this.retryTarget = null;
            } catch (e) {
                this.apiError = _policyI18n.waiverLoadFailed;
                this.retryTarget = 'exceptions';
            } finally {
                this.exceptionsLoading = false;
            }
        },
        async submitWaiver() {
            if (!this.waiverProjectId) return;
            const dirtyRevision = window.OswlDirty?.revision('policy-waiver');
            this.apiError = null;
            try {
                const body = {
                    projectId: Number(this.waiverProjectId),
                    reason: this.waiverForm.reason,
                    expiry: this.waiverForm.expiry ? (this.waiverForm.expiry + 'T23:59:59') : null,
                    targetType: this.waiverForm.targetType,
                    targetId: this.waiverForm.targetId || null,
                    componentCoordinate: this.waiverForm.componentCoordinate || null
                };
                const r = await fetch('/api/policies/exceptions', { method: 'POST', headers: this.headers(), body: JSON.stringify(body) });
                if (r.status === 401) { location.href = '/login'; return; }
                if (!r.ok) { this.apiError = _fetchErr(r, _policyI18n.waiverRequestFailed); return; }
                const savedCurrentRevision = !window.OswlDirty
                    || window.OswlDirty.clear('policy-waiver', dirtyRevision);
                this.waiverFormOpen = !savedCurrentRevision;
                await this.loadExceptions(false);
                this.showToast(_policyI18n.waiverRequested);
            } catch (e) {
                this.apiError = _policyI18n.waiverRequestFailed;
            }
        },
        async approveWaiver(ex) {
            this.apiError = null;
            try {
                const r = await fetch('/api/policies/exceptions/' + ex.id + '/approve', { method: 'POST', headers: this.headers() });
                if (r.status === 401) { location.href = '/login'; return; }
                if (!r.ok) { this.apiError = _fetchErr(r, _policyI18n.waiverActionFailed); return; }
                await this.loadExceptions();
                this.showToast(_policyI18n.waiverApproved);
            } catch (e) { this.apiError = _policyI18n.waiverActionFailed; }
        },
        async revokeWaiver(ex) {
            this.apiError = null;
            try {
                const r = await fetch('/api/policies/exceptions/' + ex.id + '/revoke', { method: 'POST', headers: this.headers() });
                if (r.status === 401) { location.href = '/login'; return; }
                if (!r.ok) { this.apiError = _fetchErr(r, _policyI18n.waiverActionFailed); return; }
                await this.loadExceptions();
                this.showToast(_policyI18n.waiverRevoked);
            } catch (e) { this.apiError = _policyI18n.waiverActionFailed; }
        },

        async exportAll() {
            this.exportingAll = true;
            this.apiError = null;
            try {
                const r = await fetch('/api/policies/export');
                if (r.status === 401) { location.href = '/login'; return; }
                if (!r.ok) { this.apiError = _fetchErr(r, _policyI18n.exportFailed); return; }
                const text = await r.text();
                const blob = new Blob([text], { type: 'application/x-yaml' });
                const url = URL.createObjectURL(blob);
                const a = document.createElement('a');
                a.href = url;
                a.download = 'oswl-policies-' + new Date().toISOString().slice(0, 10) + '.yaml';
                a.click();
                URL.revokeObjectURL(url);
            } catch (e) {
                this.apiError = _policyI18n.exportFailed;
            } finally {
                this.exportingAll = false;
            }
        },
        async importYaml() {
            if (!this.importText.trim()) return;
            const dirtyRevision = window.OswlDirty?.revision('policy-import');
            this.importing = true;
            this.apiError = null;
            this.importResult = null;
            try {
                const r = await fetch('/api/policies/import', {
                    method: 'POST', headers: this.headers(), body: JSON.stringify({ yaml: this.importText })
                });
                if (r.status === 401) { location.href = '/login'; return; }
                if (!r.ok) { this.apiError = _fetchErr(r, _policyI18n.importFailed); return; }
                const data = await r.json();
                this.importResult = _policyI18n.importResult.replace('{0}', String(data.length));
                if (window.OswlDirty) window.OswlDirty.clear('policy-import', dirtyRevision);
                await this.loadPolicies();
            } catch (e) {
                this.apiError = _policyI18n.importFailed;
            } finally {
                this.importing = false;
            }
        },
        async gitopsSync() {
            if (!this.gitops.projectId || !this.gitops.repositoryUrl) return;
            this.gitopsSyncing = true;
            this.apiError = null;
            this.gitopsResult = null;
            try {
                const body = {
                    projectId: Number(this.gitops.projectId),
                    repositoryUrl: this.gitops.repositoryUrl,
                    accessToken: this.gitops.accessToken || null,
                    branch: this.gitops.branch || null
                };
                const r = await fetch('/api/policies/gitops-sync', { method: 'POST', headers: this.headers(), body: JSON.stringify(body) });
                if (r.status === 401) { location.href = '/login'; return; }
                if (!r.ok) { this.gitopsOk = false; this.gitopsResult = _fetchErr(r, _policyI18n.gitopsFailed); return; }
                this.gitopsOk = true;
                this.gitopsResult = _policyI18n.gitopsSuccess;
                await this.loadPolicies();
            } catch (e) {
                this.gitopsOk = false;
                this.gitopsResult = _policyI18n.gitopsFailed;
            } finally {
                this.gitopsSyncing = false;
            }
        },
        async loadEffective() {
            if (!this.effectiveProjectId) return;
            this.effectiveLoading = true;
            this.apiError = null;
            try {
                const r = await fetch('/api/policies/effective/' + this.effectiveProjectId);
                if (r.status === 401) { location.href = '/login'; return; }
                if (!r.ok) { this.apiError = _fetchErr(r, _policyI18n.effectiveLoadFailed); this.retryTarget = 'effective'; return; }
                this.effective = await r.json();
                if (this.retryTarget === 'effective') this.retryTarget = null;
            } catch (e) {
                this.apiError = _policyI18n.effectiveLoadFailed;
                this.retryTarget = 'effective';
            } finally {
                this.effectiveLoading = false;
            }
        }
    };
}
