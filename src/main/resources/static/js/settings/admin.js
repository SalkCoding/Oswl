function adminTab() {
    return {
        // Restored from the URL's `section` query param when the requester has access to
        // that sub-tab, so a documentation link like /settings?tab=admin&section=audit opens
        // directly on it. Falls back to the same default as before otherwise.
        subTab: (function() {
            const fallback = _adminAccess.systemAdmin ? 'users' : (_adminAccess.auditView ? 'audit' : 'snapshot');
            const requested = initialSettingsSection(null, ['users', 'templates', 'audit', 'snapshot']);
            const allowedFor = {
                users: _adminAccess.systemAdmin,
                templates: _adminAccess.systemAdmin,
                audit: _adminAccess.auditView || _adminAccess.systemAdmin,
                snapshot: _adminAccess.snapshotManage || _adminAccess.systemAdmin
            };
            return (requested && allowedFor[requested]) ? requested : fallback;
        })(),
        users: [], templates: [], allPermissions: [],
        initialLoading: true,   // true until the first users/templates/permissions load settles
        sidebarMode: null,  // 'invite' | 'editUser' | 'newTemplate' | 'editTemplate' | null
        apiError: null,
        inviteError: null,
        inviteErrorField: null,
        toast: null,
        _toastTimer: null,
        page: 1, pageSize: 10,
        tplPage: 1, tplPageSize: 10,
        deletingTemplate: null,
        deletingUser: null,
        invite: { email: '', displayName: '', temporaryPassword: '', templateId: null, message: '' },
        editingUser: null, editingDisplayName: '', editingNameError: '', editingTemplateId: null, editingMessage: '',
        editingTpl: null, tplForm: { name: '', description: '', permissions: [] },
        auditFilter: { dateRange: '7', actorEmail: '', action: '', dateFrom: '', dateTo: '' },
        auditLogs: [],
        auditLoading: false,
        auditPage: 0, auditTotalPages: 0, auditTotalElements: 0, auditPageSize: 20,
        auditPsOpen: false,
        actorDropdownOpen: false,
        actorSearchQuery: '',
        actionDropdownOpen: false,
        dateDropdownOpen: false,
        minPasswordLength: 8,
        snapshotAirgapped: false,
        snapshotSources: [],
        snapshotOldestAsOf: null,
        snapshotStalenessWarnDays: 7,
        snapshotStalenessCriticalDays: 30,
        snapshotFile: null,
        snapshotImportMode: '',
        snapshotImporting: false,
        snapshotShowPathImport: false,
        snapshotImportPath: '',
        actionGroups: [
            { label: _auditGroupLabels['Auth'],             items: ['AUTH.LOGIN_SUCCESS', 'AUTH.LOGIN_FAILURE', 'AUTH.LOGOUT', 'AUTH.OTP_FAILURE', 'AUTH.OTP_RESEND', 'AUTH.TRUSTED_DEVICE', 'AUTH.PASSWORD_CHANGE', 'SAML.LOGIN_SUCCESS', 'SAML.LOGIN_FAILURE'] },
            { label: _auditGroupLabels['User'],             items: ['USER.CREATE', 'USER.UPDATE_NAME', 'USER.UPDATE_ROLES', 'USER.UPDATE_THEME', 'USER.ACTIVATE', 'USER.DEACTIVATE', 'USER.DELETE', 'USER.SELF_DELETE'] },
            { label: _auditGroupLabels['Role Template'],    items: ['ROLE_TEMPLATE.CREATE', 'ROLE_TEMPLATE.UPDATE', 'ROLE_TEMPLATE.DELETE'] },
            { label: _auditGroupLabels['Project'],          items: ['PROJECT.CREATE', 'PROJECT.DELETE', 'PROJECT.RESTORE', 'PROJECT.PERMANENT_DELETE', 'QUICK_IMPORT.START', 'QUICK_IMPORT.CANCEL', 'PROJECT.DEPLOYMENT_PROFILE', 'PROJECT.BATCH_PR', 'SBOM.IMPORT'] },
            { label: _auditGroupLabels['VCS'],              items: ['VCS.CONNECT', 'VCS.DISCONNECT', 'GITHUB.PAT_CONNECT', 'GITHUB.PAT_DISCONNECT'] },
            { label: _auditGroupLabels['CLI Key'],          items: ['CLI_KEY.CREATE', 'CLI_KEY.ACTIVATE', 'CLI_KEY.REVOKE'] },
            { label: _auditGroupLabels['Security'],         items: ['SECURITY_SETTING.UPDATE', 'SECURITY_SETTING.MAIL_TEST'] },
            { label: _auditGroupLabels['AI Setting'],       items: ['AI_SETTING.SAVE', 'AI_SETTING.ACTIVATE', 'AI_SETTING.DEACTIVATE', 'AI_SETTING.TEST', 'AI_SETTING.PREFERENCES_UPDATE', 'AI_SETTING.BACKFILL', 'AI_SETTING.EMBEDDED_START', 'AI_SETTING.EMBEDDED_STOP', 'AI_SETTING.EMBEDDED_CONFIG'] },
            { label: _auditGroupLabels['Cache'],            items: ['CACHE.UPDATE_TTL', 'CACHE.CLEAR'] },
            { label: _auditGroupLabels['External Setting'], items: ['EXTERNAL_SETTING.CACHE_POLICY_UPDATE', 'EXTERNAL_SETTING.GITHUB_UPDATE', 'REPORT_BRANDING.UPDATE'] },
            { label: _auditGroupLabels['License'],          items: ['LICENSE_POLICY.UPDATE', 'LICENSE.EXPORT', 'LICENSE.REFRESH_AI_INSIGHT'] },
            { label: _auditGroupLabels['Scan'],             items: ['SCAN.INGEST', 'SCAN.DELETE', 'SCAN.AUTH_FAILURE', 'SCAN.AUTH_RATE_LIMITED', 'SCAN.API_KEY_FAILURE', 'GATE.EVALUATE', 'GATE.GITHUB_PUBLISH'] },
            { label: _auditGroupLabels['Component'],        items: ['COMPONENT.DEFER', 'COMPONENT.DEFER_ALL', 'COMPONENT.CREATE_PR', 'COMPONENT.CVE_AI_REGENERATE', 'COMPONENT.CVE_AI_REGENERATE_FAILED', 'COMPONENT.BULK_STATUS_UPDATE', 'COMPONENT.DEFER_EXPIRE', 'COMPONENT.JIRA_TICKET'] },
            { label: _auditGroupLabels['Security Center'],  items: ['SECURITY_CENTER.EXPORT', 'SECURITY_CENTER.PRINT', 'SECURITY_CENTER.REFRESH_AI_INSIGHT', 'SBOM.EXPORT', 'VEX.EXPORT', 'SARIF.EXPORT', 'COMPLIANCE_REPORT.VIEW'] },
            { label: _auditGroupLabels['Monitoring'],       items: ['MONITOR.NEW_CVE', 'MONITOR.ALERT_EMAIL', 'MONITOR.ALERT_ACK'] },
            { label: _auditGroupLabels['Integration'],      items: ['JIRA.SETTINGS_UPDATE'] },
            { label: _auditGroupLabels['Webhook'],          items: ['WEBHOOK.SETTINGS_UPDATE', 'WEBHOOK.SENT', 'WEBHOOK.FAILED'] },
            { label: _auditGroupLabels['Administration'],   items: ['ORG_DASHBOARD.VIEW', 'AUDIT_LOG.EXPORT', 'AUDIT_LOG.VERIFY', 'SNAPSHOT.IMPORT', 'SNAPSHOT.EXPORT', 'SNAPSHOT.WANTED_LIST_EXPORT'] },
            { label: _auditGroupLabels['SCIM Provisioning'],items: ['SCIM.USER_CREATE', 'SCIM.USER_UPDATE', 'SCIM.USER_DEACTIVATE', 'SCIM.GROUP_CREATE', 'SCIM.GROUP_UPDATE', 'SCIM.GROUP_DELETE', 'SCIM.GROUP_MEMBER_ADD', 'SCIM.GROUP_MEMBER_REMOVE', 'SCIM.AUTH_FAILURE', 'SCIM_KEY.CREATE'] },
            { label: _auditGroupLabels['Policy'],           items: ['POLICY.CREATE', 'POLICY.UPDATE', 'POLICY.DELETE', 'POLICY.IMPORT', 'POLICY.GITOPS_SYNC', 'POLICY_EXCEPTION.REQUEST', 'POLICY_EXCEPTION.APPROVE', 'POLICY_EXCEPTION.REVOKE', 'POLICY_EXCEPTION.EXPIRE'] },
            { label: 'System',                              items: ['SYSTEM.SETUP'] },
        ],
        get filteredAuditUsers() {
            const q = this.actorSearchQuery.toLowerCase();
            return this.users.filter(u =>
                !q ||
                (u.email || '').toLowerCase().includes(q) ||
                (u.displayName || '').toLowerCase().includes(q)
            );
        },
        csrf() { return window.OswlCsrf ? window.OswlCsrf.token() : ''; },

        // ── Permission grouping ──
        // Every Permission enum value must be matched by exactly one prefix here. Anything
        // unmatched silently disappears from the template editor and becomes ungrantable —
        // which is what happened to ORG_DASHBOARD_VIEW and AUDIT_LOG_* when they were added.
        // The catch-all group below makes a future omission visible instead of silent.
        permissionGroups: {
            'Project': ['PROJECT_'],
            'Scan': ['SCAN_'],
            'Security Center': ['SECURITY_CENTER_'],
            'License': ['LICENSE_'],
            'Analysis': ['COMPONENT_', 'VERSION_', 'RISK_'],
            'Settings': ['SETTINGS_'],
            'Administration': ['ORG_DASHBOARD_', 'AUDIT_LOG_']
        },
        get groupedPermissions() {
            const result = {};
            const claimed = new Set();
            for (const [name, prefixes] of Object.entries(this.permissionGroups)) {
                const label = _permGroupLabels[name] || name;
                const matched = this.allPermissions.filter(p => prefixes.some(pref => p.code.startsWith(pref)));
                matched.forEach(p => claimed.add(p.code));
                result[label] = matched;
            }
            const ungrouped = this.allPermissions.filter(p => !claimed.has(p.code));
            if (ungrouped.length) {
                result[_permGroupLabels['Other'] || 'Other'] = ungrouped;
            }
            return result;
        },
        // ── Pagination ──
        get totalPages() { return Math.max(1, Math.ceil(this.users.length / this.pageSize)); },
        get pagedUsers() {
            const start = (this.page - 1) * this.pageSize;
            return this.users.slice(start, start + this.pageSize);
        },
        get tplTotalPages() { return Math.max(1, Math.ceil(this.templates.length / this.tplPageSize)); },
        get pagedTemplates() {
            const start = (this.tplPage - 1) * this.tplPageSize;
            return this.templates.slice(start, start + this.tplPageSize);
        },
        // ── Avatar color from name ──
        avatarColor(s) {
            const palette = ['#0d9488','#0891b2','#059669','#0284c7','#0f766e','#0369a1','#14b8a6','#0ea5e9'];
            let h = 0; for (const c of (s||'?')) h = (h * 31 + c.charCodeAt(0)) >>> 0;
            return palette[h % palette.length];
        },

        async init() {
            onSettingsSectionPopstate(() => {
                const fallback = _adminAccess.systemAdmin ? 'users' : (_adminAccess.auditView ? 'audit' : 'snapshot');
                const requested = initialSettingsSection(null, ['users', 'templates', 'audit', 'snapshot']);
                const allowed = { users: _adminAccess.systemAdmin, templates: _adminAccess.systemAdmin,
                    audit: _adminAccess.auditView || _adminAccess.systemAdmin,
                    snapshot: _adminAccess.snapshotManage || _adminAccess.systemAdmin };
                if (requested && allowed[requested]) this.subTab = requested; else this.subTab = fallback;
            });
            // Users, templates and permissions are all SYSTEM_ADMIN-only APIs; skip them
            // entirely for a delegated user and open their panel instead.
            if (_adminAccess.systemAdmin) {
                try {
                    await Promise.all([this.loadUsers(), this.loadTemplates(), this.loadPermissions()]);
                } catch(e) {
                    this.apiError = e.message || _adminI18n.loadDataFailed;
                } finally {
                    this.initialLoading = false;
                }
                try {
                    const r = await fetch('/api/settings/security');
                    if (r.ok) {
                        const s = await r.json();
                        if (s.minPasswordLength) this.minPasswordLength = s.minPasswordLength;
                    }
                } catch(_) {}
                return;
            }
            this.initialLoading = false;
            if (this.subTab === 'audit') this.loadAudit(0);
            else if (this.subTab === 'snapshot') this.loadSnapshotStatus();
        },
        headers() { return oswlJsonHeaders(); },
        async apiGet(url) {
            const r = await fetch(url);
            if (r.status === 401) { location.href = '/login'; throw new Error('Unauthorized'); }
            if (!r.ok) throw new Error(_fetchErr(r, `API error ${r.status}: ${url}`));
            return r.json();
        },
        async loadUsers() { this.users = await this.apiGet('/api/admin/users'); },
        async loadTemplates() { this.templates = await this.apiGet('/api/admin/role-templates'); },
        async loadPermissions() { this.allPermissions = await this.apiGet('/api/admin/role-templates/permissions'); },
        randomPwd() {
            const chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789';
            let pwd = '';
            for (let i = 0; i < 12; i++) pwd += chars[Math.floor(Math.random() * chars.length)];
            return pwd;
        },

        selectSubTab(key) {
            this.subTab = key;
            this.closeSidebar();
            syncSettingsSection(key);
            if (key === 'templates') this.loadTemplates();
            else if (key === 'audit') this.loadAudit(0);
            else if (key === 'snapshot') this.loadSnapshotStatus();
        },

        // ── Sidebar control (mutually exclusive) ──
        closeSidebar() {
            this.sidebarMode = null;
            this.editingUser = null;
            this.editingTpl = null;
            this.inviteError = null;
            this.inviteErrorField = null;
            oswlReleaseFocus();
        },
        showToast(msg, duration = 3500) {
            this.toast = msg;
            clearTimeout(this._toastTimer);
            this._toastTimer = setTimeout(() => { this.toast = null; }, duration);
        },
        sanitizeDate(val) {
            if (!val) return '';
            const parts = val.split('-');
            if (parts.length !== 3 || parts[0].length !== 4) return '';
            return val;
        },
        openInvite() {
            this.invite = { email: '', displayName: '', temporaryPassword: '', templateId: null, message: '' };
            this.editingUser = null;
            this.inviteError = null;
            this.inviteErrorField = null;
            this.sidebarMode = 'invite';
            this.$nextTick(() => oswlTrapFocus(this.$refs.userPanel));
        },
        openEditUser(u) {
            this.editingUser = u;
            this.editingDisplayName = u.displayName || '';
            this.editingNameError = '';
            this.editingTemplateId = (u.roleTemplates && u.roleTemplates.length > 0) ? u.roleTemplates[0].id : null;
            this.editingMessage = '';
            this.sidebarMode = 'editUser';
            this.$nextTick(() => oswlTrapFocus(this.$refs.userPanel));
        },
        openNewTemplate() {
            this.editingTpl = null;
            this.tplForm = { name: '', description: '', permissions: [] };
            this.sidebarMode = 'newTemplate';
            this.$nextTick(() => oswlTrapFocus(this.$refs.templatePanel));
        },
        openEditTemplate(t) {
            this.editingTpl = t;
            this.tplForm = { name: t.name, description: t.description||'', permissions: [...(t.permissions||[])] };
            this.sidebarMode = 'editTemplate';
            this.$nextTick(() => oswlTrapFocus(this.$refs.templatePanel));
        },

        // ── Permission Select All ──
        allSelected() { return this.allPermissions.length > 0 && this.tplForm.permissions.length === this.allPermissions.length; },
        toggleAllPermissions() {
            if (this.allSelected()) this.tplForm.permissions = [];
            else this.tplForm.permissions = this.allPermissions.map(p => p.code);
        },
        groupAllSelected(group) { return group.length > 0 && group.every(p => this.tplForm.permissions.includes(p.code)); },
        toggleGroup(group) {
            if (this.groupAllSelected(group)) {
                this.tplForm.permissions = this.tplForm.permissions.filter(c => !group.some(p => p.code === c));
            } else {
                const codes = new Set(this.tplForm.permissions);
                group.forEach(p => codes.add(p.code));
                this.tplForm.permissions = [...codes];
            }
        },

        async submitInvite() {
            this.inviteError = null;
            this.inviteErrorField = null;
            const emailRe = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
            if (!this.invite.displayName.trim()) {
                this.inviteError = _adminI18n.validateNameRequired; this.inviteErrorField = 'name'; return;
            }
            if (!this.invite.email.trim()) {
                this.inviteError = _adminI18n.validateEmailRequired; this.inviteErrorField = 'email'; return;
            }
            if (!emailRe.test(this.invite.email.trim())) {
                this.inviteError = _adminI18n.validateEmailInvalid; this.inviteErrorField = 'email'; return;
            }
            if (!this.invite.temporaryPassword.trim()) {
                this.inviteError = _adminI18n.validatePasswordRequired; this.inviteErrorField = 'password'; return;
            }
            if (this.invite.temporaryPassword.trim().length < this.minPasswordLength) {
                this.inviteError = _adminI18n.validatePasswordTooShort.replace('{0}', this.minPasswordLength); this.inviteErrorField = 'password'; return;
            }
            const { templateId, ...rest } = this.invite;
            const payload = { ...rest, templateIds: templateId ? [templateId] : [] };
            const r = await fetch('/api/admin/users', { method: 'POST', headers: this.headers(), body: JSON.stringify(payload) });
            if (r.status === 401) { location.href = '/login'; return; }
            if (!r.ok) { this.inviteError = _fetchErr(r, _adminI18n.inviteFailed); return; }
            if (!templateId) {
                const created = await r.json();
                await fetch(`/api/admin/users/${created.id}/deactivate`, { method: 'PUT', headers: this.headers() });
            }
            this.closeSidebar();
            await this.loadUsers();
        },
        async saveRoles() {
            const name = this.editingDisplayName.trim();
            if (name.length < 1) { this.editingNameError = _adminI18n.nameTooShort; return; }
            if (name.length > 20) { this.editingNameError = _adminI18n.nameTooLong; return; }
            if (name !== (this.editingUser.displayName || '').trim()) {
                const nr = await fetch(`/api/admin/users/${this.editingUser.id}/display-name`, { method: 'PUT', headers: this.headers(), body: JSON.stringify({ displayName: name }) });
                if (nr.status === 401) { location.href = '/login'; return; }
                if (!nr.ok) { alert(_fetchErr(nr, _adminI18n.updateNameFailed)); return; }
            }
            const r = await fetch(`/api/admin/users/${this.editingUser.id}/roles`, { method: 'PUT', headers: this.headers(), body: JSON.stringify({ templateIds: this.editingTemplateId ? [this.editingTemplateId] : [] }) });
            if (r.status === 401) { location.href = '/login'; return; }
            if (!r.ok) { alert(_fetchErr(r, _adminI18n.actionFailed)); return; }
            // Auto-deactivate if no role assigned and user is currently active
            if (!this.editingTemplateId && this.editingUser.enabled) {
                const dr = await fetch(`/api/admin/users/${this.editingUser.id}/deactivate`, { method: 'PUT', headers: this.headers() });
                if (dr.status === 401) { location.href = '/login'; return; }
            }
            this.closeSidebar();
            await Promise.all([this.loadUsers(), this.loadTemplates()]);
        },
        async toggleEnabled(u, enabled) {
            const url = `/api/admin/users/${u.id}/${enabled ? 'activate' : 'deactivate'}`;
            const r = await fetch(url, { method: 'PUT', headers: this.headers() });
            if (r.status === 401) { location.href = '/login'; return; }
            await this.loadUsers();
        },
        async deleteUser(u) {
            this.deletingUser = null;
            const r = await fetch(`/api/admin/users/${u.id}`, { method: 'DELETE', headers: this.headers() });
            if (r.status === 401) { location.href = '/login'; return; }
            if (!r.ok) { alert(_fetchErr(r, _adminI18n.actionFailed)); return; }
            await this.loadUsers();
        },
        async saveTemplate() {
            const url = this.editingTpl?.id ? `/api/admin/role-templates/${this.editingTpl.id}` : '/api/admin/role-templates';
            const method = this.editingTpl?.id ? 'PUT' : 'POST';
            const r = await fetch(url, { method, headers: this.headers(), body: JSON.stringify(this.tplForm) });
            if (r.status === 401) { location.href = '/login'; return; }
            if (!r.ok) { alert(_fetchErr(r, _adminI18n.actionFailed)); return; }
            this.closeSidebar(); await this.loadTemplates();
        },
        async deleteTemplate(t) {
            const r = await fetch(`/api/admin/role-templates/${t.id}`, { method: 'DELETE', headers: this.headers() });
            if (r.status === 401) { location.href = '/login'; return; }
            if (!r.ok) { alert(_fetchErr(r, _adminI18n.actionFailed)); return; }
            this.deletingTemplate = null;
            await this.loadTemplates();
        },
        async loadAudit(page) {
            if (page !== undefined) this.auditPage = page;
            const params = new URLSearchParams();
            if (this.auditFilter.dateRange === 'custom') {
                if (this.auditFilter.dateFrom) params.set('startDate', this.auditFilter.dateFrom + 'T00:00:00');
                if (this.auditFilter.dateTo)   params.set('endDate',   this.auditFilter.dateTo   + 'T23:59:59');
            } else if (this.auditFilter.dateRange) {
                const days = parseInt(this.auditFilter.dateRange, 10);
                const start = new Date();
                start.setDate(start.getDate() - days);
                params.set('startDate', start.toISOString().slice(0, 19));
            }
            if (this.auditFilter.actorEmail) params.set('actorEmail', this.auditFilter.actorEmail);
            if (this.auditFilter.action) params.set('action', this.auditFilter.action);
            params.set('page', this.auditPage);
            params.set('size', this.auditPageSize);
            this.auditLoading = true;
            try {
                const r = await fetch('/api/admin/audit-logs?' + params.toString());
                if (r.status === 401) { location.href = '/login'; return; }
            if (!r.ok) { this.apiError = _fetchErr(r, _adminI18n.auditLoadFailed); return; }
                const data = await r.json();
                this.auditLogs = data.content || [];
                this.auditTotalPages = data.page?.totalPages || data.totalPages || 0;
                this.auditTotalElements = data.page?.totalElements || data.totalElements || 0;
            } catch(e) { this.apiError = e.message || _adminI18n.auditLoadFailed; }
            finally { this.auditLoading = false; }
        },
        pageRange() {
            const total = this.auditTotalPages;
            const cur = this.auditPage + 1;
            if (total <= 7) return Array.from({ length: total }, (_, i) => i + 1);
            const range = [1];
            if (cur > 3) range.push('...');
            for (let i = Math.max(2, cur - 1); i <= Math.min(total - 1, cur + 1); i++) range.push(i);
            if (cur < total - 2) range.push('...');
            range.push(total);
            return range;
        },
        auditExportUrl(format) {
            const params = new URLSearchParams();
            if (this.auditFilter.dateRange === 'custom') {
                if (this.auditFilter.dateFrom) params.set('startDate', this.auditFilter.dateFrom + 'T00:00:00');
                if (this.auditFilter.dateTo)   params.set('endDate',   this.auditFilter.dateTo   + 'T23:59:59');
            } else if (this.auditFilter.dateRange) {
                const days = parseInt(this.auditFilter.dateRange, 10);
                const start = new Date();
                start.setDate(start.getDate() - days);
                params.set('startDate', start.toISOString().slice(0, 19));
            }
            if (this.auditFilter.actorEmail) params.set('actorEmail', this.auditFilter.actorEmail);
            if (this.auditFilter.action) params.set('action', this.auditFilter.action);
            const qs = params.toString();
            if (!format || format === 'csv') return '/api/admin/audit-logs/export.csv' + (qs ? '?' + qs : '');
            return '/api/admin/audit-logs/export?format=' + format + (qs ? '&' + qs : '');
        },
        dateRangeLabel() {
            if (this.auditFilter.dateRange === 'custom') {
                if (this.auditFilter.dateFrom || this.auditFilter.dateTo) {
                    const from = this.auditFilter.dateFrom || '...';
                    const to   = this.auditFilter.dateTo   || '...';
                    return from + ' – ' + to;
                }
                return _adminI18n.customRange;
            }
            const map = {
                '':   _adminI18n.allTime,
                '7':  _adminI18n.last7Days,
                '30': _adminI18n.last30Days,
                '90': _adminI18n.last90Days
            };
            return map[this.auditFilter.dateRange] ?? _adminI18n.last7Days;
        },

        // ── Snapshot (offline VDB) ──
        async loadSnapshotStatus() {
            try {
                const r = await this.apiGet('/api/admin/snapshot');
                this.snapshotAirgapped = r.airgapped;
                this.snapshotSources = r.sources || [];
                this.snapshotOldestAsOf = r.oldestSourceAsOf || null;
                this.snapshotStalenessWarnDays = r.stalenessWarnDays;
                this.snapshotStalenessCriticalDays = r.stalenessCriticalDays;
            } catch(e) {
                this.apiError = e.message || _adminI18n.loadDataFailed;
            }
        },
        stalenessDays() {
            if (!this.snapshotOldestAsOf) return null;
            const asOf = new Date(this.snapshotOldestAsOf + 'T00:00:00');
            return Math.floor((Date.now() - asOf.getTime()) / 86400000);
        },
        stalenessLevel() {
            const days = this.stalenessDays();
            if (days === null) return 'none';
            if (days > this.snapshotStalenessCriticalDays) return 'critical';
            if (days > this.snapshotStalenessWarnDays) return 'warn';
            return 'fresh';
        },
        stalenessLabel() {
            return { fresh: _snapshotI18n.freshnessFresh, warn: _snapshotI18n.freshnessWarn,
                     critical: _snapshotI18n.freshnessCritical, none: _snapshotI18n.freshnessNone }[this.stalenessLevel()];
        },
        async importSnapshotFile() {
            if (!this.snapshotFile) return;
            this.snapshotImporting = true;
            try {
                const form = new FormData();
                form.append('file', this.snapshotFile);
                const url = '/api/admin/snapshot/import' + (this.snapshotImportMode ? '?mode=' + this.snapshotImportMode : '');
                const r = await fetch(url, { method: 'POST', headers: { 'X-XSRF-TOKEN': this.csrf() }, body: form });
                if (r.status === 401) { location.href = '/login'; return; }
                if (!r.ok) { this.showToast(_fetchErr(r, _snapshotI18n.importFailed)); return; }
                const result = await r.json();
                this.snapshotFile = null;
                this.showToast(_snapshotI18n.importSuccess.replace('{0}', result.totalRecords).replace('{1}', Object.keys(result.sources||{}).length));
                await this.loadSnapshotStatus();
            } catch(e) {
                this.showToast(e.message || _snapshotI18n.importFailed);
            } finally {
                this.snapshotImporting = false;
            }
        },
        async importSnapshotFromPath() {
            if (!this.snapshotImportPath) return;
            this.snapshotImporting = true;
            try {
                const r = await fetch('/api/admin/snapshot/import-from-path', {
                    method: 'POST', headers: this.headers(),
                    body: JSON.stringify({ path: this.snapshotImportPath, mode: this.snapshotImportMode || null })
                });
                if (r.status === 401) { location.href = '/login'; return; }
                if (!r.ok) { this.showToast(_fetchErr(r, _snapshotI18n.importFailed)); return; }
                const result = await r.json();
                this.snapshotImportPath = '';
                this.showToast(_snapshotI18n.importSuccess.replace('{0}', result.totalRecords).replace('{1}', Object.keys(result.sources||{}).length));
                await this.loadSnapshotStatus();
            } catch(e) {
                this.showToast(e.message || _snapshotI18n.importFailed);
            } finally {
                this.snapshotImporting = false;
            }
        }
    };
}
