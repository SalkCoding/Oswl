package com.salkcoding.oswl.service.git;

/**
 * HTTPS credentials for {@link GitCloneExecutor} (not embedded in the clone URL).
 */
public record GitCloneCredentials(String username, String password) {}
