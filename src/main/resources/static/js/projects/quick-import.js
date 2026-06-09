/* ============================================================
   Quick Import Page — Alpine.js component
   Endpoint contract:
     GET  /api/quick-import/connections  → VcsConnectionDto[]
     GET  /api/quick-import/repos?provider=  → QuickImportRepoDto[]
     POST /api/quick-import/start        → { jobId: string }
     GET  /api/quick-import/jobs         → QuickImportJobsResponse
     GET  /api/quick-import/job/:jobId   → QuickImportJobStatus (poll fallback)
     GET  /api/quick-import/job/:jobId/stream  → SSE job-update events
   ============================================================ */

const REPO_PAGE_SIZE = 10;
const PROGRESS_DISMISS_MS = 2 * 60 * 1000;
const COMPLETE_DISMISS_MS = 60 * 1000;

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

/** Resolve API error bodies using page locale (_qiI18n), never server-localized strings. */
function _qiResolveError(body) {
    const q = _qi();
    const errors = q.errors || {};
    if (body && body.errorKey && errors[body.errorKey]) {
        return _qiFmt(errors[body.errorKey], ...(body.errorArgs || []));
    }
    return q.unexpectedError || 'An unexpected error occurred.';
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
        maxConcurrentSlots: 3,
        maxQueuedSlots: 3,
        activeSlotsUsed: 0,
        userQueuedCount: 0,
        userRunningCount: 0,
        completedResults: [],

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
                this._clearProgressDismiss(j);
                const phase = this._normalizePhase(j.phase);
                if (this._isTerminalPhase(phase)) {
                    this._stopJobWatch(j);
                }
            });
            this.activeJobs = this.activeJobs.filter(j => {
                const phase = this._normalizePhase(j.phase);
                return phase && !this._isTerminalPhase(phase);
            });
            this._recomputeUserQueueCounts();
            this._clearAllCompleteDismiss();
            this.completedResults = [];
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
            if (this.urlError)          return false;
            if (this.userQueuedCount >= this.maxQueuedSlots) return false;
            return true;
        },

        _isTerminalPhase(phase) {
            return phase === 'DONE' || phase === 'FAILED';
        },

        _isQueuedPhase(phase) {
            return phase === 'QUEUED';
        },

        _isRunningPhase(phase) {
            return phase && !this._isQueuedPhase(phase) && !this._isTerminalPhase(phase);
        },

        _recomputeUserQueueCounts() {
            let queued = 0;
            let running = 0;
            for (const job of this.activeJobs) {
                const phase = this._normalizePhase(job.phase);
                if (this._isQueuedPhase(phase)) queued++;
                else if (this._isRunningPhase(phase)) running++;
            }
            this.userQueuedCount = queued;
            this.userRunningCount = running;
        },

        get showProgress() {
            return this.activeJobs.length > 0;
        },

        importSlotsLabel() {
            const i18n = _qi();
            const parts = [];
            const runningSlots = Math.min(this.activeSlotsUsed, this.maxConcurrentSlots);
            if (runningSlots > 0) {
                parts.push(_qiFmt(i18n.slotsRunning, runningSlots, this.maxConcurrentSlots));
            }
            if (this.userQueuedCount > 0) {
                parts.push(_qiFmt(i18n.slotsQueued, this.userQueuedCount, this.maxQueuedSlots));
            }
            return parts.join(' · ');
        },

        _syncSlotMetrics(job) {
            if (!job) return;
            if (job.maxConcurrentSlots != null) this.maxConcurrentSlots = job.maxConcurrentSlots;
            if (job.maxQueuedSlots != null) this.maxQueuedSlots = job.maxQueuedSlots;
            if (job.activeSlotsUsed != null) {
                this.activeSlotsUsed = Math.max(0, job.activeSlotsUsed);
            }
        },

        _syncQueueSnapshot(snapshot) {
            if (!snapshot) return;
            this._syncSlotMetrics(snapshot);
            if (snapshot.activeSlotsUsed != null) {
                this.activeSlotsUsed = Math.max(0, snapshot.activeSlotsUsed);
            }
            if (snapshot.maxConcurrentSlots != null) {
                this.maxConcurrentSlots = snapshot.maxConcurrentSlots;
            }
            if (snapshot.maxQueuedSlots != null) {
                this.maxQueuedSlots = snapshot.maxQueuedSlots;
            }
            if (snapshot.userQueuedCount != null) {
                this.userQueuedCount = snapshot.userQueuedCount;
            }
            if (snapshot.userRunningCount != null) {
                this.userRunningCount = snapshot.userRunningCount;
            }
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

            try {
                const res = await fetch('/api/quick-import/start', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ repoUrl, branch }),
                });

                if (!res.ok) {
                    let errMsg = _qi().unexpectedError || 'An unexpected error occurred.';
                    try {
                        errMsg = _qiResolveError(await res.json());
                    } catch (_) { /* keep fallback */ }
                    throw new Error(errMsg);
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
                this._appendLogToJob(null, 'error', err.message);
            }
        },

        async _loadActiveJobs() {
            try {
                const res = await fetch('/api/quick-import/jobs');
                if (!res.ok) return;
                const payload = await res.json();
                const jobs = payload.jobs || [];
                this._syncQueueSnapshot(payload);

                const inflightIds = new Set();
                jobs.forEach(j => {
                    const phase = this._normalizePhase(j.phase);
                    if (!this._isTerminalPhase(phase)) {
                        inflightIds.add(j.jobId);
                    }
                });

                // Drop stale trackers that no longer exist on the server (unless showing terminal UI).
                this.activeJobs = this.activeJobs.filter(t => {
                    const phase = this._normalizePhase(t.phase);
                    if (this._isTerminalPhase(phase)) return true;
                    return inflightIds.has(t.jobId);
                });

                jobs.forEach(j => {
                    const phase = this._normalizePhase(j.phase);
                    if (!this._isTerminalPhase(phase)) {
                        this._attachJob(j);
                    }
                });
                this._recomputeUserQueueCounts();
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
            const raw = typeof phase === 'string' ? phase : (phase.name || String(phase));
            return raw.toUpperCase();
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
                    messageKey: null,
                    messageArgs: [],
                    percent: 0,
                    queuePosition: null,
                    progressLog: [],
                    lastPhase: null,
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

            this._syncSlotMetrics(job);
            tracker.repoLabel = job.repoLabel || tracker.repoLabel;
            tracker.phase = job.phase;
            tracker.message = job.message || '';
            tracker.messageKey = job.messageKey || null;
            tracker.messageArgs = job.messageArgs || [];
            tracker.percent = job.percent != null ? job.percent : tracker.percent;
            tracker.queuePosition = job.queuePosition;

            if (job.phase === tracker.lastPhase) {
                if (tracker.progressLog.length > 0) {
                    const log = [...tracker.progressLog];
                    const lastIdx = log.length - 1;
                    if (log[lastIdx].status === 'running') {
                        const nextText = this._phaseLine(job);
                        if (log[lastIdx].text !== nextText) {
                            log[lastIdx] = { ...log[lastIdx], text: nextText };
                            tracker.progressLog = log;
                        }
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

            if ((job.phase === 'DONE' || job.phase === 'FAILED') && !tracker._terminalHandled) {
                tracker._terminalHandled = true;
                this._stopJobWatch(tracker);
                if (job.phase === 'DONE') {
                    const result = {
                        ...this._withPreservedApiToken(tracker, job),
                        keyCopied: false,
                    };
                    this.completedResults.push(result);
                    this._scheduleCompleteDismiss(result);
                    try { localStorage.setItem('oswl-qi-done', '1'); } catch (_) {}
                    this._refreshBackground(job.projectId);
                }
                this._scheduleProgressDismiss(tracker);
            }

            this._recomputeUserQueueCounts();
            this._syncTracker(tracker);
        },

        _localizedMessage(job) {
            return _qiResolveError({
                errorKey: job.messageKey,
                errorArgs: job.messageArgs,
            });
        },

        _queueStatusLine(queuePosition) {
            const q = _qi();
            if (queuePosition == null) {
                return q.phaseQueued || 'Queued';
            }
            const max = this.maxConcurrentSlots || 3;
            if (queuePosition <= max) {
                return _qiFmt(q.queueNext || 'Starting soon (slot {0})\u2026', queuePosition);
            }
            return _qiFmt(q.queueWaiting || 'Waiting in queue (#{0})\u2026', queuePosition - max);
        },

        _phaseLine(job) {
            const q = _qi();
            const phase = this._normalizePhase(job.phase);
            if (phase === 'QUEUED') {
                return this._queueStatusLine(job.queuePosition);
            }
            if (phase === 'FAILED') {
                return this._localizedMessage(job);
            }
            if (phase === 'DONE') {
                if (job.messageKey === 'importComplete') {
                    return this._localizedMessage(job);
                }
                return q.phaseDone || 'Done';
            }
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
                CVE: q.subPhaseCve || 'CVE analysis',
                LICENSE: q.subPhaseLicense || 'License review',
                POSTURE: q.subPhasePosture || 'Security overview',
                TREND: q.subPhaseTrend || 'Risk trends',
                DIFF: q.subPhaseDiff || 'Version changes',
            };
            if (phase === 'ENRICHING' && job.subPhase && subLabels[job.subPhase]) {
                return phaseLabels.ENRICHING + ' · ' + subLabels[job.subPhase];
            }
            return phaseLabels[phase] || phase;
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

        _clearProgressDismiss(tracker) {
            if (!tracker?._progressDismissTimer) return;
            clearTimeout(tracker._progressDismissTimer);
            tracker._progressDismissTimer = null;
        },

        _scheduleProgressDismiss(tracker) {
            if (!tracker) return;
            this._clearProgressDismiss(tracker);
            tracker._progressDismissTimer = setTimeout(() => {
                tracker._progressDismissTimer = null;
                const idx = this.activeJobs.findIndex(j => j.jobId === tracker.jobId);
                if (idx < 0) return;
                this._stopJobWatch(tracker);
                this.activeJobs.splice(idx, 1);
            }, PROGRESS_DISMISS_MS);
        },

        _clearCompleteDismiss(result) {
            if (!result?._dismissTimer) return;
            clearTimeout(result._dismissTimer);
            result._dismissTimer = null;
        },

        _clearAllCompleteDismiss() {
            this.completedResults.forEach(r => this._clearCompleteDismiss(r));
        },

        _scheduleCompleteDismiss(result) {
            if (!result) return;
            this._clearCompleteDismiss(result);
            result._dismissTimer = setTimeout(() => {
                result._dismissTimer = null;
                const idx = this.completedResults.findIndex(r => r.jobId === result.jobId);
                if (idx >= 0) this.completedResults.splice(idx, 1);
            }, COMPLETE_DISMISS_MS);
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
                    let msg = _qi().unexpectedError || 'An unexpected error occurred.';
                    try { msg = _qiResolveError(await res.json()); } catch (_) {}
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

        async copyApiKey(result) {
            const tracker = result?.jobId ? this._findTracker(result.jobId) : null;
            const key = tracker?.revealedApiToken || result?.apiToken;
            if (!key || this._looksMaskedApiToken(key)) return;
            try {
                await navigator.clipboard.writeText(key);
                const idx = this.completedResults.findIndex(r => r.jobId === result.jobId);
                if (idx >= 0) {
                    this.completedResults.splice(idx, 1, { ...result, keyCopied: true });
                }
                setTimeout(() => {
                    const i = this.completedResults.findIndex(r => r.jobId === result.jobId);
                    if (i >= 0) {
                        this.completedResults.splice(i, 1, { ...this.completedResults[i], keyCopied: false });
                    }
                }, 2000);
            } catch (err) {
                console.error('[QuickImport] Clipboard write failed:', err);
            }
        },

        resetForm() {
            this.repoUrl  = '';
            this.branch   = '';
            this.urlError = '';
        },
    };
}
