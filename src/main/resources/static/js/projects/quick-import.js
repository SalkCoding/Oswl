/* ============================================================
   Quick Import Page — Alpine.js component
   Endpoint contract:
     GET  /api/quick-import/connections  → VcsConnectionDto[]
       { provider: 'GITHUB'|'GITLAB'|'BITBUCKET', vcsUsername: string }
     GET  /api/quick-import/repos?provider=  → QuickImportRepoDto[]
       { name, fullName, webUrl, defaultBranch, isPrivate, updatedAt }
     POST /api/quick-import/start        → { jobId: string }
       body: { repoUrl: string, branch: string|null }
     GET  /api/quick-import/job/:jobId   → QuickImportJobStatus
       { jobId, phase, message, projectId, projectName, apiToken,
         newApiKey, ecosystem, componentCount, error }
   ============================================================ */

const REPO_PAGE_SIZE = 10;

function quickImportPage() {
    return {
        /* ── VCS connections ─────────────────────── */
        loadingConnections: true,
        connectionsByProvider: {
            GITHUB: null,
            GITLAB: null,
            BITBUCKET: null,
        },

        /* ── Repo browsers ───────────────────────── */
        repoBrowsers: {
            GITHUB:    { repos: [], loading: false, error: null, search: '', page: 1 },
            GITLAB:    { repos: [], loading: false, error: null, search: '', page: 1 },
            BITBUCKET: { repos: [], loading: false, error: null, search: '', page: 1 },
        },

        /* ── Form state ──────────────────────────── */
        repoUrl: '',
        branch: '',
        urlError: '',
        isImporting: false,

        /* ── Job polling ─────────────────────────── */
        jobId: null,
        jobResult: {},
        progressLog: [],     // [{ status: 'running'|'done'|'error', text: string }]
        _pollTimer: null,
        _lastPhase: null,

        /* ── Copy feedback ───────────────────────── */
        keyCopied: false,


        /* ─────────────────────────────────────────── */

        async init() {
            await this.loadConnections();
            for (const p of this.connectedProviders) {
                this.loadRepoBrowser(p);
            }
        },

        /* ── Derived getters ──────────────────────── */

        get connectedProviders() {
            return ['GITHUB', 'GITLAB', 'BITBUCKET'].filter(
                p => this.connectionsByProvider[p] !== null
            );
        },

        get detectedProvider() {
            const url = this.repoUrl.toLowerCase();
            if (url.includes('github.com'))    return 'GITHUB';
            if (url.includes('gitlab'))        return 'GITLAB';
            if (url.includes('bitbucket.org')) return 'BITBUCKET';
            // Check self-hosted Bitbucket by matching the stored connection's serverUrl
            const bbConn = this.connectionsByProvider['BITBUCKET'];
            if (bbConn && bbConn.serverUrl) {
                try {
                    const connHost = new URL(bbConn.serverUrl).hostname.toLowerCase();
                    const urlHost  = new URL(this.repoUrl).hostname.toLowerCase();
                    if (connHost && urlHost === connHost) return 'BITBUCKET';
                } catch (_) {}
            }
            return null;
        },

        get providerNotConnected() {
            return (
                this.detectedProvider !== null &&
                this.connectionsByProvider[this.detectedProvider] === null
            );
        },

        get canImport() {
            if (!this.repoUrl.trim())   return false;
            if (!this.detectedProvider) return false;
            if (this.providerNotConnected) return false;
            if (this.urlError)          return false;
            return !this.isImporting;
        },

        get showProgress() {
            return this.progressLog.length > 0;
        },

        get importDone() {
            return this.jobResult && this.jobResult.phase === 'DONE';
        },

        /* ── Actions ──────────────────────────────── */

        async loadConnections() {
            this.loadingConnections = true;
            try {
                const res = await fetch('/api/quick-import/connections');
                if (!res.ok) throw new Error('HTTP ' + res.status);
                const list = await res.json();
                list.forEach(c => {
                    if (c.provider in this.connectionsByProvider) {
                        this.connectionsByProvider[c.provider] = c;
                    }
                });
            } catch (err) {
                console.error('[QuickImport] Failed to load connections:', err);
            } finally {
                this.loadingConnections = false;
            }
        },

        onUrlInput() {
            this.urlError = '';
            if (!this.repoUrl) return;
            try {
                const url = new URL(this.repoUrl);
                if (!['http:', 'https:'].includes(url.protocol)) {
                    this.urlError = 'URL must use http:// or https://.';
                }
            } catch (_) {
                this.urlError = 'Please enter a valid URL.';
            }
        },

        async startImport() {
            if (!this.canImport) return;
            this.isImporting  = true;
            this.jobResult    = {};
            this.progressLog  = [];
            this._lastPhase   = null;

            try {
                const res = await fetch('/api/quick-import/start', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({
                        repoUrl: this.repoUrl.trim(),
                        branch:  this.branch.trim() || null,
                    }),
                });

                if (!res.ok) {
                    const body = await res.text();
                    throw new Error(body || 'HTTP ' + res.status);
                }

                const data  = await res.json();
                this.jobId  = data.jobId;
                this._startPolling();
            } catch (err) {
                console.error('[QuickImport] Start failed:', err);
                this._appendLog('error', 'Failed to start import: ' + err.message);
                this.isImporting = false;
            }
        },

        _startPolling() {
            // Kick off an immediate first poll so the QUEUED phase entry appears right away,
            // then continue at 1.5s intervals. All log entries are driven by backend phases
            // so no manual "queued" entry is needed here.
            this._pollJob();
            this._pollTimer = setInterval(() => this._pollJob(), 1500);
        },

        async _pollJob() {
            try {
                const res = await fetch('/api/quick-import/job/' + this.jobId);
                if (res.status === 404) {
                    this._stopPolling();
                    this._appendLog('error', 'Job not found.');
                    this.isImporting = false;
                    return;
                }
                if (!res.ok) return; // retry on transient errors

                const job = await res.json();
                this._applyJobUpdate(job);
            } catch (err) {
                console.error('[QuickImport] Poll error:', err);
            }
        },

        _applyJobUpdate(job) {
            if (job.phase === this._lastPhase) {
                // Phase unchanged — update last log entry message if still running
                if (this.progressLog.length > 0 && job.message) {
                    const last = this.progressLog[this.progressLog.length - 1];
                    if (last.status === 'running') {
                        last.text = job.message || last.text;
                    }
                }
            } else {
                // Phase changed — mark previous as done, add new entry
                if (this._lastPhase !== null && this.progressLog.length > 0) {
                    const last = this.progressLog[this.progressLog.length - 1];
                    if (last.status === 'running') last.status = 'done';
                }

                const phaseLabels = {
                    QUEUED:   'Queued',
                    CLONING:  'Cloning repository…',
                    PARSING:  'Parsing dependencies…',
                    SCANNING: 'Running security scan…',
                    DONE:     'Done',
                    FAILED:   'Failed',
                };
                const label  = phaseLabels[job.phase] || job.phase;
                const text   = job.message ? label + ' — ' + job.message : label;
                const status =
                    job.phase === 'DONE'   ? 'done'    :
                    job.phase === 'FAILED' ? 'error'   :
                    'running';

                this._appendLog(status, text);
                this._lastPhase = job.phase;
            }

            if (job.phase === 'DONE' || job.phase === 'FAILED') {
                this._stopPolling();
                this.jobResult   = job;
                this.isImporting = false;
            }
        },

        /* ── Repo browser ───────────────────────── */

        async loadRepoBrowser(provider) {
            const b = this.repoBrowsers[provider];
            if (!b || b.loading) return;
            b.loading = true;
            b.error = null;
            try {
                const res = await fetch('/api/quick-import/repos?provider=' + provider);
                if (!res.ok) {
                    let msg = 'Failed to load repositories.';
                    try { const body = await res.json(); if (body.error) msg = body.error; } catch (_) {}
                    throw new Error(msg);
                }
                b.repos = await res.json();
                b.page = 1;
            } catch (err) {
                console.error('[RepoBrowser] Load failed for ' + provider + ':', err);
                b.error = err.message;
            } finally {
                b.loading = false;
            }
        },

        async refreshRepoBrowser(provider) {
            const b = this.repoBrowsers[provider];
            if (!b) return;
            b.repos = [];
            b.search = '';
            b.page = 1;
            await this.loadRepoBrowser(provider);
        },

        getBrowserFilteredRepos(provider) {
            const b = this.repoBrowsers[provider];
            if (!b) return [];
            const q = (b.search || '').toLowerCase();
            if (!q) return b.repos;
            return b.repos.filter(r => r.fullName.toLowerCase().includes(q) || r.name.toLowerCase().includes(q));
        },

        getBrowserTotalPages(provider) {
            return Math.max(1, Math.ceil(this.getBrowserFilteredRepos(provider).length / REPO_PAGE_SIZE));
        },

        getBrowserPagedRepos(provider) {
            const b = this.repoBrowsers[provider];
            if (!b) return [];
            const filtered = this.getBrowserFilteredRepos(provider);
            const start = (b.page - 1) * REPO_PAGE_SIZE;
            return filtered.slice(start, start + REPO_PAGE_SIZE);
        },

        browserPrevPage(provider) {
            const b = this.repoBrowsers[provider];
            if (b && b.page > 1) b.page--;
        },

        browserNextPage(provider) {
            const b = this.repoBrowsers[provider];
            if (b && b.page < this.getBrowserTotalPages(provider)) b.page++;
        },

        onBrowserSearch(provider) {
            const b = this.repoBrowsers[provider];
            if (b) b.page = 1;
        },

        selectRepoForImport(repo, branch) {
            this.repoUrl = repo.webUrl;
            this.branch  = branch !== undefined ? branch : (repo.defaultBranch || '');
            this.urlError = '';
            // Immediately start import with the selected (or default) branch
            this.startImport().then(() => {
                this.$nextTick(() => {
                    const progress = document.getElementById('import-progress');
                    if (progress) progress.scrollIntoView({ behavior: 'smooth', block: 'start' });
                });
            });
        },

        formatRepoDate(dateStr) {
            if (!dateStr) return '';
            try {
                const d = new Date(dateStr);
                if (isNaN(d)) return '';
                const now = new Date();
                const diff = Math.floor((now - d) / 1000);
                if (diff < 60)   return 'just now';
                if (diff < 3600) return Math.floor(diff / 60) + 'm ago';
                if (diff < 86400) return Math.floor(diff / 3600) + 'h ago';
                if (diff < 2592000) return Math.floor(diff / 86400) + 'd ago';
                return d.toLocaleDateString();
            } catch (_) { return ''; }
        },

        providerLabel(provider) {
            return { GITHUB: 'GitHub', GITLAB: 'GitLab', BITBUCKET: 'Bitbucket' }[provider] || provider;
        },

        providerBrandColor(provider) {
            return { GITHUB: '#24292f', GITLAB: '#fc6d26', BITBUCKET: '#0052cc' }[provider] || '#6b7280';
        },

        _appendLog(status, text) {
            this.progressLog.push({ status, text });
        },

        _stopPolling() {
            if (this._pollTimer) {
                clearInterval(this._pollTimer);
                this._pollTimer = null;
            }
        },

        async copyApiKey() {
            const key = this.jobResult && this.jobResult.apiToken;
            if (!key) return;
            try {
                await navigator.clipboard.writeText(key);
                this.keyCopied = true;
                setTimeout(() => { this.keyCopied = false; }, 2000);
            } catch (err) {
                console.error('[QuickImport] Clipboard write failed:', err);
            }
        },

        resetForm() {
            this._stopPolling();
            this.repoUrl      = '';
            this.branch       = '';
            this.urlError     = '';
            this.isImporting  = false;
            this.jobId        = null;
            this.jobResult    = {};
            this.progressLog  = [];
            this._lastPhase   = null;
            this.keyCopied    = false;
        },
    };
}
