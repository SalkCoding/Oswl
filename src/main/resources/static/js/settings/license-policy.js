function licensePolicyTab() {
    return {
        entries: [],
        searchQuery: '',
        page: 0,
        pageSize: 50,
        renderCount: 50,
        total: 0,
        hasMore: false,
        allLoaded: false,
        fetching: false,
        prefetching: false,
        _prefetchToken: 0,
        _scrollObserver: null,
        openStatusDropdown: null,
        statusOptions: [
            { value: 'PERMITTED',  label: _licensePolicyI18n.statusPermitted },
            { value: 'CAUTION',    label: _licensePolicyI18n.statusCaution },
            { value: 'RESTRICTED', label: _licensePolicyI18n.statusRestricted },
            { value: 'UNKNOWN',    label: _licensePolicyI18n.statusUnknown }
        ],
        apiError: null,
        toastVisible: false,
        toastMsg: '',
        headers() { return oswlJsonHeaders(); },
        statusLabel(status) {
            const opt = this.statusOptions.find(o => o.value === status);
            return opt ? opt.label : status;
        },
        get filteredPool() {
            const q = this.searchQuery.trim().toLowerCase();
            if (!q) return this.entries;
            if (!this.allLoaded) return this.entries;
            return this.entries.filter(entry => {
                const id = (entry.spdxId || '').toLowerCase();
                const name = (entry.displayName || '').toLowerCase();
                return id.includes(q) || name.includes(q);
            });
        },
        get visibleEntries() {
            return this.filteredPool.slice(0, this.renderCount);
        },
        get canExpandRender() {
            return this.allLoaded && this.renderCount < this.filteredPool.length;
        },
        resultSummary() {
            const q = this.searchQuery.trim();
            if (q) {
                const count = this.allLoaded ? this.filteredPool.length : this.total;
                return _licensePolicyI18n.searchSummary.replace('{0}', String(count));
            }
            return _licensePolicyI18n.resultSummary.replace('{0}', String(this.total));
        },
        init() {
            this.$nextTick(() => {
                this._scrollObserver = new IntersectionObserver((hits) => {
                    if (hits.some(h => h.isIntersecting)) this.loadMore();
                }, { rootMargin: '320px' });
                if (this.$refs.scrollSentinel) {
                    this._scrollObserver.observe(this.$refs.scrollSentinel);
                }
            });
            this.resetAndLoad();
        },
        showToast(msg) {
            this.toastMsg = msg;
            this.toastVisible = true;
            setTimeout(() => { this.toastVisible = false; }, 2500);
        },
        onSearchChange() {
            if (this.allLoaded) {
                this.renderCount = this.pageSize;
                return;
            }
            this.resetAndLoad();
        },
        async resetAndLoad() {
            this._prefetchToken++;
            const token = this._prefetchToken;
            // Rows are discarded here, so any unsaved status edits are gone too.
            if (window.OswlDirty) window.OswlDirty.clear('license-policy');
            this.prefetching = false;
            this.allLoaded = false;
            this.renderCount = this.pageSize;
            this.page = 0;
            this.entries = [];
            this.hasMore = false;
            this.total = 0;
            await this.fetchPage(0, false, token);
            if (token !== this._prefetchToken) return;
            if (!this.searchQuery.trim()) {
                this.prefetchRemaining(token);
            }
        },
        async loadMore() {
            if (this.canExpandRender) {
                this.renderCount = Math.min(this.renderCount + this.pageSize, this.filteredPool.length);
                return;
            }
            if (!this.hasMore || this.fetching || this.prefetching) return;
            if (this.searchQuery.trim() && !this.allLoaded) return;
            const token = this._prefetchToken;
            await this.fetchPage(this.page + 1, true, token);
        },
        async prefetchRemaining(token) {
            if (this.prefetching || token !== this._prefetchToken) return;
            this.prefetching = true;
            try {
                while (this.hasMore && token === this._prefetchToken && !this.searchQuery.trim()) {
                    await this.fetchPage(this.page + 1, true, token);
                    await new Promise(resolve => setTimeout(resolve, 0));
                }
                if (token === this._prefetchToken && !this.hasMore) {
                    this.allLoaded = true;
                }
            } finally {
                if (token === this._prefetchToken) {
                    this.prefetching = false;
                }
            }
        },
        async fetchPage(page, append, token) {
            if (token !== this._prefetchToken) return;
            this.fetching = true;
            this.apiError = null;
            try {
                const params = new URLSearchParams({
                    page: String(page),
                    size: String(this.pageSize)
                });
                const q = this.searchQuery.trim();
                if (q) params.set('q', q);
                const r = await fetch('/api/settings/license-policy?' + params.toString());
                if (token !== this._prefetchToken) return;
                if (r.status === 401) { location.href = '/login'; return; }
                if (!r.ok) { this.apiError = _fetchErr(r, _licensePolicyI18n.loadFailed); return; }
                const data = await r.json();
                this.page = data.page;
                this.total = data.total;
                this.hasMore = data.hasMore;
                const items = (data.items || []).map(it => ({ ...it, _savedStatus: it.status }));
                this.entries = append ? this.entries.concat(items) : items;
                if (!this.hasMore && !q) {
                    this.allLoaded = true;
                }
            } catch (e) {
                if (token === this._prefetchToken) {
                    this.apiError = _licensePolicyI18n.loadFailed;
                }
            } finally {
                if (token === this._prefetchToken) {
                    this.fetching = false;
                }
            }
        },
        async save(entry) {
            const dirtyRevision = window.OswlDirty?.revision('license-policy');
            const savedStatus = entry.status;
            this.apiError = null;
            try {
                const encoded = encodeURIComponent(entry.spdxId);
                const r = await fetch('/api/settings/license-policy/' + encoded, {
                    method: 'PUT', headers: this.headers(), body: JSON.stringify({ status: savedStatus })
                });
                if (r.status === 401) { location.href = '/login'; return; }
                if (!r.ok) { this.apiError = _fetchErr(r, _licensePolicyI18n.saveFailed); return; }
                entry._savedStatus = savedStatus;
                // Rows save individually — keep the warning while other rows still differ.
                if (window.OswlDirty && !this.entries.some(e => e.status !== e._savedStatus)) {
                    window.OswlDirty.clear('license-policy', dirtyRevision);
                }
                this.showToast(_licensePolicyI18n.saved);
            } catch (e) {
                this.apiError = _licensePolicyI18n.saveFailed;
            }
        }
    };
}
