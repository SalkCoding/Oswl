package com.salkcoding.oswl.dto.config;

import java.util.List;

/**
 * Outcome of a config bundle import — same shape for a dry run (nothing persisted)
 * and a real apply, so the UI can preview before committing.
 */
public record ConfigImportResult(
        boolean dryRun,
        int roleTemplatesCreated, int roleTemplatesUpdated, int roleTemplatesSkippedBuiltIn,
        int licensePolicyCreated, int licensePolicyUpdated,
        int aiSettingsCreated, int aiSettingsUpdated,
        int promptOverridesCreated, int promptOverridesUpdated,
        int cacheSettingsUpdated,
        int policiesCreated, int policiesUpdated, int policiesSkippedUnresolved,
        List<String> manualStepsRequired
) {
}
