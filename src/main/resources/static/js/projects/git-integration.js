/**
 * Git Integration Alpine.js Data Store
 * Handles branch dropdown (per-row), account selection, and import functionality
 */
function gitIntegrationData() {
    return {
        // Dropdown state (per-row branch dropdown)
        openBranchDropdownId: null,
        accountDropdownOpen: false,
        selectAll: false,
        selectedRepos: [],
        importedRepos: [],
        importFeedback: false,

        // Dropdown data
        branches: [],
        accounts: [],

        // Per-item branch selection
        itemBranches: {},

        // Selected values
        selectedAccount: 'OwlCoding',

        // Initialize data on component load
        async init() {
            await this.loadAccounts();
            // Branch data is repo-specific; loaded on demand via loadRepoBranches(owner, repo)
        },

        /**
         * Fetch branches for a specific repository.
         * Call this when the user selects a repo row.
         */
        async loadRepoBranches(owner, repo) {
            try {
                const url = `/api/github/branches?owner=${encodeURIComponent(owner)}&repo=${encodeURIComponent(repo)}`;
                const response = await fetch(url);
                if (!response.ok) throw new Error(`HTTP ${response.status}`);
                this.branches = await response.json();
            } catch (error) {
                console.error('Error loading branches:', error);
                this.branches = ['main'];
            }
        },

        /**
         * Fetch connected GitHub accounts from backend API
         */
        async loadAccounts() {
            try {
                const response = await fetch('/api/github/accounts');
                if (!response.ok) {
                    throw new Error(`Failed to load accounts: ${response.statusText}`);
                }
                const data = await response.json();
                this.accounts = data.map(a => a.login || a);
                if (this.accounts.length > 0) {
                    this.selectedAccount = this.accounts[0];
                }
            } catch (error) {
                console.error('Error loading accounts:', error);
                this.accounts = [];
            }
        },

        /**
         * Import selected repositories
         */
        importSelected() {
            if (this.selectedRepos.length === 0) return;
            for (const repo of this.selectedRepos) {
                if (!this.importedRepos.includes(repo)) {
                    this.importedRepos.push(repo);
                }
            }
            this.selectedRepos = [];
            this.selectAll = false;
            this.importFeedback = true;
            setTimeout(() => { this.importFeedback = false; }, 2000);
        },

        /**
         * Import all repositories
         */
        importAll() {
            const allItems = [1, 2, 3, 4, 5];
            for (const item of allItems) {
                if (!this.importedRepos.includes(item)) {
                    this.importedRepos.push(item);
                }
            }
            this.selectedRepos = [];
            this.selectAll = false;
            this.importFeedback = true;
            setTimeout(() => { this.importFeedback = false; }, 2000);
        }
    };
}

// Initialization is handled by Alpine.js via x-data
document.addEventListener('DOMContentLoaded', () => {
    console.log('Git integration script loaded');
});
