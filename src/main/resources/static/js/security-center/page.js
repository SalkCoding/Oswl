function securityCenterPage() {
    return {
          componentPanelOpen: false,
          activeCompId: null,
          searchQuery: '',
          bulkActionsOpen: false,
          exportOpen: false,
          sortOpen: false,
          sortMode: 'risk',
          // Injected via the th:inline script block below (attribute values are not JS-inlined).
          sortLabels: window.securityCenterData.sortLabels,
          rowsAbort: null,
          selectedComponents: [],
          selectAll: false,
          filters: {
              reviewed: false,
              nonReviewed: false,
              ignored: false,
              nonIgnored: true,
              deferred: false,
              reachable: false,
              notReachable: false,
              unknownReachability: false,
              secCritical: false,
              secHigh: false,
              secMedium: false,
              secLow: false,
              secUnknown: false,
              licRestricted: false,
              licCaution: false,
              licUnknown: false,
              licPermitted: false,
              patchable: false,
              nonPatchable: false,
              patchDeprecated: false,
              patchOutdated: false,
              patchUpToDate: false,
              hideNonRuntime: true
          },
          // ── Server-side pagination state ──────────────────
          totalComponentCount: window.securityCenterData.totalComponentCount,
          hasMoreComponents: window.securityCenterData.hasMoreComponents,
          loadedComponentCount: 0,
          rowsLoaded: false,
          rowsLoading: false,
          currentPage: 0,
          rowsFetchToken: 0,
          views: [],
          viewsOpen: false,
          savingView: false,
          newViewName: '',
          newViewShared: false,
          scanStatus: window.securityCenterData.scanStatus,
          latestScanId: window.securityCenterData.latestScanId,
          currentScanId: window.securityCenterData.currentScanId,
          aiStatus: window.securityCenterData.aiStatus,
          aiPostureInsight: window.securityCenterData.securityPostureInsight,
          aiRetrying: false,
          scanPollTimer: null,
          aiPollTimer: null,
          // ── Server-side filtered/sorted/paginated rows ─────────
          // Filtering used to run in the browser (rowVisible(), against every already-rendered
          // row's data-* attributes) — that meant shipping and DOM-rendering all 5,000+ rows up
          // front just so JS could hide most of them (measured ~15s at 5,000 components). Filter
          // changes now re-fetch just the matching page from /security-center/rows instead.
          buildRowsQueryParams(page) {
              const p = new URLSearchParams();
              p.set('scanId', this.currentScanId);
              if (this.searchQuery.trim()) p.set('search', this.searchQuery.trim());
              p.set('sortMode', this.sortMode);
              p.set('page', page);
              for (const [k, v] of Object.entries(this.filters)) {
                  if (v) p.set(k, 'true');
              }
              return p;
          },
          async refetchRows() {
              if (!this.currentScanId) return;
              const token = ++this.rowsFetchToken;
              this.rowsAbort?.abort();
              this.rowsAbort = new AbortController();
              const signal = this.rowsAbort.signal;
              this.rowsLoading = true;
              this.currentPage = 0;
              this.clearSelected();
              try {
                  const projectId = document.body.dataset.projectId;
                  const html = await OswlHttp.text('/projects/' + projectId + '/security-center/rows?'
                                        + this.buildRowsQueryParams(0).toString(), { signal });
                  if (token !== this.rowsFetchToken || signal.aborted) return;
                  const container = document.getElementById('component-rows-container');
                  OswlHttp.fragment(container, html);
                  this.applyRowsMeta(container);
              } catch (e) {
                  if (token === this.rowsFetchToken && !signal.aborted)
                      alert(window.securityCenterData.i18n[e.kind] || window.securityCenterData.i18n.actionFailed);
              } finally {
                  if (token === this.rowsFetchToken) this.rowsLoading = false;
              }
          },
          async loadMoreRows() {
              if (!this.currentScanId || this.rowsLoading || !this.hasMoreComponents) return;
              const token = ++this.rowsFetchToken;
              this.rowsAbort?.abort();
              this.rowsAbort = new AbortController();
              const signal = this.rowsAbort.signal;
              this.rowsLoading = true;
              const nextPage = this.currentPage + 1;
              try {
                  const projectId = document.body.dataset.projectId;
                  const html = await OswlHttp.text('/projects/' + projectId + '/security-center/rows?'
                                        + this.buildRowsQueryParams(nextPage).toString(), { signal });
                  if (token !== this.rowsFetchToken || signal.aborted) return;
                  const container = document.getElementById('component-rows-container');
                  OswlHttp.fragment(container, html, true);
                  this.currentPage = nextPage;
                  this.applyRowsMeta(container);
              } catch (e) {
                  if (token === this.rowsFetchToken && !signal.aborted)
                      alert(window.securityCenterData.i18n[e.kind] || window.securityCenterData.i18n.actionFailed);
              } finally {
                  if (token === this.rowsFetchToken) this.rowsLoading = false;
              }
          },
          // Reads the most recently swapped-in fragment's data-total-count/data-has-more (set
          // from the Page<> the /rows endpoint queried) and updates the pagination footer state.
          // Load-more appends another [data-total-count] wrapper rather than replacing the
          // first one, so the *last* one in the container reflects the page that was just fetched.
          applyRowsMeta(container) {
              const metas = container.querySelectorAll('[data-total-count]');
              const meta = metas.length ? metas[metas.length - 1] : null;
              this.totalComponentCount = meta ? (parseInt(meta.dataset.totalCount) || 0) : 0;
              this.hasMoreComponents = meta ? meta.dataset.hasMore === 'true' : false;
              this.loadedComponentCount = container.querySelectorAll('.component-row').length;
              this.rowsLoaded = true;
          },
          startPolling() {
              if (this.scanPollTimer) return;
              this.scanPollTimer = setInterval(async () => {
                  if (!this.latestScanId) return;
                  const r = await fetch('/api/scan/' + this.latestScanId + '/status');
                  if (!r.ok) return;
                  const d = await r.json();
                  this.scanStatus = d.status;
                  if (d.status === 'COMPLETED' || d.status === 'FAILED') {
                      clearInterval(this.scanPollTimer);
                      this.scanPollTimer = null;
                      // The CVE/license data pipeline just finished — reload once to show it.
                      // AI summaries (aiStatus) may still be generating; startAiPolling() on the
                      // freshly-reloaded page fills those in without a second reload.
                      if (d.status === 'COMPLETED') { location.reload(); }
                  }
              }, 2500);
          },
          // Polls AI enrichment progress for the scan being viewed. Only runs after the data
          // pipeline is already COMPLETED (see init()) — never triggers a reload; the posture
          // insight is filled in reactively via aiPostureInsight once aiStatus reaches COMPLETED.
          startAiPolling() {
              if (this.aiPollTimer || !this.currentScanId) return;
              this.aiPollTimer = setInterval(async () => {
                  const r = await fetch('/api/scan/' + this.currentScanId + '/status');
                  if (!r.ok) return;
                  const d = await r.json();
                  if (d.securityPostureInsight) {
                      this.aiPostureInsight = d.securityPostureInsight;
                  }
                  if (['COMPLETED', 'FAILED', 'NOT_APPLICABLE'].includes(d.aiStatus)) {
                      clearInterval(this.aiPollTimer);
                      this.aiPollTimer = null;
                      // One more read a moment later: the insight and the status are written in
                      // two separate transactions, so a poll can land between them and see
                      // COMPLETED with the insight column still empty. Without this the card
                      // would claim nothing was produced for an insight that did arrive.
                      if (!this.aiPostureInsight) {
                          setTimeout(() => this.refetchAiInsight(), 1500);
                      }
                  }
                  // Assigned last so the generating card is only torn down once the insight
                  // that goes in its place has been read.
                  this.aiStatus = d.aiStatus;
              }, 2500);
          },
          // Single non-looping read of the current scan's insight.
          async refetchAiInsight() {
              try {
                  const r = await fetch('/api/scan/' + this.currentScanId + '/status');
                  if (!r.ok) return;
                  const d = await r.json();
                  if (d.securityPostureInsight) this.aiPostureInsight = d.securityPostureInsight;
                  this.aiStatus = d.aiStatus;
              } catch (e) { /* leave the empty state in place */ }
          },
          // Re-runs insight generation for the scan being viewed.
          async retryAiInsight() {
              if (!this.currentScanId) return;
              this.aiRetrying = true;
              try {
                  const projectId = document.body.dataset.projectId;
                  const r = await fetch('/projects/' + projectId + '/security-center/refresh-insights?scanId='
                                        + this.currentScanId, { method: 'POST', headers: oswlJsonHeaders() });
                  const d = await r.json().catch(() => ({}));
                  if (!r.ok || !d.success) {
                      alert(d.message || window.securityCenterData.i18n.actionFailed);
                      return;
                  }
                  await this.refetchAiInsight();
                  if (!this.aiPostureInsight) alert(window.securityCenterData.i18n.aiRetryEmpty);
              } catch (e) {
                  alert(window.securityCenterData.i18n.actionFailed);
              } finally {
                  this.aiRetrying = false;
              }
          },
          // Sorting moved server-side — SecurityCenterService.resolveSort() applies
          // the equivalent risk/name/license ordering via the /rows query. Setting sortMode here
          // triggers the $watch in init() that re-fetches the current page.
          applySort(mode) {
              this.sortMode = mode;
              this.sortOpen = false;
          },
          applyBulkAction(action) {
              if (!this.selectedComponents.length) return;
              const projectId = document.body.dataset.projectId;
              const ids = this.selectedComponents.map(Number);
              this.bulkActionsOpen = false;

              const clearSelection = () => {
                  this.selectedComponents = [];
                  this.selectAll = false;
              };
              // fetch resolves even on 403/500 — treat non-OK and network errors as failure
              const onFailure = () => alert(window.securityCenterData.i18n.actionFailed);

              if (action === 'reviewed' || action === 'unreviewed') {
                  const value = action === 'reviewed';
                  const idStrings = ids.map(String);
                  fetch('/projects/' + projectId + '/security-center/bulk-status', {
                      method: 'PATCH',
                      headers: oswlJsonHeaders(),
                      body: JSON.stringify({ids: ids, reviewed: value})
                  }).then(r => {
                      if (!r.ok) { onFailure(); return; }
                      window.dispatchEvent(new CustomEvent('bulk-review', {
                          detail: {ids: idStrings, value: value}
                      }));
                      clearSelection();
                  }).catch(onFailure);
              } else if (action === 'ignore' || action === 'unignore') {
                  const value = action === 'ignore';
                  const idStrings = ids.map(String);
                  fetch('/projects/' + projectId + '/security-center/bulk-status', {
                      method: 'PATCH',
                      headers: oswlJsonHeaders(),
                      body: JSON.stringify({ids: ids, ignored: value})
                  }).then(r => {
                      if (!r.ok) { onFailure(); return; }
                      window.dispatchEvent(new CustomEvent('bulk-ignore', {
                          detail: {ids: idStrings, value: value}
                      }));
                      // When ignoring, automatically show non-ignored items so the user can see
                      // the list update in real time — mutating filters triggers the $watch in
                      // init() that re-fetches the current page from the server.
                      if (action === 'ignore') {
                          this.filters.nonIgnored = true;
                      }
                      clearSelection();
                  }).catch(onFailure);
              }
          },
          async batchUpgradePrs() {
              const projectId = document.body.dataset.projectId;
              const i18n = window.securityCenterData.i18n || {};
              const branch = prompt(i18n.batchPrPrompt || 'Base branch for the upgrade PRs:', 'main');
              if (!branch || !branch.trim()) return;
              try {
                  const r = await fetch('/projects/' + projectId + '/security-center/batch-pr', {
                      method: 'POST', headers: oswlJsonHeaders(),
                      body: JSON.stringify({ targetBranch: branch.trim() })
                  });
                  const body = await r.json().catch(() => ({}));
                  if (!r.ok) { alert(body.error || (i18n.actionFailed || 'Failed.')); return; }
                  if (body.candidates === 0) { alert(i18n.batchPrNone || 'No patchable components found.'); return; }
                  const tmpl = i18n.batchPrResult || 'Batch PRs: {0} created, {1} failed (of {2} candidates).';
                  alert(tmpl.replace('{0}', body.created).replace('{1}', body.failed).replace('{2}', body.candidates));
              } catch (e) {
                  alert((window.securityCenterData.i18n || {}).actionFailed || 'Failed.');
              }
          },
          exportAs(format) {
              this.exportOpen = false;
              const projectId = document.body.dataset.projectId;
              if (format === 'pdf') {
                  let url = '/projects/' + projectId + '/security-center/print';
                  const scanId = new URLSearchParams(window.location.search).get('scanId');
                  if (scanId) url += '?scanId=' + scanId;
                  window.open(url, '_blank');
                  return;
              }
              if (format === 'sbom') {
                  window.location.href = '/api/projects/' + projectId + '/sbom?format=json';
                  return;
              }
              if (format === 'vex') {
                  window.location.href = '/api/projects/' + projectId + '/vex?format=json';
                  return;
              }
              if (format === 'sarif') {
                  window.location.href = '/api/projects/' + projectId + '/sarif';
                  return;
              }
              if (format === 'compliance') {
                  window.open('/projects/' + projectId + '/security-center/compliance-report', '_blank');
                  return;
              }
              // CSV download
              let url = '/projects/' + projectId + '/security-center/export?format=csv';
              if (this.latestScanId) url += '&scanId=' + this.latestScanId;
              window.location.href = url;
          },
          toggleSelectAll() {
              if (this.selectAll) {
                  const values = Array.from(document.querySelectorAll('input[name=selectedComponent]')).map(el => el.value);
                  this.selectedComponents = values;
              } else {
                  this.selectedComponents = [];
              }
          },
          clearSelected() {
              this.selectedComponents = [];
              this.selectAll = false;
          },
          // ── Saved views ───────────────────────────────────────────────
          async loadViews() {
              const projectId = document.body.dataset.projectId;
              try {
                  const r = await fetch('/api/projects/' + projectId + '/saved-views', { headers: oswlJsonHeaders() });
                  if (!r.ok) return;
                  this.views = await r.json();
              } catch (e) { /* leave the list empty */ }
          },
          currentViewState() {
              return { filters: this.filters, sortMode: this.sortMode, searchQuery: this.searchQuery };
          },
          applyView(view) {
              let state;
              try { state = JSON.parse(view.filtersJson); } catch (e) { return; }
              if (state.filters) this.filters = Object.assign({}, this.filters, state.filters);
              if (state.sortMode) this.applySort(state.sortMode);
              if (typeof state.searchQuery === 'string') this.searchQuery = state.searchQuery;
              this.viewsOpen = false;
          },
          async saveCurrentView() {
              const name = this.newViewName.trim();
              if (!name) return;
              const projectId = document.body.dataset.projectId;
              try {
                  const r = await fetch('/api/projects/' + projectId + '/saved-views', {
                      method: 'POST', headers: oswlJsonHeaders(),
                      body: JSON.stringify({
                          name: name,
                          shared: this.newViewShared,
                          filtersJson: JSON.stringify(this.currentViewState())
                      })
                  });
                  if (!r.ok) { alert(window.securityCenterData.i18n.actionFailed); return; }
                  const saved = await r.json();
                  this.views.unshift(saved);
                  this.newViewName = '';
                  this.newViewShared = false;
                  this.savingView = false;
              } catch (e) {
                  alert(window.securityCenterData.i18n.actionFailed);
              }
          },
          async deleteView(view) {
              const projectId = document.body.dataset.projectId;
              try {
                  const r = await fetch('/api/projects/' + projectId + '/saved-views/' + view.id, {
                      method: 'DELETE', headers: oswlJsonHeaders()
                  });
                  if (!r.ok) { alert(window.securityCenterData.i18n.actionFailed); return; }
                  this.views = this.views.filter(v => v.id !== view.id);
              } catch (e) {
                  alert(window.securityCenterData.i18n.actionFailed);
              }
          },
          copyViewLink(view) {
              const url = new URL(window.location.href);
              url.searchParams.set('view', view.id);
              navigator.clipboard?.writeText(url.toString());
          },
          applyViewFromUrl() {
              const viewId = new URLSearchParams(window.location.search).get('view');
              if (!viewId) return;
              const match = this.views.find(v => String(v.id) === viewId);
              if (match) this.applyView(match);
          },
          init() {
              if (['SCANNING', 'ANALYZING', 'PENDING'].includes(this.scanStatus)) {
                  this.startPolling();
              } else if (['PENDING', 'RUNNING'].includes(this.aiStatus)) {
                  this.startAiPolling();
              }

              // The initial page load already rendered the first (default-filtered, risk-sorted)
              // page server-side — seed the pagination footer from that DOM instead of an extra
              // fetch, then start watching for user-driven filter/sort/search changes.
              this.loadedComponentCount = document.querySelectorAll('.component-row').length;
              this.rowsLoaded = true;

              this.$watch('filters', () => this.refetchRows());
              this.$watch('sortMode', () => this.refetchRows());
              let searchDebounce = null;
              this.$watch('searchQuery', () => {
                  clearTimeout(searchDebounce);
                  searchDebounce = setTimeout(() => this.refetchRows(), 300);
              });

              this.loadViews().then(() => this.applyViewFromUrl());
          }
      };
}
