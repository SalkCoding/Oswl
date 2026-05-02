/**
 * Projects Page Main Script
 * Handles project management and interaction
 */

/**
 * Delete a project by ID
 */
function deleteProject(projectId, projectName) {
    if (!projectId) return;

    const toast = document.getElementById('project-toast');
    const toastProjectName = toast.querySelector('[data-toast-project-name]');
    const toastSuffix = toast.querySelector('[data-toast-suffix]');

    // Send delete request
    fetch(`/projects/${projectId}`, {
        method: 'DELETE',
        headers: {
            'Content-Type': 'application/json',
        }
    })
    .then(response => {
        if (response.ok) {
            toastProjectName.textContent = projectName;
            toastSuffix.textContent = ' has been deleted.';

            // Show toast
            toast.style.opacity = '1';
            toast.style.transform = 'translate(-50%, 0)';

            // Hide toast after 3 seconds then reload
            setTimeout(() => {
                toast.style.opacity = '0';
                toast.style.transform = 'translate(-50%, 8px)';
            }, 3000);
            setTimeout(() => {
                window.location.reload();
            }, 1500);
        } else {
            toastProjectName.textContent = '';
            toastSuffix.textContent = 'Failed to delete project.';
            toast.style.opacity = '1';
            toast.style.transform = 'translate(-50%, 0)';
            setTimeout(() => {
                toast.style.opacity = '0';
                toast.style.transform = 'translate(-50%, 8px)';
            }, 3000);
        }
    })
    .catch(error => {
        console.error('Error:', error);
        toastProjectName.textContent = '';
        toastSuffix.textContent = 'Error deleting project.';
        toast.style.opacity = '1';
        toast.style.transform = 'translate(-50%, 0)';
        setTimeout(() => {
            toast.style.opacity = '0';
            toast.style.transform = 'translate(-50%, 8px)';
        }, 3000);
    });
}
