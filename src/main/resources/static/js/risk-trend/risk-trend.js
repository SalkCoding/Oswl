/**
 * Initialize Line Charts for Risk Trend Dashboard
 */
function showChartPlaceholder(canvasId, title, hint) {
    const canvas = document.getElementById(canvasId);
    if (!canvas) return;
    const container = canvas.closest('.relative') || canvas.parentElement;
    canvas.style.display = 'none';
    const msg = document.createElement('div');
    msg.className = 'flex flex-col items-center justify-center h-full gap-[8px] text-center';
    msg.innerHTML = `<p class="text-[14px] font-medium text-[var(--grayscale-60)] tracking-[-0.14px]">${title}</p>` +
                    (hint ? `<p class="text-[12px] text-[var(--grayscale-60)] tracking-[-0.12px]">${hint}</p>` : '');
    container.appendChild(msg);
}

// Builds an accessible <table> mirror of a chart's series so screen-reader users get the
// same numbers the canvas shows (Chart.js canvases expose nothing to assistive tech).
function buildChartDataTable(xLabel, labels, series, captionText) {
    const table = document.createElement('table');
    table.className = 'w-full text-[13px]';
    const caption = document.createElement('caption');
    caption.className = 'sr-only';
    caption.textContent = captionText;
    table.appendChild(caption);

    const headRow = document.createElement('tr');
    headRow.className = 'text-left text-[var(--grayscale-70)] border-b border-[var(--grayscale-20)]';
    const cornerTh = document.createElement('th');
    cornerTh.className = 'px-[16px] py-[12px] font-bold';
    cornerTh.textContent = xLabel;
    headRow.appendChild(cornerTh);
    series.forEach(s => {
        const th = document.createElement('th');
        th.className = 'px-[16px] py-[12px] font-bold text-right';
        th.textContent = s.label;
        headRow.appendChild(th);
    });
    const thead = document.createElement('thead');
    thead.appendChild(headRow);
    table.appendChild(thead);

    const tbody = document.createElement('tbody');
    labels.forEach((label, i) => {
        const tr = document.createElement('tr');
        tr.className = 'border-b border-[var(--grayscale-10)]';
        const th = document.createElement('th');
        th.scope = 'row';
        th.className = 'px-[16px] py-[12px] text-left font-medium text-[var(--grayscale-80)]';
        th.textContent = label;
        tr.appendChild(th);
        series.forEach(s => {
            const td = document.createElement('td');
            td.className = 'px-[16px] py-[12px] text-right text-[var(--grayscale-80)]';
            td.textContent = s.data[i] != null ? s.data[i] : 0;
            tr.appendChild(td);
        });
        tbody.appendChild(tr);
    });
    table.appendChild(tbody);
    return table;
}

// Wires a "view as table" toggle: swaps the chart's canvas wrapper for a lazily built
// data table and back. i18n needs viewAsTable/viewAsChart labels.
function setupChartTableToggle(toggleId, tableContainerId, canvasId, xLabel, labels, series, captionText, i18n) {
    const toggle = document.getElementById(toggleId);
    const tableContainer = document.getElementById(tableContainerId);
    const canvas = document.getElementById(canvasId);
    if (!toggle || !tableContainer || !canvas) return;
    const chartWrap = canvas.closest('.relative') || canvas.parentElement;
    let table = null;
    toggle.addEventListener('click', () => {
        const showing = toggle.getAttribute('aria-pressed') === 'true';
        if (showing) {
            tableContainer.classList.add('hidden');
            chartWrap.classList.remove('hidden');
            toggle.setAttribute('aria-pressed', 'false');
            toggle.textContent = (i18n && i18n.viewAsTable) || 'View as table';
        } else {
            if (!table) {
                table = buildChartDataTable(xLabel, labels, series, captionText);
                tableContainer.appendChild(table);
            }
            tableContainer.classList.remove('hidden');
            chartWrap.classList.add('hidden');
            toggle.setAttribute('aria-pressed', 'true');
            toggle.textContent = (i18n && i18n.viewAsChart) || 'View as chart';
        }
    });
}

function hideChartTableToggles(ids) {
    ids.forEach(id => {
        const toggle = document.getElementById(id);
        if (toggle) toggle.style.display = 'none';
    });
}

