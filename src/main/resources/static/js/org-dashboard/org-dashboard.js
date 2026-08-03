/**
 * Org-wide vulnerability trend line chart (admin org dashboard).
 * Data and labels are injected by Thymeleaf into window.orgDashboardData / window.orgDashboardI18n.
 */
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

    const showPlaceholder = (title, hint) => {
        const container = canvas.closest('.relative') || canvas.parentElement;
        canvas.style.display = 'none';
        const msg = document.createElement('div');
        msg.className = 'flex flex-col items-center justify-center h-full gap-[8px] text-center';
        msg.innerHTML = `<p class="text-[14px] font-medium text-[var(--grayscale-40)] tracking-[-0.14px]">${title}</p>` +
                        (hint ? `<p class="text-[12px] text-[var(--grayscale-30)] tracking-[-0.12px]">${hint}</p>` : '');
        container.appendChild(msg);
    };

    if (typeof Chart === 'undefined') {
        console.warn('Chart.js is not loaded. Please include it in your layout.');
        showPlaceholder(i18n.chartLoadFailed || 'Chart could not be loaded.', null);
        return;
    }

    const data = window.orgDashboardData;
    const hasData = data && data.labels && data.labels.length > 0 &&
        [data.critical, data.high, data.medium, data.low, data.unscored]
            .some(series => Array.isArray(series) && series.some(v => v > 0));

    if (!hasData) {
        showPlaceholder(i18n.noData, i18n.noDataHint);
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

    new Chart(canvas, {
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
});
