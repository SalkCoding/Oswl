package com.salkcoding.oswl.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * POST /projects/{projectId}/components/{componentId}/create-pr 페이로드
 */
@Getter
@NoArgsConstructor
public class CreatePrRequest {

    /** PR의 대상 베이스 브랜치 */
    private String targetBranch;

    /** 선택적 리빰우어 GitHub 로그인 목록 */
    private List<String> reviewers;

    /** PR body / description (freeform text) */
    private String prDescription;
}
