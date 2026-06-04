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
import com.salkcoding.oswl.service.ProjectAccessService;
import com.salkcoding.oswl.service.VcsAuthTokenService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Provider-agnostic endpoint for retrieving the branch list of a project's VCS repository.
 * Used to populate the target branch dropdown in the Apply Patch modal.
 *
 * GET /api/vcs/branches?projectId={id}
 */
@Slf4j
@RestController
@RequestMapping("/api/vcs")
@PreAuthorize("hasPermission(null, 'COMPONENT_DETAIL_VIEW') or hasRole('SYSTEM_ADMIN')")
@RequiredArgsConstructor
public class VcsBranchController {

    private final ProjectRepository           projectRepository;
    private final ProjectAccessService        projectAccessService;
    private final UserVcsConnectionRepository vcsConnectionRepository;
    private final EncryptionService           encryptionService;
    private final VcsAuthTokenService           vcsAuthTokenService;
    private final GitHubService               gitHubService;
    private final GitLabService               gitLabService;
    private final BitbucketService            bitbucketService;

    @GetMapping("/branches")
    public ResponseEntity<List<String>> branches(
            @RequestParam Long projectId,
            @AuthenticationPrincipal OswlUserPrincipal principal,
            HttpSession session) {

        projectAccessService.assertCanViewProject(projectId);
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
                    Long userId = principal != null ? principal.getUserId() : null;
                    String token = vcsAuthTokenService.resolveGithubToken(session, userId, parts[0]);
                    if (token == null) yield ResponseEntity.ok(List.of("main"));
                    String serverUrl = null;
                    if (userId != null) {
                        UserVcsConnection conn = vcsAuthTokenService.getConnection(userId, VcsProvider.GITHUB);
                        if (conn != null) serverUrl = conn.getServerUrl();
                    }
                    yield ResponseEntity.ok(gitHubService.getBranches(token, parts[0], parts[1], serverUrl));
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
            log.warn("[VcsBranch] Failed to retrieve branch list for project {}: {}", projectId, e.getMessage());
            return ResponseEntity.ok(List.of("main"));
        }
    }
}
