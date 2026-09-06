function cacheTab() {
    return {
        policy: 'custom',           // 'custom' | 'always' | 'permanent'
        customAmount: 7, customUnit: 'day',
        unitDropdownOpen: false,
        items: [],
        loaded: false, loading: false,
        saving: false, apiError: null, toast: null, clearConfirm: false,
        csrf() { return window.OswlCsrf ? window.OswlCsrf.token() : ''; },
        headers() { return oswlJsonHeaders(); },
        showToast(msg) { this.toast = msg; setTimeout(()=> this.toast = null, 2500); },
        formatTtl(hours) {
            if (!hours || hours <= 0) return _cacheI18n.policyAlways;
            if (hours >= 24*365*10) return 'Permanent';
            if (hours % 24 === 0) return (hours/24) + ' ' + _cacheI18n.days;
            return hours + ' ' + _cacheI18n.hours;
        },
        async load() {
            if (this.loading) return;
            this.loading = true; this.loaded = false; this.apiError = null;
            try {
                const r = await fetch('/api/settings/cache');
                if (r.status === 401) { location.href = '/login'; return; }
                if (!r.ok) { this.apiError = _fetchErr(r, _cacheI18n.loadFailed); return; }
                this.items = await r.json();
                // Infer current policy from first item's ttlHours
                if (this.items.length) {
                    const h = this.items[0].ttlHours;
                    if (h === null || h === undefined) { this.policy = 'custom'; this.customAmount = 7; this.customUnit = 'day'; }
                    else if (h <= 0) this.policy = 'always';
                    else if (h >= 24*365*10) this.policy = 'permanent';
                    else {
                        this.policy = 'custom';
                        if (h % 24 === 0) { this.customAmount = h/24; this.customUnit = 'day'; }
                        else { this.customAmount = h; this.customUnit = 'hour'; }
                    }
                }
                this.loaded = true;
            } catch(e) { this.apiError = _cacheI18n.loadFailed; }
            finally { this.loading = false; }
        },
        ttlSecondsForPolicy() {
            if (this.policy === 'always') return 1;
            if (this.policy === 'permanent') return 50 * 365 * 24 * 3600; // 50 years, fits in long
            const hours = (this.customUnit === 'day') ? this.customAmount * 24 : this.customAmount;
            return Math.max(1, hours) * 3600;
        },
        savedPolicyLabel() {
            if (this.policy === 'always') return _cacheI18n.policyAlways;
            if (this.policy === 'permanent') return _cacheI18n.policyPermanent;
            const unit = this.customUnit === 'day' ? _cacheI18n.days : _cacheI18n.hours;
            return `Cache policy set to ${this.customAmount} ${unit}.`;
        },
        async save() {
            if (!this.loaded || this.loading || this.saving) return;
            const dirtyRevision = window.OswlDirty?.revision('cache');
            this.saving = true; this.apiError = null;
            const label = this.savedPolicyLabel(); // capture before any await
            try {
                const ttlSeconds = this.ttlSecondsForPolicy();
                // Apply to every existing cache key
                for (const c of this.items) {
                    const r = await fetch('/api/settings/cache', { method: 'PUT', headers: this.headers(), body: JSON.stringify({ cacheKey: c.cacheKey, ttlSeconds }) });
                    if (r.status === 401) { location.href = '/login'; return; }
                    if (!r.ok) { this.apiError = _fetchErr(r, _cacheI18n.saveFailed); return; }
                }
                // Update items in-place so a re-fetch doesn't overwrite the policy
                this.items = this.items.map(c => ({
                    ...c, ttlSeconds, ttlHours: Math.floor(ttlSeconds / 3600)
                }));
                if (window.OswlDirty) window.OswlDirty.clear('cache', dirtyRevision);
                this.showToast(label);
            } catch (e) { this.apiError = _cacheI18n.saveFailed; }
            finally { this.saving = false; }
        },
        async clearAll() {
            const dirtyRevision = window.OswlDirty?.revision('cache');
            this.clearConfirm = false;
            try {
                const r = await fetch('/api/settings/cache/clear?cacheKey=ALL', { method: 'POST', headers: this.headers() });
                if (r.status === 401) { location.href = '/login'; return; }
                if (!r.ok) { this.apiError = _fetchErr(r, _cacheI18n.saveFailed); return; }
                const savedCurrentRevision = !window.OswlDirty
                    || window.OswlDirty.clear('cache', dirtyRevision);
                if (savedCurrentRevision) await this.load();
                this.showToast(_cacheI18n.cleared);
            } catch (e) {
                this.apiError = _cacheI18n.saveFailed;
            }
        }
    };
}
