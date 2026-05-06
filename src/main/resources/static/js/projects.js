/**
 * Projects Page Main Script
 */

// ���� ī��Ʈ ���� �ǽð� ������Ʈ ������������������������������������������������������������
function updateCounts(activeDelta, trashDelta) {
    if (window.updateProjectCounts) window.updateProjectCounts(activeDelta, trashDelta);
}

// ���� ������Ʈ ī�� �׸��� ���ΰ�ħ ��������������������������������������������������������
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
            if (window.Alpine) window.Alpine.initTree(container);
        }
    } catch (e) {
        console.error('[Projects] Failed to refresh project cards', e);
    }
}

// Trash ī�� ���ΰ�ħ (soft delete ���Ŀ��ȣ��)
async function refreshTrashCards() {
    const container = document.getElementById('trash-cards-container');
    if (!container) return;
    try {
        const res = await fetch('/projects');
        if (!res.ok) return;
        const html = await res.text();
        const parser = new DOMParser();
        const doc = parser.parseFromString(html, 'text/html');
        const newContainer = doc.getElementById('trash-cards-container');
        if (newContainer) {
            container.innerHTML = newContainer.innerHTML;
            if (window.Alpine) window.Alpine.initTree(container);
        }
    } catch (e) {
        console.error('[Projects] Failed to refresh trash cards', e);
    }
}

// ���� �佺Ʈ ��������������������������������������������������������������������������������������������������������
let _toastHideTimer = null;

function showToast(projectName, suffix) {
    const toast = document.getElementById('project-toast');
    if (!toast) return;
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

// ���� ����Ʈ ���� (�� ������) ����������������������������������������������������������������������
function deleteProject(projectId, projectName, cardEl) {
    if (!projectId) return;
    cardEl.style.transition = 'opacity 0.2s, transform 0.2s';
    cardEl.style.opacity = '0';
    cardEl.style.transform = 'scale(0.97)';

    fetch(`/projects/${projectId}`, {
        method: 'DELETE',
        headers: { 'Content-Type': 'application/json' }
    })
    .then(r => {
        if (r.ok) {
            setTimeout(() => cardEl.remove(), 200);
            const option = document.querySelector(`select option[value="${projectId}"]`);
            if (option) option.remove();
            updateCounts(-1, 1);
            showToast(projectName, ' moved to trash.');
            refreshTrashCards();
        } else {
            cardEl.style.opacity = '1';
            cardEl.style.transform = '';
            showToast('', 'Failed to delete project.');
        }
    })
    .catch(() => {
        cardEl.style.opacity = '1';
        cardEl.style.transform = '';
        showToast('', 'Error deleting project.');
    });
}

// ���� ������: ���� ���� ����������������������������������������������������������������������������������
function restoreOne(projectId, projectName, cardEl) {
    fetch(`/projects/${projectId}/restore`, { method: 'POST' })
    .then(r => {
        if (r.ok) {
            cardEl.style.transition = 'opacity 0.2s';
            cardEl.style.opacity = '0';
            setTimeout(() => { cardEl.remove(); checkTrashEmpty(); }, 200);
            updateCounts(1, -1);
            showToast(projectName, ' has been restored.');
            refreshProjectCards();
        } else {
            showToast('', 'Failed to restore project.');
        }
    });
}

// ���� ������: ���� ���� ���� (��޿��� ȣ��) ��������������������������������������
function permanentDeleteOne(projectId, projectName, cardEl) {
    fetch(`/projects/${projectId}/permanent`, { method: 'DELETE' })
    .then(r => {
        if (r.ok) {
            cardEl.style.transition = 'opacity 0.2s';
            cardEl.style.opacity = '0';
            setTimeout(() => { cardEl.remove(); checkTrashEmpty(); }, 200);
            updateCounts(0, -1);
            showToast(projectName, ' permanently deleted.');
        } else {
            showToast('', 'Failed to permanently delete project.');
        }
    });
}

// ���� ������: ��ü ���� ���� (��޿��� ȣ��) ��������������������������������������
function deleteAllPermanently() {
    const cards = document.querySelectorAll('.trash-card');
    const count = cards.length;
    fetch('/projects/trash/all', { method: 'DELETE' })
    .then(r => {
        if (r.ok) {
            cards.forEach(c => c.remove());
            updateCounts(0, -count);
            checkTrashEmpty();
            showToast('', 'All trashed projects permanently deleted.');
        } else {
            showToast('', 'Failed to delete all trashed projects.');
        }
    });
}

// ���� ������: ���� ���� ����������������������������������������������������������������������������������
function restoreSelected() {
    const ids = window.getTrashSelected ? window.getTrashSelected() : [];
    if (!ids.length) return;
    fetch('/projects/trash/restore-selected', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(ids)
    }).then(r => {
        if (r.ok) {
            ids.forEach(id => {
                const card = document.querySelector(`.trash-card[data-trash-id="${id}"]`);
                if (card) { card.style.transition = 'opacity 0.2s'; card.style.opacity = '0'; setTimeout(() => card.remove(), 200); }
            });
            updateCounts(ids.length, -ids.length);
            if (window.clearTrashSelected) window.clearTrashSelected();
            setTimeout(checkTrashEmpty, 250);
            showToast('', `${ids.length} project(s) restored.`);
        } else {
            showToast('', 'Failed to restore selected projects.');
        }
    });
}

// ���� ������: ���� ���� ���� (��޿��� ȣ��) ��������������������������������������
function deleteSelected() {
    const ids = window.getTrashSelected ? window.getTrashSelected() : [];
    if (!ids.length) return;
    fetch('/projects/trash/selected', {
        method: 'DELETE',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(ids)
    }).then(r => {
        if (r.ok) {
            ids.forEach(id => {
                const card = document.querySelector(`.trash-card[data-trash-id="${id}"]`);
                if (card) { card.style.transition = 'opacity 0.2s'; card.style.opacity = '0'; setTimeout(() => card.remove(), 200); }
            });
            updateCounts(0, -ids.length);
            if (window.clearTrashSelected) window.clearTrashSelected();
            setTimeout(checkTrashEmpty, 250);
            showToast('', `${ids.length} project(s) permanently deleted.`);
        } else {
            showToast('', 'Failed to delete selected projects.');
        }
    });
}

// ���� ������ ����� �� empty-state ǥ�� ������������������������������������������������
function checkTrashEmpty() {
    const container = document.getElementById('trash-cards-container');
    if (!container) return;
    const cards = container.querySelectorAll('.trash-card');
    const existing = container.querySelector('#trash-empty-state');
    if (cards.length === 0 && !existing) {
        const emptyDiv = document.createElement('div');
        emptyDiv.id = 'trash-empty-state';
        emptyDiv.className = 'col-span-3 flex flex-col items-center gap-[12px] py-[60px] text-[var(--grayscale-40)]';
        emptyDiv.innerHTML = `
            <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.2" stroke-linecap="round" stroke-linejoin="round"><polyline points="3 6 5 6 21 6"/><path d="M19 6l-1 14H6L5 6"/><path d="M10 11v6"/><path d="M14 11v6"/><path d="M9 6V4h6v2"/></svg>
            <p style="font-size:14px;font-weight:500;">Trash is empty</p>`;
        container.appendChild(emptyDiv);
    }
}
