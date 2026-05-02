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
            await this.loadBranches();
            await this.loadAccounts();
            // Initialize branch selection for each item
            for (let i = 1; i <= 5; i++) {
                this.itemBranches[i] = this.branches.length > 0 ? this.branches[0] : 'main';
            }
        },

        /**
         * Fetch branches from backend API
         */
        async loadBranches() {
            try {
                const response = await fetch('/projects/api/branches');
                if (!response.ok) {
                    throw new Error(`Failed to load branches: ${response.statusText}`);
                }
                this.branches = await response.json();
            } catch (error) {
                console.error('Error loading branches:', error);
                this.branches = [
                    'main',
                    'develop',
                    'feature/new-feature',
                    'hotfix/bug-fix',
                    'release/v1.0.0',
                    'staging'
                ];
            }
        },

        /**
         * Fetch accounts from backend API
         */
        async loadAccounts() {
            try {
                const response = await fetch('/projects/api/accounts');
                if (!response.ok) {
                    throw new Error(`Failed to load accounts: ${response.statusText}`);
                }
                this.accounts = await response.json();
                if (this.accounts.length > 0) {
                    this.selectedAccount = this.accounts[0];
                }
            } catch (error) {
                console.error('Error loading accounts:', error);
                this.accounts = [
                    'OwlCoding',
                    'OWL-Team',
                    'OWL-Analytics',
                    'OWL-Security'
                ];
                this.selectedAccount = this.accounts[0];
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
