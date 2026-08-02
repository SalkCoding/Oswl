package com.salkcoding.oswl.domain.enums;

/**
 * Result of bytecode call-graph reachability analysis for a scanned component.
 *
 * REACHABLE    — the project bytecode contains at least one reference to a class
 *                belonging to this component.
 * NOT_REACHABLE — no such reference was found in the analyzed project bytecode.
 * UNKNOWN       — analysis could not be performed (no bytecode supplied, I/O error,
 *                 or non-Java ecosystem).
 */
public enum Reachability {
    REACHABLE,
    NOT_REACHABLE,
    UNKNOWN
}
