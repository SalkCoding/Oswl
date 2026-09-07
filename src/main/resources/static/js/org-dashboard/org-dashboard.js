/**
 * Org-wide vulnerability trend line chart (admin org dashboard).
 * Data and labels are injected by Thymeleaf into window.orgDashboardData / window.orgDashboardI18n.
 */

// Builds an accessible <table> mirror of the chart's series so screen-reader users get the
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

document.addEventListener('DOMContentLoaded', () => {
    const i18n = window.orgDashboardI18n || {
        xAxis: 'Week',
        yAxis: 'Issue Count',
        noData: 'No scan data yet',
        noDataHint: 'Run scans across projects to see the org-wide trend.',
        chartLoadFailed: 'Chart could not be loaded.'
    };

    const canvas = document.getElementById('orgVulnTrendChart');
    if (!canvas) return;

    const tableToggle = document.getElementById('orgVulnTrendTableToggle');
    const hideTableToggle = () => { if (tableToggle) tableToggle.style.display = 'none'; };

    const showPlaceholder = (title, hint) => {
        const container = canvas.closest('.relative') || canvas.parentElement;
        canvas.style.display = 'none';
        const msg = document.createElement('div');
        msg.className = 'flex flex-col items-center justify-center h-full gap-[8px] text-center';
        msg.innerHTML = `<p class="text-[14px] font-medium text-[var(--grayscale-60)] tracking-[-0.14px]">${title}</p>` +
                        (hint ? `<p class="text-[12px] text-[var(--grayscale-60)] tracking-[-0.12px]">${hint}</p>` : '');
        container.appendChild(msg);
    };

    if (typeof Chart === 'undefined') {
        console.warn('Chart.js is not loaded. Please include it in your layout.');
        showPlaceholder(i18n.chartLoadFailed || 'Chart could not be loaded.', null);
        hideTableToggle();
        return;
    }

    const data = window.orgDashboardData;
    const hasData = data && data.labels && data.labels.length > 0 &&
        [data.critical, data.high, data.medium, data.low, data.unscored]
            .some(series => Array.isArray(series) && series.some(v => v > 0));

    if (!hasData) {
        showPlaceholder(i18n.noData, i18n.noDataHint);
        hideTableToggle();
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

    const axisColor = themeVar('--grayscale-50', '#5e6b70');
    const tickColor = themeVar('--grayscale-60', '#425055');
    const gridColor = themeVar('--grayscale-15', '#dce4e7');
    const pointBorder = themeVar('--surface', '#ffffff');

    const chart = new Chart(canvas, {
        type: 'line',
        data: {
            labels: data.labels,
            datasets: [
                { label: i18n.critical || 'Critical', data: data.critical, fill: false, borderColor: palette.critical, backgroundColor: palette.critical },
                { label: i18n.high     || 'High',     data: data.high,     fill: false, borderColor: palette.high,     backgroundColor: palette.high },
                { label: i18n.medium   || 'Medium',   data: data.medium,   fill: false, borderColor: palette.medium,   backgroundColor: palette.medium },
                { label: i18n.low      || 'Low',      data: data.low,      fill: false, borderColor: palette.low,      backgroundColor: palette.low },
                { label: i18n.unscored || 'Unscored', data: data.unscored, fill: false, borderColor: palette.unknown,  backgroundColor: palette.unknown }
            ]
        },
        options: {
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
        }
    });

    const refreshTheme = () => {
        const colors = {
            critical: themeVar('--risk-critical', '#e62727'),
            high: themeVar('--risk-high', '#f47a29'),
            medium: themeVar('--risk-medium', '#f5bd26'),
            low: themeVar('--risk-low', '#97a5ab'),
            unknown: themeVar('--risk-unknown', '#d0d9dd')
        };
        const axis = themeVar('--grayscale-50', '#5e6b70');
        const tick = themeVar('--grayscale-60', '#425055');
        const grid = themeVar('--grayscale-15', '#dce4e7');
        const point = themeVar('--surface', '#ffffff');
        const seriesColors = [colors.critical, colors.high, colors.medium, colors.low, colors.unknown];
        chart.data.datasets.forEach((dataset, index) => {
            dataset.borderColor = seriesColors[index];
            dataset.backgroundColor = seriesColors[index];
        });
        chart.options.scales.x.title.color = axis;
        chart.options.scales.x.ticks.color = tick;
        chart.options.scales.y.title.color = axis;
        chart.options.scales.y.ticks.color = tick;
        chart.options.scales.y.grid.color = grid;
        chart.options.elements.point.borderColor = point;
        chart.update('none');
    };
    if (window.OswlTheme) window.OswlTheme.subscribe(refreshTheme);

    // "View as table" toggle: swaps the canvas for a lazily built data table and back.
    const tableContainer = document.getElementById('orgVulnTrendChartTable');
    if (tableToggle && tableContainer) {
        const series = [
            { label: i18n.critical || 'Critical', data: data.critical },
            { label: i18n.high     || 'High',     data: data.high },
            { label: i18n.medium   || 'Medium',   data: data.medium },
            { label: i18n.low      || 'Low',      data: data.low },
            { label: i18n.unscored || 'Unscored', data: data.unscored }
        ];
        const chartWrap = canvas.closest('.relative') || canvas.parentElement;
        let table = null;
        tableToggle.addEventListener('click', () => {
            const showing = tableToggle.getAttribute('aria-pressed') === 'true';
            if (showing) {
                tableContainer.classList.add('hidden');
                chartWrap.classList.remove('hidden');
                tableToggle.setAttribute('aria-pressed', 'false');
                tableToggle.textContent = i18n.viewAsTable || 'View as table';
            } else {
                if (!table) {
                    table = buildChartDataTable(i18n.xAxis || 'Week', data.labels, series,
                        i18n.trendHeading || 'Org-wide Vulnerability Trend');
                    tableContainer.appendChild(table);
                }
                tableContainer.classList.remove('hidden');
                chartWrap.classList.add('hidden');
                tableToggle.setAttribute('aria-pressed', 'true');
                tableToggle.textContent = i18n.viewAsChart || 'View as chart';
            }
        });
    }
});
