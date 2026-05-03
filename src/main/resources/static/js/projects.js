/**
 * Projects Page Main Script
 * Handles project management and interaction
 */

/**
 * Refresh the project cards grid by fetching the server-rendered page
 * and swapping only the grid container's inner HTML (no full page reload).
 */
async function refreshProjectCards() {
    const container = document.getElementById('project-cards-container');
    if (!container) return;
    try {
        const res = await fetch('/projects');
        if (!res.ok) return;
        const html = await res.text();
        const parser = new DOMParser();
        const doc = parser.parseFromString(html, 'text/html');
        const newContainer = doc.getElementById('project-cards-container');
        if (newContainer) {
            container.innerHTML = newContainer.innerHTML;
            // Re-initialize Alpine on dynamically injected x-data nodes
            if (window.Alpine) {
                window.Alpine.initTree(container);
            }
        }
    } catch (e) {
        console.error('[Projects] Failed to refresh project cards', e);
    }
}

/**
 * Delete a project by ID
 */
let _toastHideTimer = null;

function showToast(projectName, suffix) {
    const toast = document.getElementById('project-toast');
    toast.querySelector('[data-toast-project-name]').textContent = projectName;
    toast.querySelector('[data-toast-suffix]').textContent = suffix;
    toast.style.opacity = '1';
    toast.style.transform = 'translate(-50%, 0)';

    if (_toastHideTimer) clearTimeout(_toastHideTimer);
    _toastHideTimer = setTimeout(() => {
        toast.style.opacity = '0';
        toast.style.transform = 'translate(-50%, 8px)';
    }, 3000);
}

function deleteProject(projectId, projectName, cardEl) {
    if (!projectId) return;

    // Fade out the card immediately for instant feedback
    cardEl.style.transition = 'opacity 0.2s, transform 0.2s';
    cardEl.style.opacity = '0';
    cardEl.style.transform = 'scale(0.97)';

    fetch(`/projects/${projectId}`, {
        method: 'DELETE',
        headers: { 'Content-Type': 'application/json' }
    })
    .then(response => {
        if (response.ok) {
            cardEl.remove();
            // Also remove the project from CLI integration panel's select dropdown
            const option = document.querySelector(`select option[value="${projectId}"]`);
            if (option) option.remove();
            showToast(projectName, ' has been deleted.');
        } else {
            // Restore card on failure
            cardEl.style.opacity = '1';
            cardEl.style.transform = '';
            showToast('', 'Failed to delete project.');
        }
    })
    .catch(error => {
        console.error('Error:', error);
        cardEl.style.opacity = '1';
        cardEl.style.transform = '';
        showToast('', 'Error deleting project.');
    });
}
