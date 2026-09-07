function diagnosticsTab() {
    return {
        results: [],
        running: false, apiError: null, toast: null,
        showToast(msg) { this.toast = msg; setTimeout(()=> this.toast = null, 2500); },
        statusLabel(s) { return _diagI18n[ (s || '').toLowerCase() ] || s; },
        statusClass(s) {
            switch (s) {
                case 'UP': return 'bg-[rgba(34,197,94,0.12)] text-[#15803d]';
                case 'DOWN': return 'bg-[rgba(239,68,68,0.12)] text-[#b91c1c]';
                case 'SKIPPED': return 'bg-[var(--grayscale-10)] text-[var(--grayscale-60)]';
                default: return 'bg-[rgba(234,179,8,0.12)] text-[#a16207]';
            }
        },
        detailText(r) {
            if (r.id !== 'disk') return r.detail;
            const parts = Object.fromEntries((r.detail || '').split(', ').map(p => { const i = p.indexOf('='); return [p.slice(0, i), p.slice(i + 1)]; }));
            const gib = value => (Number(value) / 1073741824).toLocaleString(document.documentElement.lang, {maximumFractionDigits: 1}) + ' GiB';
            return _diagI18n.diskSpace.replace('{0}', gib(parts.freeBytes)).replace('{1}', gib(parts.totalBytes)).replace('{2}', parts.freePercent) + '\n' + (parts.path || '');
        },
        async run() {
            this.running = true; this.apiError = null;
            try {
                const r = await fetch('/api/settings/diagnostics');
                if (r.status === 401) { location.href = '/login'; return; }
                if (!r.ok) { this.apiError = _fetchErr(r, _diagI18n.loadFailed); return; }
                this.results = await r.json();
            } catch (e) {
                this.apiError = _diagI18n.loadFailed;
            } finally {
                this.running = false;
            }
        },
        copyForSupport() {
            const lines = this.results.map(r => `[${r.status}] ${r.label} — ${r.detail}`);
            const text = lines.join('\n');
            navigator.clipboard.writeText(text).then(() => this.showToast(_diagI18n.copied)).catch(() => { this.apiError = _diagI18n.copyFailed; });
        }
    };
}

function scanArchivingPanel() {
    return {
        projects: [], projectsLoading: false, archError: null,
        projectId: '', retainCount: '',
        previewing: false, archiving: false,
        preview: null, result: null, exportDownloaded: false,
        resetRun() { this.preview = null; this.result = null; this.exportDownloaded = false; },
        retainQuery() {
            const n = parseInt(this.retainCount, 10);
            return Number.isInteger(n) && n >= 1 ? '?retainCount=' + n : '';
        },
        previewText() {
            if (!this.preview) return '';
            return this.preview.scans > 0
                ? _archI18n.previewSummary.replace('{0}', this.preview.scans).replace('{1}', this.preview.components)
                : _archI18n.previewEmpty;
        },
        async loadProjects() {
            this.projectsLoading = true; this.archError = null;
            try {
                const r = await fetch('/api/admin/projects');
                if (r.status === 401) { location.href = '/login'; return; }
                if (!r.ok) { this.archError = _fetchErr(r, _archI18n.projectsFailed); return; }
                this.projects = await r.json();
            } catch (e) {
                this.archError = _archI18n.projectsFailed;
            } finally {
                this.projectsLoading = false;
            }
        },
        async previewExport() {
            if (!this.projectId) return;
            this.previewing = true; this.archError = null; this.result = null;
            try {
                const r = await fetch('/api/admin/projects/' + this.projectId + '/archive-scans/export' + this.retainQuery());
                if (r.status === 401) { location.href = '/login'; return; }
                if (!r.ok) { this.archError = _fetchErr(r, _archI18n.loadFailed); return; }
                const data = await r.json();
                this.preview = {
                    scans: data.length,
                    components: data.reduce((sum, s) => sum + (s.components ? s.components.length : 0), 0)
                };
                if (data.length === 0) { this.exportDownloaded = false; return; }
                const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' });
                const url = URL.createObjectURL(blob);
                const a = document.createElement('a');
                a.href = url;
                a.download = 'oswl-scan-archive-export-p' + this.projectId + '-' + new Date().toISOString().slice(0, 10) + '.json';
                document.body.appendChild(a);
                a.click();
                a.remove();
                setTimeout(() => URL.revokeObjectURL(url), 60000);
                this.exportDownloaded = true;
            } catch (e) {
                this.archError = _archI18n.loadFailed;
            } finally {
                this.previewing = false;
            }
        },
        async archiveNow() {
            if (!this.projectId) return;
            const pending = this.preview ? this.preview.scans : '?';
            const msg = (this.exportDownloaded ? _archI18n.confirm : _archI18n.confirmNoExport)
                .replace('{0}', pending);
            if (!window.confirm(msg)) return;
            this.archiving = true; this.archError = null; this.result = null;
            try {
                const r = await fetch('/api/admin/projects/' + this.projectId + '/archive-scans' + this.retainQuery(), {
                    method: 'POST',
                    headers: { 'X-XSRF-TOKEN': window.OswlCsrf ? window.OswlCsrf.token() : '' }
                });
                if (r.status === 401) { location.href = '/login'; return; }
                if (!r.ok) { this.archError = _fetchErr(r, _archI18n.loadFailed); return; }
                const data = await r.json();
                this.result = _archI18n.result
                    .replace('{0}', data.archivedNow)
                    .replace('{1}', data.totalCompletedScans)
                    .replace('{2}', data.retainCount);
                this.preview = null; this.exportDownloaded = false;
            } catch (e) {
                this.archError = _archI18n.loadFailed;
            } finally {
                this.archiving = false;
            }
        }
    };
}
