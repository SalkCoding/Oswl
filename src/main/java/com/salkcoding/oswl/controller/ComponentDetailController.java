package com.salkcoding.oswl.controller;

import com.salkcoding.oswl.auth.security.OswlUserPrincipal;
import com.salkcoding.oswl.controller.spec.ComponentDetailControllerSpec;
import com.salkcoding.oswl.dto.CreatePrRequest;
import com.salkcoding.oswl.dto.CveDto;
import com.salkcoding.oswl.dto.DeferralRequest;
import com.salkcoding.oswl.domain.entity.Project;
import com.salkcoding.oswl.repository.ProjectRepository;
import com.salkcoding.oswl.service.ComponentDetailService;
import com.salkcoding.oswl.service.ProjectAccessService;
import com.salkcoding.oswl.service.VcsAuthTokenService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Controller
@RequestMapping("/projects/{projectId}/components/{componentId}")
@PreAuthorize("hasPermission(null, 'COMPONENT_DETAIL_VIEW') or hasRole('SYSTEM_ADMIN')")
@RequiredArgsConstructor
public class ComponentDetailController implements ComponentDetailControllerSpec {

    private final ComponentDetailService componentDetailService;
    private final ProjectRepository      projectRepository;
    private final VcsAuthTokenService    vcsAuthTokenService;
    private final ProjectAccessService   projectAccessService;

    @GetMapping
    public String detail(@PathVariable Long projectId,
                         @PathVariable Long componentId,
                         Model model,
                         HttpServletRequest request) {
        projectAccessService.assertCanViewProject(projectId);
        componentDetailService.populateModel(projectId, componentId, model);

        if ("true".equals(request.getHeader("HX-Request"))) {
            return "component-detail/fragments/detail-content :: content";
        }
        return "component-detail/index";
    }

    @PostMapping("/cves/{cveDbId}/ai-summarize")
    @ResponseBody
    @PreAuthorize("hasPermission(null, 'SECURITY_CENTER_UPDATE_STATUS') or hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<CveDto> regenerateCveAi(@PathVariable Long projectId,
                                                    @PathVariable Long componentId,
                                                    @PathVariable Long cveDbId) {
        projectAccessService.assertCanViewProject(projectId);
        return ResponseEntity.ok(
                componentDetailService.regenerateCveAiSummary(projectId, componentId, cveDbId));
    }

    @PostMapping("/defer")
    @ResponseBody
    @PreAuthorize("hasPermission(null, 'SECURITY_CENTER_UPDATE_STATUS') or hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<Void> defer(@PathVariable Long projectId,
                                      @PathVariable Long componentId,
                                      @RequestBody DeferralRequest req) {
        projectAccessService.assertCanViewProject(projectId);
        componentDetailService.defer(projectId, componentId, req);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/create-pr")
    @ResponseBody
    @PreAuthorize("hasPermission(null, 'SECURITY_CENTER_UPDATE_STATUS') or hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<Map<String, Object>> createPr(@PathVariable Long projectId,
                                                         @PathVariable Long componentId,
                                                         @RequestBody CreatePrRequest req,
                                                         HttpSession session,
                                                         @AuthenticationPrincipal OswlUserPrincipal principal) {
        projectAccessService.assertCanViewProject(projectId);
        Long userId = principal != null ? principal.getUserId() : null;
        String githubOwner = projectRepository.findById(projectId)
                .map(Project::getGithubRepo)
                .filter(r -> r != null && r.contains("/"))
                .map(r -> r.split("/", 2)[0])
                .orElse(null);
        String githubToken = vcsAuthTokenService.resolveGithubToken(session, userId, githubOwner);
        try {
            Map<String, Object> result = componentDetailService.createPullRequest(
                    projectId, componentId, req, userId, githubToken);
            return ResponseEntity.ok(result);
        } catch (IllegalStateException | IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(502).body(Map.of("error", "VCS error: " + e.getMessage()));
        }
    }
}
