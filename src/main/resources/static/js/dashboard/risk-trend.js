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
        low: '#c5cfd3'
    };

    const versions = ['1.0.0', '1.0.5', '1.1.0', '1.1.5', '1.2.0', '1.2.5'];

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
                    callback: (val) => val >= 1000 ? (val / 1000).toFixed(1) + 'K' : val
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
        const data = variant === 'license'
            ? {
                critical: [12, 18, 14, 9,  11, 7 ],
                high:     [34, 41, 38, 29, 33, 21],
                medium:   [78, 95, 82, 74, 61, 55],
                low:      [210, 198, 225, 190, 172, 163]
            }
            : {
                critical: [45, 72, 58, 89, 63, 38],
                high:     [130, 154, 117, 176, 142, 98],
                medium:   [280, 310, 265, 340, 295, 248],
                low:      [520, 490, 545, 610, 575, 432]
            };

        return [
            { label: 'Critical', data: data.critical, fill: false, borderColor: palette.critical, backgroundColor: palette.critical },
            { label: 'High',     data: data.high,     fill: false, borderColor: palette.high,     backgroundColor: palette.high },
            { label: 'Medium',   data: data.medium,   fill: false, borderColor: palette.medium,   backgroundColor: palette.medium },
            { label: 'Low',      data: data.low,      fill: false, borderColor: palette.low,      backgroundColor: palette.low }
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
