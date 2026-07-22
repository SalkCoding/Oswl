package com.salkcoding.oswl.service;

/**
 * Describes which manifest file was patched during a version-bump PR.
 */
public record ManifestPatchInfo(String filePath, String libraryName, String oldVersion, String newVersion) {

    public String toPrAppendix() {
        return "\n\n## Changed files\n- `" + filePath + "`: "
                + libraryName + " " + oldVersion + " → " + newVersion;
    }
}
