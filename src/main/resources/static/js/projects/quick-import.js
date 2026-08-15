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
const ETA_MAX_MS = 2 * 60 * 60 * 1000;
const ETA_MIN_SAMPLE_MS = 5000;

/** Bundle accessor: a missing key surfaces as the key name itself (see oswl-i18n.js). */
function _qi(key) {
    return oswlI18n(typeof _qiI18n !== 'undefined' ? _qiI18n : null, key);
}

function _qiFmt(template, ...args) {
    return oswlI18nFmt(template, ...args);
}

/** Resolve API error bodies using page locale (_qiI18n), never server-localized strings. */
function _qiResolveError(body) {
    const errors = (typeof _qiI18n !== 'undefined' && _qiI18n.errors) || {};
    if (body && body.errorKey && errors[body.errorKey]) {
        return _qiFmt(errors[body.errorKey], ...(body.errorArgs || []));
    }
    return _qi('unexpectedError');
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
        nowTick: Date.now(),
        maxConcurrentSlots: 3,
        maxQueuedSlots: 3,
        activeSlotsUsed: 0,
        userQueuedCount: 0,
        userRunningCount: 0,
        completedResults: [],

        /* ── Background scan watchers (one EventSource per project) ── */
        _scanWatchers: new Map(),
        _nowTickTimer: null,


        /* ─────────────────────────────────────────── */

        /** 1s heartbeat so elapsed/ETA texts on job cards re-render. Guarded so re-entry can't stack timers. */
        _ensureNowTickTimer() {
            if (this._nowTickTimer) return;
            this._nowTickTimer = setInterval(() => { this.nowTick = Date.now(); }, 1000);
        },

        _stopNowTickTimer() {
            if (!this._nowTickTimer) return;
            clearInterval(this._nowTickTimer);
            this._nowTickTimer = null;
        },

        /** Called when the slide-out panel opens (the first open doubles as the lazy init). */
        async onPanelOpen() {
            this._ensureNowTickTimer();
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
            this._stopNowTickTimer();
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
            return this.userQueuedCount < this.maxQueuedSlots;

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
            const parts = [];
            const runningSlots = Math.min(this.activeSlotsUsed, this.maxConcurrentSlots);
            if (runningSlots > 0) {
                parts.push(_qiFmt(_qi('slotsRunning'), runningSlots, this.maxConcurrentSlots));
            }
            if (this.userQueuedCount > 0) {
                parts.push(_qiFmt(_qi('slotsQueued'), this.userQueuedCount, this.maxQueuedSlots));
            }
            return parts.join(' › ');
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
                    this.urlError = _qi('urlProtocol');
                }
            } catch (_) {
                this.urlError = _qi('invalidUrl');
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
                    let errMsg = _qi('unexpectedError');
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

        async cancelJob(job) {
            if (!job || job.cancelRequested) return;
            job.cancelRequested = true;
            try {
                const res = await fetch('/api/quick-import/job/' + job.jobId + '/cancel', {
                    method: 'POST',
                    headers: oswlJsonHeaders(),
                });
                if (!res.ok) { job.cancelRequested = false; return; }
                // Reflect cancellation immediately; the poll/SSE will also deliver FAILED(canceled).
                job.phase = 'FAILED';
                job.messageKey = 'canceled';
                job.messageArgs = [];
                this._appendLogToJob(job, 'error', this._localizedMessage(job));
            } catch (err) {
                console.error('[QuickImport] Cancel failed:', err);
                job.cancelRequested = false;
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
                    startedAtEpochMs: serverJob.startedAtEpochMs || Date.now(),
                    runningSinceEpochMs: serverJob.runningSinceEpochMs || null,
                    progressLog: [],
                    detailLines: [],
                    aiPreview: '',
                    cacheTotal: null,
                    cacheHit: null,
                    lastPhase: null,
                    _eventSource: null,
                    _pollTimer: null,
                    _pollInFlight: false,
                    _etaAnchorPct: null,
                    _etaAnchorAtEpochMs: null,
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
                    // _syncTracker replaces activeJobs entries, so this closure's
                    // tracker may be stale; clear the ref on the live tracker.
                    const current = this._findTracker(tracker.jobId);
                    if (current) current._eventSource = null;
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
                    this._appendLogToJob(tracker, 'error', _qi('sessionExpired'));
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
            // live enrichment details — preview joins the rolling tail chunks into one
            // string; the cache badge keeps its last known values once the job is DONE.
            if (Array.isArray(job.detailLines)) tracker.detailLines = job.detailLines;
            if (Array.isArray(job.aiPreviews)) tracker.aiPreview = job.aiPreviews.join('');
            if (job.cacheTotal != null) tracker.cacheTotal = job.cacheTotal;
            if (job.cacheHit != null) tracker.cacheHit = job.cacheHit;
            tracker.startedAtEpochMs = job.startedAtEpochMs || tracker.startedAtEpochMs || null;
            if (job.runningSinceEpochMs && job.runningSinceEpochMs !== tracker.runningSinceEpochMs) {
                // Fresh run: drop ETA anchors so a stale estimate can't leak across runs.
                tracker._etaAnchorPct = null;
                tracker._etaAnchorAtEpochMs = null;
            }
            tracker.runningSinceEpochMs = job.runningSinceEpochMs || tracker.runningSinceEpochMs || null;
            if (tracker.runningSinceEpochMs && tracker.percent != null
                    && (tracker._etaAnchorPct == null || tracker.percent > tracker._etaAnchorPct)
                    && (Date.now() - tracker.runningSinceEpochMs) >= ETA_MIN_SAMPLE_MS) {
                // Mark when percent last advanced; ETA extrapolates from this anchor.
                // Percent regressions (stale poll racing newer SSE) keep the forward anchor.
                // The elapsed-time gate keeps the very first anchor (set the instant CLONING
                // starts, with ~0ms of real data) from freezing a near-zero estimate that then
                // sits hidden for the rest of that phase — some phases still report a near-fixed
                // percent (PARSING/ENRICHING report gradually climbing progress, but CLONING/
                // SCANNING have no in-phase signal), so without this gate the ETA could surface
                // only once
                // a *later* phase transition happened to accumulate 5s of elapsed time.
                // Requiring 5s of real elapsed time up front instead means the estimate appears
                // as soon as there's a real sample, regardless of which phase we're in.
                tracker._etaAnchorPct = tracker.percent;
                tracker._etaAnchorAtEpochMs = Date.now();
            }

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

        /* ── Elapsed / ETA display ────────────────── */

        _fmtDuration(ms) {
            if (ms == null || ms < 0) return '';
            const total = Math.floor(ms / 1000);
            const h = Math.floor(total / 3600);
            const m = Math.floor((total % 3600) / 60);
            const s = total % 60;
            const mm = String(m).padStart(2, '0');
            const ss = String(s).padStart(2, '0');
            return h > 0 ? h + ':' + mm + ':' + ss : mm + ':' + ss;
        },

        /** Elapsed time since the job was queued (ticks via nowTick). */
        elapsedText(job) {
            void this.nowTick;
            if (!job || !job.startedAtEpochMs) return '';
            return this._fmtDuration(Date.now() - job.startedAtEpochMs);
        },

        /**
         * Remaining-time estimate extrapolated from the last percent advance.
         * Percent is a per-phase step value, so extrapolating from "now" inflates the
         * estimate while a long phase runs; the anchor keeps it stable and the
         * countdown below ticks it down between updates. Clamped so progress
         * regressions/jumps can't spike it.
         * Hidden while queued, in the first seconds, or when percent is unreliable.
         */
        etaText(job) {
            void this.nowTick;
            if (!job) return '';
            const phase = this._normalizePhase(job.phase);
            if (!this._isRunningPhase(phase)) return '';
            const pct = job.percent;
            if (!job.runningSinceEpochMs || pct == null || pct < 5 || pct >= 100) return '';
            if (job._etaAnchorPct == null || job._etaAnchorAtEpochMs == null) return '';
            const progressedMs = job._etaAnchorAtEpochMs - job.runningSinceEpochMs;
            // Redundant with the ETA_MIN_SAMPLE_MS gate applied when the anchor is set
            // (see _applyJobUpdate) — kept as a safety net in case of clock skew.
            if (progressedMs < ETA_MIN_SAMPLE_MS) return '';
            const anchorPct = Math.max(job._etaAnchorPct, 1);
            let remainingMs = (progressedMs * (100 - anchorPct)) / anchorPct;
            remainingMs -= Date.now() - job._etaAnchorAtEpochMs;
            remainingMs = Math.min(Math.max(remainingMs, 0), ETA_MAX_MS);
            const remainingSec = Math.round(remainingMs / 1000);
            if (remainingSec >= 60) {
                return _qiFmt(_qi('etaMinutes'), Math.ceil(remainingSec / 60));
            }
            // Round up to 10s steps so the number doesn't jitter every second
            return _qiFmt(_qi('etaSeconds'), Math.max(10, Math.ceil(remainingSec / 10) * 10));
        },

        _localizedMessage(job) {
            return _qiResolveError({
                errorKey: job.messageKey,
                errorArgs: job.messageArgs,
            });
        },

        /**
         * Cache-hit badge. Hidden on a first scan (0 hits) — "0 of N served from cache" would
         * read as if something went wrong, so the badge only appears once there is a real hit.
         */
        cacheBadgeText(job) {
            if (!job || !job.cacheTotal || !job.cacheHit) return '';
            return _qiFmt(_qi('cacheHitBadge'), job.cacheTotal, job.cacheHit);
        },

        _queueStatusLine(queuePosition) {
            if (queuePosition == null) {
                return _qi('phaseQueued');
            }
            const max = this.maxConcurrentSlots || 3;
            if (queuePosition <= max) {
                return _qiFmt(_qi('queueNext'), queuePosition);
            }
            return _qiFmt(_qi('queueWaiting'), queuePosition - max);
        },

        _phaseLine(job) {
            const phase = this._normalizePhase(job.phase);
            if (phase === 'QUEUED') {
                return this._queueStatusLine(job.queuePosition);
            }
            if (phase === 'FAILED') {
                return this._localizedMessage(job);
            }
            if (phase === 'DONE') {
                // the job reaches DONE as soon as the CVE/license data pipeline finishes —
                // AI summaries (aiStatus) may still be generating in the background.
                const aiPending = job.aiStatus === 'PENDING' || job.aiStatus === 'RUNNING';
                const base = job.messageKey === 'importComplete'
                        ? this._localizedMessage(job)
                        : _qi('phaseDone');
                return aiPending ? base + ' ' + _qi('aiSummaryGenerating') : base;
            }
            const phaseLabels = {
                QUEUED:    _qi('phaseQueued'),
                CLONING:   _qi('phaseCloning'),
                PARSING:   _qi('phaseParsing'),
                SCANNING:  _qi('phaseScanning'),
                ENRICHING: _qi('phaseEnriching'),
                DONE:      _qi('phaseDone'),
                FAILED:    _qi('phaseFailed'),
            };
            const subLabels = {
                CVE: _qi('subPhaseCve'),
                LICENSE: _qi('subPhaseLicense'),
                POSTURE: _qi('subPhasePosture'),
                TREND: _qi('subPhaseTrend'),
                DIFF: _qi('subPhaseDiff'),
            };
            if (phase === 'ENRICHING' && job.subPhase && subLabels[job.subPhase]) {
                return phaseLabels.ENRICHING + ' › ' + subLabels[job.subPhase];
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
                    let msg = _qi('unexpectedError');
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
            try {
                const d = new Date(dateStr);
                if (isNaN(d)) return '';
                const now = new Date();
                const diff = Math.floor((now - d) / 1000);
                if (diff < 60)   return _qi('justNow');
                if (diff < 3600) return _qiFmt(_qi('minutesShort'), Math.floor(diff / 60));
                if (diff < 86400) return _qiFmt(_qi('hoursShort'), Math.floor(diff / 3600));
                if (diff < 2592000) return _qiFmt(_qi('daysShort'), Math.floor(diff / 86400));
                return d.toLocaleDateString();
            } catch (_) { return ''; }
        },

        providerLabel(provider) {
            const map = {
                GITHUB: _qi('providerGithub'),
                GITLAB: _qi('providerGitlab'),
                BITBUCKET: _qi('providerBitbucket'),
            };
            return map[provider] || provider;
        },

        _refreshBackground(projectId) {
            if (typeof window.refreshProjectCards === 'function') {
                window.refreshProjectCards();
            }
            if (!projectId) return;
            // One watcher per project so back-to-back imports are all tracked.
            const prev = this._scanWatchers.get(projectId);
            if (prev) { prev.close(); this._scanWatchers.delete(projectId); }
            try {
                const es = new EventSource('/projects/scan-status/stream?ids=' + projectId);
                this._scanWatchers.set(projectId, es);
                es.addEventListener('scan-update', () => {
                    es.close();
                    this._scanWatchers.delete(projectId);
                    if (typeof window.refreshProjectCards === 'function') {
                        window.refreshProjectCards();
                    }
                });
                es.onerror = () => { es.close(); this._scanWatchers.delete(projectId); };
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
