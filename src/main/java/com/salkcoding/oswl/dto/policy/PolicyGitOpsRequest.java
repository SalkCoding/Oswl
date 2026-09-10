package com.salkcoding.oswl.dto.policy;

/**
 * Trigger a one-time GitOps sync of .oswl/policy.yaml for a project.
 */
public record PolicyGitOpsRequest(
        Long projectId,
        String repositoryUrl,
        String accessToken,
        String branch
) {}
