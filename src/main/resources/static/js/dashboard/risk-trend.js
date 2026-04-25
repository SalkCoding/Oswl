/**
 * Initialize Stacked Area Charts for Risk Trend Dashboard
 */
document.addEventListener('DOMContentLoaded', () => {
    // Load Chart.js via CDN if not present (only for fallback, assume it's loaded in the layout)
    if (typeof Chart === 'undefined') {
        console.warn('Chart.js is not loaded. Please include it in your layout.');
        return;
    }

    const commonOptions = {
        responsive: true,
        maintainAspectRatio: false,
        plugins: {
            legend: {
                display: false // We use our custom HTML legend above the charts
            },
            tooltip: {
                mode: 'index',
                intersect: false,
            }
        },
        scales: {
            x: {
                title: {
                    display: true,
                    text: 'Version',
                    align: 'end',
                    color: '#5e6b70',
                    font: { size: 14, family: 'Inter' }
                },
                grid: {
                    display: false
                },
                ticks: {
                    color: '#425055',
                    font: { size: 14, family: 'Inter' }
                }
            },
            y: {
                stacked: true,
                title: {
                    display: true,
                    text: 'Issue Count',
                    align: 'end',
                    color: '#5e6b70',
                    font: { size: 14, family: 'Inter' }
                },
                grid: {
                    color: '#f3f5f6'
                },
                min: 0,
                max: 1000,
                ticks: {
                    stepSize: 250,
                    color: '#425055',
                    font: { size: 14, family: 'Inter' }
                }
            }
        },
        elements: {
            line: {
                tension: 0.4, // Smooth curves
                borderWidth: 0
            },
            point: {
                radius: 0
            }
        },
        interaction: {
            mode: 'nearest',
            axis: 'x',
            intersect: false
        }
    };

    // Dummy data mimicking the Figma Mockup
    // This should be dynamically populated via an API or Thymeleaf model properties
    const versions = ['1.0.0', '1.0.5', '1.1.0', '1.1.5', '1.2.0', '1.2.5'];
    
    // Datasets
    const createDatasets = () => [
        {
            label: 'Critical',
            data: [350, 400, 320, 200, 150, 100],
            fill: true,
            backgroundColor: 'rgba(255, 77, 79, 0.4)' // Red translucent
        },
        {
            label: 'High',
            data: [250, 250, 300, 200, 150, 80],
            fill: true,
            backgroundColor: 'rgba(250, 140, 22, 0.4)' // Orange translucent
        },
        {
            label: 'Medium',
            data: [200, 200, 250, 150, 120, 90],
            fill: true,
            backgroundColor: 'rgba(250, 219, 20, 0.4)' // Yellow translucent
        },
        {
            label: 'Low',
            data: [150, 150, 100, 100, 80, 50],
            fill: true,
            backgroundColor: 'rgba(82, 196, 26, 0.4)' // Green translucent
        }
    ];

    const ctxSecurity = document.getElementById('securityRiskChart');
    if (ctxSecurity) {
        new Chart(ctxSecurity, {
            type: 'line',
            data: {
                labels: versions,
                datasets: createDatasets()
            },
            options: commonOptions
        });
    }

    const ctxLicense = document.getElementById('licenseRiskChart');
    if (ctxLicense) {
        new Chart(ctxLicense, {
            type: 'line',
            data: {
                labels: versions,
                datasets: createDatasets() // Maybe specific license data here
            },
            options: commonOptions
        });
    }
});
