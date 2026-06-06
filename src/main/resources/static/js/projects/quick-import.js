/* ============================================================
   Quick Import Page — Alpine.js component
   Endpoint contract:
     GET  /api/quick-import/connections  → VcsConnectionDto[]
     GET  /api/quick-import/repos?provider=  → QuickImportRepoDto[]
     POST /api/quick-import/start        → { jobId: string }
     GET  /api/quick-import/jobs         → QuickImportJobStatus[]
     GET  /api/quick-import/job/:jobId   → QuickImportJobStatus (poll fallback)
     GET  /api/quick-import/job/:jobId/stream  → SSE job-update events
   ============================================================ */

const REPO_PAGE_SIZE = 10;

function _qi() {
    return typeof _qiI18n !== 'undefined' ? _qiI18n : {};
}

function _qiFmt(template) {
    let s = String(template);
    for (let i = 1; i < arguments.length; i++) {
        s = s.split('{' + (i - 1) + '}').join(arguments[i]);
    }
    return s;
}

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

        /* ── Multi-job tracking ─────────────────── */
        activeJobs: [],
        maxConcurrentSlots: 2,
        jobResult: {},

        /* ── Copy feedback ───────────────────────── */
        keyCopied: false,

        /* ── Background scan watcher ─────────────── */
        _scanWatcher: null,


        /* ─────────────────────────────────────────── */

        async init() {
            await this.onPanelOpen();
        },

        /** Called when the slide-out panel opens (also on first mount). */
        async onPanelOpen() {
            await this.loadConnections();
            for (const p of this.connectedProviders) {
                this.loadRepoBrowser(p);
            }
            await this._loadActiveJobs();
        },

        /**
         * Called when the slide-out panel closes.
         * Clears completed import UI so the next open starts fresh, but keeps in-flight jobs.
         */
        onPanelClose() {
            this.activeJobs.forEach(j => {
                if (j.phase === 'DONE' || j.phase === 'FAILED') {
                    this._stopJobWatch(j);
                }
            });
            this.activeJobs = this.activeJobs.filter(j =>
                j.phase && j.phase !== 'DONE' && j.phase !== 'FAILED'
            );
            if (this.importDone || this.importFailed) {
                this.jobResult = {};
            }
            this.repoUrl = '';
            this.branch = '';
            this.urlError = '';
        },

        /* ── Derived getters ──────────────────────── */

        get connectedProviders() {
            return ['GITHUB', 'GITLAB', 'BITBUCKET'].filter(
                p => this.connectionsByProvider[p] !== null
            );
        },

        get detectedProvider() {
            const raw = (this.repoUrl || '').trim();
            if (!raw) return null;

            let urlHost = null;
            try {
                urlHost = new URL(raw).hostname.toLowerCase();
            } catch (_) {
                return null;
            }

            for (const provider of ['GITHUB', 'GITLAB', 'BITBUCKET']) {
                const conn = this.connectionsByProvider[provider];
                if (!conn || !conn.serverUrl) continue;
                try {
                    const connHost = new URL(conn.serverUrl).hostname.toLowerCase();
                    if (connHost && urlHost === connHost) return provider;
                } catch (_) {}
            }

            const url = raw.toLowerCase();
            if (url.includes('github.com')) return 'GITHUB';
            if (url.includes('gitlab.com') || url.includes('gitlab')) return 'GITLAB';
            if (url.includes('bitbucket.org')) return 'BITBUCKET';
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
            return true;
        },

        get runningJobCount() {
            return this.activeJobs.filter(j => j.phase && j.phase !== 'DONE' && j.phase !== 'FAILED').length;
        },

        get showProgress() {
            return this.activeJobs.length > 0;
        },

        get importDone() {
            return this.jobResult && this.jobResult.phase === 'DONE';
        },

        get importFailed() {
            return this.jobResult && this.jobResult.phase === 'FAILED';
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
                    this.urlError = _qi().urlProtocol || 'URL must use http:// or https://.';
                }
            } catch (_) {
                this.urlError = _qi().invalidUrl || 'Please enter a valid URL.';
            }
        },

        async startImport(fromRepoBrowser = false) {
            if (!fromRepoBrowser && !this.canImport) return;
            const repoUrl = this.repoUrl.trim();
            if (!repoUrl) return;
            const branch = this.branch.trim() || null;

            // Clear previous success/failure card when starting a new import
            this.jobResult = {};

            try {
                const res = await fetch('/api/quick-import/start', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ repoUrl, branch }),
                });

                if (!res.ok) {
                    const body = await res.text();
                    throw new Error(body || 'HTTP ' + res.status);
                }

                const data = await res.json();
                this._attachJob({
                    jobId: data.jobId,
                    phase: 'QUEUED',
                    repoLabel: this._repoLabelFromUrl(repoUrl),
                    percent: 0,
                });
                this.repoUrl = '';
                this.branch = '';
            } catch (err) {
                console.error('[QuickImport] Start failed:', err);
                this._appendLogToJob(null, 'error',
                    _qiFmt(_qi().startFailed || 'Failed to start import: {0}', err.message));
            }
        },

        async _loadActiveJobs() {
            try {
                const res = await fetch('/api/quick-import/jobs');
                if (!res.ok) return;
                const jobs = await res.json();
                if (jobs.length > 0 && jobs[0].maxConcurrentSlots) {
                    this.maxConcurrentSlots = jobs[0].maxConcurrentSlots;
                }
                // Only resume in-flight jobs — completed jobs should not block the next import.
                jobs.forEach(j => {
                    const phase = this._normalizePhase(j.phase);
                    if (phase !== 'DONE' && phase !== 'FAILED') {
                        this._attachJob(j);
                    }
                });
            } catch (err) {
                console.error('[QuickImport] Failed to load jobs:', err);
            }
        },

        _repoLabelFromUrl(url) {
            try {
                const path = new URL(url).pathname.replace(/^\//, '');
                return path || url;
            } catch (_) {
                return url;
            }
        },

        _findTracker(jobId) {
            return this.activeJobs.find(j => j.jobId === jobId);
        },

        _normalizePhase(phase) {
            if (!phase) return phase;
            if (typeof phase === 'string') return phase;
            return phase.name || String(phase);
        },

        _normalizeJob(job) {
            if (!job) return job;
            return { ...job, phase: this._normalizePhase(job.phase) };
        },

        /** Server masks apiToken after the first HTTP poll; SSE + polling can race and overwrite the full key. */
        _looksMaskedApiToken(token) {
            return typeof token === 'string' && (token.includes('\u2026') || token.includes('…'));
        },

        _captureRevealedApiToken(tracker, job) {
            if (!tracker || !job || job.phase !== 'DONE' || !job.newApiKey || !job.apiToken) return;
            if (!this._looksMaskedApiToken(job.apiToken)) {
                tracker.revealedApiToken = job.apiToken;
            }
        },

        _withPreservedApiToken(tracker, job) {
            if (!job || job.phase !== 'DONE' || !job.newApiKey) return job;
            const full = tracker?.revealedApiToken;
            if (!full) return job;
            if (!job.apiToken || this._looksMaskedApiToken(job.apiToken)) {
                return { ...job, apiToken: full };
            }
            return job;
        },

        /** Re-assign tracker in activeJobs so Alpine picks up nested mutations. */
        _syncTracker(tracker) {
            const idx = this.activeJobs.findIndex(j => j.jobId === tracker.jobId);
            if (idx < 0) return;
            this.activeJobs.splice(idx, 1, {
                ...tracker,
                progressLog: tracker.progressLog.map(e => ({ ...e })),
            });
        },

        _attachJob(serverJob) {
            if (!serverJob || !serverJob.jobId) return;
            let tracker = this._findTracker(serverJob.jobId);
            if (!tracker) {
                tracker = {
                    jobId: serverJob.jobId,
                    repoLabel: serverJob.repoLabel || serverJob.jobId,
                    phase: null,
                    message: '',
                    percent: 0,
                    queuePosition: null,
                    progressLog: [],
                    lastPhase: null,
                    lastAiPreviewCount: 0,
                    lastDetailCount: 0,
                    _eventSource: null,
                    _pollTimer: null,
                    _pollInFlight: false,
                    revealedApiToken: null,
                };
                this.activeJobs.push(tracker);
            }
            this._applyJobUpdate(tracker, serverJob);
            if (serverJob.phase !== 'DONE' && serverJob.phase !== 'FAILED') {
                this._startJobWatch(tracker);
            }
        },

        _startJobWatch(tracker) {
            // Poll immediately (pre–multi-job behavior); SSE is an optional fast path.
            if (!tracker._pollTimer) {
                this._startPollingFallback(tracker);
            }
            if (tracker._eventSource || typeof EventSource === 'undefined') {
                return;
            }
            try {
                const es = new EventSource('/api/quick-import/job/' + tracker.jobId + '/stream');
                tracker._eventSource = es;
                es.addEventListener('job-update', (e) => {
                    try {
                        const job = this._normalizeJob(JSON.parse(e.data));
                        this._applyJobUpdate(tracker, job);
                    } catch (err) {
                        console.error('[QuickImport] SSE parse error:', err);
                    }
                });
                es.onerror = () => {
                    es.close();
                    tracker._eventSource = null;
                };
            } catch (_) { /* polling already active */ }
        },

        _startPollingFallback(tracker) {
            if (tracker._pollTimer) return;
            tracker._pollInFlight = false;
            const poll = () => this._pollJob(tracker);
            poll();
            tracker._pollTimer = setInterval(() => {
                if (!tracker._pollInFlight) poll();
            }, 1500);
        },

        async _pollJob(tracker) {
            if (tracker._pollInFlight) return;
            tracker._pollInFlight = true;
            try {
                const res = await fetch('/api/quick-import/job/' + tracker.jobId);
                if (res.status === 404) {
                    this._stopJobWatch(tracker);
                    this._appendLogToJob(tracker, 'error',
                        _qi().sessionExpired || 'Import session expired.');
                    return;
                }
                if (!res.ok) return;
                const job = this._normalizeJob(await res.json());
                this._applyJobUpdate(tracker, job);
            } catch (err) {
                console.error('[QuickImport] Poll error:', err);
            } finally {
                tracker._pollInFlight = false;
            }
        },

        _applyJobUpdate(tracker, job) {
            if (!tracker || !job) return;
            job = this._normalizeJob(job);

            if (job.maxConcurrentSlots) {
                this.maxConcurrentSlots = job.maxConcurrentSlots;
            }
            tracker.repoLabel = job.repoLabel || tracker.repoLabel;
            tracker.phase = job.phase;
            tracker.message = job.message || '';
            tracker.percent = job.percent != null ? job.percent : tracker.percent;
            tracker.queuePosition = job.queuePosition;

            if (job.detailLines && job.detailLines.length > tracker.lastDetailCount) {
                const newLines = job.detailLines.slice(tracker.lastDetailCount);
                newLines.forEach(line => this._appendLogToJob(tracker, 'running', line));
                tracker.lastDetailCount = job.detailLines.length;
            }

            if (job.aiPreviews && job.aiPreviews.length > tracker.lastAiPreviewCount) {
                const prefix = _qi().aiPreviewPrefix || 'AI preview: ';
                job.aiPreviews.slice(tracker.lastAiPreviewCount).forEach(line => {
                    this._appendLogToJob(tracker, 'done', prefix + line);
                });
                tracker.lastAiPreviewCount = job.aiPreviews.length;
            }

            if (job.phase === tracker.lastPhase) {
                if (tracker.progressLog.length > 0 && job.message) {
                    const log = [...tracker.progressLog];
                    const lastIdx = log.length - 1;
                    if (log[lastIdx].status === 'running') {
                        log[lastIdx] = { ...log[lastIdx], text: this._phaseLine(job) };
                        tracker.progressLog = log;
                    }
                }
            } else {
                if (tracker.lastPhase !== null && tracker.progressLog.length > 0) {
                    const log = [...tracker.progressLog];
                    const lastIdx = log.length - 1;
                    if (log[lastIdx].status === 'running') {
                        log[lastIdx] = { ...log[lastIdx], status: 'done' };
                        tracker.progressLog = log;
                    }
                }
                const status =
                    job.phase === 'DONE'   ? 'done'    :
                    job.phase === 'FAILED' ? 'error'   :
                    'running';
                this._appendLogToJob(tracker, status, this._phaseLine(job));
                tracker.lastPhase = job.phase;
            }

            this._captureRevealedApiToken(tracker, job);

            if (job.phase === 'DONE' || job.phase === 'FAILED') {
                this._stopJobWatch(tracker);
                this.jobResult = this._withPreservedApiToken(tracker, job);
                if (job.phase === 'DONE') {
                    try { localStorage.setItem('oswl-qi-done', '1'); } catch (_) {}
                    this._refreshBackground(job.projectId);
                }
            }

            this._syncTracker(tracker);
        },

        _phaseLine(job) {
            const q = _qi();
            const phaseLabels = {
                QUEUED:    q.phaseQueued    || 'Queued',
                CLONING:   q.phaseCloning   || 'Cloning repository…',
                PARSING:   q.phaseParsing   || 'Parsing dependencies…',
                SCANNING:  q.phaseScanning  || 'Running security scan…',
                ENRICHING: q.phaseEnriching || 'Analyzing components…',
                DONE:      q.phaseDone      || 'Done',
                FAILED:    q.phaseFailed    || 'Failed',
            };
            const subLabels = {
                CVE: q.subPhaseCve || 'CVE enrichment',
                LICENSE: q.subPhaseLicense || 'License policy',
                POSTURE: q.subPhasePosture || 'Security posture',
                TREND: q.subPhaseTrend || 'Risk trend',
                DIFF: q.subPhaseDiff || 'Version diff',
            };
            let label = phaseLabels[job.phase] || job.phase;
            if (job.subPhase && subLabels[job.subPhase]) {
                label = label + ' · ' + subLabels[job.subPhase];
            }
            if (job.message) {
                return label + ' — ' + job.message;
            }
            return label;
        },

        _appendLogToJob(tracker, status, text) {
            if (!tracker) {
                console.warn('[QuickImport]', text);
                return;
            }
            tracker.progressLog = [...tracker.progressLog, { status, text }];
        },

        _stopJobWatch(tracker) {
            if (tracker._pollTimer) {
                clearInterval(tracker._pollTimer);
                tracker._pollTimer = null;
            }
            if (tracker._eventSource) {
                tracker._eventSource.close();
                tracker._eventSource = null;
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
                    let msg = _qi().loadReposFailed || 'Failed to load repositories.';
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
            this.startImport(true).then(() => {
                this.$nextTick(() => {
                    const progress = document.getElementById('import-progress');
                    if (progress) progress.scrollIntoView({ behavior: 'smooth', block: 'start' });
                });
            });
        },

        formatRepoDate(dateStr) {
            if (!dateStr) return '';
            const q = _qi();
            const fmt = (t, n) => String(t || '').replace('{0}', n);
            try {
                const d = new Date(dateStr);
                if (isNaN(d)) return '';
                const now = new Date();
                const diff = Math.floor((now - d) / 1000);
                if (diff < 60)   return q.justNow || 'just now';
                if (diff < 3600) return fmt(q.minutesShort, Math.floor(diff / 60));
                if (diff < 86400) return fmt(q.hoursShort, Math.floor(diff / 3600));
                if (diff < 2592000) return fmt(q.daysShort, Math.floor(diff / 86400));
                return d.toLocaleDateString();
            } catch (_) { return ''; }
        },

        providerLabel(provider) {
            const q = _qi();
            const map = {
                GITHUB: q.providerGithub || 'GitHub',
                GITLAB: q.providerGitlab || 'GitLab',
                BITBUCKET: q.providerBitbucket || 'Bitbucket',
            };
            return map[provider] || provider;
        },

        providerBrandColor(provider) {
            return { GITHUB: '#24292f', GITLAB: '#fc6d26', BITBUCKET: '#0052cc' }[provider] || '#6b7280';
        },

        _refreshBackground(projectId) {
            if (typeof window.refreshProjectCards === 'function') {
                window.refreshProjectCards();
            }
            if (!projectId) return;
            if (this._scanWatcher) { this._scanWatcher.close(); this._scanWatcher = null; }
            try {
                const es = new EventSource('/projects/scan-status/stream?ids=' + projectId);
                this._scanWatcher = es;
                es.addEventListener('scan-update', () => {
                    es.close();
                    this._scanWatcher = null;
                    if (typeof window.refreshProjectCards === 'function') {
                        window.refreshProjectCards();
                    }
                });
                es.onerror = () => { es.close(); this._scanWatcher = null; };
            } catch (_) {}
        },

        async copyApiKey() {
            const tracker = this.jobResult?.jobId
                ? this._findTracker(this.jobResult.jobId)
                : null;
            const key = tracker?.revealedApiToken
                || (this.jobResult && this.jobResult.apiToken);
            if (!key || this._looksMaskedApiToken(key)) return;
            try {
                await navigator.clipboard.writeText(key);
                this.keyCopied = true;
                setTimeout(() => { this.keyCopied = false; }, 2000);
            } catch (err) {
                console.error('[QuickImport] Clipboard write failed:', err);
            }
        },

        resetForm() {
            this.activeJobs.forEach(j => this._stopJobWatch(j));
            if (this._scanWatcher) { this._scanWatcher.close(); this._scanWatcher = null; }
            this.repoUrl       = '';
            this.branch        = '';
            this.urlError      = '';
            this.activeJobs    = [];
            this.jobResult     = {};
            this.keyCopied     = false;
        },
    };
}
