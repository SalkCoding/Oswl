package com.salkcoding.oswl.dto;

/**
 * POST /api/admin/snapshot/import-from-path payload. {@code path} must resolve under
 * {@code oswl.airgapped.import-dir} (enforced server-side — see SnapshotAdminController).
 * {@code mode} is optional: {@code "replace"} or {@code "merge"}; omitted lets the bundle's own
 * {@code meta.json} decide (falling back to replace).
 */
public record SnapshotImportFromPathRequest(String path, String mode) {}
