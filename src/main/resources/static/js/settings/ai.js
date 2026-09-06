function aiTab() {
    return {
        // Which radio is selected. 'EMBEDDED' is a UI-only value: on the wire the embedded
        // sidecar is the LOCAL provider, told apart by its base URL (see activeProviderKind).
        mode: 'off', // 'off' | 'OPENAI' | 'ANTHROPIC' | 'GEMINI' | 'LOCAL' | 'EMBEDDED'
        // Server's answer to "which one is actually serving calls right now" — the radio only
        // reflects what the user is looking at, which is why an active provider and a running
        // embedded model used to look identical.
        activeProviderKind: 'OFF',
        // Sub-tab: 'provider' | 'context' | 'prompts'. Restored from the URL's `section`
        // query param so a documentation link like /settings?tab=ai&section=prompts opens
        // directly on that panel.
        subTab: initialSettingsSection('provider'),
        _contextLoaded: false, _promptsLoaded: false, _providerLoaded: false,
        forms: {
            OPENAI:    { apiKey: '', modelName: 'gpt-5.6-terra',   baseUrl: '' },
            ANTHROPIC: { apiKey: '', modelName: 'claude-opus-5',   baseUrl: '' },
            GEMINI:    { apiKey: '', modelName: 'gemini-3.1-pro',  baseUrl: '' },
            LOCAL:     { apiKey: '', modelName: 'llama3.3',        baseUrl: 'http://localhost:11434/v1' }
        },
        // Suggestions only — any id can be typed in. Kept here rather than inline in each
        // dropdown so a model refresh is a one-line change instead of eight duplicated
        // array literals (each dropdown previously repeated its list twice).
        models: {
            OPENAI: ['gpt-5.6-sol', 'gpt-5.6-terra', 'gpt-5.6-luna',
                     'gpt-5.5-pro', 'gpt-5.5',
                     'gpt-5.4-pro', 'gpt-5.4', 'gpt-5.4-mini', 'gpt-5.4-nano'],
            ANTHROPIC: ['claude-opus-5', 'claude-fable-5', 'claude-sonnet-5', 'claude-haiku-4-5',
                        'claude-opus-4-8', 'claude-opus-4-7', 'claude-sonnet-4-6'],
            GEMINI: ['gemini-3.1-pro', 'gemini-3.6-flash', 'gemini-3.5-flash', 'gemini-3.1-flash-lite',
                     'gemini-3-pro', 'gemini-3-flash',
                     'gemini-2.5-pro', 'gemini-2.5-flash', 'gemini-2.5-flash-lite'],
            // Popular Ollama tags. Small models first — an 8B-class model on CPU is what
            // most self-hosted setups can actually run at a usable speed.
            LOCAL: ['llama3.3', 'llama3.2', 'llama3.1',
                    'qwen3', 'qwen3-coder', 'qwen2.5', 'qwen2.5-coder',
                    'gemma3', 'gemma3n', 'phi4', 'phi4-mini',
                    'deepseek-r1', 'deepseek-v3',
                    'mistral', 'mistral-nemo', 'mixtral',
                    'codellama', 'granite3.3', 'smollm2']
        },

        /** Case-insensitive substring filter over the suggestion list for one provider. */
        filterModels(provider, query) {
            const list = this.models[provider] || [];
            if (!query) return list;
            const q = query.toLowerCase();
            return list.filter(o => o.toLowerCase().includes(q));
        },
        preferences: {
            promptsLocale: 'en', cveLimit: 10, licenseLimit: 8, cveSeverities: ['CRITICAL', 'HIGH'],
            temperature: null, maxTokens: null, dailyCallCap: 0,
            defaultDeploymentProfile: 'COMMERCIAL_PRODUCT',
            reasoningEffort: 'DEFAULT', autoBackfillInsights: false
        },
        promptKeys: [], promptOverrides: {}, promptEditKey: 'system.default', promptEditText: '',
        localeDropdownOpen: false,
        deploymentDropdownOpen: false,
        effortDropdownOpen: false,
        promptKeyDropdownOpen: false,
        backfilling: false,
        effortOptions: [
            { value: 'DEFAULT', label: _aiI18n.effortDefault },
            { value: 'LOW',     label: _aiI18n.effortLow },
            { value: 'MEDIUM',  label: _aiI18n.effortMedium },
            { value: 'HIGH',    label: _aiI18n.effortHigh },
            { value: 'XHIGH',   label: _aiI18n.effortXhigh },
            { value: 'MAX',     label: _aiI18n.effortMax }
        ],
        // Mirror of AiEffort (Java): what each provider actually receives. XHIGH/MAX exist only
        // on Anthropic's output_config.effort — everywhere else reasoning_effort tops out at
        // "high", so those two levels are sent as "high" rather than being rejected.
        effortWire: {
            DEFAULT: { openai: null,     anthropic: null },
            LOW:     { openai: 'low',    anthropic: 'low' },
            MEDIUM:  { openai: 'medium', anthropic: 'medium' },
            HIGH:    { openai: 'high',   anthropic: 'high' },
            XHIGH:   { openai: 'high',   anthropic: 'xhigh' },
            MAX:     { openai: 'high',   anthropic: 'max' }
        },
        deploymentOptions: [
            { value: 'COMMERCIAL_PRODUCT',      label: _aiI18n.deployCommercial },
            { value: 'SAAS',                    label: _aiI18n.deploySaas },
            { value: 'INTERNAL_TOOL',           label: _aiI18n.deployInternal },
            { value: 'ON_PREMISE_DISTRIBUTION', label: _aiI18n.deployOnPrem }
        ],
        savingProvider: false, savingEnrichment: false, testing: false,
        apiError: null, apiErrorHint: null, toastVisible: false, toastMsg: '', toastKind: 'success', _toastTimer: null,
        usageStats: null,
        usageEvents: [], usageEventPage: 0, usageEventTotalPages: 0,
        embedded: { binaryFound: false, running: false, modelFile: null, activeModel: null, fallbackUsed: false, lastError: null, modelsDir: null, availableModels: [], downloading: false, downloadedBytes: 0, downloadTotalBytes: 0, airgapped: false },
        embeddedBusy: false, embeddedError: null, _embeddedPollActive: false,
        embeddedModel: '', embeddedDirInput: '', embeddedSaving: false,

        /**
         * Token counts run into the millions on a busy instance, where a raw 12847362 is
         * unreadable. Below 1000 the exact number is still the most useful thing to show.
         * One decimal is kept only when it carries information (1.2K, but 15K not 15.0K).
         */
        fmtTokens(value) {
            const n = Number(value) || 0;
            if (n < 1000) return String(n);
            const [scaled, unit] = n < 1000000 ? [n / 1000, 'K'] : [n / 1000000, 'M'];
            // 3 significant digits: 9.87K, 98.7K, 987K
            const decimals = scaled < 10 ? 2 : (scaled < 100 ? 1 : 0);
            return scaled.toFixed(decimals).replace(/\.?0+$/, '') + unit;
        },

        /** Full count with thousands separators — used as the title of an abbreviated value. */
        exactTokens(value) {
            return (Number(value) || 0).toLocaleString();
        },

        /** Integer hit-rate percentage; 0 until at least one cache outcome is recorded. */
        cacheHitRate(stats) {
            const hits = Number(stats && stats.cacheHitCount) || 0;
            const misses = Number(stats && stats.cacheMissCount) || 0;
            const total = hits + misses;
            return total > 0 ? Math.round((hits / total) * 100) : 0;
        },

        localeLabel() {
            if (this.preferences.promptsLocale === 'ko') return _aiI18n.localeKo;
            if (this.preferences.promptsLocale === 'ja') return _aiI18n.localeJa;
            return _aiI18n.localeEn;
        },
        deploymentLabel() {
            const opt = this.deploymentOptions.find(o => o.value === this.preferences.defaultDeploymentProfile);
            return opt ? opt.label : this.preferences.defaultDeploymentProfile;
        },

        effortLabel() {
            const opt = this.effortOptions.find(o => o.value === this.preferences.reasoningEffort);
            return opt ? opt.label : _aiI18n.effortDefault;
        },

        /** One line naming the exact request field and value the selected provider will receive. */
        effortWireHint() {
            const wire = this.effortWire[this.preferences.reasoningEffort] || this.effortWire.DEFAULT;
            const isAnthropic = this.mode === 'ANTHROPIC';
            const value = isAnthropic ? wire.anthropic : wire.openai;
            if (!value) return _aiI18n.effortWireNone;
            return isAnthropic
                ? _aiI18n.effortWireAnthropic.replace('{0}', value)
                : _aiI18n.effortWireOpenai.replace('{0}', value);
        },

        /**
         * Regenerates scan-level insights on demand. Confirmed first because each scan in
         * scope is a paid provider call — this is the whole reason the automatic version is
         * opt-in.
         */
        async runBackfill(force) {
            const question = force ? _aiI18n.backfillConfirmAll : _aiI18n.backfillConfirmMissing;
            if (!window.confirm(question)) return;
            this.backfilling = true;
            try {
                const r = await fetch('/api/settings/ai/backfill?force=' + (force ? 'true' : 'false'), {
                    method: 'POST',
                    headers: this.headers()
                });
                if (r.status === 401) { location.href = '/login'; return; }
                const data = await r.json().catch(() => ({}));
                if (!r.ok || !data.success) {
                    this.showToast(data.message || _aiI18n.backfillFailed, 'error');
                    return;
                }
                this.showToast(_aiI18n.backfillStarted);
            } catch (e) {
                this.showToast(_aiI18n.backfillFailed, 'error');
            } finally {
                this.backfilling = false;
            }
        },

        csrf() {
            return window.OswlCsrf ? window.OswlCsrf.token() : '';
        },

        async init() {
            // A stale error from the previously selected provider would be misleading once
            // the user switches — clear it as soon as the selection changes.
            this.$watch('mode', () => { this.apiError = null; this.apiErrorHint = null; });
            try {
                const r = await fetch('/api/settings/ai');
                if (r.status === 401) { location.href = '/login'; return; }
                if (!r.ok) { this.apiError = _fetchErr(r, _aiI18n.loadFailed); return; }
                const data = await r.json();
                if (data.activeProviderKind) this.activeProviderKind = data.activeProviderKind;
                if (data && data.active && data.provider) {
                    // Land on the entry that is actually live — for a running embedded model
                    // that is the Embedded option, not the Local/Ollama one it borrows.
                    this.mode = this.activeProviderKind === 'EMBEDDED' ? 'EMBEDDED' : data.provider;
                    const f = this.forms[data.provider];
                    if (f) {
                        if (data.baseUrl)   f.baseUrl   = data.baseUrl;
                        if (data.modelName) f.modelName = data.modelName;
                    }
                }
                if (data.promptsLocale) this.preferences.promptsLocale = data.promptsLocale;
                if (data.cveLimit)      this.preferences.cveLimit = data.cveLimit;
                if (data.licenseLimit)  this.preferences.licenseLimit = data.licenseLimit;
                if (data.cveSeverities) {
                    this.preferences.cveSeverities = data.cveSeverities.split(',').map(s => s.trim()).filter(Boolean);
                }
                if (data.temperature != null) this.preferences.temperature = data.temperature;
                if (data.maxTokens != null) this.preferences.maxTokens = data.maxTokens;
                if (data.dailyCallCap != null) this.preferences.dailyCallCap = data.dailyCallCap;
                if (data.defaultDeploymentProfile) {
                    this.preferences.defaultDeploymentProfile = data.defaultDeploymentProfile;
                }
                if (data.reasoningEffort) this.preferences.reasoningEffort = data.reasoningEffort;
                this.preferences.autoBackfillInsights = !!data.autoBackfillInsights;
                // The 'context' group's fields all come from this single response — nothing
                // further to fetch for it. 'provider' (usage/embedded status) and 'prompts'
                // each have their own calls, deferred until that sub-tab is actually opened.
                await this.loadSection(this.subTab);
            } catch (e) { this.apiError = _aiI18n.loadFailed; }
        },

        /** Loads a sub-tab's own data on first visit only — repeat visits reuse what's cached. */
        async loadSection(key) {
            if (key === 'provider' && !this._providerLoaded) {
                this._providerLoaded = true;
                await this.loadUsageStats();
                await this.loadUsageEvents(0);
                await this.loadEmbeddedStatus();
            } else if (key === 'prompts' && !this._promptsLoaded) {
                this._promptsLoaded = true;
                await this.loadPrompts();
            } else if (key === 'context') {
                this._contextLoaded = true;
            }
        },

        selectSubTab(key) {
            this.subTab = key;
            syncSettingsSection(key);
            this.loadSection(key);
        },

        async loadEmbeddedStatus() {
            try {
                const r = await fetch('/api/settings/ai/embedded');
                if (r.ok) {
                    this.embedded = await r.json();
                    this.syncEmbeddedInputs();
                    if (this.embedded.downloading && !this._embeddedPollActive) {
                        // A page reload landed mid-download (it runs server-side, independent
                        // of any browser session) — resume polling instead of leaving a stale
                        // progress snapshot on screen forever.
                        this.embeddedBusy = true;
                        this._pollEmbeddedUntilSettled().finally(() => { this.embeddedBusy = false; });
                    }
                }
            } catch (e) { /* non-fatal */ }
        },

        syncEmbeddedInputs() {
            this.embeddedModel = this.embedded.modelFile || '';
            this.embeddedDirInput = this.embedded.modelsDir || '';
        },

        async startEmbedded() {
            this.embeddedBusy = true;
            this.embeddedError = null;
            try {
                const url = '/api/settings/ai/embedded/start'
                    + (this.embeddedModel ? '?model=' + encodeURIComponent(this.embeddedModel) : '');
                const r = await fetch(url, {
                    method: 'POST',
                    headers: this.headers()
                });
                const data = await r.json();
                this.embedded = data;
                if (!r.ok) {
                    this.embeddedError = data.message || _aiI18n.embeddedFailed;
                    return;
                }
                if (data.downloading) {
                    // No .gguf present yet — the server started downloading Qwen3 in the
                    // background and returned immediately. Poll until it settles.
                    await this._pollEmbeddedUntilSettled();
                } else {
                    this._applyEmbeddedStartResult(data);
                }
            } catch (e) {
                this.embeddedError = _aiI18n.embeddedFailed;
            } finally {
                this.embeddedBusy = false;
            }
        },

        _applyEmbeddedStartResult(data) {
            if (data.success && data.running) {
                // Stay on (and light up) the Embedded entry. It previously jumped the radio to
                // Local/Ollama, which is where the "which one is on?" confusion came from.
                this.mode = 'EMBEDDED';
                this.activeProviderKind = 'EMBEDDED';
                if (data.baseUrl) this.forms.LOCAL.baseUrl = data.baseUrl;
                if (data.activeModel) this.forms.LOCAL.modelName = data.activeModel.replace(/\.gguf$/i, '');
                this.showToast(_aiI18n.embeddedStarted);
            } else {
                this.embeddedError = data.message || data.lastError || _aiI18n.embeddedFailed;
            }
        },

        /**
         * Polls GET /embedded every 1.5s while a background download and/or llama-server
         * launch is in flight (download progress, then the same ~90s health wait a normal
         * start already blocks on). Stops as soon as the outcome is decided: running, or
         * lastError set. A generous 20-minute cap guards against polling forever if the
         * server-side state machine ever gets stuck without reporting either.
         */
        async _pollEmbeddedUntilSettled() {
            if (this._embeddedPollActive) return;
            this._embeddedPollActive = true;
            const deadline = Date.now() + 20 * 60 * 1000;
            try {
                while (Date.now() < deadline) {
                    await new Promise(resolve => setTimeout(resolve, 1500));
                    let data;
                    try {
                        const r = await fetch('/api/settings/ai/embedded');
                        if (!r.ok) continue;
                        data = await r.json();
                    } catch (e) { continue; }
                    this.embedded = data;
                    if (data.running) {
                        this._applyEmbeddedStartResult({ ...data, success: true });
                        return;
                    }
                    if (data.lastError) {
                        this.embeddedError = data.lastError;
                        return;
                    }
                    // Still downloading, or still waiting for llama-server to become healthy.
                }
                this.embeddedError = _aiI18n.embeddedFailed;
            } finally {
                this._embeddedPollActive = false;
            }
        },

        embeddedButtonLabel() {
            if (this.embedded.downloading) {
                return _aiI18n.embeddedDownloading.replace('{0}', this.embeddedDownloadPercent());
            }
            return this.embeddedBusy ? _aiI18n.embeddedStarting : _aiI18n.embeddedStart;
        },

        embeddedDownloadPercent() {
            const total = this.embedded.downloadTotalBytes || 0;
            if (!total) return 0;
            return Math.min(100, Math.round((this.embedded.downloadedBytes || 0) / total * 100));
        },

        embeddedDownloadMB() {
            return {
                done: Math.round((this.embedded.downloadedBytes || 0) / 1024 / 1024),
                total: Math.round((this.embedded.downloadTotalBytes || 0) / 1024 / 1024)
            };
        },

        async saveEmbeddedConfig() {
            const dirtyRevision = window.OswlDirty?.revision('ai-embedded');
            this.embeddedSaving = true;
            this.embeddedError = null;
            try {
                const r = await fetch('/api/settings/ai/embedded/config', {
                    method: 'PUT',
                    headers: this.headers(),
                    body: JSON.stringify({ dir: this.embeddedDirInput, model: this.embeddedModel })
                });
                const data = await r.json();
                this.embedded = data;
                if (r.ok && data.success) {
                    const savedCurrentRevision = !window.OswlDirty
                        || window.OswlDirty.clear('ai-embedded', dirtyRevision);
                    if (savedCurrentRevision) this.syncEmbeddedInputs();
                    this.showToast(_aiI18n.embeddedConfigSaved);
                } else {
                    this.embeddedError = data.message || _aiI18n.embeddedFailed;
                }
            } catch (e) {
                this.embeddedError = _aiI18n.embeddedFailed;
            } finally {
                this.embeddedSaving = false;
            }
        },

        async stopEmbedded() {
            this.embeddedBusy = true;
            this.embeddedError = null;
            try {
                const r = await fetch('/api/settings/ai/embedded/stop', {
                    method: 'POST',
                    headers: this.headers()
                });
                if (r.ok) {
                    this.embedded = await r.json();
                    // Stopping the sidecar deactivates the LOCAL provider server-side, so
                    // nothing is serving calls until another entry is saved.
                    this.activeProviderKind = 'OFF';
                    this.showToast(_aiI18n.embeddedStopped);
                }
            } catch (e) { /* keep state */ } finally {
                this.embeddedBusy = false;
            }
        },

        async loadUsageStats() {
            try {
                const r = await fetch('/api/settings/ai/usage');
                if (r.ok) this.usageStats = await r.json();
            } catch (e) { /* non-fatal */ }
        },

        async loadUsageEvents(page) {
            try {
                const r = await fetch('/api/settings/ai/usage/events?page=' + page + '&size=10');
                if (!r.ok) return;
                const data = await r.json();
                this.usageEvents = data.content || [];
                this.usageEventPage = data.number || 0;
                this.usageEventTotalPages = Math.min(data.totalPages || 0, 10);
            } catch (e) { /* non-fatal */ }
        },

        usageEventsPrevPage() { if (this.usageEventPage > 0) this.loadUsageEvents(this.usageEventPage - 1); },
        usageEventsNextPage() { if (this.usageEventPage + 1 < this.usageEventTotalPages) this.loadUsageEvents(this.usageEventPage + 1); },

        async loadPrompts() {
            try {
                const r = await fetch('/api/settings/ai/prompts');
                if (!r.ok) return;
                const data = await r.json();
                this.promptKeys = data.editableKeys || [];
                this.promptOverrides = data.overrides || {};
                if (this.promptKeys.length && !this.promptKeys.includes(this.promptEditKey)) {
                    this.promptEditKey = this.promptKeys[0];
                }
                await this.loadPromptEditor(data);
            } catch (_) { /* optional */ }
        },

        async loadPromptEditor(cachedPrompts) {
            const key = this.promptEditKey;
            if (this.promptOverrides[key]) {
                this.promptEditText = this.promptOverrides[key];
                return;
            }
            const data = cachedPrompts || await fetch('/api/settings/ai/prompts').then(r => r.json());
            this.promptEditText = (data.resolvedTemplates && data.resolvedTemplates[key]) || '';
        },

        toggleCveSeverity(level, checked) {
            if (checked && !this.preferences.cveSeverities.includes(level)) {
                this.preferences.cveSeverities.push(level);
            } else if (!checked) {
                this.preferences.cveSeverities = this.preferences.cveSeverities.filter(s => s !== level);
            }
        },

        headers() { return oswlJsonHeaders(); },

        providerLabel(mode) {
            const map = {
                OPENAI: _aiI18n.providerOpenai,
                ANTHROPIC: _aiI18n.providerAnthropic,
                GEMINI: _aiI18n.providerGemini,
                LOCAL: _aiI18n.providerLocal,
            };
            return map[mode] || mode;
        },

        showToast(msg, kind = 'success') {
            this._toastTimer && clearTimeout(this._toastTimer);
            this.toastMsg = msg;
            this.toastKind = kind;
            this.toastVisible = true;
            this._toastTimer = setTimeout(() => { this.toastVisible = false; }, kind === 'error' ? 5000 : 3000);
        },

        preferencePayload() {
            const severities = this.preferences.cveSeverities.length
                ? this.preferences.cveSeverities
                : ['CRITICAL', 'HIGH'];
            const overrides = { ...this.promptOverrides };
            if (this.promptEditKey && this.promptEditText) {
                overrides[this.promptEditKey] = this.promptEditText;
            }
            return {
                promptsLocale: this.preferences.promptsLocale || 'en',
                cveLimit:      Number(this.preferences.cveLimit) || 10,
                licenseLimit:  Number(this.preferences.licenseLimit) || 8,
                cveSeverities: severities.join(','),
                temperature:   this.preferences.temperature,
                maxTokens:     this.preferences.maxTokens,
                dailyCallCap:  Number(this.preferences.dailyCallCap) || 0,
                defaultDeploymentProfile: this.preferences.defaultDeploymentProfile || 'COMMERCIAL_PRODUCT',
                reasoningEffort: this.preferences.reasoningEffort || 'DEFAULT',
                autoBackfillInsights: !!this.preferences.autoBackfillInsights,
                promptOverrides: Object.keys(overrides).length ? JSON.stringify(overrides) : null
            };
        },

        async saveProvider() {
            // Embedded AI has no Save: its Start button both activates it and health-checks
            // the sidecar. The button is hidden for that mode; this is the belt-and-braces.
            if (this.mode === 'EMBEDDED') return;
            const providerMode = this.mode;
            const dirtyRevision = window.OswlDirty?.revision('ai-provider');
            this.savingProvider = true; this.apiError = null; this.apiErrorHint = null;
            try {
                if (providerMode === 'off') {
                    const r = await fetch('/api/settings/ai/deactivate', {
                        method: 'PUT',
                        headers: this.headers()
                    });
                    if (r.status === 401) { location.href = '/login'; return; }
                    if (!r.ok) { this.apiError = _fetchErr(r, _aiI18n.disableFailed); return; }
                    this.activeProviderKind = 'OFF';
                    if (window.OswlDirty) window.OswlDirty.clear('ai-provider', dirtyRevision);
                    this.showToast(this.embedded.running
                        ? _aiI18n.disabled + ' ' + _aiI18n.activatedEmbeddedStillRunning
                        : _aiI18n.disabled);
                    return;
                }
                const f = this.forms[providerMode];
                const body = {
                    provider:  providerMode,
                    apiKey:    f.apiKey    || null,
                    modelName: f.modelName || null,
                    baseUrl:   f.baseUrl   || null,
                    activate:  true
                };
                const r = await fetch('/api/settings/ai', {
                    method: 'PUT',
                    headers: this.headers(),
                    body: JSON.stringify(body)
                });
                if (r.status === 401) { location.href = '/login'; return; }
                if (!r.ok) { this.apiError = _fetchErr(r, _aiI18n.saveFailed); return; }
                this.activeProviderKind = providerMode;
                if (window.OswlDirty) window.OswlDirty.clear('ai-provider', dirtyRevision);
                let msg = _aiI18n.activated.replace('{0}', this.providerLabel(providerMode));
                // The sidecar process is a separate on/off control — saving a different
                // provider does not stop it. Say so, otherwise it keeps consuming memory
                // while nothing routes to it.
                if (this.embedded.running) {
                    msg += ' ' + _aiI18n.activatedEmbeddedStillRunning;
                }
                this.showToast(msg);
            } catch (e) { this.apiError = String(e); }
            finally { this.savingProvider = false; }
        },

        async saveEnrichment() {
            const contextRevision = window.OswlDirty?.revision('ai-context');
            const promptsRevision = window.OswlDirty?.revision('ai-prompts');
            this.savingEnrichment = true; this.apiError = null; this.apiErrorHint = null;
            try {
                const prefs = this.preferencePayload();
                if (this.mode === 'off') {
                    const r = await fetch('/api/settings/ai/deactivate', {
                        method: 'PUT',
                        headers: this.headers(),
                        body: JSON.stringify(prefs)
                    });
                    if (r.status === 401) { location.href = '/login'; return; }
                    if (!r.ok) { this.apiError = _fetchErr(r, _aiI18n.saveFailed); return; }
                    if (window.OswlDirty) {
                        window.OswlDirty.clear('ai-context', contextRevision);
                        window.OswlDirty.clear('ai-prompts', promptsRevision);
                    }
                    this.showToast(_aiI18n.enrichmentSaved);
                    return;
                }
                const body = { provider: this.mode, activate: true, ...prefs };
                const r = await fetch('/api/settings/ai', {
                    method: 'PUT',
                    headers: this.headers(),
                    body: JSON.stringify(body)
                });
                if (r.status === 401) { location.href = '/login'; return; }
                if (!r.ok) { this.apiError = _fetchErr(r, _aiI18n.saveFailed); return; }
                if (window.OswlDirty) {
                    window.OswlDirty.clear('ai-context', contextRevision);
                    window.OswlDirty.clear('ai-prompts', promptsRevision);
                }
                this.showToast(_aiI18n.enrichmentSaved);
            } catch (e) { this.apiError = String(e); }
            finally { this.savingEnrichment = false; }
        },

        async testConnection() {
            // Embedded AI is health-checked by Start itself, and has no API key or
            // endpoint form to probe.
            if (this.mode === 'off' || this.mode === 'EMBEDDED') return;
            this.testing = true;
            this.apiError = null;
            this.apiErrorHint = null;
            try {
                const f = this.forms[this.mode];
                const body = {
                    provider:  this.mode,
                    apiKey:    f.apiKey    || null,
                    modelName: f.modelName || null,
                    baseUrl:   f.baseUrl   || null
                };
                const r = await fetch('/api/settings/ai/test-connection', {
                    method: 'POST',
                    headers: this.headers(),
                    body: JSON.stringify(body)
                });
                if (r.status === 401) { location.href = '/login'; return; }
                const data = await r.json();
                // The toast is the only notice. A success may still carry a hint (e.g. the
                // model id was not in the provider's catalogue) — that is a warning, not an
                // error, so it is toasted rather than left on the page. Hard failures keep the
                // top banner too, because it is the only place long provider diagnostics fit.
                const success = !!(r.ok && data.success);
                const message = data.message
                    || (success ? _aiI18n.testOk : _aiI18n.testFailed);
                if (success) {
                    this.showToast(data.hint ? message + ' — ' + data.hint : message,
                                   data.hint ? 'error' : 'success');
                } else {
                    this.apiError = message;
                    this.apiErrorHint = data.hint || null;
                    this.showToast(message, 'error');
                }
            } catch (e) {
                this.apiError = _aiI18n.testFailed;
                this.apiErrorHint = e.message;
                this.showToast(this.apiError, 'error');
            } finally {
                this.testing = false;
            }
        }
    };
}
