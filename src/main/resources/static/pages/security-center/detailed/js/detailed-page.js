(() => {
    const overlay = document.querySelector('[data-modal="apply-patch"]');
    if (!overlay) {
        return;
    }

    const closeButton = overlay.querySelector(".modal-close-button");
    const cancelButton = overlay.querySelector(".secondary-action");

    const closeModal = () => {
        overlay.hidden = true;
    };

    closeButton?.addEventListener("click", closeModal);
    cancelButton?.addEventListener("click", closeModal);

    overlay.addEventListener("click", (event) => {
        if (event.target === overlay) {
            closeModal();
        }
    });
})();
