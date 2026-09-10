package com.salkcoding.oswl.domain.enums;

/**
 * Scope of an API key. PROJECT keys are bound to a single project and used by
 * the CLI scan client. SCIM keys are admin-level provisioning tokens and must
 * not be accepted by the normal scan API.
 */
public enum ApiKeyScope {
    PROJECT,
    SCIM
}
