/**
 * Initialize Line Charts for Risk Trend Dashboard
 */
document.addEventListener('DOMContentLoaded', () => {
    if (typeof Chart === 'undefined') {
        console.warn('Chart.js is not loaded. Please include it in your layout.');
        return;
    }

    const palette = {
        critical: '#e62727',
        high: '#f47a29',
        medium: '#f5bd26',
        low: '#97a5ab',
        unknown: '#d0d9dd'
    };

    const licensePalette = {
        restricted: '#e62727',
        caution:    '#f59126',
        permitted:  '#84dca5',
        unknown:    '#abb8be'
    };

    // 백엔드에서 주입된 실제 데이터, 없으면 빈 배열로 폴백
    const trendData = (window.riskTrendData && window.riskTrendData.versions.length > 0)
        ? window.riskTrendData
        : null;

    if (!trendData) {
        // No scan data: show empty-state message in each chart canvas
        ['securityRiskChart', 'licenseRiskChart'].forEach(id => {
            const canvas = document.getElementById(id);
            if (!canvas) return;
            const container = canvas.closest('.relative') || canvas.parentElement;
            canvas.style.display = 'none';
            const msg = document.createElement('div');
            msg.className = 'flex flex-col items-center justify-center h-full gap-[8px] text-center';
            msg.innerHTML = '<p class="text-[14px] font-medium text-[var(--grayscale-40)] tracking-[-0.14px]">No scan data yet</p>' +
                            '<p class="text-[12px] text-[var(--grayscale-30)] tracking-[-0.12px]">Run a scan to see the risk trend over time.</p>';
            container.appendChild(msg);
        });
        // Hide AI insight boxes when no data
        document.querySelectorAll('.ai-insight-box').forEach(el => el.style.display = 'none');
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
                    text: 'Version',
                    align: 'end',
                    color: '#5e6b70',
                    font: { size: 14, family: 'Inter', weight: '500' },
                    padding: { top: 8 }
                },
                grid: { display: false },
                border: { display: false },
                ticks: {
                    color: '#425055',
                    font: { size: 14, family: 'Inter', weight: '500' },
                    maxRotation: 0
                }
            },
            y: {
                title: {
                    display: true,
                    text: 'Issue Count',
                    align: 'end',
                    color: '#5e6b70',
                    font: { size: 14, family: 'Inter' },
                    padding: { bottom: 8 }
                },
                grid: {
                    color: '#dce4e7',
                    drawBorder: false
                },
                border: { display: false, dash: [0] },
                min: 0,
                ticks: {
                    color: '#425055',
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
            point: { radius: 4, hoverRadius: 6, borderWidth: 2, borderColor: 'white' }
        },
        interaction: { mode: 'nearest', axis: 'x', intersect: false }
    };

    const createDatasets = (variant) => {
        if (variant === 'license') {
            const d = trendData.license;
            return [
                { label: 'Restricted', data: d.restricted || [], fill: false, borderColor: licensePalette.restricted, backgroundColor: licensePalette.restricted },
                { label: 'Caution',    data: d.caution    || [], fill: false, borderColor: licensePalette.caution,    backgroundColor: licensePalette.caution },
                { label: 'Permitted',  data: d.permitted  || [], fill: false, borderColor: licensePalette.permitted,  backgroundColor: licensePalette.permitted },
                { label: 'Unknown',    data: d.unknown    || [], fill: false, borderColor: licensePalette.unknown,    backgroundColor: licensePalette.unknown }
            ];
        }
        const d = trendData.security;
        return [
            { label: 'Critical', data: d.critical, fill: false, borderColor: palette.critical, backgroundColor: palette.critical },
            { label: 'High',     data: d.high,     fill: false, borderColor: palette.high,     backgroundColor: palette.high },
            { label: 'Medium',   data: d.medium,   fill: false, borderColor: palette.medium,   backgroundColor: palette.medium },
            { label: 'Low',      data: d.low,      fill: false, borderColor: palette.low,      backgroundColor: palette.low },
            { label: 'Unscored', data: d.none,     fill: false, borderColor: palette.unknown,  backgroundColor: palette.unknown }
        ];
    };

    const ctxSecurity = document.getElementById('securityRiskChart');
    if (ctxSecurity) {
        new Chart(ctxSecurity, {
            type: 'line',
            data: { labels: versions, datasets: createDatasets('security') },
            options: commonOptions
        });
    }

    const ctxLicense = document.getElementById('licenseRiskChart');
    if (ctxLicense) {
        new Chart(ctxLicense, {
            type: 'line',
            data: { labels: versions, datasets: createDatasets('license') },
            options: commonOptions
        });
    }
});
