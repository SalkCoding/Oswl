package com.salkcoding.oswl.controller;

import com.salkcoding.oswl.auth.entity.UserVcsConnection;
import com.salkcoding.oswl.auth.enums.VcsProvider;
import com.salkcoding.oswl.auth.repository.UserVcsConnectionRepository;
import com.salkcoding.oswl.auth.security.EncryptionService;
import com.salkcoding.oswl.auth.security.OswlUserPrincipal;
import com.salkcoding.oswl.domain.entity.Project;
import com.salkcoding.oswl.repository.ProjectRepository;
import com.salkcoding.oswl.service.GitHubService;
import com.salkcoding.oswl.service.GitLabService;
import com.salkcoding.oswl.service.BitbucketService;
import com.salkcoding.oswl.service.SessionCipherService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 프로젝트 VCS 리포지토리의 브랜치 목록을 조회하는 프로바이더 비종속적 엔드포인트.
 * Apply Patch 모달에서 대상 브랜치 드롭다운을 콐울 때 사용한다.
 *
 * GET /api/vcs/branches?projectId={id}
 */
@Slf4j
@RestController
@RequestMapping("/api/vcs")
@RequiredArgsConstructor
public class VcsBranchController {

    private final ProjectRepository           projectRepository;
    private final UserVcsConnectionRepository vcsConnectionRepository;
    private final EncryptionService           encryptionService;
    private final SessionCipherService        sessionCipher;
    private final GitHubService               gitHubService;
    private final GitLabService               gitLabService;
    private final BitbucketService            bitbucketService;

    private static final String SESSION_GITHUB_TOKENS = "githubTokens";

    @GetMapping("/branches")
    public ResponseEntity<List<String>> branches(
            @RequestParam Long projectId,
            @AuthenticationPrincipal OswlUserPrincipal principal,
            HttpSession session) {

        Project project = projectRepository.findById(projectId).orElse(null);
        if (project == null || project.getGithubRepo() == null
                || !project.getGithubRepo().contains("/")) {
            return ResponseEntity.ok(List.of("main"));
        }

        VcsProvider provider = project.getVcsProvider();
        String repoPath      = project.getGithubRepo();

        try {
            return switch (provider) {
                case GITHUB -> {
                    String[] parts = repoPath.split("/", 2);
                    String token   = getGithubToken(session, parts[0]);
                    if (token == null) yield ResponseEntity.ok(List.of("main"));
                    yield ResponseEntity.ok(gitHubService.getBranches(token, parts[0], parts[1]));
                }
                case GITLAB -> {
                    Long userId = principal != null ? principal.getUserId() : null;
                    if (userId == null) yield ResponseEntity.ok(List.of("main"));
                    UserVcsConnection conn = vcsConnectionRepository
                            .findByUserIdAndProviderAndActiveTrue(userId, VcsProvider.GITLAB)
                            .orElse(null);
                    if (conn == null) yield ResponseEntity.ok(List.of("main"));
                    String token = encryptionService.decrypt(conn.getAccessTokenEncrypted());
                    yield ResponseEntity.ok(gitLabService.getBranches(token, conn.getServerUrl(), repoPath));
                }
                case BITBUCKET -> {
                    Long userId = principal != null ? principal.getUserId() : null;
                    if (userId == null) yield ResponseEntity.ok(List.of("main"));
                    UserVcsConnection conn = vcsConnectionRepository
                            .findByUserIdAndProviderAndActiveTrue(userId, VcsProvider.BITBUCKET)
                            .orElse(null);
                    if (conn == null) yield ResponseEntity.ok(List.of("main"));
                    String token = encryptionService.decrypt(conn.getAccessTokenEncrypted());
                    yield ResponseEntity.ok(bitbucketService.getBranches(
                            token, conn.getVcsUsername(), conn.getServerUrl(), repoPath));
                }
            };
        } catch (Exception e) {
            log.warn("[VcsBranch] 프로젝트 {} 브랜치 목록 조회 실패: {}", projectId, e.getMessage());
            return ResponseEntity.ok(List.of("main"));
        }
    }

    @SuppressWarnings("unchecked")
    private String getGithubToken(HttpSession session, String owner) {
        Object obj = session.getAttribute(SESSION_GITHUB_TOKENS);
        if (!(obj instanceof Map<?, ?> tokens) || tokens.isEmpty()) return null;
        Map<String, String> map = (Map<String, String>) tokens;
        String encrypted = map.containsKey(owner) ? map.get(owner) : map.values().iterator().next();
        return sessionCipher.decrypt(encrypted);
    }
}
