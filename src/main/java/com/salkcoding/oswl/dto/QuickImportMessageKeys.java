package com.salkcoding.oswl.dto;

/** Stable keys for Quick Import UI messages (resolved on the client via i18n). */
public final class QuickImportMessageKeys {

    private QuickImportMessageKeys() {}

    public static final String QUEUE_FULL = "queueFull";
    public static final String LOAD_REPOS = "loadRepos";
    public static final String TOKEN_DECRYPT = "tokenDecrypt";

    public static final String SCAN_SUBMIT_FAILED = "scanSubmitFailed";
    public static final String CLONE_PATH_TOO_LONG = "clonePathTooLong";
    public static final String CLONE_FAILED = "cloneFailed";
    public static final String CLONE_TIMEOUT = "cloneTimeout";
    public static final String INVALID_REPO_URL = "invalidRepoUrl";
    public static final String UNEXPECTED = "unexpected";
    public static final String ENRICHMENT_FAILED = "enrichmentFailed";
    public static final String CLI_POLICY_BLOCKED = "cliPolicyBlocked";
    public static final String IMPORT_COMPLETE = "importComplete";
}
