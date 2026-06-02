/**
 * Projects Page Main Script
 */

const _p = window.projectsPageI18n || {};

function _fmt(template, n) {
    return String(template).replace('{0}', n);
}

// ── 카운트 배지 실시간 업데이트 ─────────────────────────────────────────────
function updateCounts(activeDelta, trashDelta) {
    if (window.updateProjectCounts) window.updateProjectCounts(activeDelta, trashDelta);
}

// ── 프로젝트 카드 그리드 새로고침 ───────────────────────────────────────────
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

// Trash 카드 새로고침 (soft delete 이후에도)
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

// ── 토스트 ─────────────────────────────────────────────────────────────────
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

// ── 프로젝트 삭제 (휴지통 이동) ─────────────────────────────────────────────
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
            showToast(projectName, _p.movedToTrash || ' moved to trash.');
            refreshTrashCards();
        } else {
            cardEl.style.opacity = '1';
            cardEl.style.transform = '';
            showToast('', _p.deleteFailed || 'Failed to delete project.');
        }
    })
    .catch(() => {
        cardEl.style.opacity = '1';
        cardEl.style.transform = '';
        showToast('', _p.errorDeleting || 'Error deleting project.');
    });
}

// ── 휴지통: 단일 복원 ───────────────────────────────────────────────────────
function restoreOne(projectId, projectName, cardEl) {
    fetch(`/projects/${projectId}/restore`, { method: 'POST' })
    .then(r => {
        if (r.ok) {
            cardEl.style.transition = 'opacity 0.2s';
            cardEl.style.opacity = '0';
            setTimeout(() => { cardEl.remove(); checkTrashEmpty(); }, 200);
            updateCounts(1, -1);
            showToast(projectName, _p.restored || ' has been restored.');
            refreshProjectCards();
        } else {
            showToast('', _p.restoreFailed || 'Failed to restore project.');
        }
    });
}

// ── 휴지통: 영구 삭제 (단일, 모달에서 호출) ─────────────────────────────────
function permanentDeleteOne(projectId, projectName, cardEl) {
    fetch(`/projects/${projectId}/permanent`, { method: 'DELETE' })
    .then(r => {
        if (r.ok) {
            cardEl.style.transition = 'opacity 0.2s';
            cardEl.style.opacity = '0';
            setTimeout(() => { cardEl.remove(); checkTrashEmpty(); }, 200);
            updateCounts(0, -1);
            showToast(projectName, _p.permanentDeleted || ' permanently deleted.');
        } else {
            showToast('', _p.permanentDeleteFailed || 'Failed to permanently delete project.');
        }
    });
}

// ── 휴지통: 전체 영구 삭제 (모달에서 호출) ───────────────────────────────────
function deleteAllPermanently() {
    const cards = document.querySelectorAll('.trash-card');
    const count = cards.length;
    fetch('/projects/trash/all', { method: 'DELETE' })
    .then(r => {
        if (r.ok) {
            cards.forEach(c => c.remove());
            updateCounts(0, -count);
            checkTrashEmpty();
            showToast('', _p.deleteAllSuccess || 'All trashed projects permanently deleted.');
        } else {
            showToast('', _p.deleteAllFailed || 'Failed to delete all trashed projects.');
        }
    });
}

// ── 휴지통: 선택 복원 ───────────────────────────────────────────────────────
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
            showToast('', _fmt(_p.restoreSelected || '{0} project(s) restored.', ids.length));
            refreshProjectCards();
        } else {
            showToast('', _p.restoreSelectedFailed || 'Failed to restore selected projects.');
        }
    });
}

// ── 휴지통: 선택 영구 삭제 (모달에서 호출) ───────────────────────────────────
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
            showToast('', _fmt(_p.deleteSelected || '{0} project(s) permanently deleted.', ids.length));
        } else {
            showToast('', _p.deleteSelectedFailed || 'Failed to delete selected projects.');
        }
    });
}

// 휴지통 비었을 때 empty-state 표시 ─────────────────────────────────────────
function checkTrashEmpty() {
    const container = document.getElementById('trash-cards-container');
    if (!container) return;
    const cards = container.querySelectorAll('.trash-card');
    const existing = container.querySelector('#trash-empty-state');
    if (cards.length === 0 && !existing) {
        const emptyDiv = document.createElement('div');
        emptyDiv.id = 'trash-empty-state';
        emptyDiv.className = 'col-span-3 flex flex-col items-center gap-[12px] py-[60px] text-[var(--grayscale-40)]';
        const label = _p.trashEmpty || 'Trash is empty';
        emptyDiv.innerHTML = `
            <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.2" stroke-linecap="round" stroke-linejoin="round"><polyline points="3 6 5 6 21 6"/><path d="M19 6l-1 14H6L5 6"/><path d="M10 11v6"/><path d="M14 11v6"/><path d="M9 6V4h6v2"/></svg>
            <p style="font-size:14px;font-weight:500;">${label}</p>`;
        container.appendChild(emptyDiv);
    }
}