document.addEventListener('DOMContentLoaded', () => {
    const i18nEarly = window.riskTrendI18n || {
        noData: 'No scan data yet',
        noDataHint: 'Run a scan to see the risk trend over time.',
        chartLoadFailed: 'Chart could not be loaded.'
    };

    if (typeof Chart === 'undefined') {
        console.warn('Chart.js is not loaded. Please include it in your layout.');
        ['securityRiskChart', 'licenseRiskChart'].forEach(id => {
            showChartPlaceholder(id, i18nEarly.chartLoadFailed || 'Chart could not be loaded.', null);
        });
        hideChartTableToggles(['securityRiskTableToggle', 'licenseRiskTableToggle']);
        return;
    }

    const themeVar = (name, fallback) => {
        if (window.OswlTheme && window.OswlTheme.cssVar) {
            return window.OswlTheme.cssVar(name, fallback);
        }
        return fallback;
    };

    const palette = {
        critical: themeVar('--risk-critical', '#e62727'),
        high:     themeVar('--risk-high',     '#f47a29'),
        medium:   themeVar('--risk-medium',   '#f5bd26'),
        low:      themeVar('--risk-low',       '#97a5ab'),
        unknown:  themeVar('--risk-unknown',   '#d0d9dd')
    };

    const licensePalette = {
        restricted: themeVar('--risk-critical', '#e62727'),
        caution:    themeVar('--risk-caution',  '#f59126'),
        permitted:  themeVar('--risk-permitted','#84dca5'),
        unknown:    themeVar('--risk-unknown',   '#abb8be')
    };

    const axisColor = themeVar('--grayscale-50', '#5e6b70');
    const tickColor = themeVar('--grayscale-60', '#425055');
    const gridColor = themeVar('--grayscale-15', '#dce4e7');
    const pointBorder = themeVar('--surface', '#ffffff');

    // 백엔드에서 주입된 실제 데이터, 없으면 빈 배열로 폴백
    const trendData = (window.riskTrendData && window.riskTrendData.versions.length > 0)
        ? window.riskTrendData
        : null;

    const i18n = window.riskTrendI18n || {
        xAxis: 'Version',
        yAxis: 'Issue Count',
        noData: 'No scan data yet',
        noDataHint: 'Run a scan to see the risk trend over time.'
    };

    if (!trendData) {
        // No scan data: show empty-state message in each chart canvas
        ['securityRiskChart', 'licenseRiskChart'].forEach(id => {
            showChartPlaceholder(id, i18n.noData, i18n.noDataHint);
        });
        // Hide AI insight boxes when no data
        document.querySelectorAll('.ai-insight-box').forEach(el => el.style.display = 'none');
        hideChartTableToggles(['securityRiskTableToggle', 'licenseRiskTableToggle']);
        return;
    }

    const versions = trendData.versions;

    const commonOptions = {
        responsive: true,
        maintainAspectRatio: false,
        plugins: {
            legend: { display: false },
            tooltip: { mode: 'index', intersect: false }
        },
        scales: {
            x: {
                title: {
                    display: true,
                    text: i18n.xAxis,
                    align: 'end',
                    color: axisColor,
                    font: { size: 14, family: 'Inter', weight: '500' },
                    padding: { top: 8 }
                },
                grid: { display: false },
                border: { display: false },
                ticks: {
                    color: tickColor,
                    font: { size: 14, family: 'Inter', weight: '500' },
                    maxRotation: 0
                }
            },
            y: {
                title: {
                    display: true,
                    text: i18n.yAxis,
                    align: 'end',
                    color: axisColor,
                    font: { size: 14, family: 'Inter' },
                    padding: { bottom: 8 }
                },
                grid: {
                    color: gridColor,
                    drawBorder: false
                },
                border: { display: false, dash: [0] },
                min: 0,
                ticks: {
                    color: tickColor,
                    font: { size: 14, family: 'Inter', weight: '500' },
                    stepSize: 1,
                    callback: (val) => {
                        if (!Number.isInteger(val)) return null;
                        return val >= 1000 ? Math.round(val / 1000) + 'K' : val;
                    }
                }
            }
        },
        elements: {
            line: { tension: 0.3, borderWidth: 2 },
            point: { radius: 4, hoverRadius: 6, borderWidth: 2, borderColor: pointBorder }
        },
        interaction: { mode: 'nearest', axis: 'x', intersect: false }
    };

    const createDatasets = (variant) => {
        if (variant === 'license') {
            const d = trendData.license;
            return [
                { label: i18n.restricted || 'Restricted', data: d.restricted || [], fill: false, borderColor: licensePalette.restricted, backgroundColor: licensePalette.restricted },
                { label: i18n.caution    || 'Caution',    data: d.caution    || [], fill: false, borderColor: licensePalette.caution,    backgroundColor: licensePalette.caution },
                { label: i18n.permitted  || 'Permitted',  data: d.permitted  || [], fill: false, borderColor: licensePalette.permitted,  backgroundColor: licensePalette.permitted },
                { label: i18n.unknown    || 'Unknown',    data: d.unknown    || [], fill: false, borderColor: licensePalette.unknown,    backgroundColor: licensePalette.unknown }
            ];
        }
        const d = trendData.security;
        return [
            { label: i18n.critical || 'Critical', data: d.critical, fill: false, borderColor: palette.critical, backgroundColor: palette.critical },
            { label: i18n.high     || 'High',     data: d.high,     fill: false, borderColor: palette.high,     backgroundColor: palette.high },
            { label: i18n.medium   || 'Medium',   data: d.medium,   fill: false, borderColor: palette.medium,   backgroundColor: palette.medium },
            { label: i18n.low      || 'Low',      data: d.low,      fill: false, borderColor: palette.low,      backgroundColor: palette.low },
            { label: i18n.unscored || 'Unscored', data: d.none,     fill: false, borderColor: palette.unknown,  backgroundColor: palette.unknown }
        ];
    };

    const charts = [];
    const ctxSecurity = document.getElementById('securityRiskChart');
    if (ctxSecurity) {
        charts.push(new Chart(ctxSecurity, {
            type: 'line',
            data: { labels: versions, datasets: createDatasets('security') },
            options: commonOptions
        }));
        setupChartTableToggle('securityRiskTableToggle', 'securityRiskChartTable', 'securityRiskChart',
            i18n.xAxis || 'Version', versions, createDatasets('security'), i18n.chartSecurity || 'Security Risk', i18n);
    }

    const ctxLicense = document.getElementById('licenseRiskChart');
    if (ctxLicense) {
        charts.push(new Chart(ctxLicense, {
            type: 'line',
            data: { labels: versions, datasets: createDatasets('license') },
            options: commonOptions
        }));
        setupChartTableToggle('licenseRiskTableToggle', 'licenseRiskChartTable', 'licenseRiskChart',
            i18n.xAxis || 'Version', versions, createDatasets('license'), i18n.chartLicense || 'License Risk', i18n);
    }

    const refreshTheme = () => {
        const colors = {
            critical: themeVar('--risk-critical', '#e62727'),
            high: themeVar('--risk-high', '#f47a29'),
            medium: themeVar('--risk-medium', '#f5bd26'),
            low: themeVar('--risk-low', '#97a5ab'),
            unknown: themeVar('--risk-unknown', '#d0d9dd'),
            caution: themeVar('--risk-caution', '#f59126'),
            permitted: themeVar('--risk-permitted', '#84dca5')
        };
        const axis = themeVar('--grayscale-50', '#5e6b70');
        const tick = themeVar('--grayscale-60', '#425055');
        const grid = themeVar('--grayscale-15', '#dce4e7');
        const point = themeVar('--surface', '#ffffff');
        commonOptions.scales.x.title.color = axis;
        commonOptions.scales.x.ticks.color = tick;
        commonOptions.scales.y.title.color = axis;
        commonOptions.scales.y.ticks.color = tick;
        commonOptions.scales.y.grid.color = grid;
        commonOptions.elements.point.borderColor = point;
        charts.forEach(chart => {
            const colorsForChart = chart.canvas.id === 'licenseRiskChart'
                ? [colors.critical, colors.caution, colors.permitted, colors.unknown]
                : [colors.critical, colors.high, colors.medium, colors.low, colors.unknown];
            chart.data.datasets.forEach((dataset, index) => {
                dataset.borderColor = colorsForChart[index];
                dataset.backgroundColor = colorsForChart[index];
            });
            chart.update('none');
        });
    };
    if (window.OswlTheme) window.OswlTheme.subscribe(refreshTheme);
});
