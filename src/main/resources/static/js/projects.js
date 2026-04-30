/**
 * Projects Page Main Script
 * Handles project management and interaction
 */

/**
 * Delete a project by ID
 */
function deleteProject(projectId, triggerElement) {
    if (!projectId) return;
    
    // Show confirmation toast
    const toast = document.getElementById('project-toast');
    const toastMessage = toast.querySelector('[data-toast-message]');
    toastMessage.textContent = `Deleting project ${projectId}...`;
    
    // Show toast with opacity animation
    toast.style.opacity = '1';
    toast.style.transform = 'translate(-50%, 0)';
    
    // Send delete request
    fetch(`/projects/${projectId}`, {
        method: 'DELETE',
        headers: {
            'Content-Type': 'application/json',
        }
    })
    .then(response => {
        if (response.ok) {
            toastMessage.textContent = 'Project deleted successfully';
            // Reload the page after a short delay
            setTimeout(() => {
                window.location.reload();
            }, 1500);
        } else {
            toastMessage.textContent = 'Failed to delete project';
        }
    })
    .catch(error => {
        console.error('Error:', error);
        toastMessage.textContent = 'Error deleting project';
    })
    .finally(() => {
        // Hide toast after 3 seconds
        setTimeout(() => {
            toast.style.opacity = '0';
            toast.style.transform = 'translate(-50%, 8px)';
        }, 3000);
    });
}

document.addEventListener('DOMContentLoaded', () => {
    console.log('Projects script loaded');
});
