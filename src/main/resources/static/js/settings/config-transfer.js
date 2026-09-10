function configTransferTab() {
    return {
        exporting: false, applying: false, apiError: null, toast: null,
        bundle: null, preview: null,
        showToast(msg) { this.toast = msg; setTimeout(()=> this.toast = null, 2500); },
        headers() { return oswlJsonHeaders(); },
        async exportBundle() {
            this.exporting = true; this.apiError = null;
            try {
                const r = await fetch('/api/settings/config-transfer/export');
                if (r.status === 401) { location.href = '/login'; return; }
                if (!r.ok) { this.apiError = _fetchErr(r, _cfgI18n.exportFailed); return; }
                const data = await r.json();
                const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' });
                const url = URL.createObjectURL(blob);
                const a = document.createElement('a');
                a.href = url;
                a.download = 'oswl-config-' + new Date().toISOString().slice(0, 10) + '.json';
                document.body.appendChild(a);
                a.click();
                a.remove();
                setTimeout(() => URL.revokeObjectURL(url), 60000);
            } catch (e) {
                this.apiError = _cfgI18n.exportFailed;
            } finally {
                this.exporting = false;
            }
        },
        onFileSelected(evt) {
            const file = evt.target.files && evt.target.files[0];
            if (!file) return;
            this.apiError = null; this.preview = null; this.bundle = null;
            const reader = new FileReader();
            reader.onload = async () => {
                try {
                    this.bundle = JSON.parse(reader.result);
                } catch (e) {
                    this.apiError = _cfgI18n.previewFailed;
                    return;
                }
                await this.runPreview();
            };
            reader.readAsText(file);
        },
        async runPreview() {
            try {
                const r = await fetch('/api/settings/config-transfer/import?dryRun=true', {
                    method: 'POST', headers: this.headers(), body: JSON.stringify(this.bundle)
                });
                if (r.status === 401) { location.href = '/login'; return; }
                if (!r.ok) { this.apiError = _fetchErr(r, _cfgI18n.previewFailed); return; }
                this.preview = await r.json();
            } catch (e) {
                this.apiError = _cfgI18n.previewFailed;
            }
        },
        async applyImport() {
            if (!this.bundle) return;
            this.applying = true; this.apiError = null;
            try {
                const r = await fetch('/api/settings/config-transfer/import?dryRun=false', {
                    method: 'POST', headers: this.headers(), body: JSON.stringify(this.bundle)
                });
                if (r.status === 401) { location.href = '/login'; return; }
                if (!r.ok) { this.apiError = _fetchErr(r, _cfgI18n.applyFailed); return; }
                this.preview = await r.json();
                this.showToast(_cfgI18n.applied);
            } catch (e) {
                this.apiError = _cfgI18n.applyFailed;
            } finally {
                this.applying = false;
            }
        }
    };
}
