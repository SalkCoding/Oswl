package com.salkcoding.oswl.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * POST /projects/{projectId}/components/{componentId}/create-pr payload
 */
@Getter
@NoArgsConstructor
public class CreatePrRequest {

    /** Target base branch for the PR */
    private String targetBranch;

    /** Optional list of reviewer GitHub logins */
    private List<String> reviewers;

    /** PR body / description (freeform text) */
    private String prDescription;
}
