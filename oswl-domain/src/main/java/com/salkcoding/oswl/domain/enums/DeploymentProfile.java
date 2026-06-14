package com.salkcoding.oswl.domain.enums;

/**
 * How the analyzed product is deployed — steers AI triage emphasis (SaaS exposure vs distribution vs internal).
 */
public enum DeploymentProfile {
    /** Network-facing SaaS or multi-tenant service */
    SAAS,
    /** Internal-only tooling; lower external exploit exposure */
    INTERNAL_TOOL,
    /** Shipped to customers (on-prem, OEM, appliance) */
    ON_PREMISE_DISTRIBUTION,
    /** General commercial product (default) */
    COMMERCIAL_PRODUCT
}
