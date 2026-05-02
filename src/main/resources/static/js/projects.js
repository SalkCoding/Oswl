/**
 * Projects Page Main Script
 * Handles project management and interaction
 */

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
