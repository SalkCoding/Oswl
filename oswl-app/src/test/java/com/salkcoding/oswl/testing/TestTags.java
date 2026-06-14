package com.salkcoding.oswl.testing;

/** JUnit 5 tag names for Gradle testFast / CI matrix filtering. */
public final class TestTags {
    private TestTags() {}

    public static final String FAST = "fast";
    public static final String AUTH = "auth";
    public static final String PARSER = "parser";
    public static final String WEB = "web";
    public static final String INTEGRATION = "integration";
    public static final String LIVE = "live";
}
