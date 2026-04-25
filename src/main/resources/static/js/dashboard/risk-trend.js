/**
 * Initialize Stacked Area Charts for Risk Trend Dashboard
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

    const toRgba = (hex, alpha) => {
        const r = parseInt(hex.slice(1, 3), 16);
        const g = parseInt(hex.slice(3, 5), 16);
        const b = parseInt(hex.slice(5, 7), 16);
        return `rgba(${r}, ${g}, ${b}, ${alpha})`;
    };

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
                    display: true, text: 'Version', align: 'end',
                    color: '#5e6b70', font: { size: 13, family: 'Inter' }
                },
                grid: { display: false },
                ticks: { color: '#425055', font: { size: 12, family: 'Inter' } }
            },
            y: {
                stacked: true,
                title: {
                    display: true, text: 'Issue Count', align: 'end',
                    color: '#5e6b70', font: { size: 13, family: 'Inter' }
                },
                grid: { color: '#f3f5f6' },
                min: 0,
                ticks: { color: '#425055', font: { size: 12, family: 'Inter' } }
            }
        },
        elements: {
            line: { tension: 0.4, borderWidth: 2 },
            point: { radius: 0, hoverRadius: 6 }
        },
        interaction: { mode: 'nearest', axis: 'x', intersect: false }
    };

    const versions = ['1.0.0', '1.0.5', '1.1.0', '1.1.5', '1.2.0', '1.2.5'];

    const createDatasets = (variant) => {
        // Slightly different sample numbers per chart for visual differentiation.
        const data = variant === 'license'
            ? {
                critical: [60, 80, 70, 65, 50, 40],
                high:     [120, 130, 120, 100, 90, 80],
                medium:   [180, 200, 180, 160, 150, 140],
                low:      [250, 260, 250, 240, 230, 220]
            }
            : {
                critical: [350, 400, 320, 200, 150, 100],
                high:     [250, 250, 300, 200, 150, 80],
                medium:   [200, 200, 250, 150, 120, 90],
                low:      [150, 150, 100, 100, 80, 50]
            };

        return [
            {
                label: 'Critical', data: data.critical, fill: true,
                backgroundColor: toRgba(palette.critical, 0.4),
                borderColor: palette.critical
            },
            {
                label: 'High', data: data.high, fill: true,
                backgroundColor: toRgba(palette.high, 0.4),
                borderColor: palette.high
            },
            {
                label: 'Medium', data: data.medium, fill: true,
                backgroundColor: toRgba(palette.medium, 0.4),
                borderColor: palette.medium
            },
            {
                label: 'Low', data: data.low, fill: true,
                backgroundColor: toRgba(palette.low, 0.4),
                borderColor: palette.low
            }
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
