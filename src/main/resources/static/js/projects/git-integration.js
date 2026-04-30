/**
 * Git Integration Alpine.js Data Store
 * Handles dropdown functionality for branch version and account selection
 */
function gitIntegrationData() {
    return {
        // Dropdown state
        branchDropdownOpen: false,
        accountDropdownOpen: false,
        selectAll: false,
        selectedRepos: [],
        
        // Dropdown data
        branches: [],
        accounts: [],
        
        // Selected values
        selectedBranch: 'main',
        selectedAccount: 'OwlCoding',
        
        // Initialize data on component load
        async init() {
            await this.loadBranches();
            await this.loadAccounts();
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
                // Set first branch as default if available
                if (this.branches.length > 0) {
                    this.selectedBranch = this.branches[0];
                }
            } catch (error) {
                console.error('Error loading branches:', error);
                // Fallback to hardcoded branches if API fails
                this.branches = [
                    'main',
                    'develop',
                    'feature/new-feature',
                    'hotfix/bug-fix',
                    'release/v1.0.0',
                    'staging'
                ];
                this.selectedBranch = this.branches[0];
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
                // Set first account as default if available
                if (this.accounts.length > 0) {
                    this.selectedAccount = this.accounts[0];
                }
            } catch (error) {
                console.error('Error loading accounts:', error);
                // Fallback to hardcoded accounts if API fails
                this.accounts = [
                    'OwlCoding',
                    'OWL-Team',
                    'OWL-Analytics',
                    'OWL-Security'
                ];
                this.selectedAccount = this.accounts[0];
            }
        }
    };
}

// Initialize when DOM is ready
document.addEventListener('DOMContentLoaded', () => {
    // The initialization is handled by Alpine.js x-init directive
    console.log('Git integration script loaded');
});
