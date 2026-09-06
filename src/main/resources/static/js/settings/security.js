function securityTab() {
    return {
        // ── Mail Server ──
        mailMode: 'DISABLED',
        mailForm: {
            host: '',
            port: 587,
            encryption: 'STARTTLS',
            username: '',
            password: '',
            senderName: 'OsWL',
            senderAddress: ''
        },
        mailTesting: false,
        mailSaving: false,
        mailTestStatus: null,   // null | 'ok' | 'error'
        mailTestMessage: '',

        // ── Two-Factor Auth ──
        twoFaMode: 'DISABLED',
        twoFaSaving: false,

        // ── Common ──
        apiError: null,
        loaded: false,
        loading: false,
        toastVisible: false,
        toastMsg: '',

        init() {
            this.load();
        },

        async load() {
            if (this.loading) return;
            this.loaded = false;
            this.loading = true;
            this.apiError = null;
            try {
                const d = await OswlHttp.json('/api/settings/security');
                if (!['DISABLED', 'SMTP'].includes(d.mailMode) || !['DISABLED', 'EMAIL_OTP'].includes(d.twoFaMode))
                    throw new OswlHttp.HttpError('server', 0);
                if (d.mailMode)  this.mailMode  = d.mailMode;
                if (d.twoFaMode) this.twoFaMode = d.twoFaMode;
                if (d.mail) {
                    if (d.mail.host          != null) this.mailForm.host          = d.mail.host;
                    if (d.mail.port          != null) this.mailForm.port          = d.mail.port;
                    if (d.mail.encryption    != null) this.mailForm.encryption    = d.mail.encryption;
                    if (d.mail.username      != null) this.mailForm.username      = d.mail.username;
                    if (d.mail.senderName    != null) this.mailForm.senderName    = d.mail.senderName;
                    if (d.mail.senderAddress != null) this.mailForm.senderAddress = d.mail.senderAddress;
                }
                this.loaded = true;
            } catch (e) { this.apiError = _securityI18n[e.kind] || _securityI18n.backendNotConnected; }
            finally { this.loading = false; }
        },

        showToast(msg) {
            this.toastMsg = msg;
            this.toastVisible = true;
            setTimeout(() => { this.toastVisible = false; }, 3000);
        },

        applyEncryptionDefaults(enc) {
            if (enc === 'STARTTLS') this.mailForm.port = 587;
            else if (enc === 'SSL_TLS') this.mailForm.port = 465;
            else if (enc === 'NONE') this.mailForm.port = 25;
        },

        async testMail() {
            if (!this.loaded || this.loading) return;
            this.mailTesting = true;
            this.mailTestStatus = null;
            this.mailTestMessage = '';
            try {
                const r = await OswlHttp.request('/api/settings/security/mail/test', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ ...this.mailForm })
                });
                if (r.ok) {
                    this.mailTestStatus = 'ok';
                    this.mailTestMessage = _securityI18n.testOk;
                } else {
                    const body = await r.json().catch(() => ({}));
                    this.mailTestStatus = 'error';
                    this.mailTestMessage = body.message || _securityI18n.mailTestFailed.replace('{0}', r.status);
                }
            } catch (e) {
                this.mailTestStatus = 'error';
                this.mailTestMessage = _securityI18n[e.kind] || _securityI18n.backendNotConnected;
            } finally {
                this.mailTesting = false;
            }
        },

        async saveMail() {
            if (!this.loaded || this.loading || this.mailSaving) return;
            this.mailSaving = true;
            this.apiError = null;
            try {
                const r = await OswlHttp.request('/api/settings/security', {
                    method: 'PUT',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ mailMode: this.mailMode, mail: this.mailForm })
                });
                if (r.ok) {
                    if (window.OswlDirty) window.OswlDirty.clear('security-mail');
                    this.showToast(_securityI18n.saved);
                } else {
                    const body = await r.json().catch(() => ({}));
                    this.apiError = body.message || _securityI18n.backendNotConnected;
                }
            } catch (e) {
                this.apiError = _securityI18n[e.kind] || _securityI18n.backendNotConnected;
            } finally {
                this.mailSaving = false;
            }
        },

        async saveTwoFa() {
            if (!this.loaded || this.loading || this.twoFaSaving) return;
            this.twoFaSaving = true;
            this.apiError = null;
            try {
                const r = await OswlHttp.request('/api/settings/security', {
                    method: 'PUT',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ twoFaMode: this.twoFaMode })
                });
                if (r.ok) {
                    if (window.OswlDirty) window.OswlDirty.clear('security-2fa');
                    this.showToast(_securityI18n.saved);
                } else {
                    const body = await r.json().catch(() => ({}));
                    this.apiError = body.message || _securityI18n.backendNotConnected;
                }
            } catch (e) {
                this.apiError = _securityI18n[e.kind] || _securityI18n.backendNotConnected;
            } finally {
                this.twoFaSaving = false;
            }
        }
    };
}
    