package com.salkcoding.oswl.domain.enums;

/** Indicates how a project version was imported into OsWL. */
public enum ImportSource {
    /** Imported via the GitHub Integration panel. */
    GIT,
    /** Imported via the CLI scan tool. */
    CLI
}
